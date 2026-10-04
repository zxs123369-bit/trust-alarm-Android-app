/* 原生壳能力探测
 *
 * 用途：手机端 UI 复用网页版（Android WebView 加载 file:///android_asset/web/），
 * 由原生通过 addJavascriptInterface 注入 window.TrustAlarmNative。
 * 网页据此判断「是否该走原生能力」——最典型的是：
 *
 *   · 浏览器里：闹钟只能靠页面定时器 + Web Audio 响铃（页面关掉就没了）
 *   · 原生壳里：交给系统 AlarmManager，App 被杀也能响
 *
 * 这样同一套网页代码，在两种宿主里各自走最优路径，不需要维护两份 UI。
 */

/** 原生桥对象（不存在则为 undefined） */
export const native = (typeof window !== 'undefined' && window.TrustAlarmNative)
  ? window.TrustAlarmNative
  : null;

/** 是否运行在 Android 原生壳里 */
export const isNativeShell = !!native;

/**
 * 安全调用原生桥：任何异常都吞掉并返回 fallback。
 *
 * 为什么要包一层：桥在旧版壳里可能没有新方法（版本不一致），
 * 或原生侧抛异常 —— 这都不应该让整个页面崩掉。
 */
function call(fn, fallback, ...args) {
  if (!native || typeof native[fn] !== 'function') return fallback;
  try {
    return native[fn](...args);
  } catch (e) {
    console.warn('[native] 调用失败', fn, e);
    return fallback;
  }
}

/** 注册系统闹钟（原生壳内才有效，浏览器返回 false） */
export function registerSystemAlarm(uid, hhmm, triggerAtMillis, note) {
  return call('registerAlarm', false, uid, hhmm, triggerAtMillis, note || '');
}

/** 取消系统闹钟 */
export function cancelSystemAlarm(uid) {
  return call('cancelAlarm', undefined, uid);
}

/** 该闹钟是否已在系统注册 */
export function isSystemAlarmScheduled(uid) {
  return call('isAlarmScheduled', false, uid);
}

/** 是否有精确闹钟权限（Android 12+；浏览器恒为 false 无意义） */
export function canScheduleExact() {
  return call('canScheduleExact', false);
}

/** 跳系统精确闹钟授权页 */
export function openExactAlarmSettings() {
  return call('openExactAlarmSettings', undefined);
}

/** 启动/停止心跳前台服务（现同时负责闹钟同步） */
export function startHeartbeat() {
  return call('startHeartbeat', undefined);
}
export function stopHeartbeat() {
  return call('stopHeartbeat', undefined);
}

/**
 * 立刻同步一次闹钟（不等后台服务下一个周期，最多省 60 秒）。
 *
 * 后台服务本身每分钟会同步，这个只是让用户刚进页面/刚收到通知时
 * 不必等 —— 属于体验优化，不是正确性依赖。
 */
export function syncAlarmsNow() {
  return call('syncAlarmsNow', undefined);
}

/** 登录成功后同步 refresh_token 给原生（心跳与开机重排需要） */
export function syncRefreshToken(refreshToken) {
  return call('syncRefreshTokenToNative', undefined, refreshToken || '');
}

/** 绑定设备成功后同步 device_uid 给原生 */
export function saveDeviceUid(deviceUid, deviceName) {
  return call('saveDeviceUid', undefined, deviceUid, deviceName || '我的手机');
}

/** 读取原生已存的 device_uid（用于判断「本机是哪台设备」） */
export function getDeviceUid() {
  return call('getDeviceUid', '');
}

/** 读取原生已存的设备名 */
export function getDeviceName() {
  return call('getDeviceName', '');
}

/** 本机机型（Build.MODEL），绑定时写入可提升多设备账号下的匹配准确度 */
export function deviceModel() {
  return call('deviceModel', '');
}

/** 退出登录时清原生会话 */
export function clearNativeSession() {
  return call('clearSession', undefined);
}

/**
 * ★ 查询是否有闹钟正在响铃（App 内兜底）。
 *
 * 为什么需要：Android 10+ 会拦后台 App 启动 Activity
 * （系统日志 `Background activity launch blocked`），
 * 此时铃声/震动/通知照常，但**界面上看不到是谁在响、也没法关**。
 * 网页在加载后主动查一次，若有正在响的就弹出兜底层 ——
 * 这条路不依赖系统是否允许后台启动界面，国产 ROM 上最可靠。
 *
 * @returns {{ringing:boolean, uid?:string, displayTime?:string, note?:string}}
 */
export function getRingingState() {
  const raw = call('getRingingState', '{}');
  try {
    return typeof raw === 'string' ? JSON.parse(raw) : (raw || {});
  } catch {
    return {};
  }
}

/** 停止当前响铃（网页兜底层点「关闭闹钟」时调用） */
export function stopRinging() {
  return call('stopRinging', undefined);
}

// ---------------- 后台可靠性引导（国产 ROM 关键） ----------------
//
// 为什么需要：国产 ROM（小米/华为/OPPO/vivo）会激进清理后台，
// 导致闹钟被压制或延迟。加电池白名单 + 允许自启动能显著改善。
// 这些开关都在系统设置里，App 无法代改，只能引导用户去开。

/** 当前设备品牌（小写，如 'xiaomi'），用于给出针对性的操作提示 */
export function deviceManufacturer() {
  return call('deviceManufacturer', '');
}

/** 是否已加入电池优化白名单 */
export function isIgnoringBatteryOptimizations() {
  return call('isIgnoringBatteryOptimizations', false);
}

/** 打开电池优化白名单授权页（能在白名单里时打开列表页） */
export function openBatteryOptimizationSettings() {
  return call('openBatteryOptimizationSettings', undefined);
}

/** 打开本应用详情页（自启动/后台管理的通用入口） */
export function openAppSettings() {
  return call('openAppSettings', undefined);
}

/**
 * 尝试打开厂商专有的「自启动管理」页。
 * @returns true=已打开厂商页；false=该机型没有，调用方应退回 openAppSettings()
 */
export function openAutoStartSettings() {
  return call('openAutoStartSettings', false);
}
