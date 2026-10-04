/* 全局兜底与体验增强（P7）
 *
 * 职责：
 *   1. 网络状态感知 —— 断网/服务不可达时给明确提示，而不是让页面卡住
 *   2. 全局错误兜底 —— 未捕获异常/未处理 Promise 不让页面变空白
 *   3. 会话过期统一处理 —— 令牌彻底失效时统一跳登录并说明原因
 *   4. 版本更新提示 —— Service Worker 更新后提示刷新
 *   5. 离线能力说明 —— 明确告知 PWA 无法做的事（不误导）
 *
 * 设计原则：所有提示都用人话，不出现技术名词（PRD §13）。
 */

import { escapeHtml } from './router.js';

// ---------------- 网络状态 ----------------
let online = navigator.onLine;
const banner = { el: null };

function bannerEl() {
  if (banner.el && document.body.contains(banner.el)) return banner.el;
  const el = document.createElement('div');
  el.id = 'net-banner';
  el.className = 'net-banner hidden';
  document.body.appendChild(el);
  banner.el = el;
  return el;
}

/** 显示/隐藏顶部横幅 */
export function setBanner(text, kind = 'warn', autoHideMs = 0) {
  const el = bannerEl();
  if (!text) {
    el.classList.add('hidden');
    el.innerHTML = '';
    return;
  }
  el.className = 'net-banner ' + kind;
  el.innerHTML = `<span class="grow">${escapeHtml(text)}</span>`;
  if (autoHideMs > 0) {
    setTimeout(() => setBanner(''), autoHideMs);
  }
}

export function initNetworkWatch() {
  window.addEventListener('online', () => {
    online = true;
    setBanner('网络已恢复', 'ok', 2200);
  });
  window.addEventListener('offline', () => {
    online = false;
    setBanner('当前无网络连接。已加载的页面仍可浏览，但无法获取最新数据。', 'warn');
  });
  if (!navigator.onLine) {
    setBanner('当前无网络连接。已加载的页面仍可浏览，但无法获取最新数据。', 'warn');
  }
}

/** 供 api.js 判断：离线时不要发请求，直接给明确提示 */
export function isOffline() {
  return !navigator.onLine;
}

// ---------------- 全局错误兜底 ----------------
export function initErrorGuard() {
  window.addEventListener('unhandledrejection', (ev) => {
    const reason = ev.reason;
    // 令牌类错误由 api.js 统一处理，这里不重复提示
    if (reason && (reason.status === 401 || reason.isUnauthorized)) return;
    console.error('[未处理的 Promise 拒绝]', reason);
    setBanner(
      (reason && reason.message) ? String(reason.message).slice(0, 80)
                                 : '操作出现问题，请重试',
      'err', 3600
    );
  });

  window.addEventListener('error', (ev) => {
    // 资源加载失败（如某张图片）不打断用户，只记录
    if (ev.target && ev.target !== window && ev.target.tagName) {
      console.warn('[资源加载失败]', ev.target.src || ev.target.href);
      return;
    }
    console.error('[未捕获异常]', ev.message);
  });
}

// ---------------- 会话过期统一处理 ----------------
/* api.js 在令牌彻底失效时会调用这里。
 * 集中处理的好处：不用每个页面各写一遍「跳登录 + 提示」，
 * 也避免出现「有的页面跳了、有的没跳」的不一致。 */
let sessionExpiredHandled = false;

export function onSessionExpired() {
  if (sessionExpiredHandled) return;
  sessionExpiredHandled = true;

  import('./store.js').then(async (store) => {
    store.reset();
    const { clearTokens } = await import('./api.js');
    clearTokens();
    setBanner('登录已过期，请重新登录', 'warn', 4000);
    if (location.hash !== '#/login') {
      location.hash = '#/login';
    }
    // 允许下一次过期再次提示
    setTimeout(() => { sessionExpiredHandled = false; }, 3000);
  });
}

