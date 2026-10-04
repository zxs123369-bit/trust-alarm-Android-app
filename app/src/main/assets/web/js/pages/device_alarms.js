/* 设备闹钟列表页（P3）
 *
 * 覆盖 PRD §4.1 三种结果：
 *   - 免确认成功（Android 信任成员 / 本人）
 *   - 需对方确认（need_dialog=true）→ 展示服务端 dialog_text 原文
 *   - iOS 需 App 内确认（need_local_confirm=true）
 *
 * confirm_status: 0=待确认 1=已确认
 * source:        'remote' 他人下发 / 'local' 本人
 */

import { alarmApi, deviceApi } from '../api.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, showError, toastOk, confirmBox,
  hhmmToMsToday, fmtRelative, errorState } from '../ui.js';
import { escapeHtml } from '../router.js';

let deviceUid = null;
let deviceName = '设备';
let data = null;      // { list, total, view_source }

export async function render(params) {
  deviceUid = params.uid;
  setLayout({ title: '闹钟', backBtn: true, tabbar: false });
  setView(`<div class="page">${skeleton(3)}</div>`);

  // 设备名用于标题与文案，取不到就退回通用名
  try {
    const d = await deviceApi.detail(deviceUid);
    deviceName = d.device_name || deviceName;
    window.__deviceOwnerIsMe = !!d.owner_is_me;
    window.__devicePlatform = d.platform;
  } catch { /* 忽略，用默认名 */ }

  try {
    data = await alarmApi.list(deviceUid);
  } catch (e) {
    showError(e);
    setView(`<div class="page">${errorState(e, () => render(params), { backHref: '#/devices' })}</div>`);
    return;
  }
  scrollTop();
  draw();
  return () => { /* 无轮询 */ };
}

function draw() {
  const list = (data && data.list) || [];
  const total = (data && data.total) || 0;
  const vs = (data && data.view_source) || '';

  setLayout({
    title: `${deviceName} 的闹钟`,
    backBtn: true,
    tabbar: false,
    actions: [{ text: '新建', onClick: () => openCreateSheet() }],
  });

  if (!list.length) {
    setView(`
      <div class="page">
        ${emptyState('⏰', '还没有闹钟', '点右上角「新建」为该设备设置闹钟')}
        <button class="btn block" id="btn-new">新建闹钟</button>
        <div class="note mt12">
          当前身份：${escapeHtml(vsText(vs))}<br>
          他人下发的闹钟需设备主人确认后才会生效。
        </div>
      </div>
    `);
    const nb = document.getElementById('btn-new');
    if (nb) nb.addEventListener('click', () => openCreateSheet());
    return;
  }

  // ---- 分组：把「要处理的」和「已过去的」分开 ----
  //
  // 用户反馈「查看自己的闹钟非常杂乱」—— 原来所有闹钟平铺，
  // 待确认的、将来的、已经响过的混在一起，看不出该关注什么。
  // 按「需要你动手 > 将要响 > 已过去」分三组，每组内按时间排序。
  const now = Date.now();
  const needAction = [];   // 待我确认 —— 唯一需要用户动手的
  const upcoming = [];     // 将要响（按时间升序，最近的在前）
  const past = [];         // 已过期/已响过（按时间降序，最近的在前）

  list.forEach((a) => {
    if (a.confirm_status === 0) needAction.push(a);
    else if (a.alarm_time > now) upcoming.push(a);
    else past.push(a);
  });
  upcoming.sort((x, y) => x.alarm_time - y.alarm_time);
  past.sort((x, y) => y.alarm_time - x.alarm_time);

  const section = (title, arr, hint) => arr.length ? `
    <div class="card-hd" style="padding:4px 2px 8px">
      <span class="grow">${title}（${arr.length}）</span>
      ${hint ? `<span class="muted small">${hint}</span>` : ''}
    </div>
    ${arr.map((a) => alarmCard(a)).join('')}
  ` : '';

  setView(`
    <div class="page">
      <div class="row between mb8">
        <div class="muted small">共 ${total} 个闹钟</div>
        <div class="muted small">我的权限：${escapeHtml(vsText(vs))}</div>
      </div>

      ${section('需要你确认', needAction, '这些人想为你的设备设闹钟')}
      ${section('将要响铃', upcoming, '按时间先后')}
      ${section('已过去', past, '')}

      <div class="note mt12">
        <b>关于确认：</b>他人为你设置的闹钟，需你确认后才会在本机生效；
        信任成员在你开启信任开关后可免确认（iPhone 仍需在 App 内确认一次）。
      </div>
    </div>
  `);

  bindEvents();
}

