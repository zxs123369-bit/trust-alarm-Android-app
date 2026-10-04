package com.trustalarm.app.bridge

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.trustalarm.app.alarm.AlarmRingService
import com.trustalarm.app.alarm.AlarmScheduler
import com.trustalarm.app.data.AlarmItem
import com.trustalarm.app.data.TokenStore
import com.trustalarm.app.service.HeartbeatService

/**
 * 网页版 ↔ 原生能力的桥。
 *
 * ## 为什么需要它
 *
 * 手机端 UI 直接复用网页版（WebView 加载 `assets/web/`），保证「界面与网页版一致」。
 * 但网页做不到三件关键事，必须由原生补上：
 *
 *   1. **注册系统闹钟**：浏览器无后台唤醒，App 被杀/息屏后闹钟就不响。
 *      → [registerAlarm] / [cancelAlarm]
 *   2. **心跳保活**：后端 120 秒无心跳判离线，离线设备的闹钟会走重试队列。
 *      → [startHeartbeat] / [stopHeartbeat]
 *   3. **精确闹钟权限**：Android 12+ 需用户手动授权，网页无权引导。
 *      → [canScheduleExact] / [openExactAlarmSettings]
 *
 * ## 线程说明（重要）
 *
 * [JavascriptInterface] 标注的方法运行在 **WebView 的 JS 线程**，不是主线程。
 * 涉及系统服务的调用（AlarmManager 等）本身是线程安全的，可以直接调；
 * 但若将来加入需要主线程的操作（弹窗、UI 操作），必须切主线程。
 *
 * ## 安全说明
 *
 * 桥只暴露「完成闹钟功能所必需」的最小能力，且不接收任意 URL、不执行任意命令。
 * WebView 只加载本地 assets，不允许外域导航（见 WebMainActivity）。
 */
class NativeBridge(private val context: Context) {

    companion object {
        private const val TAG = "NativeBridge"

        /** JS 侧的全局对象名：window.TrustAlarmNative */
        const val JS_NAME = "TrustAlarmNative"

        /**
         * 当前 WebView 的弱引用 —— 用于把原生侧轮换后的 refresh_token 回写网页。
         *
         * 用弱引用：WebView 生命周期由 Activity 管，桥对象不该拖住它。
         * 桥是 long-lived 的（随 Activity 创建），WebView 也是，二者同生共死，
         * 但显式用 WeakReference 更稳妥（Activity 重建时旧 WebView 可被回收）。
         */
        private var webViewRef: java.lang.ref.WeakReference<android.webkit.WebView>? = null

        /** 由 WebMainActivity 在创建桥之后调用，注册 WebView 以便回写令牌。 */
        fun attachWebView(wv: android.webkit.WebView) {
            webViewRef = java.lang.ref.WeakReference(wv)
        }

        /**
         * 把原生侧轮换后的新 refresh_token 回写给网页 localStorage。
         *
         * 见 [pushRefreshTokenToWeb] 的说明 —— 这是避免「两处各持一把、
         * 互相判为泄露」的关键。
         */
        fun pushRefreshTokenToWeb(refreshToken: String) {
            if (refreshToken.isBlank()) return
            val wv = webViewRef?.get() ?: return
            // 必须切主线程：WebView.evaluateJavascript 只能在主线程调
            wv.post {
                try {
                    val js = "try{localStorage.setItem('ta_refresh_token'," +
                        org.json.JSONObject.quote(refreshToken) + ");}catch(e){}"
                    wv.evaluateJavascript(js, null)
                    Log.i(TAG, "已把新 refresh_token 回写网页 localStorage")
                } catch (e: Exception) {
                    Log.w(TAG, "回写 refresh_token 失败：${e.message}")
                }
            }
        }
    }

    private val store = TokenStore(context)

    /**
     * 注册一个闹钟到系统。
     *
     * @param uid      闹钟唯一 id（用于取消时匹配，必须与后端一致）
     * @param hhmm     显示时间 "07:30"（仅用于日志与界面展示）
     * @param triggerAtMillis 触发时间戳（毫秒）
     * @param note     备注，可为空
     * @return true=已注册；false=时间已过或权限不足
     */
    @JavascriptInterface
    fun registerAlarm(
        uid: String,
        hhmm: String,
        triggerAtMillis: Long,
        note: String?,
    ): Boolean {
        if (uid.isBlank()) {
            Log.w(TAG, "registerAlarm: uid 为空，忽略")
            return false
        }
        val item = AlarmItem(
            uid = uid,
            deviceUid = store.getDeviceUid(),
            displayTime = hhmm,
            triggerAtMillis = triggerAtMillis,
            note = note?.takeIf { it.isNotBlank() && it != "null" },
            source = "app_local",
            confirmStatus = 1, // 网页版走到这一步说明已确认
            creatorIsMe = true,
        )
        val ok = AlarmScheduler.schedule(context, item)
        Log.i(TAG, "registerAlarm $hhmm(${uid.take(8)}) -> $ok")
        return ok
    }

