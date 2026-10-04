package com.trustalarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 闹钟响铃前台服务 —— **真正负责出声与拉起界面的那个组件**。
 *
 * ## 为什么必须有这个服务（★ 用户实测「App 在后台不响」的根因）
 *
 * 原实现是 `AlarmReceiver.onReceive` 里直接 `context.startActivity(ringIntent)`。
 * 这在 Android 10 (API 29) 之后**必然失败**：
 *
 *   系统新增「后台启动 Activity」限制（Background Activity Start）。
 *   App 处于后台时（息屏、按 Home、被切走），从广播接收器里 startActivity
 *   会被系统**静默拦截** —— 不抛异常、不报错、不崩溃，就是什么都不发生。
 *   所以原代码里那句 `catch (e) { 改用通知 }` 永远走不到，
 *   表现为「到点完全没反应」。
 *
 * 正确做法（系统闹钟类 App 的标准架构）：
 *   到点 → 广播收到 → **启动前台服务** → 服务持有高优先级全屏通知
 *   → 由前台服务去 startActivity（前台服务处于 FGS 状态，
 *     是系统允许后台启动 Activity 的少数场景之一）
 *
 *   即使 Activity 仍被拦（部分国产 ROM 更严），**通知 + 铃声 + 震动依然生效**，
 *   用户至少能被叫醒 —— 比「静默失败」好得多。
 *
 * ## 为什么铃声/震动放在服务里而不是 Activity 里
 *   原来铃声与震动都在 AlarmRingActivity 内。Activity 起不来 → 全程静默。
 *   搬到服务后，「响」不再依赖界面能否成功拉起，两者解耦。
 *   Activity 只负责展示，并通知服务停止响铃。
 *
 * ## 生命周期
 *   start → 响铃 + 通知 + 尝试拉起界面
 *   停止 → 用户点「关闭闹钟」（界面或通知按钮）→ stopSelf()
 *   兜底 → 最长 5 分钟自动停（避免忘了关一直响到没电）
 */
class AlarmRingService : Service() {