/** 单条闹钟卡片 */
function alarmCard(a) {
  const pending = a.confirm_status === 0;
  const time = a.display_time || '--:--';
  const isFuture = a.alarm_time > Date.now();

  const tags = [];
  if (pending) tags.push('<span class="tag warn">待确认</span>');
  else tags.push('<span class="tag ok">已确认</span>');
  if (a.source === 'remote') {
    tags.push('<span class="tag info">他人设置</span>');
  } else {
    tags.push('<span class="tag">本机</span>');
  }
  if (!isFuture) tags.push('<span class="tag">已过期</span>');

  return `
    <div class="card">
      <div class="card-bd">
        <div class="row between">
          <div>
            <div style="font-size:30px;font-weight:600;line-height:1.15;letter-spacing:1px">
              ${escapeHtml(time)}
            </div>
            <div class="muted small mt8">
              ${a.note ? escapeHtml(a.note) : '（无备注）'}
            </div>
          </div>
          <div style="text-align:right">${tags.join(' ')}</div>
        </div>

        <div class="muted small mt8">
          ${a.alarm_time ? (isFuture ? '将于 ' : '已于 ') + fmtAbs(a.alarm_time) : ''}
          ${isFuture && sameDay(a.alarm_time) ? '<span class="tag ok" style="margin-left:6px">今天</span>' : ''}
        </div>

        <div class="btn-row mt12">
          ${pending ? `
            <button class="btn sm" data-confirm="${escapeHtml(a.uid)}">同意</button>
            <button class="btn ghost sm" data-reject="${escapeHtml(a.uid)}">拒绝</button>
          ` : `
            <button class="btn ghost sm" data-edit="${escapeHtml(a.uid)}"
                    data-hour="${hh(a.display_time)}" data-minute="${mm(a.display_time)}"
                    data-at="${a.alarm_time || ''}"
                    data-note="${escapeHtml(a.note || '')}">修改</button>
            <button class="btn danger-ghost sm" data-del="${escapeHtml(a.uid)}">删除</button>
          `}
        </div>
      </div>
    </div>`;
}

/** 该时间戳是否落在今天（用于「今天」标签） */
function sameDay(ms) {
  const d = new Date(Number(ms));
  const n = new Date();
  return d.getFullYear() === n.getFullYear()
    && d.getMonth() === n.getMonth()
    && d.getDate() === n.getDate();
}

function hh(t) { return t ? String(t).split(':')[0] : '7'; }
function mm(t) { return t ? String(t).split(':')[1] : '0'; }