    /** 取消一个已注册的系统闹钟。 */
    @JavascriptInterface
    fun cancelAlarm(uid: String) {
        if (uid.isBlank()) return
        AlarmScheduler.cancel(context, uid)
        Log.i(TAG, "cancelAlarm ${uid.take(8)}")
    }

    /** 该闹钟当前是否已注册到系统（用于网页显示「已在本机注册」）。 */
    @JavascriptInterface
    fun isAlarmScheduled(uid: String): Boolean =
        uid.isNotBlank() && AlarmScheduler.isScheduled(context, uid)

    /** 是否已获得「精确闹钟」权限（Android 12+）。 */
    @JavascriptInterface
    fun canScheduleExact(): Boolean = AlarmScheduler.canScheduleExact(context)

    /** 跳转到系统的精确闹钟授权页。 */
    @JavascriptInterface
    fun openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开精确闹钟设置失败", e)
        }
    }

    /** 启动心跳前台服务（绑定设备后调用）。 */
    @JavascriptInterface
    fun startHeartbeat() {
        try {
            HeartbeatService.start(context)
            Log.i(TAG, "startHeartbeat")
        } catch (e: Exception) {
            Log.e(TAG, "启动心跳失败", e)
        }
    }

    /**
     * 立刻同步一次闹钟（不等后台服务的下一个周期，最多省 60 秒）。
     *
     * 用途：进入「本地闹钟」页、收到新闹钟消息、用户手动刷新时调用。
     */
    @JavascriptInterface
    fun syncAlarmsNow() {
        try {
            HeartbeatService.syncNow(context)
            Log.i(TAG, "syncAlarmsNow")
        } catch (e: Exception) {
            Log.e(TAG, "立即同步失败", e)
        }
    }

    /** 停止心跳（退出登录时调用）。 */
    @JavascriptInterface
    fun stopHeartbeat() {
        try {
            HeartbeatService.stop(context)
            Log.i(TAG, "stopHeartbeat")
        } catch (e: Exception) {
            Log.e(TAG, "停止心跳失败", e)
        }
    }

    /**
     * 把原生存的 refresh_token 交给网页版。
     *
     * 场景：用户在原生登录页登录过（旧版遗留），此时令牌在 SharedPreferences 里；
     * 网页版登录态在 localStorage。两者要打通，页面才能免登录。
     * 反向（网页 → 原生）由网页在登录成功后调 [syncRefreshTokenToNative] 完成。
     */
    @JavascriptInterface
    fun getRefreshToken(): String = store.getRefreshToken().orEmpty()

    /** 网页登录/刷新成功后，把 refresh_token 同步给原生，供心跳与开机重排使用。 */
    @JavascriptInterface
    fun syncRefreshTokenToNative(refreshToken: String) {
        if (refreshToken.isBlank()) return
        store.saveRefreshToken(refreshToken)
        Log.i(TAG, "已同步 refresh_token 到原生（长度 ${refreshToken.length}）")
    }

    /** 保存绑定的设备 uid（网页绑定设备成功后调用），心跳与开机重排要用。 */
    @JavascriptInterface
    fun saveDeviceUid(deviceUid: String, deviceName: String) {
        if (deviceUid.isBlank()) return
        store.saveDevice(deviceUid, deviceName.ifBlank { "我的手机" })
        Log.i(TAG, "已保存设备 ${deviceUid.take(8)}")
    }

    /** 读取原生已存的 device_uid（网页用来判断「本机是哪台设备」）。 */
    @JavascriptInterface
    fun getDeviceUid(): String = store.getDeviceUid()

    /** 读取原生已存的设备名（网页用作匹配线索）。 */
    @JavascriptInterface
    fun getDeviceName(): String = store.getDeviceName()

    /**
     * 本机机型（Build.MODEL）。
     *
     * 为什么要给网页：绑定时写入 model 能大幅提升后续
     * findMyDeviceUid 的匹配准确度 —— 多设备账号下，
     * 机型是区分「哪台才是当前手机」最可靠的线索。
     * 之前绑定时不带 model，导致只能退化成「取第一台」而选错设备。
     */
    @JavascriptInterface
    fun deviceModel(): String = android.os.Build.MODEL.orEmpty()

    /**
     * 本机机型的**摘要**（sha256 前 16 位，小写十六进制）—— 与后端
     * `app/core/masking.py:model_digest` 保持完全一致的算法。
     *
     * 为什么需要（真机实测根因）：
     *   后端 `/devices` 按 PRD §9 把型号脱敏成「Android 设备」，网页层
     *   无法用机型判断「哪台设备才是本机」；而未脱敏的 `model` 又不能给。
     *   摘要让网页层能**比对**（相等即同机型）但**反推不出**型号，
     *   兼顾识别准确度与脱敏要求。
     *
     * 归一化必须与后端一致：trim + 转小写后再 hash。
     */
    @JavascriptInterface
    fun deviceModelDigest(): String {
        val m = android.os.Build.MODEL.orEmpty().trim().lowercase()
        if (m.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(m.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 清除本地会话（退出登录时调用）。 */
    @JavascriptInterface
    fun clearSession() {
        store.clear()
        store.clearDevice()
        Log.i(TAG, "已清除本地会话")
    }

    /** 供网页判断是否跑在原生壳里（比 typeof 探测更直接）。 */
    @JavascriptInterface
    fun isNativeShell(): Boolean = true

    /**
     * ★ 查询是否有闹钟正在响铃（App 内兜底）。
     *
     * 为什么需要（用户实测「响了但界面不弹、进 App 也看不到」）：
     *   Android 10+ 拦截后台 App 启动 Activity（系统日志
     *   `Background activity launch blocked`）。此时铃声/震动/通知照常，
     *   但**没有界面**。本方法让网页在加载完成后主动问一次，
     *   若正在响就弹出响铃层 —— 不依赖任何后台启动界面的能力，
     *   是国产 ROM 上最可靠的一道兜底。
     *
     * @return JSON 字符串：{"ringing":true,"uid","displayTime","note"}
     *         没有在响时返回 "{}"
     */
    @JavascriptInterface
    fun getRingingState(): String = try {
        AlarmRingService.currentRingingJson()
    } catch (e: Exception) {
        Log.w(TAG, "查询响铃状态失败：${e.message}")
        "{}"
    }

    /** 停止当前响铃（网页兜底层点「关闭」时调用）。 */
    @JavascriptInterface
    fun stopRinging() {
        try {
            AlarmRingService.stop(context)
            Log.i(TAG, "已请求停止响铃")
        } catch (e: Exception) {
            Log.w(TAG, "停止响铃失败：${e.message}")
        }
    }

    /**
     * ★ 打开「电池优化白名单」授权页。
     *
     * 为什么需要（国产 ROM 上闹钟可靠性的决定性因素）：
     *   息屏后若应用被系统电池优化冻结，闹钟可能被压制。
     *   加入白名单后可显著提升后台存活与准时性。
     *
     * 实现：优先用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接弹授权框
     * （带包名，用户体验最好）；失败则退回电池优化列表页。
     */
    @JavascriptInterface
    fun openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    // 直接请求忽略电池优化（系统弹窗，一键允许）
                    val direct = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    ).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(direct)
                        Log.i(TAG, "已打开电池优化授权弹窗")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "直接授权失败，退回列表页：${e.message}")
                    }
                }
                // 已在白名单 / 直接授权不可用 → 打开电池优化列表
                val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(list)
                Log.i(TAG, "已打开电池优化列表")
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开电池优化设置失败", e)
        }
    }

    /** 是否已加入电池优化白名单（供界面显示状态） */
    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 打开本应用的「应用详情」页（自启动 / 后台管理入口都在这里）。
     *
     * 为什么用这一页而不是厂商专有 Activity：
     *   小米/华为/OPPO/vivo 的自启动管理页面类名各不相同且随版本变化，
     *   硬编码跳转极易失效（且会因 targetSdk 限制抛
     *   ActivityNotFoundException）。跳到系统原生的应用详情页是**唯一
     *   稳定可达**的入口，用户在那里能找到「电池」「自启动」等入口。
     *   厂商专有的那些页面我们也尝试跳，失败就静默退回详情页。
     */
    @JavascriptInterface
    fun openAppSettings() {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            Log.i(TAG, "已打开应用详情页")
        } catch (e: Exception) {
            Log.e(TAG, "打开应用详情失败", e)
        }
    }

    /**
     * 尝试打开厂商专有的「自启动管理」页；不可用返回 false。
     *
     * 各厂商的 ComponentName 是社区长期总结的，**不保证在所有版本有效**，
     * 所以调用方必须处理 false（退回 openAppSettings）。
     */
    @JavascriptInterface
    fun openAutoStartSettings(): Boolean {
        // 常见厂商自启动管理页（按品牌匹配，逐个尝试）
        val candidates = listOf(
            // 小米 / Redmi
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO / realme
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // 三星
            "com.samsung.android.lool" to
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            // 一加
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            // 乐视 / 魅族等
            "com.letv.android.letvsafe" to
                "com.letv.android.letvsafe.AutobootManageActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )
        for ((pkg, cls) in candidates) {
            try {
                val i = Intent().apply {
                    component = android.content.ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                Log.i(TAG, "已打开厂商自启动页：$pkg/$cls")
                return true
            } catch (_: Exception) {
                // 该厂商/版本没有这个页面 → 试下一个
            }
        }
        Log.i(TAG, "没有可用的厂商自启动页，调用方应退回应用详情页")
        return false
    }

    /**
     * 当前设备品牌（供界面提示用，例如「小米手机请找『自启动』」）。
     * 取 Build.MANUFACTURER，小写。
     */
    @JavascriptInterface
    fun deviceManufacturer(): String =
        (android.os.Build.MANUFACTURER ?: "").lowercase()

    /** 原生壳版本号，便于网页针对性兼容。 */
    @JavascriptInterface
    fun shellVersion(): String = "1.0"
}
