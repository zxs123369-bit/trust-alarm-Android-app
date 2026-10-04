/* 通用 UI：Toast / 确认弹层 / 表单弹层 / 骨架屏 / 状态标签
 *
 * 设计原则：
 *   - 所有插入 DOM 的服务端文案一律走 textContent 或 escapeHtml，防 XSS
 *   - 错误文案直接用服务端 message（后端已按 PRD §13 做成用户化表述），
 *     前端不自己造一套，避免两套口径
 */

import { escapeHtml } from './router.js';

// ---------------- 防重复触发 ----------------

/**
 * 给按钮加「重入保护」：处理期间忽略后续点击，并把按钮置灰。
 *
 * ★ 为什么需要（真机实测）：
 *   「刷新」等按钮直接绑 onClick，没有任何防抖。CDP 实测连点 10 次 →
 *   发出 **30 次** fetch（families + devices + local-alarms 各 10 次），
 *   放大 10 倍。用户手抖或弱网焦虑时狂点，会把后端打爆；并发请求
 *   互相覆盖还可能让列表闪回旧数据。
 *
 * 与「节流」的区别：
 *   这里用「in-flight 锁」而不是时间节流 —— 无论点多快，只要上一次
 *   还没结束就不再发起。这比固定窗口更贴合「等它做完」的直觉，
 *   也不会出现「节流窗口过了但请求还在飞」的重复。
 *
 * @param {HTMLElement} btn   触发按钮（用于置灰与恢复文案）
 * @param {Function}    fn    实际处理函数，可返回 Promise
 * @returns {Function} 包装后的事件处理函数
 */
export function guardReentry(btn, fn) {
  let running = false;
  return async function wrapped(ev) {
    if (running) return;
    running = true;
    const label = (btn && (btn.dataset.label || btn.textContent)) || '';
    try {
      if (btn) {
        btn.disabled = true;
        btn.classList.add('busy');
      }
      await fn.call(this, ev);
    } catch (e) {
      // 交给调用方自己的错误处理；这里只保证锁一定释放
      console.warn('[guardReentry] 处理失败：', e && e.message);
    } finally {
      running = false;
      if (btn && btn.isConnected) {
        btn.disabled = false;
        btn.classList.remove('busy');
        // 若渲染过程替换了按钮，isConnected 会为 false，不再操作
        if (label) btn.textContent = label;
      }
    }
  };
}

// ---------------- Toast ----------------
function toastWrap() {
  let w = document.getElementById('toast-wrap');
  if (!w) {
    w = document.createElement('div');
    w.id = 'toast-wrap';
    document.body.appendChild(w);
  }
  return w;
}

/**
 * @param {string} msg
 * @param {'info'|'ok'|'err'} [type]
 * @param {number} [ms]
 */
export function toast(msg, type = 'info', ms = 2400) {
  const el = document.createElement('div');
  el.className = 'toast' + (type !== 'info' ? ' ' + type : '');
  el.textContent = String(msg);     // textContent：防 XSS
  toastWrap().appendChild(el);
  setTimeout(() => {
    el.style.transition = 'opacity .2s';
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 220);
  }, ms);
}

export const toastOk = (m) => toast(m, 'ok');
export const toastErr = (m) => toast(m, 'err', 3200);

/** 统一处理接口异常：把服务端 message 直接展示 */
export function showError(e) {
  const msg = e && e.message ? e.message : '操作失败，请稍后重试';
  toastErr(msg);
  if (e && e.isUnauthorized) {
    // 令牌失效：交给调用方跳登录；这里只提示
    console.warn('[ui] 未授权，需要重新登录');
  }
  return msg;
}

// ---------------- 底部弹层 ----------------
function modalWrap() {
  let w = document.getElementById('modal-wrap');
  if (!w) {
    w = document.createElement('div');
    w.id = 'modal-wrap';
    w.addEventListener('click', (ev) => {
      if (ev.target === w) closeModal();
    });
    document.body.appendChild(w);
  }
  return w;
}

