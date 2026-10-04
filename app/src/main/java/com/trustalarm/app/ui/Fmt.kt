package com.trustalarm.app.ui

import java.util.Calendar

/**
 * 时间与文本格式化 —— 逐行复刻 `web/js/ui.js` 的对应函数。
 *
 * ## 为什么要一比一复刻而不是用 `android.text.format.DateUtils`
 *
 * 网页版的时间文案是**逐字确定**的（「刚刚 / N 分钟前 / N 小时前 / N 天前」，
 * 超过 7 天退回 `MM-dd HH:mm`）。`DateUtils.getRelativeTimeSpanString` 的
 * 文案是系统语言相关的（「3 分钟前」vs「3 min ago」vs「3 分钟」），
 * 两端会出现肉眼可见的不一致。本项目的验收标准是「与网页版一致」，
 * 所以宁可自己拼字符串。
 *
 * 后端时间戳统一是**毫秒**（与 JS `Date.now()` 同口径），见 `Model.kt` 里
 * 各字段的 `Long` 类型。这里不做秒/毫秒的猜测 —— 猜测会在某些字段上出错，
 * 而错误的时间显示是用户能直接看到的。
 */

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

/** 毫秒时间戳 → `MM-dd HH:mm`（对应 `ui.js :: fmtTime`）。0 或非法值返回「—」。 */
fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "—"
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val mo = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val mi = cal.get(Calendar.MINUTE)
    return "${pad2(mo)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}"
}

/**
 * 毫秒时间戳 → 相对时间（对应 `ui.js :: fmtRelative`）。
 *
 * 分档与网页版完全一致：
 *   · 未来时间 → 退回绝对时间 [fmtTime]
 *   · < 1 分钟 → 「刚刚」
 *   · < 60 分钟 → 「N 分钟前」
 *   · < 24 小时 → 「N 小时前」
 *   · < 7 天 → 「N 天前」
 *   · 其余 → 绝对时间 [fmtTime]
 */
fun fmtRelative(ms: Long): String {
    if (ms <= 0L) return "—"
    val diff = System.currentTimeMillis() - ms
    if (diff < 0) return fmtTime(ms)
    val min = diff / 60_000L
    if (min < 1) return "刚刚"
    if (min < 60) return "$min 分钟前"
    val hr = min / 60
    if (hr < 24) return "$hr 小时前"
    val day = hr / 24
    if (day < 7) return "$day 天前"
    return fmtTime(ms)
}

/**
 * `HH:mm` → 今天的毫秒时间戳（已过则顺延到明天）。
 *
 * 与网页版 `ui.js :: hhmmToMsToday` 和后端 `_resolve_time_ms` **同一套逻辑**。
 * 三处必须一致，否则「前端显示的下次响铃时间」与「后端实际排的时间」会差一天。
 *
 * @return 解析失败返回 null（网页版同样返回 null）
 */
fun hhmmToMsToday(hhmm: String): Long? {
    val parts = hhmm.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= System.currentTimeMillis()) {
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return cal.timeInMillis
}

/** 设备平台 → 展示名（对应 `ui.js :: platformName`） */
fun platformName(p: String?): String = when (p) {
    "android" -> "Android"
    "ios" -> "iPhone / iPad"
    null, "" -> "未知"
    else -> p
}

/** 在线状态（对应 `ui.js :: onlineText`）。[status]：0 离线 / 1 在线 / null 无权查看 */
fun onlineText(status: Int?): Pair<String, Boolean> = when (status) {
    1 -> "在线" to true
    0 -> "离线" to false
    else -> "未知" to false
}
