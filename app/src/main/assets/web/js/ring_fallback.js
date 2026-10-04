/* 响铃兜底层 —— App 内最后一道保险
 *
 * ## 为什么需要（用户实测「响了但界面不弹、进软件也看不到」）
 *
 * Android 10+ 会**拦截后台 App 启动 Activity**，系统日志为：
 *   `Background activity launch blocked ... allowsBackgroundActivityStarts=false`
 *
 * 后果：闹钟到点时
 *   · 铃声在响（AlarmRingService 的 MediaPlayer）✅
 *   · 震动在震 ✅
 *   · 通知也在 ✅
 *   · **但全屏响铃界面起不来** ❌
 * 用户点亮屏幕后只听到声音，看不到是谁在响、也不知道怎么关 ——
 * 这正是用户反馈的现象。
 *
 * ## 本模块做什么
 *
 * 不跟系统对着干（那不可靠），而是**换一条必胜的路**：
 * 网页加载后主动向原生查询「是否有闹钟正在响」，若有就在网页内
 * 渲染一个全屏响铃层。这条路完全不依赖「后台启动 Activity」能力，
 * 因此在任何 ROM 上都成立。
 *
 * 触发时机（三条，覆盖用户所有可能的路径）：
 *   1. App 冷启动 / 页面加载完成
 *   2. 页面从后台回到前台（visibilitychange）
 *   3. 定时轮询（每 5 秒，覆盖「App 已在前台时闹钟响起」）
 *
 * 关闭方式：调原生 stopRinging()，同时关掉响铃服务与本地覆盖层。
 */
import { isNativeShell, getRingingState, stopRinging } from './native.js';

const OVERLAY_ID = 'ring-overlay';
let pollTimer = null;
let shownUid = '';

/** 转义 HTML，避免备注里的字符破坏结构 */
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 渲染全屏响铃层 */
function renderOverlay(info) {
  // 已展示同一个闹钟就不重复渲染（避免闪烁）
  if (shownUid === info.uid) return;
  removeOverlay();
  shownUid = info.uid || '';

  const el = document.createElement('div');
  el.id = OVERLAY_ID;
  el.style.cssText = [
    'position:fixed', 'inset:0', 'z-index:99999',
    'background:linear-gradient(160deg,#1a202c,#2d3748)',
    'color:#fff', 'display:flex', 'flex-direction:column',
    'align-items:center', 'justify-content:center',
    'font-family:system-ui,-apple-system,sans-serif',
    'padding:24px', 'box-sizing:border-box',
  ].join(';');

  el.innerHTML = `
    <div style="font-size:64px;line-height:1;margin-bottom:8px">&#9200;</div>
    <div style="font-size:20px;opacity:.75;margin-bottom:4px">闹钟时间到了</div>
    <div style="font-size:64px;font-weight:700;letter-spacing:2px;margin:8px 0">
      ${esc(info.displayTime || '--:--')}
    </div>
    ${info.note ? `<div style="font-size:18px;opacity:.85;margin-bottom:8px">${esc(info.note)}</div>` : ''}
    <button id="ring-stop" style="
      margin-top:32px;padding:18px 56px;font-size:20px;font-weight:600;
      border:none;border-radius:999px;background:#e53e3e;color:#fff;
      cursor:pointer;box-shadow:0 6px 20px rgba(229,62,62,.45)">
      关闭闹钟
    </button>
    <div style="margin-top:16px;font-size:13px;opacity:.5">
      若系统未自动弹出响铃界面，可在此关闭
    </div>`;

  document.body.appendChild(el);
  el.querySelector('#ring-stop').addEventListener('click', () => {
    try { stopRinging(); } catch { /* 忽略 */ }
    removeOverlay();
  });
}

/** 移除覆盖层 */
export function removeOverlay() {
  const el = document.getElementById(OVERLAY_ID);
  if (el) el.remove();
  shownUid = '';
}

/** 查一次响铃状态，需要就展示 */
function check() {
  if (!isNativeShell) return;
  let st = {};
  try { st = getRingingState() || {}; } catch { st = {}; }
  if (st.ringing) {
    renderOverlay(st);
  } else if (shownUid) {
    // 原生侧已停止响铃（例如用户从系统通知点「关闭」）→ 收起覆盖层
    removeOverlay();
  }
}

/** 启动兜底监听 */
export function initRingFallback() {
  if (!isNativeShell) return;
  // 1) 立即查一次（App 启动 / 页面加载）
  check();
  // 2) 回到前台时再查（用户点亮屏幕、从后台切回来）
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) check();
  });
  // 3) 轮询：覆盖「App 已在前台时闹钟响起」这种情况
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(check, 5000);
}
