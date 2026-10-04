package com.trustalarm.app.alarm

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trustalarm.app.R

/**
 * 响铃界面 —— 系统闹钟到点后展示。
 *
 * ## 为什么要全屏 + 锁屏可见
 *   闹钟的意义就是「人在睡觉时也能被叫醒」。
 *   若只是普通通知，用户在锁屏下可能看不到/听不到。
 *   所以：showWhenLocked + turnScreenOn + 全屏。
 *
 * ## ★ 铃声与震动已移到 AlarmRingService（用户实测「后台不响」的修复）
 *
 *   原来本 Activity 负责放铃与震动。但 Android 10+ 后台 App 无法拉起 Activity，
 *   界面起不来 → 铃声震动也一起没了 → 完全静默。
 *
 *   现在：AlarmRingService 负责「响」，本界面只负责「显示 + 接受关闭操作」。
 *   界面拉起失败也不影响响铃。本 Activity 关闭时同步停掉服务。
 *
 * ## 为什么用 RingtoneManager 而不是打包音频
 *   用系统默认闹铃，用户熟悉且不增加 APK 体积；
 *   后续可换成自定义铃声（放 res/raw + 设置页选择）。
 */
class AlarmRingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上显示 + 点亮屏幕（闹钟的关键体验）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 若设备有锁屏且已解锁过，请求 dismiss keyguard（否则界面被锁屏遮挡）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) {
                km.requestDismissKeyguard(this, null)
            }
        }

        setContentView(R.layout.activity_alarm_ring)

        val displayTime = intent.getStringExtra(AlarmRingService.EXTRA_DISPLAY_TIME).orEmpty()
        val note = intent.getStringExtra(AlarmRingService.EXTRA_NOTE).orEmpty()

        findViewById<TextView>(R.id.txtTime).text = displayTime.ifBlank { "--:--" }
        findViewById<TextView>(R.id.txtNote).text = note

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAndFinish() }
    }

    private fun stopAndFinish() {
        // 停掉服务（铃声 + 震动 + 通知都在服务里）
        AlarmRingService.stop(this)
        finish()
    }

    /**
     * 界面被系统销毁（例如被划掉）时也要停响。
     *
     * 注意：这里用 onDestroy 而非 onPause —— 用户可能想边看边响，
     * 短暂失焦不该停。真正的停止出口只有「关闭闹钟」按钮与 5 分钟自动停。
     */
    override fun onDestroy() {
        AlarmRingService.stop(this)
        super.onDestroy()
    }

    /** 按返回键不关闭 —— 必须点「关闭」才停（避免迷迷糊糊按掉闹钟） */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 故意不处理
    }
}
