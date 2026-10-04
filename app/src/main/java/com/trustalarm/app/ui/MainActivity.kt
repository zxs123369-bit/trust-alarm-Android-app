package com.trustalarm.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trustalarm.app.R
import com.trustalarm.app.alarm.AlarmNotifier
import com.trustalarm.app.alarm.AlarmScheduler
import com.trustalarm.app.data.AlarmItem
import com.trustalarm.app.data.ApiClient
import com.trustalarm.app.data.TokenStore
import com.trustalarm.app.service.HeartbeatService
import kotlinx.coroutines.launch

/**
 * 主页：本机闹钟列表 + 系统注册状态。
 *
 * ## 核心流程
 *   登录 → 绑定本机（若无）→ 拉取本机闹钟 → 逐个注册到系统 AlarmManager
 *   → 展示每个闹钟「已注册 / 未注册」状态
 *
 * 这个页面回答的核心问题：「我的闹钟到底会不会响？」
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIF = 1001
    }

    private lateinit var api: ApiClient
    private lateinit var store: TokenStore
    private lateinit var container: LinearLayout
    private lateinit var txtStatus: TextView

    /** 防重入：绑定/加载过程中重复点按钮不会并发发请求 */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        api = ApiClient(applicationContext)
        store = TokenStore(applicationContext)
        container = findViewById(R.id.listAlarms)
        txtStatus = findViewById(R.id.txtStatus)

        AlarmNotifier.ensureChannels(this)
        requestNotificationPermission()

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAndRegister() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            HeartbeatService.stop(this)
            api.clearSession()
            store.clearDevice()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        lifecycleScope.launch {
            // 恢复会话 → 确保已绑定本机（必要时自助建家）→ 拉闹钟并注册
            if (!api.restoreSession()) {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
                return@launch
            }
            if (ensureDeviceBound()) {
                loadAndSchedule()
                startHeartbeat()
            }
        }
    }

    /**
     * 绑定本机（第一次使用时）；已绑定则复用。
     *
     * 【流程修正】原实现要求「必须先有家庭」，否则只提示「去网页版创建」，
     * 导致单人用户第一次装 App 就被卡死（App 内无任何建家入口）。
     * 现在：没有家庭就自助创建一个，然后继续绑定 —— 用户零感知。
     */
    private suspend fun ensureDeviceBound(): Boolean {
        if (store.hasDevice()) return true
        try {
            val fams = api.listFamilies()
            val famUid = if (fams.isEmpty()) {
                setStatus("正在为你创建家庭…")
                val uid = api.createFamily("我的家庭")
                Log.i(TAG, "已自助创建家庭：${uid.take(8)}")
                uid
            } else {
                fams[0].optString("uid")
            }
            if (famUid.isBlank()) {
                setStatus("绑定失败：家庭创建返回空 uid")
                return false
            }
            setStatus("正在绑定本机…")
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"
            val dev = api.bindDevice(famUid, "我的手机", model)
            store.saveDevice(dev.uid, dev.deviceName)
            Log.i(TAG, "本机已绑定：${dev.deviceName} (${dev.uid.take(8)})")
            Toast.makeText(this, "本机已绑定：${dev.deviceName}", Toast.LENGTH_LONG).show()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "绑定设备失败", e)
            setStatus("绑定设备失败：${e.message}\n请检查手机与电脑是否同一 WiFi")
            return false
        }
    }

    /** 统一的状态文字出口（避免多处赋值互相覆盖） */
    private fun setStatus(text: String) {
        runOnUiThread { txtStatus.text = text }
    }

    /**
     * 【按钮入口】刷新并注册。
     * 原实现：deviceUid 为空时静默 return，用户点了「没反应」。
     * 现在：先确保已绑定（含自助建家庭），再拉闹钟注册 —— 点了必有事发生。
     */
    private fun refreshAndRegister() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                if (!ensureDeviceBound()) return@launch
                loadAndSchedule()
                startHeartbeat()
            } finally {
                busy = false
            }
        }
    }

    /** 拉取闹钟并注册到系统 */
    private fun loadAndSchedule() {
        val deviceUid = store.getDeviceUid()
        if (deviceUid.isBlank()) {
            // 理论上不会走到：调用方已先 ensureDeviceBound()。
            // 留个可操作的兜底，而不是静默失败。
            setStatus("尚未绑定设备，请点「刷新并注册」")
            return
        }
        setStatus("加载中…")
        lifecycleScope.launch {
            try {
                val alarms = api.listAlarms(deviceUid)
                var registered = 0
                val decorated = alarms.map { item ->
                    val ok = if (item.shouldSchedule) {
                        val r = AlarmScheduler.schedule(this@MainActivity, item)
                        if (r) registered++
                        r
                    } else {
                        AlarmScheduler.isScheduled(this@MainActivity, item.uid)
                    }
                    item.copy(scheduledLocally = ok)
                }
                render(decorated, registered)
            } catch (e: Exception) {
                Log.e(TAG, "加载闹钟失败", e)
                setStatus("加载失败：${e.message}")
            }
        }
    }

    private fun render(alarms: List<AlarmItem>, registered: Int) {
        container.removeAllViews()

        val canExact = AlarmScheduler.canScheduleExact(this)
        setStatus(buildString {
            append("共 ${alarms.size} 个闹钟，已注册 $registered 个")
            if (!canExact) {
                append("\n⚠ 未获得「精确闹钟」权限，闹钟可能延迟几分钟")
            }
        })

        if (!canExact) {
            // 引导用户去授权 —— 这是闹钟能准时响的前提
            val btn = Button(this).apply {
                text = "去开启精确闹钟权限"
                setOnClickListener { openExactAlarmSettings() }
            }
            container.addView(btn)
        }

        if (alarms.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无闹钟。家人在网页版给你设置闹钟后会出现在这里。"
            })
            return
        }

        alarms.forEach { a ->
            val tv = TextView(this).apply {
                text = buildString {
                    append(a.displayTime)
                    append("  ")
                    append(
                        when {
                            !a.shouldSchedule && a.confirmStatus == 0 -> "（待你确认）"
                            a.scheduledLocally -> "✅ 已在本机注册"
                            a.triggerAtMillis <= System.currentTimeMillis() -> "（已过时间）"
                            else -> "⚠ 未注册到系统"
                        },
                    )
                    if (!a.note.isNullOrBlank()) append("  · ${a.note}")
                    if (a.source == "remote") append("  [家人设置]")
                }
                textSize = 16f
                setPadding(24, 28, 24, 28)
            }
            container.addView(tv)

            // 待确认的闹钟：提供确认/拒绝入口（对应 PRD「App 内确认」）
            if (a.confirmStatus == 0) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Button(this).apply {
                    text = "同意"
                    setOnClickListener { respond(a, true) }
                })
                row.addView(Button(this).apply {
                    text = "拒绝"
                    setOnClickListener { respond(a, false) }
                })
                container.addView(row)
            }
        }
    }

    private fun respond(a: AlarmItem, approve: Boolean) {
        lifecycleScope.launch {
            try {
                api.confirmAlarm(a.uid, approve)
                Toast.makeText(
                    this@MainActivity,
                    if (approve) "已同意，闹钟已注册" else "已拒绝",
                    Toast.LENGTH_SHORT,
                ).show()
                loadAndSchedule()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF,
            )
        }
    }

    private fun startHeartbeat() {
        try {
            HeartbeatService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "心跳服务启动失败：${e.message}")
        }
    }
}
