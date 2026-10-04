package com.trustalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trustalarm.app.data.ApiClient
import com.trustalarm.app.data.TokenStore
import com.trustalarm.app.service.HeartbeatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后：重新注册闹钟 **并重启后台服务**。
 *
 * ## 为什么必须重排闹钟
 *   AlarmManager 注册的闹钟在**重启后全部丢失**（系统行为）。
 *   若不重排，用户重启手机后所有闹钟都不响了 —— 对信任类产品
 *   这是致命的（家人以为闹钟会响，实际没有）。
 *
 * ## 为什么还要重启后台服务（★ 用户实测反馈的缺陷）
 *   重启手机后进程全没了，心跳与闹钟同步都停止。若不在这里拉起
 *   HeartbeatService：
 *     · 设备显示离线（后端 120 秒无心跳即判离线）
 *     · 之后家人新设的闹钟不会被注册（没人去同步）
 *   原实现只重排闹钟、不启动服务，导致重启后永久离线，
 *   直到用户手动重新绑定设备。
 *
 * ## 实现方式
 *   开机广播 → 启动后台服务（它内部会立刻同步一次闹钟）
 *            → 同时自己再重排一遍作为兜底（服务启动可能被系统延迟）。
 *   用 goAsync() 延长生命周期（网络请求需要时间）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "收到 $action，恢复后台服务并重排闹钟")

        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = TokenStore(appContext)
                val deviceUid = store.getDeviceUid()
                if (!store.hasRefreshToken() || deviceUid.isBlank()) {
                    Log.i(TAG, "未登录或未绑定设备，跳过")
                    return@launch
                }

                // 1) 恢复后台常驻服务（心跳 + 闹钟同步）。
                //    这一步比下面的一次性重排更重要 —— 它让系统持续自我维护。
                try {
                    HeartbeatService.start(appContext)
                    Log.i(TAG, "已启动后台服务")
                } catch (e: Exception) {
                    // Android 12+ 对后台启动前台服务有限制，失败不能影响重排
                    Log.w(TAG, "启动后台服务失败（继续重排）：${e.message}")
                }

                // 1.5) ★ 挂周期性重排链（开机是唯一的「必然被调用」时机之一）。
                //      App 被清后台会进入 stopped 状态，系统不再投递其广播，
                //      一次性闹钟随之失效。这条周期链负责在状态恢复后自动补注。
                try {
                    AlarmScheduler.scheduleRearm(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "挂周期重排失败：${e.message}")
                }

                // 2) 立即重排一次，避免等服务的下一个周期（最坏 60 秒）
                val api = ApiClient(appContext)
                if (!api.restoreSession()) {
                    Log.w(TAG, "会话已失效，跳过重排")
                    return@launch
                }

                val alarms = api.listAlarms(deviceUid)
                var n = 0
                alarms.filter { it.shouldSchedule }.forEach { item ->
                    if (AlarmScheduler.schedule(appContext, item)) n++
                }
                Log.i(TAG, "重排完成：$n / ${alarms.size} 个闹钟已注册")
            } catch (e: Exception) {
                Log.e(TAG, "重排失败", e)
            } finally {
                pending.finish()
            }
        }
    }
}
