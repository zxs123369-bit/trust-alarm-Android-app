package com.trustalarm.app.ui.localalarms

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 跳到系统「精确闹钟」授权页。
 *
 * ## 为什么独立成函数而不复用 `NativeBridge.openExactAlarmSettings()`
 *
 * `NativeBridge` 是 WebView 桥，阶段 6（摘除 WebView）会整体删除。
 * 原生 UI 若依赖它，删除时就会一起被带走 —— 所以这里写一份独立的实现。
 * 逻辑与桥里那份逐行一致（S+ 才需要授权，带 package 直达本应用条目）。
 *
 * Android 12（API 31）起 `SCHEDULE_EXACT_ALARM` 需用户手动授予，
 * 未授权时闹钟会被系统延迟（可能延迟几分钟），所以必须给用户一个去处。
 */
fun openExactAlarmSettings(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    } catch (e: Exception) {
        Log.e("LocalAlarms", "打开精确闹钟设置失败", e)
    }
}
