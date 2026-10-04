/* 我的 / 设置页（P6）
 *
 * 后端真实契约：
 *   GET    /auth/me            → {uid,phone_masked,nickname,avatar_url,is_unlimited,created_at}
 *   GET    /auth/sessions      → 登录设备列表
 *   DELETE /auth/sessions/{id} → 吊销指定会话
 *   POST   /auth/logout        → 登出当前
 *   POST   /auth/logout-all    → 登出全部设备
 *   GET    /devices?family_uid → 统计用
 *   GET    /messages           → 未读统计
 */

import { authApi, familyApi, messageApi } from '../api.js';
import { isNativeShell, stopHeartbeat, clearNativeSession } from '../native.js';
import { setLayout, setView, go, scrollTop } from '../layout.js';
import {
  skeleton, emptyState, showError, toastOk, confirmBox, fmtRelative, fmtTime,
  guardReentry,
} from '../ui.js';
import * as store from '../store.js';
import { escapeHtml } from '../router.js';

let me = null;
let families = [];
let sessions = [];
let unread = 0;

export async function render() {
  setLayout({ title: '我的', tabbar: true, activeTab: '/settings' });
  setView(`<div class="page">${skeleton(3)}</div>`);
  scrollTop();

  // 并发拉取；sessions 失败不影响主展示（有的部署可能不开该接口）
  const [meR, famR, msgR, sesR] = await Promise.allSettled([
    authApi.me(),
    familyApi.list(),
    messageApi.list(),
    authApi.sessions(),
  ]);

  if (meR.status === 'fulfilled') {
    me = meR.value;
    store.setUser(me);
  } else {
    showError(meR.reason);
  }
  families = famR.status === 'fulfilled' ? (famR.value || []) : [];
  const msgList = msgR.status === 'fulfilled'
    ? ((msgR.value && msgR.value.list) || []) : [];
  unread = msgList.filter((m) => !m.is_read).length;
  sessions = sesR.status === 'fulfilled'
    ? norm(sesR.value) : [];
  if (sesR.status === 'rejected') {
    console.warn('[settings] 会话列表获取失败', sesR.reason);
  }

  store.setUnread(unread);
  draw();
  return () => { /* 无轮询 */ };
}

function norm(r) {
  if (Array.isArray(r)) return r;
  return (r && r.list) || [];
}

