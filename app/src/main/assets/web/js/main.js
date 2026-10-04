/* 启动入口：注册路由、恢复登录态、启动路由 */

import {
  restoreSession, authApi, clearTokens, getRefreshToken,
  setSessionExpiredHandler,
} from './api.js';
import {
  initNetworkWatch, initErrorGuard, initUpdateWatch, initInstallHint,
  onSessionExpired,
} from './pwa.js';
import { initRingFallback } from './ring_fallback.js';
import { route, startRouter, go, currentPath } from './router.js';
import { setLayout, setView } from './layout.js';
import { loadingBlock, installErrorRetryDelegate } from './ui.js';
import * as store from './store.js';
import { messageApi } from './api.js';

import * as pageLogin from './pages/login.js';
import * as pageFamilies from './pages/families.js';
import * as pageFamily from './pages/family.js';
import * as pageFamilyLogs from './pages/family_logs.js';
import * as pageMember from './pages/member.js';
import * as pageDevices from './pages/devices.js';
import * as pageLocalAlarms from './pages/local_alarms.js';
import * as pageDevice from './pages/device.js';
import * as pageDeviceAlarms from './pages/device_alarms.js';
import * as pageDeviceTrust from './pages/device_trust.js';
import * as pageDeviceGrants from './pages/device_grants.js';
import * as pageMessages from './pages/messages.js';
import * as pageRequests from './pages/requests.js';
import * as pageLogs from './pages/logs.js';
import * as pageSettings from './pages/settings.js';
import * as pageReliability from './pages/reliability.js';

// ---------------- 路由表 ----------------
route('/login', () => pageLogin.render());
route('/families', guarded(pageFamilies.render));
route('/family/:uid', guarded(pageFamily.render));
// 成员详情：必须注册在 /family/:uid 之后（更具体路径优先匹配）
route('/family/:uid/member/:memberUid', guarded(pageMember.render));
route('/family/:uid/logs', guarded(pageFamilyLogs.render));
route('/devices', guarded(pageDevices.render));
  route('/local-alarms', guarded(pageLocalAlarms.render));
// 注意：更具体的路径要注册在 /device/:uid 之前，否则 ':uid' 会吞掉 'alarms'
route('/device/:uid/alarms', guarded(pageDeviceAlarms.render));
route('/device/:uid/logs', guarded(pageLogs.render));
route('/device/:uid/trust', guarded(pageDeviceTrust.render));
route('/device/:uid/grants', guarded(pageDeviceGrants.render));
route('/device/:uid', guarded(pageDevice.render));
route('/messages', guarded(pageMessages.render));
route('/requests', guarded(pageRequests.render));
route('/logs', guarded(pageLogs.render));
route('/settings', guarded(pageSettings.render));
// 后台可靠性引导（国产 ROM 上决定闹钟能否准时响）
route('/reliability', guarded(pageReliability.render));
// 默认首页：本地闹钟（用户要求它作为主页）
route('/', () => go('/local-alarms', { replace: true }));

/** 需要登录的页面包装：未登录则跳登录页 */
function guarded(renderFn) {
  return (params) => {
    if (!store.getUser()) {
      go('/login', { replace: true });
      return;
    }
    return renderFn(params);
  };
}

// ---------------- 启动 ----------------
async function boot() {
  // 全局兜底先装上：网络状态、错误、会话过期、版本更新、安装引导
  initNetworkWatch();
  initErrorGuard();
  initUpdateWatch();
  initInstallHint();
  // ★ 错误页「重试」按钮的事件委托（只需装一次）。
  //   各页的错误态由 errorState() 渲染，回调通过 data-retry 注册。
  installErrorRetryDelegate();
  // ★ 响铃兜底：Android 10+ 会拦后台启动 Activity，导致「有声音但没界面」。
  //   这里在网页内自查并弹出响铃层 —— 不依赖系统的后台启动能力，
  //   是国产 ROM 上唯一可靠的兜底路径（见 ring_fallback.js）。
  initRingFallback();
  setSessionExpiredHandler(onSessionExpired);

  setLayout({ title: '信任闹钟', tabbar: false });
  setView(`<div class="page">${loadingBlock('正在恢复登录状态…')}</div>`);

  // 有 refresh_token 就尝试静默恢复会话（access_token 只在内存，刷新页面会丢）
  if (getRefreshToken()) {
    const ok = await restoreSession();
    if (ok) {
      try {
        const me = await authApi.me();
        store.setUser(me);
      } catch {
        clearTokens();
      }
    } else {
      clearTokens();
    }
  }

  store.markBooted();

  // App 自更新检查（仅原生壳有效；浏览器里静默跳过）。
  // 放在启动末端：不阻塞首屏，且此时网络与登录态都已就绪。
  // 用 silent —— 无更新时不打扰；有更新才弹提示。
  try {
    const { checkAppUpdate } = await import('./update.js');
    checkAppUpdate({ silent: true });
  } catch { /* 更新检查失败不影响使用 */ }

  const path = currentPath();
  // 已登录却停在登录页 → 直接进首页
  if (store.getUser() && (path === '/login' || path === '/')) {
    go('/local-alarms', { replace: true });   // 新主页：本地闹钟
  } else if (!store.getUser() && path !== '/login') {
    go('/login', { replace: true });
  }

  startRouter();
  refreshUnread();
  // 每 30 秒刷新未读数（消息是轮询模式，PRD 一期无推送）
  setInterval(refreshUnread, 30000);
}

async function refreshUnread() {
  if (!store.getUser()) return;
  try {
    const d = await messageApi.list();
    const list = (d && d.list) || [];
    store.setUnread(list.filter((m) => !m.is_read).length);
  } catch {
    /* 静默失败：不打断用户 */
  }
}

// 暴露给页面模块使用（避免循环 import）
window.__app = { go, refreshUnread };

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}

// 注册 Service Worker（离线壳）
/* [Hermes] WebView 环境下不注册 Service Worker：
   file:// 协议下 SW 不可用，且缓存会导致「网页改了 App 没变」。
   同步脚本 android/sync_web.py 会自动剥离本块。 */
