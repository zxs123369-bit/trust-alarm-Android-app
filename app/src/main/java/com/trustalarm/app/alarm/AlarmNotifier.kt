package com.trustalarm.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trustalarm.app.R

/**
 * 闹钟通知。
 *
 * ## 为什么这里曾经是「响了但哪里都看不到」的元凶之一（用户实测）
 *
 * 原实现有两个渠道：
 *   · 本文件建 `alarm_ring`（IMPORTANCE_HIGH + setFullScreenIntent）
 *   · AlarmRingService 自己建 `alarm_ringing`
 * 而**实际发出通知的是服务**（它用 `alarm_ringing`）。
 * 于是本文件精心配的 setFullScreenIntent / setCategory(ALARM) 全都没被用上 ——
 * 用户看到的效果是：有声音，但通知栏没有一条能点的闹钟通知，
 * 解锁后也不知道是谁在响。
 *
 * 现在：**渠道 id 统一为一个常量**，服务与兜底路径共用同一渠道配置，
 * 避免「配了但没生效」这种极难察觉的错配。
 */
object AlarmNotifier {

    /**
     * 响铃通知渠道 id —— **全局唯一**，服务与兜底都必须用它。
     *
     * 注意：改这个 id 会让旧渠道变成孤儿（旧渠道仍存在于系统里但不被使用），
     * 所以升级时应保证新渠道被真正创建（见 [ensureChannels]）。
     */
    const val CHANNEL_RING = "alarm_ringing"

    /** 心跳前台服务的常驻通知渠道（低优先级，不打扰） */
    const val CHANNEL_HEARTBEAT = "heartbeat"

    /**
     * 创建/更新通知渠道。
     *
     * 为什么要「更新」而不只是「创建」：渠道一旦创建，后续对
     * importance 等关键字段的修改**不会生效**（Android 的渠道不可变策略），
     * 但 setDescription / setSound 等部分属性仍可更新。
     * 这里每次都调用 createNotificationChannel 是幂等且安全的。
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RING,
                "闹钟响铃",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "闹钟到点时的响铃提醒"
                // 声音由 AlarmRingService 的 MediaPlayer 播放（走闹钟音量通道），
                // 通知本身不要再响一次，否则会双份。
                setSound(null, null)
                // 震动同样由服务的 Vibrator 控制
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                // 允许在锁屏上显示全部内容（闹钟信息不敏感，且必须看清）
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HEARTBEAT,
                "设备在线",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持与家庭的连接，让家人看到设备在线"
            },
        )
    }

    /**
     * 响铃通知（高优先级 + 全屏 Intent + 停止按钮）。
     *
     * 兜底路径：当 AlarmRingService 无法启动（极少数 ROM）时使用。
     * 正常情况下由服务自己发同配置的通知。
     */
    fun showRinging(context: Context, alarmUid: String, displayTime: String, note: String) {
        ensureChannels(context)

        val full = Intent(context, AlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmRingService.EXTRA_ALARM_UID, alarmUid)
            putExtra(AlarmRingService.EXTRA_DISPLAY_TIME, displayTime)
            putExtra(AlarmRingService.EXTRA_NOTE, note)
        }
        val pi = PendingIntent.getActivity(
            context,
            alarmUid.hashCode(),
            full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("闹钟 $displayTime")
            .setContentText(note.ifBlank { "点按关闭闹钟" })
            // 与 AlarmRingService 的响铃通知保持一致的配置：
            //   MAX → 亮屏时以横幅弹出；fullScreenIntent → 锁屏直接全屏
            //   ongoing=false → 避免被系统抑制横幅
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_RING, n)
        } catch (e: SecurityException) {
            // 未授予通知权限：静默（界面里会提示用户去开权限）
        }
    }

    /** 响铃通知 id —— 服务与兜底必须一致，否则会出现两条通知 */
    const val NOTIF_ID_RING = 2001

    /** 心跳服务的常驻通知（前台服务必需） */
    fun heartbeatNotification(context: Context, deviceName: String): android.app.Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.trustalarm.app.ui.WebMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_HEARTBEAT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("信任闹钟运行中")
            .setContentText("$deviceName · 保持在线")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