function draw() {
  setLayout({ title: '我的', tabbar: true, activeTab: '/settings' });

  const memberOf = families.length;

  setView(`
    <div class="page">
      <div class="card">
        <div class="card-bd">
          <div class="row" style="gap:12px">
            <div class="thumb" style="width:52px;height:52px;font-size:24px">👤</div>
            <div class="grow">
              <div style="font-size:17px;font-weight:600">${escapeHtml(me?.nickname || '—')}</div>
              <div class="muted small mt8">${escapeHtml(me?.phone_masked || '')}</div>
              <div class="mt8">
                ${me?.is_unlimited ? '<span class="tag ok">免限流</span>' : ''}
                <span class="tag">${memberOf} 个家庭</span>
                ${unread ? `<span class="tag err">${unread} 条未读</span>` : ''}
              </div>
            </div>
          </div>
          ${me?.created_at
            ? `<div class="muted small mt12">注册于 ${fmtTime(me.created_at)}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <div class="card-hd">快捷入口</div>
        <div class="card-bd tight">
          <a class="item" href="#/families">
            <div class="thumb">🏠</div>
            <div class="grow"><div class="t">我的家庭</div>
              <div class="s">${memberOf} 个家庭</div></div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/devices">
            <div class="thumb">📱</div>
            <div class="grow"><div class="t">我的设备</div>
              <div class="s">查看与管理设备</div></div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/messages">
            <div class="thumb">🔔</div>
            <div class="grow"><div class="t">消息</div>
              <div class="s">${unread ? `${unread} 条未读` : '暂无未读'}</div></div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/requests">
            <div class="thumb">✋</div>
            <div class="grow"><div class="t">查看申请</div>
              <div class="s">同意或拒绝他人的查看申请</div></div>
            <span class="arrow">›</span>
          </a>
          <a class="item" href="#/reliability">
            <div class="thumb">⏰</div>
            <div class="grow"><div class="t">后台可靠性设置</div>
              <div class="s">闹钟不响？先检查这几项系统权限</div></div>
            <span class="arrow">›</span>
          </a>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">
          <span class="grow">登录设备（${sessions.length}）</span>
          <button class="btn ghost sm" id="btn-ses">刷新</button>
        </div>
        <div class="card-bd tight">
          ${sessions.length ? sessions.map(sessionRow).join('')
            : '<div class="card-bd muted small">暂无其他登录设备</div>'}
        </div>
      </div>

      <div class="card">
        <div class="card-bd">
          <button class="btn ghost block mb8" id="btn-logout">退出登录</button>
          <button class="btn danger-ghost block" id="btn-logout-all">退出所有设备</button>
          <div class="hint">退出所有设备会立即失效所有端的登录状态</div>
        </div>
      </div>

      <div class="card" id="card-update" style="display:none">
        <div class="card-hd"><span class="grow">应用更新</span></div>
        <div class="card-bd">
          <div class="muted small" id="app-ver"></div>
          <button class="btn block mt8" id="btn-check-update">检查更新</button>
        </div>
      </div>

      <div class="note mt12">
        <b>关于本客户端</b><br>
        PWA 版（<span class="mono">${escapeHtml(location.host)}</span>）
        可在浏览器中完成全部业务操作：家庭、设备、闹钟、信任、授权。
        <br><br>
        受浏览器限制，以下功能需安装原生 App：
        <br>· 静默创建手机系统闹钟
        <br>· 在 iPhone 上代为完成闹钟确认
        <br>· 关闭页面后仍上报在线状态
      </div>
    </div>
  `);

  const sesBtn = document.getElementById('btn-ses');
  if (sesBtn) sesBtn.addEventListener('click', guardReentry(sesBtn, refreshSessions));
  document.getElementById('btn-logout').addEventListener('click', logout);
  document.getElementById('btn-logout-all').addEventListener('click', logoutAll);
  document.querySelectorAll('[data-kill]').forEach((b) => {
    b.addEventListener('click', () => killSession(b.getAttribute('data-kill')));
  });

  // 应用更新卡片：仅原生壳显示（浏览器刷新即最新，无需自更新）
  const upCard = document.getElementById('card-update');
  if (upCard) {
    import('../update.js').then((m) => {
      if (!m.canSelfUpdate) return;
      upCard.style.display = '';
      const verEl = document.getElementById('app-ver');
      const btn = document.getElementById('btn-check-update');
      try {
        const info = JSON.parse(window.TrustAlarmUpdater.checkUpdate() || '{}');
        if (verEl) verEl.textContent = `当前版本 v${info.currentVersion || '—'}`;
      } catch { /* 拿不到就留空 */ }
      if (btn) btn.addEventListener('click', () => m.checkAppUpdate({ force: true }));
    }).catch(() => { /* 非壳环境无妨 */ });
  }
}

/* 会话条目
 * 后端真实字段（实测）：id(int) user_agent created_at expires_at is_current(bool)
 * 没有 device_info / ip 字段，不要臆造。 */
function sessionRow(s) {
  const id = s.id;
  const cur = s.is_current === true;
  const ua = s.user_agent || '';
  const when = s.created_at ? fmtRelative(s.created_at) : '';
  const exp = s.expires_at ? fmtTime(s.expires_at) : '';

  // user_agent 对用户不友好，先做友好化归类（PRD §13：界面不出现技术标识）
  const kind = uaKind(ua);

  return `
    <div class="item">
      <div class="thumb">${cur ? '📲' : kind.icon}</div>
      <div class="grow">
        <div class="t">${escapeHtml(kind.text)}
          ${cur ? '<span class="tag ok">当前</span>' : ''}</div>
        <div class="s">
          ${when ? '登录于 ' + when : ''}${exp ? ' · 至 ' + exp : ''}
        </div>
      </div>
      ${cur ? '' : `<button class="btn danger-ghost sm" data-kill="${escapeHtml(String(id))}">下线</button>`}
    </div>`;
}

/** 把 user_agent 归类成用户看得懂的说法 */
function uaKind(ua) {
  const s = String(ua || '').toLowerCase();
  if (!s) return { icon: '❓', text: '未知设备' };
  if (s.includes('iphone') || s.includes('ipad') || s.includes('ios')) {
    return { icon: '📱', text: 'iPhone / iPad' };
  }
  if (s.includes('android')) return { icon: '🤖', text: 'Android 手机' };
  if (s.includes('windows')) return { icon: '💻', text: 'Windows 电脑' };
  if (s.includes('mac os') || s.includes('macintosh')) {
    return { icon: '💻', text: 'Mac 电脑' };
  }
  if (s.includes('linux')) return { icon: '💻', text: 'Linux 设备' };
  if (s.includes('python') || s.includes('curl') || s.includes('httpx')) {
    return { icon: '🧪', text: '测试客户端' };
  }
  return { icon: '💻', text: '其他设备' };
}

async function refreshSessions() {
  try {
    sessions = norm(await authApi.sessions());
    draw();
    toastOk('已刷新');
  } catch (e) {
    showError(e);
  }
}

async function killSession(id) {
  const ok = await confirmBox(
    '下线该设备',
    '该设备上的登录将立即失效，需要重新登录。',
    { okText: '下线', danger: true }
  );
  if (!ok) return;
  try {
    await authApi.revokeSession(id);
    toastOk('已下线');
    await refreshSessions();
  } catch (e) {
    showError(e);
  }
}

async function logout() {
  const ok = await confirmBox('退出登录', '退出后需要重新输入手机号和密码。',
                              { okText: '退出' });
  if (!ok) return;
  try {
    await authApi.logout();
  } catch { /* 即使服务端调用失败，本地也要清干净 */ }
  doLocalLogout();
}

async function logoutAll() {
  const ok = await confirmBox(
    '退出所有设备',
    '所有设备（含当前）的登录都会失效，需要重新登录。',
    { okText: '全部退出', danger: true }
  );
  if (!ok) return;
  try {
    await authApi.logoutAll();
  } catch { /* 同上 */ }
  doLocalLogout();
}

async function doLocalLogout() {
  const { clearTokens } = await import('../api.js');
  clearTokens();
  store.reset();

  // 原生壳：停掉心跳并清掉原生存的令牌与设备绑定。
  // 不清的话：心跳会继续以旧身份上报，且换号登录后仍绑着上一个账号的设备。
  if (isNativeShell) {
    stopHeartbeat();
    clearNativeSession();
  }

  toastOk('已退出');
  go('/login', { replace: true });
}