    companion object {
        private const val TAG = "AlarmRingService"
        const val ACTION_START = "ring_start"
        const val ACTION_STOP = "ring_stop"
        const val EXTRA_ALARM_UID = "alarm_uid"
        const val EXTRA_DISPLAY_TIME = "display_time"
        const val EXTRA_NOTE = "note"

        private const val CHANNEL_ID = "alarm_ringing"
        private const val NOTIF_ID = 2001

        /** 最长响铃时长（毫秒）—— 防止用户没关一直响 */
        private const val AUTO_STOP_MS = 5 * 60 * 1000L

        /**
         * ★ 正在响铃的闹钟（全局状态，供「App 内兜底」查询）。
         *
         * 为什么需要（用户实测「响了但界面不弹、进 App 也看不到」）：
         *   Android 10+ 会拦截后台 App 启动 Activity，系统日志为
         *   `Background activity launch blocked`。此时铃声/震动/通知都正常，
         *   但**没有任何界面**告诉用户是谁在响、怎么关。
         *   本字段让网页在加载后能主动查到「有个闹钟正在响」，
         *   从而在 App 内弹出响铃层 —— 这是最后一道兜底，
         *   不依赖任何后台启动界面的能力。
         *
         * 用 @Volatile：写入在主线程（onStartCommand），
         * 读取可能来自 WebView 的 JS 线程（bridge 调用）。
         */
        @Volatile
        private var ringingUid: String = ""

        @Volatile
        private var ringingTime: String = ""

        @Volatile
        private var ringingNote: String = ""

        /** 查询是否正在响铃，返回 JSON（给 NativeBridge 用）。 */
        fun currentRingingJson(): String {
            if (ringingUid.isBlank()) return "{}"
            return org.json.JSONObject()
                .put("ringing", true)
                .put("uid", ringingUid)
                .put("displayTime", ringingTime)
                .put("note", ringingNote)
                .toString()
        }

        fun start(context: Context, uid: String, displayTime: String, note: String) {
            val i = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_UID, uid)
                putExtra(EXTRA_DISPLAY_TIME, displayTime)
                putExtra(EXTRA_NOTE, note)
            }
            // Android 8+ 必须用 startForegroundService，否则后台启动服务会被拒。
            // ★ Android 12+ 可能抛 ForegroundServiceStartNotAllowedException
            //   （从后台广播起服务被限制）。这里**不把异常往上抛**，
            //   而是自己降级：直接拉起响铃界面 + 发通知，保证用户一定有出口。
            //   （历史教训：抛给 AlarmReceiver 后只发通知，而通知若没权限就全没了。）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "前台服务启动被拒，降级：直接拉起界面 + 通知", e)
                // 降级 1：直接拉起全屏响铃界面（用户一定有「关闭」按钮）
                try {
                    context.startActivity(
                        Intent(context, AlarmRingActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                            )
                            putExtra(EXTRA_ALARM_UID, uid)
                            putExtra(EXTRA_DISPLAY_TIME, displayTime)
                            putExtra(EXTRA_NOTE, note)
                        },
                    )
                } catch (e2: Exception) {
                    Log.w(TAG, "直接拉起界面也失败：${e2.message}")
                }
                // 降级 2：发一条全屏通知（有权限时能横幅/全屏弹出）
                AlarmNotifier.showRinging(context, uid, displayTime, note)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStop: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令")
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val uid = intent?.getStringExtra(EXTRA_ALARM_UID).orEmpty()
                val time = intent?.getStringExtra(EXTRA_DISPLAY_TIME).orEmpty()
                val note = intent?.getStringExtra(EXTRA_NOTE).orEmpty()
                Log.i(TAG, "开始响铃 $time（${uid.take(8)}）")

                // 记录响铃状态：供 App 内兜底查询（后台界面被拦时的最后一道保险）
                ringingUid = uid
                ringingTime = time
                ringingNote = note

                // 必须是前台服务：既保证不被杀，也让通知可见
                startForeground(NOTIF_ID, buildNotification(time, note))
                acquireWakeLock()
                startRinging()
                launchRingActivity(uid, time, note)
                scheduleAutoStop()
                // 响铃期间保持重排链（响完后 AlarmScheduler 会因 unmark 而跳过）
            }
        }
        // 被系统杀掉后自动重启（用户还在等这个闹钟）
        return START_STICKY
    }

    // ---------------- 响铃 ----------------

    private fun startRinging() {
        // 铃声走「闹钟」音量通道：用户把媒体音量调低也不影响闹钟
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 没有默认闹铃也不能整体失败 —— 震动仍是有效的叫醒手段
            Log.w(TAG, "播放闹铃失败（继续震动）：${e.message}")
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.let {
            val pattern = longArrayOf(0, 500, 500, 500, 500, 500, 1000)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "震动失败：${e.message}")
            }
        }
    }

    private fun teardown() {
        // 清除响铃状态（否则 App 内兜底会一直以为在响）
        ringingUid = ""
        ringingTime = ""
        ringingNote = ""
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        autoStop?.removeCallbacksAndMessages(null)
        autoStop = null
        releaseWakeLock()
    }

    /**
     * 拉起全屏响铃界面。
     *
     * 前台服务持有时，后台启动 Activity 更容易被系统放行；但仍可能被
     * 国产 ROM 拦截 —— 那种情况下通知+铃声+震动已经生效，可接受。
     */
    private fun launchRingActivity(uid: String, time: String, note: String) {
        val ring = Intent(this, AlarmRingActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(EXTRA_ALARM_UID, uid)
            putExtra(EXTRA_DISPLAY_TIME, time)
            putExtra(EXTRA_NOTE, note)
        }
        try {
            startActivity(ring)
            Log.i(TAG, "已拉起响铃界面")
        } catch (e: Exception) {
            // 这里才是真正的降级点：界面起不来，但通知/铃声/震动都在响
            Log.w(TAG, "无法拉起响铃界面，仅保留通知与铃声：${e.message}")
        }
    }

    // ---------------- 通知 ----------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "闹钟响铃",
            NotificationManager.IMPORTANCE_HIGH, // 必须 HIGH：要能横幅弹出并响
        ).apply {
            description = "闹钟到点时的响铃提醒"
            setSound(null, null)   // 声音由 MediaPlayer 播放，避免双份
            enableVibration(false) // 震动由 Vibrator 控制
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)     // 闹钟应能穿透勿扰（需系统授权，尽力而为）
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(time: String, note: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AlarmRingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_DISPLAY_TIME, time)
                putExtra(EXTRA_NOTE, note)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("闹钟 $time")
            .setContentText(note.ifBlank { "点按关闭闹钟" })
            // ★ 必须 MAX：只有 MAX 才会以「横幅(heads-up)」形式弹出。
            //   HIGH 只在部分 ROM 上弹横幅，多数会降到抽屉里不显眼 ——
            //   用户实测「亮屏时看不到闹钟」就是这个原因。
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // ★ 去掉 setOngoing(true)：Android 对 ongoing 通知有额外的去重/抑制
            //   逻辑（尤其与前台服务通知同时存在时），会阻止横幅弹出。
            //   用 setAutoCancel(false) 保证它不会被误点掉即可。
            .setOngoing(false)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // ★ setFullScreenIntent(highPriority=true)：
            //   锁屏/息屏时直接拉全屏界面；亮屏时系统退化为横幅 —— 两条路都要
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            // 让通知在锁屏上也能被点击进入（部分 ROM 需要显式声明）
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(0, "关闭闹钟", stopPi)
            .build()
    }

    // ---------------- 电源 ----------------

    /**
     * 持有唤醒锁：确保从「到点」到「用户操作」这段时间 CPU 不休眠。
     * 铃声播放与界面拉起都需要 CPU 在跑；超时自动释放防泄漏。
     *
     * ## 为什么用 FULL_WAKE_LOCK 级别的组合（用户实测「息屏不响」）
     *
     * 原来只有 PARTIAL_WAKE_LOCK —— 它**只保 CPU，不点屏**。
     * 息屏状态下到点：CPU 起来了、铃声能响，但屏幕黑着，
     * 用户看不到界面（且部分 ROM 在黑屏 + Doze 下会把音频也压掉）。
     *
     * 现在同时申请 ACQUIRE_CAUSES_WAKEUP + 屏幕级锁：
     *   · ACQUIRE_CAUSES_WAKEUP：获取锁时**立即点亮屏幕**
     *   · SCREEN_BRIGHT_WAKE_LOCK（已弃用但兼容旧系统）
     *   · 新系统走 Activity 的 setTurnScreenOn(true)（已在响铃界面里）
     * 这样息屏到点会：亮屏 + 响铃 + 震动 + 通知，与系统闹钟一致。
     *
     * 注意：FULL_WAKE_LOCK 属于 deprecated API，但仍是被系统闹钟类 App
     * 广泛使用的最可靠方式；配合前台服务与通知，行为可控。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "TrustAlarm:AlarmRing",
            ).apply {
                setReferenceCounted(false)
                acquire(AUTO_STOP_MS + 30_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取唤醒锁失败（退回仅保 CPU）：${e.message}")
            // 兜底：至少保住 CPU，铃声还能响
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrustAlarm:AlarmRing")
                    .apply {
                        setReferenceCounted(false)
                        acquire(AUTO_STOP_MS + 30_000L)
                    }
            } catch (e2: Exception) {
                Log.w(TAG, "部分唤醒锁也失败：${e2.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /** 5 分钟没人关就自动停 —— 避免闹钟一直响到没电 */
    private fun scheduleAutoStop() {
        autoStop = Handler(mainLooper)
        autoStop?.postDelayed({
            Log.w(TAG, "超过 ${AUTO_STOP_MS / 1000}s 无人关闭，自动停止")
            teardown()
            stopSelf()
        }, AUTO_STOP_MS)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
