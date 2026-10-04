package com.trustalarm.app.ui.reliability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台可靠性相关的系统能力（原生实现，不依赖 WebView 桥）。
 *
 * ## 为什么单独一份而不是复用 NativeBridge
 *
 * `NativeBridge` 是 WebView 桥，阶段 6 摘 WebView 时会整体删除。
 * 原生 UI 若依赖它，删除时一并被带走 —— 与 `ui/localalarms/ExactAlarm.kt`
 * 同一理由，这里写独立实现。逻辑与桥里那几份一致。
 *
 * ## 这三件事为什么必须跳系统设置
 *
 * 精确闹钟、电池白名单、自启动都是**系统级开关，App 无权代改**。
 * 只能读取状态 + 跳转到对应设置页引导用户手动开。
 */
object ReliabilityNative {

    private const val TAG = "ReliabilityNative"

    /** 是否已加入电池优化白名单（可直接查询，可信） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        Log.e(TAG, "查询电池白名单失败", e)
        false
    }

    /**
     * 打开电池优化授权。
     *
     * 优先用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹本应用的授权框；
     * 部分 ROM 不支持该 action，退回白名单列表页（用户自己找本应用）。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            if (!isIgnoringBatteryOptimizations(context)) {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(direct)
                return
            }
        } catch (_: Exception) {
            // 落到下面的列表页
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开电池优化设置失败", e)
        }
    }

    /** 打开系统「应用详情」页（唯一稳定入口，作为自启动页的兜底） */
    fun openAppSettings(context: Context) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开应用详情失败", e)
        }
    }

    /**
     * 打开厂商「自启动」设置页。
     *
     * 厂商类名随 ROM 版本变动，**不可靠**，所以逐个试；
     * 全部失败返回 false，由调用方退回 [openAppSettings]。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = listOf(
            // 小米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            // OPPO / realme
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                context.startActivity(
                    Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (_: Exception) {
                // 试下一个
            }
        }
        return false
    }

    /** 厂商名（用于给出针对性的自启动提示文案） */
    fun deviceManufacturer(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.MANUFACTURER
        } else {
            @Suppress("DEPRECATION")
            Build.MANUFACTURER
        }

    /** 按品牌给出「去哪儿找自启动开关」的具体提示（1:1 平移网页版 autoStartHint） */
    fun autoStartHint(brand: String?): String {
        val b = (brand ?: "").lowercase()
        return when {
            b.contains("xiaomi") || b.contains("redmi") ->
                "小米：应用详情 → 自启动（或「省电策略」选「无限制」）"
            b.contains("huawei") || b.contains("honor") ->
                "华为/荣耀：应用详情 → 耗电详情 → 应用启动管理 → 手动管理，三项全开"
            b.contains("oppo") || b.contains("realme") ->
                "OPPO/realme：应用详情 → 耗电管理 → 允许后台运行 + 允许自启动"
            b.contains("vivo") || b.contains("iqoo") ->
                "vivo/iQOO：应用详情 → 权限管理 → 自启动"
            b.contains("samsung") ->
                "三星：电池 → 后台使用限制 → 把本应用加入「永不休眠的应用」"
            else ->
                "在「应用详情 → 电池 / 后台管理」里设为「不受限制」，并允许自启动"
        }
    }
}