// ---------------- 版本更新提示 ----------------
export function initUpdateWatch() {
  if (!('serviceWorker' in navigator)) return;

  // 路径 1：SW 通过 postMessage 主动通知（见 sw.js 的 activate）
  navigator.serviceWorker.addEventListener('message', (ev) => {
    if (ev.data && ev.data.type === 'SW_UPDATED') {
      setBanner('有新版本可用，下拉刷新即可更新', 'info', 6000);
    }
  });

  // 路径 2：页面侧检测到 controller 变化（新 SW 接管）也提示一次
  let reloaded = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloaded) return;
    reloaded = true;
    setBanner('已更新到新版本', 'ok', 2600);
  });
}

// ---------------- 安装引导 ----------------
const INSTALL_DISMISS_KEY = 'ta_install_dismissed_at';
const DISMISS_DAYS = 7;

/** 是否已安装（独立窗口运行） */
function isStandalone() {
  return window.matchMedia('(display-mode: standalone)').matches
      || window.navigator.standalone === true;
}

function dismissedRecently() {
  try {
    const t = Number(localStorage.getItem(INSTALL_DISMISS_KEY) || 0);
    if (!t) return false;
    return (Date.now() - t) < DISMISS_DAYS * 86400 * 1000;
  } catch {
    return false;
  }
}

export function dismissInstall() {
  try { localStorage.setItem(INSTALL_DISMISS_KEY, String(Date.now())); } catch { /* ignore */ }
}

/**
 * 安装引导：Android 走 beforeinstallprompt，iOS 只能给文字指引。
 * 已安装 / 近期忽略过 则不打扰。
 */
export function initInstallHint() {
  if (isStandalone() || dismissedRecently()) return;

  let deferredPrompt = null;

  window.addEventListener('beforeinstallprompt', (ev) => {
    ev.preventDefault();
    deferredPrompt = ev;
    showInstallBar(true, deferredPrompt);
  });

  // iOS Safari 不支持 beforeinstallprompt，用 UA 判断后给图文指引
  const ua = navigator.userAgent;
  const isIOS = /iPad|iPhone|iPod/.test(ua) && !window.MSStream;
  const isSafari = /^((?!chrome|android|crios|fxios).)*safari/i.test(ua);
  if (isIOS && isSafari && !isStandalone()) {
    showInstallBar(false, null);
  }
}

function showInstallBar(canPrompt, promptEvent) {
  const el = document.getElementById('install-bar');
  if (!el) return;
  el.classList.remove('hidden');

  // 关闭按钮（用 addEventListener 而非内联 onclick：CSP 禁止内联脚本）
  const closeBtn = el.querySelector('.ib-close');
  if (closeBtn && !closeBtn.dataset.bound) {
    closeBtn.dataset.bound = '1';
    closeBtn.addEventListener('click', () => {
      el.classList.add('hidden');
      dismissInstall();
    });
  }

  const text = el.querySelector('.ib-text');
  const btn = el.querySelector('.ib-btn');

  if (canPrompt) {
    text.textContent = '添加到主屏幕，像 App 一样打开';
    btn.textContent = '添加';
    btn.onclick = async () => {
      el.classList.add('hidden');
      try {
        promptEvent.prompt();
        await promptEvent.userChoice;
      } catch { /* 用户取消或浏览器不支持，忽略 */ }
    };
  } else {
    text.innerHTML = '添加到主屏幕：点底部 <b>分享</b> → <b>添加到主屏幕</b>';
    btn.textContent = '知道了';
    btn.onclick = () => {
      el.classList.add('hidden');
      dismissInstall();
    };
  }
}

// ---------------- 离线能力说明 ----------------
/** 在设置页等处展示，明确 PWA 的能力边界，不误导用户 */
export function capabilityNote() {
  return `PWA 版可在浏览器中完成全部业务操作（家庭、设备、闹钟、信任、授权），
但受浏览器限制，无法：
· 静默创建手机系统闹钟（需安装原生 App）
· 在 iPhone 上代你完成闹钟确认
· 关闭页面后仍上报在线状态`;
}