export function closeModal() {
  const w = document.getElementById('modal-wrap');
  if (!w) return;
  w.classList.remove('on', 'center');
  w.innerHTML = '';
}

/**
 * 通用弹层
 * @param {object} o
 * @param {string} o.title
 * @param {string|Node} o.body      字符串会被 HTML 转义后作为纯文本；需要富内容时传 Node
 * @param {Array}  [o.actions]      [{ text, kind, onClick, closeAfter }]
 * @param {boolean}[o.center]       是否居中（默认底部弹出）
 * @param {Function}[o.onClose]
 */
export function openModal(o) {
  const { title = '', body = '', actions = [], center = false, onClose } = o;
  const w = modalWrap();
  w.classList.add('on');
  if (center) w.classList.add('center');

  const sheet = document.createElement('div');
  sheet.className = 'modal';

  const hd = document.createElement('div');
  hd.className = 'modal-hd';
  const t = document.createElement('div');
  t.className = 'grow';
  t.textContent = title;
  hd.appendChild(t);

  const bd = document.createElement('div');
  bd.className = 'modal-bd';
  if (body instanceof Node) bd.appendChild(body);
  else {
    const p = document.createElement('div');
    p.className = 'small';
    p.style.whiteSpace = 'pre-wrap';
    p.style.wordBreak = 'break-word';
    p.textContent = String(body);       // textContent：防 XSS
    bd.appendChild(p);
  }

  sheet.append(hd, bd);

  if (actions.length) {
    const ft = document.createElement('div');
    ft.className = 'modal-ft';
    actions.forEach((a) => {
      const btn = document.createElement('button');
      btn.className = 'btn' + (a.kind ? ' ' + a.kind : '');
      btn.textContent = a.text;
      btn.addEventListener('click', async () => {
        const keep = a.onClick ? await a.onClick() : undefined;
        if (keep !== false && a.closeAfter !== false) {
          closeModal();
          if (onClose) onClose();
        }
      });
      ft.appendChild(btn);
    });
    sheet.appendChild(ft);
  }

  w.innerHTML = '';
  w.appendChild(sheet);
}

/** 确认框（Promise<boolean>） */
export function confirmBox(title, message, { okText = '确定', danger = false } = {}) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };
    openModal({
      title,
      body: message,
      center: true,
      actions: [
        { text: '取消', kind: 'ghost', onClick: () => done(false) },
        {
          text: okText,
          kind: danger ? 'danger' : '',
          onClick: () => done(true),
        },
      ],
      onClose: () => done(false),
    });
    // 点遮罩关闭也要 resolve
    const w = document.getElementById('modal-wrap');
    const once = () => { done(false); w.removeEventListener('transitionend', once); };
    w.addEventListener('transitionend', once, { once: true });
  });
}

/** 输入框弹层（Promise<string|null>） */
export function promptBox(title, { label = '', placeholder = '', value = '',
                                  okText = '确定', inputType = 'text' } = {}) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };

    const frag = document.createDocumentFragment();
    const f = document.createElement('div');
    f.className = 'field';
    if (label) {
      const l = document.createElement('label');
      l.textContent = label;
      f.appendChild(l);
    }
    const input = document.createElement('input');
    input.type = inputType;
    input.placeholder = placeholder;
    input.value = value;
    f.appendChild(input);
    frag.appendChild(f);

    openModal({
      title,
      body: frag,
      center: true,
      actions: [
        { text: '取消', kind: 'ghost', onClick: () => done(null) },
        { text: okText, onClick: () => done(input.value.trim()) },
      ],
      onClose: () => done(null),
    });
    setTimeout(() => input.focus(), 120);
    input.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') { done(input.value.trim()); closeModal(); }
    });
  });
}

// ---------------- 骨架 / 空态 ----------------
export function skeleton(rows = 3) {
  let s = '<div class="skeleton">';
  for (let i = 0; i < rows; i++) s += '<div class="sk"></div>';
  return s + '</div>';
}

