package com.trustalarm.app.data

import org.json.JSONObject

/**
 * 一条待注册的闹钟（后端 /devices/{uid}/alarms 的 list 元素 + 本机注册状态）。
 *
 * 字段来自后端真实契约（实测，不是猜的）：
 *   uid, device_uid, display_time, alarm_time(ms), note,
 *   source(app_local/remote), confirm_status, status, creator_is_me
 */
data class AlarmItem(
    val uid: String,
    val deviceUid: String,
    val displayTime: String,      // "07:30"
    val triggerAtMillis: Long,    // 触发时间戳（alarm_time）
    val note: String?,
    val source: String,           // app_local / remote
    val confirmStatus: Int,       // 1=已确认（可注册），0=待确认
    val creatorIsMe: Boolean,
    /** 本机是否已注册到系统闹钟 */
    val scheduledLocally: Boolean = false,
) {
    /**
     * 是否应当注册到系统闹钟：已确认 + 时间未过（含宽限期）。
     *
     * ## 为什么要留「宽限期」（用户实测「刚设的闹钟不响」的根因之一）
     *
     *   实测场景：20:45:36 设了 20:46:00 的闹钟（提前 23 秒）。
     *   若同步发生在那之后（哪怕只晚 2 秒），原判定 `> now` 会认为
     *   「已过期」→ 直接跳过 → **闹钟永不注册、到点不响**。
     *   而用户刚设完闹钟时，晚几秒响是完全可以接受的；
     *   直接丢掉才是真正的问题。
     *
     *   所以宽限 [GRACE_MS]：刚过期不久（默认 90 秒）仍注册，
     *   由 AlarmManager 立刻触发（它内部对过去时间会立即投递）。
     *   这样「设 1 分钟后的闹钟」在任何同步时点都不会被漏掉。
     *
     *   真正的历史旧闹钟（过期远超宽限期）仍然跳过，避免开机时把
     *   一堆陈年闹钟全部补响。
     */
    val shouldSchedule: Boolean
        get() = confirmStatus == 1 &&
            triggerAtMillis > System.currentTimeMillis() - GRACE_MS

    companion object {
        /**
         * 过期宽限期（毫秒）。
         *
         * 90 秒的选择依据：
         *   · 足够覆盖「同步周期 10 秒 + 网络往返」的最坏延迟
         *   · 又远小于「用户会认为是陈年旧闹钟」的量级（分钟以上）
         */
        const val GRACE_MS = 90_000L

        fun fromJson(o: JSONObject, scheduled: Boolean = false): AlarmItem = AlarmItem(
            uid = o.optString("uid"),
            deviceUid = o.optString("device_uid"),
            displayTime = o.optString("display_time"),
            triggerAtMillis = o.optLong("alarm_time"),
            note = o.optString("note").takeIf { it.isNotBlank() && it != "null" },
            source = o.optString("source", "remote"),
            confirmStatus = o.optInt("confirm_status", 0),
            creatorIsMe = o.optBoolean("creator_is_me", false),
            scheduledLocally = scheduled,
        )
    }
}

/** 登录后的会话信息 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userUid: String,
    val nickname: String,
    val phone: String,
)

/** 设备信息（本机绑定的那台） */
data class DeviceInfo(
    val uid: String,
    val deviceName: String,
    val platform: String,
    val onlineStatus: Int,
    val autoAlarmTrustSwitch: Int,
) {
    companion object {
        fun fromJson(o: JSONObject): DeviceInfo = DeviceInfo(
            uid = o.optString("uid"),
            deviceName = o.optString("device_name"),
            platform = o.optString("platform"),
            onlineStatus = o.optInt("online_status", 0),
            autoAlarmTrustSwitch = o.optInt("auto_alarm_trust_switch", 0),
        )
    }
}
