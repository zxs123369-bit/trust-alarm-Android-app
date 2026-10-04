/* 本地闹钟页（兜底：收到的闹钟在本机注册失败时由 App 响铃）
 *
 * ## 用户需求
 *   导航栏「设备」改为「本地闹钟」，作为主页（第一位）。
 *   「当收到的闹钟在手机本地注册失败时候响铃」。
 *
 * ## 能力边界（界面上必须诚实说明，不能假装能后台响）
 *   PWA 能做的：页面打开时到点响铃（前端定时器 + Web Audio）
 *   PWA 做不到：页面关闭后响铃 —— 浏览器无后台唤醒能力
 *   原生 App 才能做：真正的后台响铃 / 系统闹钟注册
 *
 *   所以本页面的核心价值是：
 *     · 让用户看到**哪些闹钟本地注册失败**（这些可能不会响）
 *     · 页面在前台时真的响铃兜底
 *     · 请求通知权限，页面在后台时用系统通知提醒（不是响铃）
 *
 * 后端契约（GET /local-alarms?device_uid=）：
 *   {list: [{uid, display_time, alarm_time, note, source, local_state,
 *            needs_fallback, failure_type, retry_count, within_24h}],
 *    total, needs_fallback_count, device_name, device_uid, capability}
 */
import { api, alarmApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import { skeleton, emptyState, showError, toast, toastErr, fmtRelative, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';
import * as store from '../store.js';
import {
  isNativeShell,
  registerSystemAlarm,
  cancelSystemAlarm,
  isSystemAlarmScheduled,
  canScheduleExact,
  openExactAlarmSettings,
  startHeartbeat,
  syncAlarmsNow,
  saveDeviceUid,
} from '../native.js';

/**
 * 从「我名下」的设备里挑出**本机**那一台。
 *
 * 为什么不能直接取 mine[0]：
 *   一个账号名下可能有多台 android 设备（换过手机、在网页版建过）。
 *   取第一台会把心跳与闹钟同步打到另一台上，实测后果是真机显示离线、
 *   闹钟永不注册。挑选序（可靠度递减）：
 *     1. 原生已存的 device_uid（用户在「绑定本机」时写过）
 *     2. 设备名与手机自身名称一致
 *     3. 最近有心跳的那台（在用的一定有近期心跳）
 *     4. 最后才取第一台
 */
async function pickLocalDevice(mine) {
  // ★ 判定「本机」的核心难点：多设备账号下没有任何字段能 100% 证明
  //   「这台手机就是那台设备」。历史教训（真机实测三次）：
  //     · 按「数组第一台」→ 选到早已废弃的旧绑定（心跳/闹钟全打错）
  //     · 按「设备名一致」→ 用户常给多台手机起同名（如都叫「我的手机」），
  //       于是又选错
  //     · 按「原生已存 uid」→ 若原生存的是**旧值**（如网页版绑过的僵尸设备），
  //       会一直采信错误值。真机实测：原生存 dev49（WebView Shell），
  //       真机其实是 dev50，导致本地闹钟页永远显示「没有待响的闹钟」。
  //   所以能否匹配，取决于**是否有足够独特的证据**。

  // 先拿到本机的「独特证据」：机型摘要。
  // 用它比对设备列表里的 `model_digest`（后端只对归属人返回，见 masking.model_digest）。
  let myDigest = '';
  try {
    if (isNativeShell && window.TrustAlarmNative?.deviceModelDigest) {
      myDigest = (window.TrustAlarmNative.deviceModelDigest() || '').trim().toLowerCase();
    }
  } catch (_) { /* 忽略 */ }

  // 机型唯一匹配的那台 —— 如果有，它比「原生存值」更可信（原生存值可能是旧值）。
  let modelHit = null;
  if (myDigest) {
    const byModel = mine.filter(
      (d) => String(d.model_digest || '').trim().toLowerCase() === myDigest
    );
    if (byModel.length === 1) modelHit = byModel[0];
  }

  // 1) 原生已存的 device_uid，且该设备仍在列表里 → 采信，但**必须再校验**。
  //
  //    ★ 为什么不能无条件采信（真机实测根因）：
  //      原生存值可能是**历史遗留的错误绑定**。真机实测：原生存的是 dev49
  //      （绑到自己建的空家庭里的僵尸设备），而真机是 dev50 —— 却因「原生
  //      已存」被标为最高优先级【可信】，于是本地闹钟页一直查错设备、列表恒空。
  //
  //    校验规则：机型摘要是独特证据。若原生存值那台的摘要与真机**明确不符**，
  //    则不信它（降级），改由机型匹配决定。
  let savedUid = '';
  try {
    if (isNativeShell && window.TrustAlarmNative?.getDeviceUid) {
      savedUid = window.TrustAlarmNative.getDeviceUid() || '';
    }
  } catch (_) { /* 忽略 */ }
  if (savedUid) {
    const hit = mine.find((d) => d.uid === savedUid);
    if (hit) {
      const hitDigest = String(hit.model_digest || '').trim().toLowerCase();
      // 该设备摘要与真机矛盾（都有值且不同）→ 原生存值已失效
      const contradicts = myDigest && hitDigest && hitDigest !== myDigest;
      if (!contradicts) return mark(hit, true, '原生已存 uid');
      console.warn('[pickLocalDevice] 原生已存 uid 与真机机型矛盾，弃用',
        hit.device_name, hit.uid.slice(0, 8), '存:', hitDigest, '真机:', myDigest);
    }
  }

  // 2) 机型唯一一致 —— 独特证据，可用。（上面已算好 modelHit）
  if (modelHit) return mark(modelHit, true, '机型一致');

  // 2b) 若原生存值机型矛盾、且没有机型唯一匹配，但原生存值仍在名下 →
  //     保守回退用它（宁可显示空，也别显示另一台的闹钟），但标为「推测」。
  if (savedUid) {
    const hit = mine.find((d) => d.uid === savedUid);
    if (hit) return mark(hit, false, '原生已存 uid（机型存疑）');
  }

  // 3) 设备名一致 —— 仅在**唯一匹配**时才采信。
  //
  //    为什么加「唯一」这个条件：用户给多台手机起同一个名字是常见做法
  //    （实测两台都叫「我的手机」），此时按名字选等于随机挑一台。
  //    有多个同名候选就**不采信**，交给下面的更强线索。
  let savedName = '';
  try {
    if (isNativeShell && window.TrustAlarmNative?.getDeviceName) {
      savedName = (window.TrustAlarmNative.getDeviceName() || '').trim();
    }
  } catch (_) { /* 忽略 */ }
  if (savedName) {
    const sameName = mine.filter((d) => (d.device_name || '').trim() === savedName);
    // 唯一同名 → 可信；多台同名 → 不采信（避免随机挑一台）
    if (sameName.length === 1) return mark(sameName[0], true, '名称唯一');
  }

  // 4) 最近绑定 —— 在缺独特证据时比「第一台」可靠得多。
  //    用户通常先在这台手机上绑定、再开始用它。
  //    注意：这条是**推测**，不算 confident，不会写回原生。
  const withBind = mine.filter((d) => Number(d.bind_at || 0) > 0);
  if (withBind.length) {
    withBind.sort((a, b) => Number(b.bind_at) - Number(a.bind_at));
    return mark(withBind[0], false, '最近绑定（推测）');
  }

  // 5) 最近心跳
  const withHb = mine.filter((d) => Number(d.last_heartbeat_at || 0) > 0);
  if (withHb.length) {
    withHb.sort((a, b) => Number(b.last_heartbeat_at) - Number(a.last_heartbeat_at));
    return mark(withHb[0], false, '最近心跳（推测）');
  }

  // 6) 保底
  return mark(mine[0], false, '第一台（保底）');
}

/**
 * 给选中的设备打上「是否可信」标记。
 *
 * 为什么需要：本机识别在多设备账号下无法 100% 确定。
 * 只有**可信**的结论才允许写回原生存储 —— 否则错误会被固化，
 * 下一轮又用这个错值去做名称匹配，形成自我强化的偏差（实测踩过）。
 */
function mark(dev, confident, reason) {
  const o = Object.assign({}, dev, { __confident: confident, __why: reason });
  try {
    console.info('[pickLocalDevice] 选中', dev.device_name, dev.uid.slice(0, 8), reason,
      confident ? '(可信)' : '(推测)');
  } catch (_) { /* 忽略 */ }
  return o;
}

/**
 * 把「这台手机」绑定成一个新设备，并告诉原生。
 *
 * 场景：用户登录了但名下没有设备（或名下设备都是别的手机）。
 * 之前只能去家庭页操作，路径长且不直观；这里提供就近入口。
 */
async function bindThisPhone() {
  try {
    const famUid = await pickFamilyUid();
    if (!famUid) {
      showError({ message: '你还没有家庭，请先创建一个家庭' });
      return;
    }
    // 默认名字带机型，便于在家庭列表里区分多台设备
    let defName = '我的手机';
    try {
      const model = window.TrustAlarmNative?.deviceModel?.();
      if (model) defName = String(model).slice(0, 20);
    } catch (_) { /* 忽略 */ }

    const nm = (window.prompt('给这台手机起个名字', defName) || '').trim();
    if (!nm) return;

    const d = await api.post('/devices/bind', {
      family_uid: famUid,
      device_name: nm,
      platform: 'android',
      model: (window.TrustAlarmNative?.deviceModel?.() || '').slice(0, 64) || null,
    });
    if (isNativeShell && d && d.uid) {
      saveDeviceUid(d.uid, nm);
      startHeartbeat();
      syncAlarmsNow();
    }
    toast('绑定成功');
    render();
  } catch (e) {
    showError(e);
  }
}

let rows = [];
let capInfo = null;
let needsFallback = 0;
let deviceName = '本机';
let currentDeviceUid = null;
let timer = null;          // 响铃定时器
let firedKeys = new Set(); // 已响过的闹钟（避免重复响）
let ticker = null;         // 倒计时刷新

export async function render() {
  setLayout({ title: '本地闹钟', tabbar: true });
  setView(`<div class="page">${skeleton(4)}</div>`);

  try {
    // 先取我名下**所有**设备（跨全部家庭汇总）。
    // 注：不能只看第一个家庭 —— 本机设备可能在别的家庭里（真机实测根因）。
    const mine = await listMyDevicesAcrossFamilies();
    if (!mine.length) {
      setView(`
        <div class="page">
          ${emptyState('📱', '你名下还没有设备',
            '本地闹钟用于你自己的设备；先绑定这台手机')}
          <button class="btn block" id="btn-bind-here">绑定这台手机</button>
          <button class="btn block" id="btn-go" style="background:#edf2f7;color:#2d3748">
            去家庭页绑定
          </button>
        </div>`);
      document.getElementById('btn-go')
        .addEventListener('click', () => go('/families'));
      document.getElementById('btn-bind-here')
        .addEventListener('click', () => bindThisPhone());
      return () => stopTimers();
    }

    // ★ 选择「本机」设备不能再简单取 mine[0]
    //
    //  一个账号名下可能有多台 android 设备（换过手机、在网页版建过）。
    //  取第一台会把心跳与闹钟同步打到**另一台**上，实测后果：
    //    · 真机永远「从未心跳」→ 家人看到它离线
    //    · 从错设备拉闹钟 → 列表为空 → 闹钟永不注册、到点不响
    //
    //  优先用原生侧已存的 device_uid（用户在「绑定本机」时写过的），
    //  只在其无效时才回退。
    const chosen = await pickLocalDevice(mine);
    currentDeviceUid = chosen.uid;
    deviceName = chosen.device_name || '本机';

    // ★ 关键：把「本机就是这台设备」告诉原生。
    //   之前在「家庭页 → 绑定设备」才会写，若用户是从网页版绑的、
    //   或直接登录进来的，原生就永远不知道该同步哪台设备 ——
    //   只能靠猜，而猜错的概率在多设备账号上很高。
    //
    //   ★ 但**不能**把猜出来的结果当成「绑定」写回去：
    //     一旦把猜错的名字（如「我的手机」）写入原生，下一轮
    //     「按名称匹配」就会拿这个猜测值去匹配，形成**自我强化**的
    //     错误（实测踩到：两台都叫「我的手机」，越猜越偏）。
    //     所以只在**有独特证据**（机型一致 / 原生已有该 uid）时才落盘。
    if (isNativeShell && chosen && chosen.uid && chosen.__confident) {
      try {
        saveDeviceUid(chosen.uid, chosen.device_name || '我的手机');
      } catch (_) { /* 桥不可用时忽略 */ }
    }

    const r = await api.get(
      `/local-alarms?device_uid=${encodeURIComponent(currentDeviceUid)}`
    );
    rows = (r && r.list) || [];
    capInfo = (r && r.capability) || null;
    needsFallback = (r && r.needs_fallback_count) || 0;
    deviceName = (r && r.device_name) || deviceName;

    // ★ 原生壳专属：确保后台服务在跑 + 立刻同步一次闹钟。
    //
    // 注意：闹钟注册的**正确性不依赖本页面** —— 后台服务（HeartbeatService）
    // 每分钟会自己拉列表并全量注册。这里只是"进页面就尽快生效"的体验优化，
    // 以及兜底把后台服务拉起来（防止它被系统回收后一直没恢复）。
    if (isNativeShell) {
      startHeartbeat();          // 幂等，已在跑则无副作用
      syncAlarmsNow();           // 让后台服务立刻同步一次
      registerAllToSystem();     // 同时本地先注册一遍，页面立即能显示状态
    }
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render())}</div>`);
    return () => stopTimers();
  }

  scrollTop();
  draw();
  startTimers();

  return () => stopTimers();
}

/**
 * 取「我名下所有设备」——跨**全部**家庭汇总，而不是只看第一个家庭。
 *
 * ★ 为什么必须跨家庭（真机实测根因）：
 *   原实现是 `pickFamilyUid()` 取 `fams[0]`，再用它查 `/devices?family_uid=`。
 *   但一个用户常常属于**多个**家庭，且本机设备不一定在第一个里。
 *   真机实测：妈妈属于两个家庭 ——
 *     · fam52「我的家庭」（她自己建，只含 dev49 僵尸设备）
 *     · fam43「我们家」（爸爸建，含 dev50 真机）
 *   `fams[0]` 恰好是 fam52 → 设备列表只有 dev49 → 本地闹钟页永远显示
 *   「没有待响的闹钟」，而真机（dev50）上其实有闹钟。
 *
 *   这与历史上「取设备数组第一台」是**同一类错误**（假定顺序有意义）。
 *   本机是哪台，应由「机型/名称/绑定」等证据判定，绝不该由家庭顺序决定。
 *   所以这里改为：遍历所有家庭，汇总去重后的我的设备。
 */
async function listMyDevicesAcrossFamilies() {
  const fams = (await api.get('/families')) || [];
  const seen = new Map();
  for (const f of fams) {
    if (!f || !f.uid) continue;
    let devs = [];
    try {
      devs = (await api.get('/devices?family_uid=' + encodeURIComponent(f.uid))) || [];
    } catch (_) {
      continue; // 单个家庭失败不影响其它家庭
    }
    for (const d of devs) {
      if (d && d.owner_is_me && d.uid && !seen.has(d.uid)) {
        seen.set(d.uid, d);
      }
    }
  }
  return [...seen.values()];
}

/** 取第一个可访问家庭的 uid（仅用于「绑定新设备」这类必须指定家庭的场景） */
async function pickFamilyUid() {
  const fams = await api.get('/families');
  return (fams && fams[0] && fams[0].uid) || '';
}

/**
 * 把所有待响闹钟注册进系统 AlarmManager（仅原生壳）。
 *
 * 返回实际注册成功的数量，失败的不影响其它闹钟继续注册。
 */
function registerAllToSystem() {
  if (!isNativeShell) return 0;
  let ok = 0;
  rows.forEach((x) => {
    // 只注册未来 24 小时内的（后端 within_24h 标记），过远的时间
    // 没必要占用系统资源；到点前再次进入本页会补注册。
    if (!x.alarm_time || x.alarm_time <= Date.now()) return;
    const done = registerSystemAlarm(
      x.uid,
      x.display_time || '',
      x.alarm_time,
      x.note || ''
    );
    if (done) ok += 1;
  });
  if (ok > 0) {
    console.log(`[native] 已注册 ${ok} 个系统闹钟`);
  }
  return ok;
}

/** 系统层是否已注册某个闹钟（仅原生壳有值） */
function systemStateOf(uid) {
  if (!isNativeShell) return null;
  return isSystemAlarmScheduled(uid);
}

function stopTimers() {
  if (timer) { clearInterval(timer); timer = null; }
  if (ticker) { clearInterval(ticker); ticker = null; }
}

function draw() {
  setLayout({
    title: '本地闹钟',
    tabbar: true,
    actions: [
      { text: '通知权限', onClick: askNotifyPermission },
      { text: '刷新', onClick: () => render() },
    ],
  });

  if (!rows.length) {
    setView(`
      <div class="page">
        ${emptyState('⏰', '没有待响的闹钟',
          '收到他人的闹钟后会显示在这里，本地注册失败时会由本页兜底提醒')}
        ${capabilityCard()}
      </div>`);
    return;
  }

  const now = Date.now();
  const failed = rows.filter((x) => x.needs_fallback);
  const upcoming = rows.filter((x) => !x.needs_fallback);

  const listHtml = (list) => list.map((x) => row(x, now)).join('');

  setView(`
    <div class="page">
      ${needsFallback
        ? `<div class="note warn-note">
             ⚠ 有 ${needsFallback} 个闹钟在【${escapeHtml(deviceName)}】本地注册失败 ——
             这些闹钟可能不会响。本页打开时会为你兜底提醒。
           </div>` : ''}

      ${failed.length ? `
      <div class="card">
        <div class="card-hd">
          <span class="grow">本地注册失败（${failed.length}）</span>
          <span class="tag warn">需兜底</span>
        </div>
        <div class="card-bd tight">${listHtml(failed)}</div>
      </div>` : ''}

      <div class="card">
        <div class="card-hd">
          <span class="grow">待生效闹钟（${upcoming.length}）</span>
          <span class="tag">${escapeHtml(deviceName)}</span>
        </div>
        <div class="card-bd tight">${listHtml(upcoming)}</div>
      </div>

      ${capabilityCard()}
    </div>
  `);

  bindRowActions();
}

/** 能力边界说明 —— 必须让用户知道当前环境的局限，否则会误以为闹钟一定会响 */
function capabilityCard() {
  // 原生壳里能力完全不同：闹钟注册进系统，关掉 App 也响。
  // 若仍显示"需安装 App"就是误导用户，所以这里要分环境说明。
  if (isNativeShell) {
    const exactOk = canScheduleExact();
    return `
    <div class="card">
      <div class="card-hd"><span class="grow">本机闹钟能力</span></div>
      <div class="card-bd">
        <div class="note ok-note">
          ✅ 已获得系统闹钟能力：闹钟已注册到手机系统，
          App 关闭、息屏、重启后都会准时响。
        </div>
        ${exactOk ? '' : `
        <div class="note warn-note">
          ⚠ 尚未获得「精确闹钟」权限，闹钟可能延迟几分钟。
        </div>
        <button class="btn block" id="btn-exact">去开启精确闹钟权限</button>`}
        <div class="hint">
          · 闹钟由系统唤醒，不依赖本页面是否打开<br>
          · 到点会全屏响铃并震动，点「关闭」才停
        </div>
      </div>
    </div>`;
  }

  return `
    <div class="card">
      <div class="card-hd"><span class="grow">本功能的边界</span></div>
      <div class="card-bd">
        <div class="note">
          ${capInfo && capInfo.note
            ? escapeHtml(capInfo.note)
            : '网页版只能在页面打开时响铃；关闭页面后需安装手机 App 才能准时响铃。'}
        </div>
        <div class="hint">
          · 页面打开时：到点会响铃并弹提醒（真实生效）<br>
          · 页面在后台：发送系统通知提醒（不是响铃）<br>
          · 页面已关闭：无法响铃 —— 这是浏览器的限制，需安装 App
        </div>
      </div>
    </div>`;
}

function row(x, now) {
  const left = x.alarm_time - now;
  const isFailed = x.needs_fallback;
  const past = left < 0;
  const soon = left >= 0 && left <= 60 * 60 * 1000;   // 1 小时内

  return `
    <div class="item" style="align-items:flex-start" data-alarm="${escapeHtml(x.uid)}"
         data-at="${x.alarm_time}" data-time="${escapeHtml(x.display_time)}">
      <div class="thumb" style="font-size:17px">${isFailed ? '⚠️' : '⏰'}</div>
      <div class="grow">
        <div class="t" style="font-size:17px">
          ${escapeHtml(x.display_time)}
          ${isFailed ? '<span class="tag warn">本地注册失败</span>' : ''}
          ${soon ? '<span class="tag ok">即将响铃</span>' : ''}
          ${x.source === 'remote' ? '<span class="tag">他人设置</span>' : ''}
        </div>
        <div class="s muted3">
          ${past ? '已过时间' : countdownText(left)}
          ${x.retry_count ? ` · 已重试 ${x.retry_count} 次` : ''}
          ${x.note ? ' · ' + escapeHtml(x.note) : ''}
        </div>
      </div>
      <button class="btn ghost sm" data-cancel="${escapeHtml(x.uid)}">删除</button>
    </div>`;
}

function countdownText(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h >= 24) return `${Math.floor(h / 24)} 天后`;
  if (h > 0) return `${h} 小时 ${m} 分后`;
  if (m > 0) return `${m} 分后`;
  return `${s} 秒后`;
}

function bindRowActions() {
  document.querySelectorAll('[data-cancel]').forEach((b) => {
    b.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      const uid = b.getAttribute('data-cancel');
      // 删除走设备闹钟接口（本地闹钟的删除语义与设备闹钟一致）
      try {
        await alarmApi.remove(uid);
        // 原生壳里还要把系统里的闹钟一并取消，否则删了还会响
        if (isNativeShell) cancelSystemAlarm(uid);
        toast('已删除');
        render();
      } catch (e) {
        showError(e);
      }
    });
  });

  // 原生壳：引导开启精确闹钟权限
  const exactBtn = document.getElementById('btn-exact');
  if (exactBtn) {
    exactBtn.addEventListener('click', () => openExactAlarmSettings());
  }
}

/** 每 15 秒刷新倒计时并检查是否到点 */
function startTimers() {
  stopTimers();
  ticker = setInterval(() => {
    const now = Date.now();
    document.querySelectorAll('[data-at]').forEach((el) => {
      const at = Number(el.getAttribute('data-at'));
      const key = el.getAttribute('data-alarm');
      const left = at - now;
      const sub = el.querySelector('.s');
      if (sub && left > 0) {
        sub.textContent = countdownText(left);
      }
      if (left <= 0 && left > -60_000 && !firedKeys.has(key)) {
        firedKeys.add(key);
        // 原生壳里由系统闹钟负责响铃，此处不再重复（见 checkDue 的说明）
        if (!isNativeShell) ring(el.getAttribute('data-time'));
      }
    });
  }, 15000);

  // 立即检查一次（可能有已到点的）
  checkDue();
}

function checkDue() {
  // 原生壳里到点由系统的 AlarmReceiver 拉起全屏响铃页，
  // 这里若再用 Web Audio 响一遍就是重复打扰，故跳过。
  if (isNativeShell) return;

  const now = Date.now();
  rows.forEach((x) => {
    const left = x.alarm_time - now;
    if (left <= 0 && left > -60_000 && !firedKeys.has(x.uid)) {
      firedKeys.add(x.uid);
      ring(x.display_time);
    }
  });
}

/** 响铃：Web Audio 蜂鸣 + 震动 + 通知（页面打开时真实生效） */
function ring(timeLabel) {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (Ctx) {
      const ctx = new Ctx();
      // 三声短促蜂鸣，每声 0.18s，间隔 0.25s
      for (let i = 0; i < 3; i++) {
        const o = ctx.createOscillator();
        const g = ctx.createGain();
        o.type = 'sine';
        o.frequency.value = 880;
        g.gain.value = 0.25;
        o.connect(g);
        g.connect(ctx.destination);
        const t0 = ctx.currentTime + i * 0.45;
        o.start(t0);
        o.stop(t0 + 0.18);
      }
      setTimeout(() => ctx.close && ctx.close(), 2000);
    }
  } catch { /* 浏览器未授权音频时静默跳过 */ }

  if (navigator.vibrate) navigator.vibrate([300, 150, 300, 150, 300]);

  toast(`⏰ ${timeLabel} 的闹钟响了`, 'ok', 6000);

  if ('Notification' in window && Notification.permission === 'granted') {
    try {
      new Notification('信任闹钟', {
        body: `${timeLabel} 的闹钟响了`,
        tag: 'ta-' + timeLabel,
      });
    } catch { /* 某些平台需要 Service Worker 才能发通知 */ }
  }
}

/** 请求通知权限（页面在后台时用通知提醒，虽不能响铃但能提醒） */
async function askNotifyPermission() {
  if (!('Notification' in window)) {
    toastErr('当前浏览器不支持通知');
    return;
  }
  if (Notification.permission === 'granted') {
    toast('通知权限已开启');
    return;
  }
  try {
    const p = await Notification.requestPermission();
    if (p === 'granted') toast('已开启通知，页面在后台时会推送提醒');
    else toastErr('未开启通知权限');
  } catch {
    toastErr('无法请求通知权限');
  }
}