export function emptyState(icon, title, sub = '') {
  return `<div class="empty">
    <div class="ico">${escapeHtml(icon)}</div>
    <div class="t">${escapeHtml(title)}</div>
    ${sub ? `<div class="s">${escapeHtml(sub)}</div>` : ''}
  </div>`;
}

/**
 * 「加载失败」页 —— 带**重试按钮**的错误态（真机实测补齐）。
 *
 * ★ 为什么需要（真机实测）：原本 11 个页面都写成
 *   `setView(emptyState('⚠️', '加载失败', e.message))` —— **只有文案、没有重试**。
 *   用户（尤其长辈）看到错误后唯一的出路是退出去再进来，很多人不知道怎么恢复。
 *
 *   统一收成一个 helper，既保证体验一致，也避免以后新页面再漏掉重试入口。
 *
 * @param {Error|string} err  错误对象（取其 message）或文案
 * @param {Function} onRetry  点击「重试」时的回调（通常是该页的 render）
 * @param {object}  [opts]
 * @param {string}  [opts.backHref]  额外给一个「返回」出口（如 '#/families'）
 */
export function errorState(err, onRetry, { backHref = '' } = {}) {
  const msg = (err && err.message) ? err.message : String(err || '未知错误');
  // 用 data 属性 + 事件委托，避免把函数塞进 HTML
  const id = 'err-retry-' + Math.random().toString(36).slice(2, 9);
  stateHandlers[id] = onRetry;
  return `<div class="empty">
    <div class="ico">⚠️</div>
    <div class="t">加载失败</div>
    <div class="s">${escapeHtml(msg)}</div>
    <button class="btn block mt12" data-retry="${id}">重试</button>
    ${backHref ? `<a class="btn ghost block mt12" href="${escapeHtml(backHref)}">返回</a>` : ''}
  </div>`;
}

/** 错误态重试回调的注册表（key → 回调），供事件委托取用。 */
const stateHandlers = Object.create(null);

/**
 * 装上重试按钮的事件委托（只需在启动时调一次）。
 * 用委托而非逐页绑定：错误页会被反复重建，委托一次即可持久生效。
 */
export function installErrorRetryDelegate() {
  document.addEventListener('click', (ev) => {
    const el = ev.target && ev.target.closest && ev.target.closest('[data-retry]');
    if (!el) return;
    const fn = stateHandlers[el.getAttribute('data-retry')];
    if (typeof fn === 'function') {
      ev.preventDefault();
      fn();
    }
  });
}

export function loadingBlock(text = '加载中…') {
  return `<div class="loading"><div class="spinner"></div>${escapeHtml(text)}</div>`;
}

// ---------------- 格式化 ----------------
/** 毫秒时间戳 → 'MM-DD HH:mm' */
export function fmtTime(ms) {
  if (!ms) return '—';
  const d = new Date(Number(ms));
  if (Number.isNaN(d.getTime())) return '—';
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 毫秒时间戳 → 相对时间（刚刚 / N 分钟前 / …） */
export function fmtRelative(ms) {
  if (!ms) return '—';
  const diff = Date.now() - Number(ms);
  if (diff < 0) return fmtTime(ms);
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day} 天前`;
  return fmtTime(ms);
}

/** 'HH:MM' → 今天的毫秒时间戳（已过则顺延到明天），与后端 _resolve_time_ms 同逻辑 */
export function hhmmToMsToday(hhmm) {
  const [h, m] = String(hhmm).split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return null;
  const d = new Date();
  d.setHours(h, m, 0, 0);
  if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
  return d.getTime();
}

/** 设备平台 → 展示名 */
export function platformName(p) {
  if (p === 'android') return 'Android';
  if (p === 'ios') return 'iPhone / iPad';
  return p || '未知';
}

/** 在线状态（0 离线 / 1 在线），null 表示无权查看 */
export function onlineText(st) {
  if (st === 1) return { text: '在线', cls: 'ok' };
  if (st === 0) return { text: '离线', cls: '' };
  return { text: '未知', cls: '' };
}
