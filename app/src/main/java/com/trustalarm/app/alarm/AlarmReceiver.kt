package com.trustalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.trustalarm.app.data.ApiClient
import com.trustalarm.app.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发器 —— AlarmManager 到点后回调这里。
 *
 * ## 这是「下发注册本地闹钟」的落地环节
 *   后端下发闹钟 → App 收到 → AlarmScheduler 注册到系统
 *   → 到点系统唤醒本 Receiver → 交给 AlarmRingService 响铃
 *
 * ## 为什么本类只做「转发」而不直接响铃（★ 用户实测「后台不响」的修复）
 *
 *   BroadcastReceiver 的 onReceive 主线程只有约 10 秒，且**进程不在前台时
 *   不允许启动 Activity 以外的很多操作**，更要命的是：
 *
 *     Android 10+ 禁止后台 App 从广播里 startActivity（静默拦截，不抛异常）。
 *     原来这里直接 `context.startActivity(AlarmRingActivity)`，
 *     所以 App 一旦退到后台，到点就完全没反应 —— 用户实测到的就是这个。
 *
 *   修法：这里只负责「把闹钟到点这件事转交给前台服务」，由服务去响铃、
 *   发通知、尝试拉起界面。Receiver 保持极轻量，快速返回。
 *
 * ## 为什么要唤醒锁
 *   系统在冷启动广播时可能只保证很短时间 CPU 可用。若设备刚睡下，
 *   响应完就休眠，铃声可能起不来。这里持一个短时唤醒锁，
 *   覆盖到「服务已 startForeground」为止（服务自己有更长的锁）。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RING = "com.trustalarm.app.ACTION_RING"

        /**
         * 周期性「重排」动作（见 AlarmScheduler.scheduleRearm）。
         *
         * 收到的唯一任务：把后端上仍未到点的闹钟**重新注册一遍**。
         * 这是「App 被清掉后台后到点不响」的修复手段 ——
         * 用户清理后台会把应用置为 stopped，系统不再投递其广播，
         * 已注册的一次性闹钟也随之失效；周期重排能在状态恢复后自动补回。
         */
        const val ACTION_REARM = "com.trustalarm.app.ACTION_REARM"

        const val EXTRA_ALARM_UID = "alarm_uid"
        const val EXTRA_DISPLAY_TIME = "display_time"
        const val EXTRA_NOTE = "note"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REARM -> handleRearm(context)
            ACTION_RING -> handleRing(context, intent)
            else -> return
        }
    }

    /**
     * 周期性重排：从后端拉一次闹钟并全量重注册。
     *
     * 为什么放在广播里而不是只靠前台服务：
     *   AlarmManager 的周期广播比「服务能不能活着」更可靠 ——
     *   即使我们的前台服务被 ROM 杀掉了，这个广播仍会（在应用未被
     *   永久停止的前提下）唤醒进程跑一次，从而自我修复。
     *
     * 用 goAsync()：网络请求不能在 onReceive 的主线程同步做。
     */
    private fun handleRearm(context: Context) {
        Log.i(TAG, "收到周期重排广播，开始校验并补注闹钟")
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = TokenStore(appCtx)
                if (!store.hasRefreshToken()) {
                    Log.i(TAG, "重排：未登录，跳过")
                    return@launch
                }
                val api = ApiClient(appCtx)
                if (!api.restoreSession()) {
                    Log.w(TAG, "重排：会话恢复失败，跳过")
                    return@launch
                }
                val deviceUid = store.getDeviceUid()
                if (deviceUid.isBlank()) {
                    Log.w(TAG, "重排：无 device_uid，跳过")
                    return@launch
                }
                val alarms = try {
                    api.listAlarms(deviceUid)
                } catch (e: Exception) {
                    Log.w(TAG, "重排：拉取闹钟失败：${e.message}")
                    return@launch
                }
                var n = 0
                alarms.filter { it.shouldSchedule }.forEach { item ->
                    if (AlarmScheduler.schedule(appCtx, item)) n++
                }
                Log.i(TAG, "重排完成：补注 $n 个闹钟（共 ${alarms.size} 条）")
            } catch (e: Exception) {
                Log.e(TAG, "重排异常", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 到点响铃：转交前台服务。
     *
     * 本方法保持**极轻量** —— 只抢一个短时唤醒锁并启动服务，
     * 真正的铃声/震动/通知/拉界面全在 AlarmRingService 里。
     */
    private fun handleRing(context: Context, intent: Intent) {
        val alarmUid = intent.getStringExtra(EXTRA_ALARM_UID) ?: return
        val displayTime = intent.getStringExtra(EXTRA_DISPLAY_TIME).orEmpty()
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()

        Log.i(TAG, "闹钟到点：$displayTime（${alarmUid.take(8)}）→ 转交响铃服务")

        // ★ 先抢一个短时唤醒锁（用户实测「息屏不响」的修复之一）
        //
        // 为什么必须在最前面：AlarmManager 到点只是**短暂**唤醒 CPU，
        // 广播处理完就允许重新休眠。若在 startForegroundService 之前
        // 设备又睡下去（息屏 + Doze 时很可能），服务可能迟迟起不来
        // 或铃声被压制 —— 表现为「亮屏会响、息屏不响」。
        // 这里持锁覆盖「从收到广播到服务接手响铃」这段关键窗口，
        // 服务自身有更长的锁（见 AlarmRingService.acquireWakeLock）。
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "TrustAlarm:AlarmReceiver",
        ).apply {
            setReferenceCounted(false)
            try {
                acquire(10_000L)   // 10 秒足够服务接手（它自己会续锁）
            } catch (e: Exception) {
                Log.w(TAG, "获取唤醒锁失败：${e.message}")
            }
        }

        // 已触发 → 清掉注册记录，并**单独记下「已响过」**。
        // 后者是必需的：宽限期机制下，若只清记录，下一轮同步会把
        // 这个刚响过的闹钟又注册一遍 → 十秒后再响一次（实测过）。
        AlarmScheduler.unmarkScheduled(context, alarmUid)
        AlarmScheduler.markFired(context, alarmUid)

        // 交给前台服务：它负责铃声、震动、通知、尝试拉起界面。
        // 即使后续一切界面被系统拦截，铃声与通知也会响 —— 不会再静默失败。
        try {
            AlarmRingService.start(context.applicationContext, alarmUid, displayTime, note)
            Log.i(TAG, "已启动 AlarmRingService")
        } catch (e: Exception) {
            // 极少数 ROM 连前台服务都不让起 → 退到纯通知，至少留个痕迹
            Log.e(TAG, "启动响铃服务失败，降级为通知", e)
            AlarmNotifier.showRinging(context, alarmUid, displayTime, note)
        } finally {
            // 服务已接手（或已降级），尽快释放，避免长期占锁
            try {
                if (wl.isHeld) wl.release()
            } catch (_: Exception) {
            }
        }
    }
}
