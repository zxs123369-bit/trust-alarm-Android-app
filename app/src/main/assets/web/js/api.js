/* 接口封装层
 *
 * 职责：
 *   1. 统一拼 URL、加 Authorization 头、解析统一返回体
 *   2. access_token 将过期时自动刷新
 *   3. 令牌刷新的「单飞」（single-flight）—— 见下方说明，这是最关键的一点
 *
 * 返回体约定（后端 app/core/response.py）：
 *   { "code": 0, "message": "ok", "data": {...} }
 *   code: 0 成功 / 1000 段业务 / 2000 段权限 / 3000 段限流
 */

/* 接口基址。
 *
 * 浏览器里用相对路径 '/api/v1'（同源，由后端反代），这是默认且推荐的方式。
 *
 * 但在 Android 原生壳（WebView 加载 file:///android_asset/）下，
 * 相对路径会解析成 file:///api/v1 —— 必然失败。此时由原生在页面加载前
 * 注入 window.__apiBase 指定后端绝对地址（见 WebMainActivity 的注入脚本）。
 *
 * 因此在壳里 BASE 变成 'http://192.168.x.x:8848/api/v1'。
 * 后端已开 CORS，跨源 fetch 可用。
 */
const BASE = (typeof window !== 'undefined' && window.__apiBase)
  ? String(window.__apiBase).replace(/\/+$/, '') + '/api/v1'
  : '/api/v1';

/**
 * 单个请求的超时上限（毫秒）。
 *
 * ★ 为什么必须有（真机实测）：`fetch` 默认**没有超时** —— 当连接建立了
 *   但响应迟迟不来（弱网、服务假死、链路丢包）时，它会永远 pending，
 *   既不 resolve 也不 reject。页面于是永久停在骨架屏，用户看不到任何
 *   错误、也没有重试入口（长辈只能反复开关 App）。
 *
 * 取值理由：本项目接口正常 <20ms，15 秒足够覆盖最慢的冷启动/大列表；
 * 再长用户会以为卡死。要调就改这一个常量。
 */
const REQUEST_TIMEOUT_MS = 15000;

// ---------------- 令牌存储 ----------------
// access_token 只放内存：XSS 需在页面存活期内窃取，窗口更小。
// refresh_token 放 localStorage：用于刷新页面后静默恢复登录态。
// 后端对 refresh 做了「轮换 + 泄露检测」，即使泄露也可控。
let accessToken = null;
const REFRESH_KEY = 'ta_refresh_token';

export function getRefreshToken() {
  try { return localStorage.getItem(REFRESH_KEY); } catch { return null; }
}
export function setTokens(access, refresh) {
  accessToken = access || null;
  try {
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh);
  } catch { /* 隐私模式下可能失败，忽略 */ }

  // 原生壳：把 refresh_token 同步给原生。
  //
  // 为什么需要：心跳服务（HeartbeatService）与开机重排（BootReceiver）
  // 都跑在原生侧、用原生存令牌，它们需要 refresh_token 才能刷 access_token
  // 去拉闹钟列表。不同步的话，重启手机后闹钟不会重排。
  if (refresh && typeof window !== 'undefined' && window.TrustAlarmNative) {
    try { window.TrustAlarmNative.syncRefreshTokenToNative(refresh); } catch { /* 忽略 */ }
  }
}
export function clearTokens() {
  accessToken = null;
  try { localStorage.removeItem(REFRESH_KEY); } catch { /* ignore */ }
}
export function hasSession() {
  return !!(accessToken || getRefreshToken());
}

// ---------------- 错误类型 ----------------
/* 会话彻底失效时的回调（由 main.js 注册 pwa.onSessionExpired）
 * 用回调而非直接 import：api.js 是最底层模块，不应反向依赖 UI 层，
 * 否则会形成 api ↔ pwa/store 的循环 import。 */
let sessionExpiredHandler = null;
export function setSessionExpiredHandler(fn) {
  sessionExpiredHandler = fn;
}
function notifySessionExpired() {
  if (typeof sessionExpiredHandler === 'function') {
    try { sessionExpiredHandler(); } catch (e) { console.error('[api] 会话回调出错', e); }
  }
}