function fmtAbs(ms) {
  const d = new Date(Number(ms));
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function vsText(vs) {
  return {
    owner: '设备归属人',
    family_owner: '家庭群主',
    trust_member: '信任成员',
    granted: '已授权查看',
    creator: '闹钟创建人',
    none: '无查看权限',
  }[vs] || (vs || '—');
}

function bindEvents() {
  document.querySelectorAll('[data-confirm]').forEach((b) => {
    b.addEventListener('click', () => doConfirm(b.getAttribute('data-confirm'), true));
  });
  document.querySelectorAll('[data-reject]').forEach((b) => {
    b.addEventListener('click', () => doConfirm(b.getAttribute('data-reject'), false));
  });
  document.querySelectorAll('[data-edit]').forEach((b) => {
    b.addEventListener('click', () => openEditSheet(
      b.getAttribute('data-edit'),
      b.getAttribute('data-hour'),
      b.getAttribute('data-minute'),
      b.getAttribute('data-note'),
      b.getAttribute('data-at'),
    ));
  });
  document.querySelectorAll('[data-del]').forEach((b) => {
    b.addEventListener('click', () => doDelete(b.getAttribute('data-del')));
  });
}

// ---------------- 时间选择弹层 ----------------

/** 把 Date 格式化成 YYYY-MM-DD（本地时区，不能用 toISOString 会差一天） */
function toDateStr(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function todayStr() { return toDateStr(new Date()); }
function tomorrowStr() {
  const d = new Date(); d.setDate(d.getDate() + 1); return toDateStr(d);
}

/**
 * 时间选择弹层。
 *
 * 设计要点（对齐用户反馈）：
 *   · 加「日期」选择：原来只有时分，无法表达「明天/下周三 07:30」
 *   · 加「今天/明天」快捷切换：这是最高频的两种意图，一键即可
 *   · 时间用原生 <input type="time">：手机上直接调系统时间轮盘，
 *     比原来两个 number 输入框快得多，也避免手动输入越界
 *   · 实时预览「还有多久响」，让用户确认选对了没
 *
 * @returns {Promise<null|{hour,minute,date,note,alarm_time_ms}>}
 */
async function timeSheet({
  title, hour = 7, minute = 30, date = '', note = '', okText = '确定',
}) {
  const { openModal } = await import('../ui.js');

  const frag = document.createDocumentFragment();

  // ---- 日期：今天 / 明天 快捷 + 具体日期 ----
  const df = document.createElement('div');
  df.className = 'field';
  df.innerHTML = '<label>日期</label>';

  const quick = document.createElement('div');
  quick.className = 'btn-row';
  quick.style.marginBottom = '8px';

  const dateInp = document.createElement('input');
  dateInp.type = 'date';
  dateInp.value = date || todayStr();
  dateInp.min = todayStr();   // 不允许选过去（后端也拒绝）

  const bToday = document.createElement('button');
  bToday.className = 'btn sm';
  bToday.type = 'button';
  bToday.textContent = '今天';
  const bTom = document.createElement('button');
  bTom.className = 'btn ghost sm';
  bTom.type = 'button';
  bTom.textContent = '明天';

  const syncQuick = () => {
    const v = dateInp.value;
    bToday.className = v === todayStr() ? 'btn sm' : 'btn ghost sm';
    bTom.className = v === tomorrowStr() ? 'btn sm' : 'btn ghost sm';
  };
  bToday.onclick = () => { dateInp.value = todayStr(); syncQuick(); refresh(); };
  bTom.onclick = () => { dateInp.value = tomorrowStr(); syncQuick(); refresh(); };
  dateInp.addEventListener('input', () => { syncQuick(); refresh(); });

  quick.append(bToday, bTom);
  df.append(quick, dateInp);

  // ---- 时间：原生 time 控件 ----
  const tf = document.createElement('div');
  tf.className = 'field';
  tf.innerHTML = '<label>时间</label>';
  const timeInp = document.createElement('input');
  timeInp.type = 'time';
  timeInp.value = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  timeInp.style.fontSize = '26px';
  timeInp.style.textAlign = 'center';
  tf.appendChild(timeInp);

  // ---- 备注 ----
  const nf = document.createElement('div');
  nf.className = 'field';
  nf.innerHTML = '<label>备注（可选）</label>';
  const noteInp = document.createElement('input');
  noteInp.type = 'text';
  noteInp.maxLength = 30;
  noteInp.value = note;
  noteInp.placeholder = '如：起床';
  nf.appendChild(noteInp);

  // ---- 预览：把「选了什么」讲清楚，含相对时间 ----
  const preview = document.createElement('div');
  preview.className = 'note';
  const refresh = () => {
    const tv = timeInp.value || '07:30';
    const dv = dateInp.value || todayStr();
    const [y, mo, d] = dv.split('-').map(Number);
    const [h2, m2] = tv.split(':').map(Number);
    const when = new Date(y, mo - 1, d, h2, m2, 0, 0);
    const diff = when.getTime() - Date.now();

    if (diff <= 0) {
      preview.innerHTML = '<span class="warn">⚠ 这个时间已经过了，请重新选择</span>';
      return;
    }
    const mins = Math.round(diff / 60000);
    const rel = mins < 60
      ? `${mins} 分钟后`
      : mins < 60 * 24
        ? `${Math.floor(mins / 60)} 小时后`
        : `${Math.floor(mins / 1440)} 天后`;

    const label = dv === todayStr() ? '今天'
      : dv === tomorrowStr() ? '明天'
        : `${mo}月${d}日`;
    preview.innerHTML = `将在 <b>${label} ${tv}</b> 响起　<span class="muted">（${rel}）</span>`;
  };
  timeInp.addEventListener('input', refresh);
  syncQuick();
  refresh();

  frag.append(df, tf, nf, preview);

  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    openModal({
      title,
      body: frag,
      center: true,
      actions: [
        { text: '取消', kind: 'ghost', onClick: () => finish(null) },
        {
          text: okText,
          onClick: () => {
            const tv = timeInp.value || '';
            const dv = dateInp.value || '';
            if (!tv || !dv) { finish(null); return; }
            const [y, mo, d] = dv.split('-').map(Number);
            const [h2, m2] = tv.split(':').map(Number);
            const when = new Date(y, mo - 1, d, h2, m2, 0, 0);
            if (when.getTime() <= Date.now()) {
              // 选了过去时间：不静默改成别的日子，让用户自己决定
              preview.innerHTML = '<span class="warn">⚠ 这个时间已经过了，请重新选择</span>';
              return false;   // 阻止关闭弹层
            }
            finish({
              hour: h2,
              minute: m2,
              date: dv,
              alarm_time_ms: when.getTime(),
              note: noteInp.value.trim() || null,
            });
          },
        },
      ],
      onClose: () => finish(null),
    });
  });
}

