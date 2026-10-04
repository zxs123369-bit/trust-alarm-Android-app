/* 布局：顶部栏 + 底部 Tab + 页面容器
 * 每个页面渲染前调 setLayout 决定是否显示 Tab / 返回键 / 右侧操作。
 */

import { escapeHtml, go, back } from './router.js';
import { guardReentry } from './ui.js';

/* 底部导航
 * 用户要求：「设备」选项没有意义，改为「本地闹钟」兜底，
 * 并与「家庭」调换位置，作为主页（打开 App 默认落在这里）。
 */
const TABS = [
  { path: '/local-alarms', icon: '⏰', label: '本地闹钟' },
  { path: '/families',     icon: '🏠', label: '家庭' },
  { path: '/messages',     icon: '🔔', label: '消息', badge: true },
  { path: '/settings',     icon: '👤', label: '我的' },
];

/**
 * @param {object} o
 * @param {string} o.title           标题
 * @param {boolean}[o.tabbar=true]   显示底部导航
 * @param {boolean}[o.backBtn]       显示返回按钮
 * @param {string} [o.activeTab]     高亮的 Tab 路径
 * @param {Array}  [o.actions]       右侧按钮 [{ text, onClick }]
 */
export function setLayout(o = {}) {
  const {
    title = '信任闹钟',
    tabbar = true,
    backBtn = false,
    activeTab = '',
    actions = [],
  } = o;

  const app = document.getElementById('app');
  let bar = document.getElementById('appbar');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'appbar';
    app.insertBefore(bar, app.firstChild);
  }
  bar.className = 'appbar';
  bar.innerHTML = '';

  if (backBtn) {
    const b = document.createElement('button');
    b.className = 'back';
    b.setAttribute('aria-label', '返回');
    b.textContent = '‹';
    b.addEventListener('click', back);
    bar.appendChild(b);
  }

  const t = document.createElement('div');
  t.className = 'title';
  t.textContent = title;
  bar.appendChild(t);

  actions.forEach((a) => {
    const btn = document.createElement('button');
    btn.className = 'act';
    btn.textContent = a.text;
    btn.addEventListener('click', guardReentry(btn, a.onClick));
    btn.dataset.label = a.text;
    bar.appendChild(btn);
  });

  renderTabbar(tabbar, activeTab);
  const view = document.getElementById('view');
  if (view) view.className = tabbar ? '' : 'no-tabbar';
}

function renderTabbar(show, active) {
  let tb = document.getElementById('tabbar');
  if (!show) {
    if (tb) tb.remove();
    return;
  }
  if (!tb) {
    tb = document.createElement('div');
    tb.id = 'tabbar';
    tb.className = 'tabbar';
    document.body.appendChild(tb);
  }
  tb.innerHTML = '';
  TABS.forEach((t) => {
    const a = document.createElement('a');
    a.href = '#' + t.path;
    if (active === t.path) a.className = 'on';
    a.innerHTML = `<span class="ico">${t.icon}</span><span>${escapeHtml(t.label)}</span>`;
    if (t.badge) {
      const b = document.createElement('span');
      b.id = 'tab-badge';
      b.className = 'badge hidden';
      a.appendChild(b);
    }
    tb.appendChild(a);
  });
  // 立刻同步一次角标
  import('./store.js').then((m) => {
    const el = document.getElementById('tab-badge');
    if (!el) return;
    const n = m.getState().unread;
    if (n > 0) { el.textContent = n > 99 ? '99+' : String(n); el.classList.remove('hidden'); }
  });
}

/** 页面内容容器 */
export function viewEl() {
  return document.getElementById('view');
}

/** 设置页面内容（字符串，需自行 escapeHtml 或用 ui.js 的构建器） */
export function setView(html) {
  const v = viewEl();
  if (v) v.innerHTML = html;
  return v;
}

/** 渲染后到指定锚点（页面切换时回到顶部） */
export function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'instant' });
}

export { go };
