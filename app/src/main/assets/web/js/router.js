/* hash 路由：无依赖、免服务端配置、刷新不 404
 *
 * 路由表形如：
 *   '#/device/:uid'  →  { path: '/device/:uid', keys: ['uid'], page: fn }
 * 匹配后把参数传给页面函数：page({ uid: 'xxx' })
 */

const routes = [];
let currentCleanup = null;

export function route(pattern, page) {
  const keys = [];
  const regex = new RegExp(
    '^' + pattern
      .replace(/\/:([A-Za-z_][A-Za-z0-9_]*)/g, (_, k) => { keys.push(k); return '/([^/]+)'; })
      .replace(/\*/g, '.*') + '$'
  );
  routes.push({ pattern, regex, keys, page });
}

export function currentPath() {
  const h = location.hash.replace(/^#/, '');
  return h || '/';
}

export function go(path, { replace = false } = {}) {
  const target = '#' + (path.startsWith('/') ? path : '/' + path);
  if (location.hash === target) return;
  if (replace) location.replace(target);
  else location.hash = target;
}

export function back() {
  if (history.length > 1) history.back();
  else go('/families');
}

function match(path) {
  for (const r of routes) {
    const m = path.match(r.regex);
    if (m) {
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { page: r.page, params, pattern: r.pattern };
    }
  }
  return null;
}

async function render() {
  const path = currentPath();
  const hit = match(path);

  // 上一个页面可能注册了清理函数（如轮询定时器）
  if (typeof currentCleanup === 'function') {
    try { currentCleanup(); } catch { /* ignore */ }
    currentCleanup = null;
  }

  const root = document.getElementById('view');
  if (!hit) {
    // 未匹配到路由。
    //
    // ★ 为什么不再只显示「页面不存在」（真机实测）：
    //   像 `#/family`、`#/member`、`#/device` 这类**参数化路由**，正常使用中
    //   都会带 uid 进入；但用户回退、或点了过期链接时会把 uid 丢掉，
    //   于是落到这里显示「页面不存在 /family」—— 用户会以为 App 坏了。
    //   现在改为：给出人话解释 + 两条明确的出口（回主页 / 去家庭列表），
    //   并说明「缺少标识」这个真实原因。
    const seg = path.split('/').filter(Boolean);
    const needsParam = seg.length > 0 &&
      ['family', 'member', 'device', 'logs'].includes(seg[0]);
    const title = needsParam ? '缺少要查看的对象' : '页面不存在';
    const sub = needsParam
      ? '这个页面需要先选择具体对象。请从下面的入口进入。'
      : `未找到：${path}`;
    const primary = needsParam
      ? '<a class="btn block mt12" href="#/families">去家庭列表</a>'
      : '<a class="btn block mt12" href="#/local-alarms">回到首页</a>';
    root.innerHTML = `
      <div class="page">
        <div class="empty">
          <div class="ico">${needsParam ? '📄' : '🧭'}</div>
          <div class="t">${escapeHtml(title)}</div>
          <div class="s">${escapeHtml(sub)}</div>
        </div>
        ${primary}
        <a class="btn ghost block mt12" href="#/local-alarms">回到本地闹钟</a>
      </div>`;
    return;
  }

  // 页面函数可返回一个清理函数（用于取消轮询等）
  try {
    const cleanup = await hit.page(hit.params);
    if (typeof cleanup === 'function') currentCleanup = cleanup;
  } catch (e) {
    console.error('[router] 页面渲染失败', e);
    root.innerHTML = `
      <div class="page">
        <div class="empty">
          <div class="ico">⚠️</div>
          <div class="t">页面加载失败</div>
          <div class="s">${escapeHtml(e && e.message ? e.message : String(e))}</div>
        </div>
        <button class="btn block mt12" onclick="location.reload()">重新加载</button>
      </div>`;
  }
}

export function startRouter() {
  window.addEventListener('hashchange', render);
  render();
}

/** 供页面模块使用（避免各页各自实现一遍） */
export function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