// ---------------- 新建 ----------------
async function openCreateSheet() {
  // 默认值选一个「最近的将来时刻」比固定 7:30 更省事：
  // 多数场景是设「再过一会儿」的闹钟，默认值越接近用户意图，改动越少。
  const guess = new Date(Date.now() + 10 * 60 * 1000);
  const t = await timeSheet({
    title: `为「${deviceName}」设置闹钟`,
    hour: guess.getHours(),
    minute: guess.getMinutes(),
  });
  if (!t) return;

  try {
    const res = await alarmApi.create(deviceUid, {
      alarm_time_ms: t.alarm_time_ms,
      date: t.date,
      hour: t.hour,
      minute: t.minute,
    }, t.note);
    await showCreateResult(res);
    await reload();
  } catch (e) {
    showError(e);
  }
}

/** 把后端返回的三种结果翻译成人话（PRD §4.1） */
async function showCreateResult(res) {
  const { toast, openModal } = await import('../ui.js');
  const d = res || {};
  const alarm = d.alarm || {};
  const time = alarm.display_time || '';

  let title, body, kind;

  if (d.need_local_confirm) {
    // iOS：需在手机 App 内确认（PWA 无法代劳）
    title = '已发送，需在手机上确认';
    kind = 'warn';
    body = `闹钟 ${time} 已发送到「${deviceName}」。\n\n` +
           `iPhone 不允许应用静默创建闹钟，需要在手机 App 内手动确认一次。\n` +
           `（PWA 版无法完成这一步，属原生 App 的功能）`;
  } else if (d.need_dialog) {
    // 需设备主人确认：展示服务端定稿文案
    title = '已发送申请，等待确认';
    kind = 'warn';
    body = `${d.dialog_text || '已向设备主人发送确认申请'}\n\n` +
           `设备主人在「消息」中同意后，该闹钟才会生效。`;
  } else {
    title = '设置成功';
    kind = 'ok';
    body = `闹钟 ${time} 已设置到「${deviceName}」。`;
  }

  toast(body.split('\n')[0], kind === 'ok' ? 'ok' : 'info', 3000);

  // 用弹层把完整说明呈现出来（iOS 与需确认两种情况信息量大）
  if (kind !== 'ok') {
    const el = document.createElement('div');
    el.className = 'note ' + (kind === 'warn' ? 'warn' : '');
    el.style.whiteSpace = 'pre-wrap';
    el.textContent = body;
    openModal({
      title,
      body: el,
      center: true,
      actions: [{ text: '知道了' }],
    });
  }
}

// ---------------- 修改 ----------------
async function openEditSheet(uid, hour, minute, note, alarmTimeMs) {
  // 传当前闹钟的日期进去，编辑时默认停在原日期上（而不是每次回到今天）
  let curDate = '';
  if (alarmTimeMs) {
    const d = new Date(Number(alarmTimeMs));
    const p = (n) => String(n).padStart(2, '0');
    curDate = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  }

  const t = await timeSheet({
    title: '修改闹钟',
    hour: parseInt(hour, 10) || 7,
    minute: parseInt(minute, 10) || 0,
    date: curDate,
    note,
    okText: '保存',
  });
  if (!t) return;
  try {
    await alarmApi.update(uid, {
      alarm_time_ms: t.alarm_time_ms,
      date: t.date,
      hour: t.hour,
      minute: t.minute,
      note: t.note,
    });
    toastOk('已保存');
    await reload();
  } catch (e) {
    showError(e);
  }
}

// ---------------- 删除 ----------------
async function doDelete(uid) {
  const yes = await confirmBox('删除闹钟', '确定删除该闹钟？删除后不再响起。',
                               { okText: '删除', danger: true });
  if (!yes) return;
  try {
    await alarmApi.remove(uid);
    toastOk('已删除');
    await reload();
  } catch (e) {
    showError(e);
  }
}

// ---------------- 确认 / 拒绝 ----------------
async function doConfirm(uid, approve) {
  const yes = await confirmBox(
    approve ? '同意该闹钟' : '拒绝该闹钟',
    approve ? '同意后该闹钟将在设备上生效。' : '拒绝后该闹钟不会生效。',
    { okText: approve ? '同意' : '拒绝', danger: !approve }
  );
  if (!yes) return;
  try {
    await alarmApi.confirm(uid, approve);
    toastOk(approve ? '已同意' : '已拒绝');
    await reload();
  } catch (e) {
    showError(e);
  }
}

async function reload() {
  try {
    data = await alarmApi.list(deviceUid);
    draw();
  } catch (e) {
    showError(e);
  }
  // 未读数可能变化（确认后消息状态变），顺带刷新
  if (window.__app && window.__app.refreshUnread) window.__app.refreshUnread();
}
