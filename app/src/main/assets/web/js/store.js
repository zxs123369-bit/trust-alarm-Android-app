/* 轻量 store：登录用户 + 常用缓存
 *
 * 为什么不引入状态库：本项目状态简单（当前用户、家庭/设备列表、消息数），
 * 一个模块级对象加「变更通知」已经足够，且没有构建步骤。
 */

const listeners = new Set();

const state = {
  user: null,          // { uid, nickname, phone_masked, ... }
  families: [],        // 家庭列表缓存
  currentFamily: null, // 当前查看的家庭详情
  unread: 0,           // 未读消息数（底部 Tab 角标）
  booted: false,       // 是否完成启动检查
};

export function getState() { return state; }
export function getUser() { return state.user; }

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function emit() {
  listeners.forEach((fn) => {
    try { fn(state); } catch (e) { console.error('[store] 订阅者出错', e); }
  });
}

export function setUser(u) {
  state.user = u || null;
  emit();
}

export function setFamilies(list) {
  state.families = Array.isArray(list) ? list : [];
  emit();
}

export function setCurrentFamily(f) {
  state.currentFamily = f || null;
  emit();
}

export function setUnread(n) {
  const v = Math.max(0, Number(n) || 0);
  if (state.unread === v) return;
  state.unread = v;
  emit();
  updateBadge();
}

export function markBooted() {
  state.booted = true;
  emit();
}

/** 清空登录态（登出或令牌失效时调用） */
export function reset() {
  state.user = null;
  state.families = [];
  state.currentFamily = null;
  state.unread = 0;
  emit();
  updateBadge();
}

function updateBadge() {
  const el = document.getElementById('tab-badge');
  if (!el) return;
  if (state.unread > 0) {
    el.textContent = state.unread > 99 ? '99+' : String(state.unread);
    el.classList.remove('hidden');
  } else {
    el.classList.add('hidden');
  }
}