export class ApiError extends Error {
  constructor(message, { code = -1, status = 0, data = null } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.data = data;
  }
  /** 需要重新登录（令牌彻底失效） */
  get isUnauthorized() { return this.status === 401; }
}

// ---------------- 令牌刷新（单飞） ----------------
/* 为什么必须单飞：
 * 后端的 /auth/refresh 是「一次性轮换」——用掉旧 refresh 就立刻吊销它，
 * 并签发新的。如果两个请求同时发现 access 过期、各自去 refresh：
 *   请求A 用 refresh_1 → 成功，refresh_1 被吊销，拿到 refresh_2
 *   请求B 也用 refresh_1 → 后端发现「已吊销令牌被再次使用」
 *   → 判定为泄露 → **吊销该用户全部刷新令牌**（用户被强制登出）
 * 所以这里用一个共享 Promise：并发调用只发一次请求，其余等结果。
 */
let refreshPromise = null;

async function doRefresh() {
  const rt = getRefreshToken();
  if (!rt) throw new ApiError('登录状态已失效，请重新登录', { status: 401 });

  const resp = await fetch(`${BASE}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: rt }),
  });
  const text = await resp.text();
  let json;
  try { json = JSON.parse(text); } catch { json = {}; }

  if (!resp.ok) {
    clearTokens();
    notifySessionExpired();
    throw new ApiError(json.message || '登录状态已失效，请重新登录',
                       { code: json.code, status: resp.status, data: json });
  }
  const d = json.data || {};
  setTokens(d.access_token, d.refresh_token);
  return d.access_token;
}

function refreshAccessToken() {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

/** 页面启动/刷新后，用 refresh_token 静默换回 access_token */
export async function restoreSession() {
  if (accessToken) return true;
  if (!getRefreshToken()) return false;
  try {
    await refreshAccessToken();
    return true;
  } catch {
    return false;
  }
}

// ---------------- 主请求函数 ----------------
const JSON_HEADERS = { 'Content-Type': 'application/json' };

/**
 * @param {string} method  GET/POST/PATCH/DELETE
 * @param {string} path    以 / 开头，相对 BASE（如 '/families'）
 * @param {object} [opts]
 * @param {object} [opts.body]        请求体（自动 JSON.stringify）
 * @param {object} [opts.query]       查询参数
 * @param {boolean}[opts.auth=true]   是否带令牌
 * @param {boolean}[opts.retry=true]  401 时是否自动刷新并重试一次
 */
export async function request(method, path, opts = {}) {
  const { body, query, auth = true, retry = true } = opts;

  let url = BASE + path;
  if (query) {
    const qs = new URLSearchParams();
    Object.entries(query).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') qs.append(k, v);
    });
    const s = qs.toString();
    if (s) url += '?' + s;
  }

  const headers = { ...JSON_HEADERS };
  if (auth && accessToken) headers['Authorization'] = 'Bearer ' + accessToken;

  // 离线时直接给出明确原因，不去发一个注定失败的请求
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    throw new ApiError('当前无网络连接，请检查网络后重试', { status: 0 });
  }

  let resp;
  // ★ 请求超时兜底（真机实测：没有它会导致永久卡在骨架屏）。
  //
  //   实测现象：后端可达但响应挂起时（弱网、服务假死、中间设备丢包），
  //   `fetch` 会**永远 pending**，既不 resolve 也不 reject →
  //   页面一直显示骨架屏，没有报错、没有重试入口。长辈用户只能反复开关 App。
  //
  //   浏览器默认没有请求超时（TCP 层可能等几分钟），必须显式用
  //   AbortController。15 秒对本项目的接口足够（正常 <20ms）。
  const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = ctl ? setTimeout(() => ctl.abort(), REQUEST_TIMEOUT_MS) : null;
  try {
    resp = await fetch(url, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: ctl ? ctl.signal : undefined,
    });
  } catch (e) {
    // 网络层失败：区分「主动超时」与「连不上」，文案不同
    const aborted = e && (e.name === 'AbortError');
    throw new ApiError(
      aborted
        ? `请求超时（超过 ${Math.round(REQUEST_TIMEOUT_MS / 1000)} 秒无响应）。请检查网络后重试。`
        : `无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。`,
      { status: 0, data: { raw: String(e) } }
    );
  } finally {
    if (timer) clearTimeout(timer);   // 无论成败都要清掉，避免定时器泄漏
  }

  const text = await resp.text();
  let json = null;
  if (text) {
    try { json = JSON.parse(text); } catch { json = { message: text }; }
  }

  // 401：尝试刷新令牌后重试一次
  if (resp.status === 401 && auth && retry && getRefreshToken()) {
    try {
      await refreshAccessToken();
      return request(method, path, { ...opts, retry: false });
    } catch {
      clearTokens();
      notifySessionExpired();
      throw new ApiError('登录状态已失效，请重新登录', { code: 2001, status: 401 });
    }
  }

  if (!resp.ok) {
    const msg = (json && json.message) || `请求失败（${resp.status}）`;
    throw new ApiError(msg, {
      code: json ? json.code : -1,
      status: resp.status,
      data: json,
    });
  }

  // 成功：返回 data 字段（统一返回体的载荷）
  // 注意：后端约定的错误码 0 表示成功，这里同时校验一下
  if (json && typeof json.code === 'number' && json.code !== 0) {
    throw new ApiError(json.message || '操作失败',
                       { code: json.code, status: resp.status, data: json });
  }
  return json ? json.data : null;
}

export const api = {
  get: (p, opts) => request('GET', p, opts),
  post: (p, body, opts) => request('POST', p, { ...opts, body }),
  patch: (p, body, opts) => request('PATCH', p, { ...opts, body }),
  del: (p, opts) => request('DELETE', p, opts),
};

// ---------------- 按域封装的接口 ----------------
export const authApi = {
  register: (phone, password, nickname) =>
    api.post('/auth/register', { phone, password, nickname }, { auth: false }),
  login: (phone, password) =>
    api.post('/auth/login', { phone, password }, { auth: false }),
  logout: (refreshToken) =>
    api.post('/auth/logout', refreshToken ? { refresh_token: refreshToken } : {}),
  logoutAll: () => api.post('/auth/logout-all', {}),
  me: () => api.get('/auth/me'),
  sessions: (refreshToken) =>
    api.get('/auth/sessions', { query: refreshToken ? { refresh_token: refreshToken } : {} }),
  revokeSession: (id) => api.del(`/auth/sessions/${id}`),
  forgot: (phone) => api.post('/auth/password/forgot', { phone }, { auth: false }),
  reset: (resetToken, newPassword) =>
    api.post('/auth/password/reset', { reset_token: resetToken, new_password: newPassword },
             { auth: false }),
};

export const familyApi = {
  list: () => api.get('/families'),
  create: (name) => api.post('/families', { name }),
  detail: (uid) => api.get(`/families/${uid}`),
  // 邀请制：发邀请（不再直接加人）
  inviteMember: (uid, phone) => api.post(`/families/${uid}/members`, { phone }),
  listInvitations: (uid) => api.get(`/families/${uid}/invitations`),
  cancelInvitation: (uid, invUid) =>
    api.del(`/families/${uid}/invitations/${invUid}`),
  removeMember: (uid, memberUid) => api.del(`/families/${uid}/members/${memberUid}`),
  quit: (uid) => api.post(`/families/${uid}/quit`, {}),
};

/** 我用到的家庭邀请（被邀请方） */
export const invitationApi = {
  mine: () => api.get('/invitations/mine'),
  accept: (invUid) => api.post(`/invitations/${invUid}/accept`, {}),
  reject: (invUid) => api.post(`/invitations/${invUid}/reject`, {}),
};

export const deviceApi = {
  list: (familyUid) => api.get('/devices', { query: { family_uid: familyUid } }),
  bind: (familyUid, deviceName, platform) =>
    api.post('/devices/bind', { family_uid: familyUid, device_name: deviceName, platform }),
  detail: (uid) => api.get(`/devices/${uid}`),
  rename: (uid, name) => api.patch(`/devices/${uid}`, { device_name: name }),
  unbind: (uid) => api.post(`/devices/${uid}/unbind`, {}),
  heartbeat: (uid, model) => api.post(`/devices/${uid}/heartbeat`, {
    model: model || null,
  }),
};

/**
 * 把「时间对象」归一成后端入参。
 *
 * 三种时间写法（时间戳 / 指定日期 / 仅时分）的取舍逻辑只应有一处，
 * 所以 create 与 update 共用这个函数。
 *
 * 注意：note 单独处理，因为它可能为 null（显式清空备注）。
 */
function _buildTimeBody(time) {
  const body = {};
  if (!time || typeof time !== 'object') return body;
  if (time.alarm_time_ms != null) body.alarm_time_ms = Number(time.alarm_time_ms);
  if (time.date) body.date = time.date;
  if (time.hour != null) body.hour = Number(time.hour);
  if (time.minute != null) body.minute = Number(time.minute);
  return body;
}

export const alarmApi = {
  list: (deviceUid) => api.get(`/devices/${deviceUid}/alarms`),

  /**
   * 创建闹钟。
   *
   * @param time 三种形式：
   *   · { alarm_time_ms }                    显式时间戳（选了具体年月日时用这个）
   *   · { date:'YYYY-MM-DD', hour, minute }   指定日期
   *   · { hour, minute }                     今天的 HH:MM，已过顺延到明天
   * @param note 备注，null 表示无
   */
  create: (deviceUid, time = {}, note = null) => {
    const body = _buildTimeBody(time);
    body.note = note || null;
    return api.post(`/devices/${deviceUid}/alarms`, body);
  },

  /**
   * 修改闹钟。只改传了的字段。
   *
   * @param patch 可含 hour/minute/date/alarm_time_ms 与 note
   */
  update: (alarmUid, patch) => {
    const body = { ..._buildTimeBody(patch) };
    if (patch && 'note' in patch) body.note = patch.note || null;
    return api.patch(`/alarms/${alarmUid}`, body);
  },

  remove: (alarmUid) => api.del(`/alarms/${alarmUid}`),
  confirm: (alarmUid, approve) =>
    api.post(`/alarms/${alarmUid}/confirm`, { approve }),
  dispatchTask: (alarmUid) => api.get(`/alarms/${alarmUid}/dispatch-task`),
};

export const trustApi = {
  list: (deviceUid) => api.get(`/devices/${deviceUid}/trust-members`),
  add: (deviceUid, phone) => api.post(`/devices/${deviceUid}/trust-members`, { phone }),
  // 按 uid 添加：成员详情页只有 uid（拿不到脱敏后的真实手机号）
  addByUid: (deviceUid, uid) =>
    api.post(`/devices/${deviceUid}/trust-members/by-uid`, { uid }),
  remove: (deviceUid, memberUid) => api.del(`/devices/${deviceUid}/trust-members/${memberUid}`),
  setSwitch: (deviceUid, enabled) =>
    api.patch(`/devices/${deviceUid}/trust-switch`, { enabled }),
};

export const grantApi = {
  request: (deviceUid) => api.post(`/devices/${deviceUid}/view-requests`, {}),
  received: (status) => api.get('/view-requests/received', { query: { status } }),
  mine: () => api.get('/view-requests/mine'),
  approve: (id) => api.post(`/view-requests/${id}/approve`, {}),
  reject: (id) => api.post(`/view-requests/${id}/reject`, {}),
  list: (deviceUid) => api.get(`/devices/${deviceUid}/grants`),
  revoke: (deviceUid, memberUid) => api.del(`/devices/${deviceUid}/grants/${memberUid}`),
};

export const messageApi = {
  list: () => api.get('/messages'),
  read: (uid) => api.post(`/messages/${uid}/read`, {}),
  readAll: () => api.post('/messages/read-all', {}),
};

export const logApi = {
  operations: (opts) => api.get('/logs/operations', { query: opts }),
};
