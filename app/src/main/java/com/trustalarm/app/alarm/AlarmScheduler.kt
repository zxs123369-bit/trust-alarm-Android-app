package com.trustalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trustalarm.app.data.AlarmItem

/**
 * 系统闹钟调度器 —— 本 App 的核心能力。
 *
 * ## 为什么这是核心
 *   PWA 做不到「页面关闭后响铃」（浏览器无后台唤醒）。
 *   原生 App 用 AlarmManager 注册系统级闹钟 —— 即使 App 被杀、
 *   手机息屏，到点系统仍会拉起 AlarmReceiver 响铃。
 *   这就是用户说的「下发注册本地闹钟」。
 *
 * ## 精确度选择
 *   setExactAndAllowWhileIdle：精确 + 待机也触发（Doze 下允许一次）
 *   这是闹钟类应用的正确选择；setRepeating 会漂移且不精确。
 *   Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，未授权时降级并告知用户。
 *
 * ## 为什么要 allDay 循环重排
 *   闹钟是「一次性」的（系统不允许大量精确重复闹钟）。
 *   到点响过后，由 AlarmReceiver 重新排下一个，形成循环。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** 请求码基数：同一闹钟的 PendingIntent 必须稳定，否则取消不掉 */
    private fun requestCode(uid: String): Int = uid.hashCode()

    /**
     * 注册一个闹钟到系统。
     * @return true 表示已注册（精确或降级），false 表示权限不足未注册
     */
    fun schedule(context: Context, item: AlarmItem): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = item.triggerAtMillis

        // ★ 刚过期的闹钟仍要注册（用户实测「刚设的闹钟不响」的修复）。
        //
        // 原实现：`triggerAt <= now` 直接跳过。后果是 —— 若同步发生得比
        // 闹钟时间晚哪怕 1 秒（例如 20:45:36 设的 20:46:00 闹钟，
        // 同步在 20:46:02 到达），闹钟就被判「已过期」而**永不注册**，
        // 用户端表现为「设了但不响」。
        //
        // 现在：与 Models.shouldSchedule 一致地留 90 秒宽限。
        //   · 刚过期的：用 setAlarmClock 注册，AlarmManager 对过去时间
        //     会**立即投递**（相当于「迟到几秒响」）—— 远好于静默丢弃。
        //   · 真正陈旧的（超出宽限）：仍然跳过，避免开机补响一堆旧闹钟。
        val now = System.currentTimeMillis()
        if (triggerAt <= now - AlarmItem.GRACE_MS) {
            Log.w(TAG, "闹钟时间已过（超出宽限），跳过：${item.displayTime}")
            return false
        }
        // 已过期但在宽限内 → 用当前时间立即注册（避免 AlarmManager
        // 收到过去时间时的行为差异，统一按「现在」触发）
        val effectiveTrigger = if (triggerAt <= now) now + 500 else triggerAt

        val pi = pendingIntent(context, item)

        return try {
            if (canScheduleExact(am)) {
                // ★ 用 setAlarmClock 而不是 setExactAndAllowWhileIdle
                //
                // 为什么必须换（用户实测「亮屏会响、息屏不响」的根因）：
                //
                //   setExactAndAllowWhileIdle 虽然「允许在待机下触发」，但它
                //   **不保证准时** —— Doze 下系统对每个 App 有配额限制
                //   （约每 9 分钟一次），到点若不满足配额就**推迟**。
                //   息屏一段时间后设备进入 Doze，闹钟于是被推迟甚至长时间
                //   不触发；亮屏时不在 Doze，所以准时响 —— 现象完全吻合。
                //
                //   setAlarmClock 是**系统闹钟专用 API**：
                //     · Doze 下必定准时唤醒（不受配额限制）
                //     · 会点亮屏幕并被系统视为「用户可见的闹钟」
                //     · 系统状态栏显示闹钟图标（用户预期的闹钟语义）
                //     · 提供 showIntent 供用户点图标回到 App
                //   这是闹钟类 App 唯一正确的选择，系统时钟/闹钟应用都用它。
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(effectiveTrigger, showIntent(context, item)),
                    pi,
                )
                Log.i(TAG, "已注册系统级闹钟(AlarmClock) ${item.displayTime}（${item.uid.take(8)}）")
            } else {
                // 未授权精确闹钟：降级。setAlarmClock 在无权限时会抛
                // SecurityException，所以这里仍用允许待机的版本保底。
                // 不静默失败 —— 上层会提示用户去授权。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pi)
                Log.w(TAG, "精确闹钟未授权，已降级注册 ${item.displayTime}（息屏可能不准）")
            }
            markScheduled(context, item.uid, effectiveTrigger)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "注册闹钟被拒（缺精确闹钟权限）", e)
            false
        }
    }

    /** 取消一个闹钟 */
    fun cancel(context: Context, uid: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = cancelPendingIntent(context, uid)
        am.cancel(pi)
        pi.cancel()
        unmarkScheduled(context, uid)
        Log.i(TAG, "已取消闹钟 ${uid.take(8)}")
    }

    /** 是否具备精确闹钟能力（Android 12+ 需用户授权） */
    fun canScheduleExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExact(am)
    }

    // ---------- PendingIntent ----------

    private fun ringIntent(context: Context, item: AlarmItem): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_RING
            putExtra(AlarmReceiver.EXTRA_ALARM_UID, item.uid)
            putExtra(AlarmReceiver.EXTRA_DISPLAY_TIME, item.displayTime)
            putExtra(AlarmReceiver.EXTRA_NOTE, item.note ?: "")
            putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, item.triggerAtMillis)
        }

    private fun pendingIntent(context: Context, item: AlarmItem): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(item.uid),
            ringIntent(context, item),
            // FLAG_UPDATE_CURRENT：同一 uid 重复注册时更新 extras
            // FLAG_IMMUTABLE：Android 12+ 必须显式指定可变性
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 「保活重排」的 PendingIntent —— 一个固定 requestCode 的广播。
     *
     * 用途：给 AlarmManager 挂一个**周期性**的轻量闹钟，到点只做一件事：
     * 把后端上仍在未来时刻的闹钟**重新注册一遍**。
     *
     * 为什么需要它（用户实测「App 清掉后台后不响」的根因）：
     *
     *   ① 一次性闹钟会「用完即走」——响过之后系统里就没有了。
     *      若某次注册因权限/存储异常没成功，之后再也没有重试机会。
     *   ② 更关键：国产 ROM 的「清理后台」会把应用置为 **stopped 状态**。
     *      Android 对 stopped 应用**不投递它自己的广播** ——
     *      已注册的 PendingIntent 会被静默吞掉，用户端表现为「到点不响」。
     *      而周期性提醒能在这个状态解除后（用户再次打开 App、系统拉起服务）
     *      自动把闹钟补回来，不必等用户手动进「本地闹钟」页刷新。
     */
    fun rearmPendingIntent(context: Context): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_REARM
        }
        return PendingIntent.getBroadcast(
            context,
            REARM_REQUEST_CODE,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 挂一个周期性「重排」闹钟（幂等，重复调用只是覆盖同一 PendingIntent）。
     *
     * 周期 15 分钟：足够应对「被清理后自动恢复」，又不会明显耗电
     * （每次只是读一次本地记录 + 比对，必要时才发网络/注册）。
     * 用 setInexactRepeating 而非精确重复 —— 这个任务对时刻不敏感，
     * 交给系统合并唤醒更省电；真正的闹钟仍是 setAlarmClock（精确）。
     */
    fun scheduleRearm(context: Context, intervalMs: Long = 15 * 60 * 1000L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = System.currentTimeMillis() + intervalMs
        try {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                trigger,
                intervalMs,
                rearmPendingIntent(context),
            )
            Log.i(TAG, "已挂周期性重排（每 ${intervalMs / 60000} 分钟）")
        } catch (e: Exception) {
            Log.w(TAG, "挂周期性重排失败：${e.message}")
        }
    }

    /** 取消周期性重排（登出时调用） */
    fun cancelRearm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.cancel(rearmPendingIntent(context))
        } catch (_: Exception) {
        }
    }

    /** 重排用的固定 requestCode（与各闹钟的 uid.hashCode() 区分开） */
    private const val REARM_REQUEST_CODE = 0x52454152 // "REAR"

    /**
     * setAlarmClock 需要的「展示意图」：用户点系统状态栏的闹钟图标时打开这里。
     *
     * 为什么必须有：AlarmClockInfo 的构造要求传入 showIntent，
     * 系统闹钟界面也依赖它把用户带回能管理该闹钟的地方。
     * 这里指向响铃界面（用户看到的是「这个闹钟」，语义最贴切）；
     * 用 requestCode 偏移避免与响铃 PendingIntent 冲突。
     */
    private fun showIntent(context: Context, item: AlarmItem): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(item.uid) xor 0x5A5A,
            Intent(context, AlarmRingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmReceiver.EXTRA_ALARM_UID, item.uid)
                putExtra(AlarmReceiver.EXTRA_DISPLAY_TIME, item.displayTime)
                putExtra(AlarmReceiver.EXTRA_NOTE, item.note ?: "")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelPendingIntent(context: Context, uid: String): PendingIntent {
        // 取消时 Intent 内容可以简化 —— PendingIntent 的匹配只看
        // requestCode + Intent 的「关键字段」（action/component/data）
        val i = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_RING
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(uid),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------- 已注册记录（供开机后重排、界面展示） ----------

    private const val PREFS = "scheduled_alarms"

    /** 已触发过的闹钟 uid（避免宽限期内被重复注册 → 重复响铃） */
    private const val PREFS_FIRED = "fired_alarms"

    fun markScheduled(context: Context, uid: String, triggerAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(uid, triggerAt).apply()
    }

    fun unmarkScheduled(context: Context, uid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(uid).apply()
    }

    /**
     * 标记「该闹钟已经响过了」。
     *
     * ## 为什么需要单独记（修复「同一闹钟响两次」）
     *
     *   AlarmReceiver 到点后会调 unmarkScheduled 清掉注册记录 ——
     *   原意是「响过了就别再排」。但这与「90 秒宽限期」冲突：
     *   记录被清掉后，下一轮同步（10 秒一次）看到这个闹钟仍在
     *   宽限期内、且本地无记录，就**又注册了一遍** → 10 秒后再响一次。
     *   实测日志：20:54:44 与 20:54:54 各响一次。
     *
     *   所以「已触发」必须单独持久化，且同步时跳过它。
     */
    fun markFired(context: Context, uid: String) {
        if (uid.isBlank()) return
        context.getSharedPreferences(PREFS_FIRED, Context.MODE_PRIVATE)
            .edit().putLong(uid, System.currentTimeMillis()).apply()
    }

    /** 该闹钟是否已经触发过（触发过就不再注册） */
    fun isFired(context: Context, uid: String): Boolean =
        context.getSharedPreferences(PREFS_FIRED, Context.MODE_PRIVATE)
            .contains(uid)

    /** 清理过旧的触发记录，避免这个表无限增长 */
    fun pruneFired(context: Context, keepDays: Long = 7) {
        val cutoff = System.currentTimeMillis() - keepDays * 24 * 3600 * 1000
        val sp = context.getSharedPreferences(PREFS_FIRED, Context.MODE_PRIVATE)
        val stale = sp.all.filterValues { (it as? Long ?: 0L) < cutoff }.keys
        if (stale.isNotEmpty()) {
            val e = sp.edit()
            stale.forEach { e.remove(it) }
            e.apply()
        }
    }

    /** 仍待触发（未来时间）的已注册闹钟 uid → 触发时间 */
    fun scheduledMap(context: Context): Map<String, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all.filterValues { (it as? Long ?: 0L) > System.currentTimeMillis() }
            .mapValues { it.value as Long }

    /** 某闹钟是否已注册到系统 */
    fun isScheduled(context: Context, uid: String): Boolean =
        scheduledMap(context).containsKey(uid)
}
