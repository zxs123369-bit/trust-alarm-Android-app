package com.trustalarm.app.ui.devicealarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.theme.TaTheme
import java.util.Calendar

/**
 * 时间选择器 —— 对应 `web/js/pages/device_alarms.js :: timeSheet()`。
 *
 * ## 网页版的设计要点（原生侧必须保留）
 *
 * 网页版注释列了四条（对齐用户反馈）：
 *   1. **加日期选择** —— 原来只有时分，无法表达「明天/下周三 07:30」
 *   2. **今天 / 明天快捷** —— 最高频的两种意图，一键即可
 *   3. **时间用系统控件** —— 手机上直接调时间轮盘，比两个数字输入框快
 *   4. **实时预览「还有多久响」** —— 让用户确认选对了没
 *
 * 原生侧用 Compose 的 `DatePicker` / `TimePicker` 实现 3，用两个快捷芯片实现 2，
 * 预览文案与网页版逐字对齐（含「⚠ 这个时间已经过了」的拦截）。
 *
 * ## 选了过去时间不静默改期
 *
 * 网页版明确：**不静默改成别的日子，让用户自己决定**（阻止关闭弹层 + 提示）。
 * 这里保持同样行为。
 */

/** 用户选定的时间结果（对应网页版 `timeSheet` 的 resolve 值） */
data class PickedTime(
    val hour: Int,
    val minute: Int,
    /** yyyy-MM-dd */
    val date: String,
    val alarmTimeMs: Long,
    val note: String?,
)

/** 日期 → yyyy-MM-dd（本地时区；不能用 toISOString，会差一天） */
private fun dateStr(cal: Calendar): String {
    val p = { n: Int -> if (n < 10) "0$n" else n.toString() }
    return "${cal.get(Calendar.YEAR)}-${p(cal.get(Calendar.MONTH) + 1)}-${p(cal.get(Calendar.DAY_OF_MONTH))}"
}

private fun todayCal(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

private fun tomorrowCal(): Calendar = todayCal().apply { add(Calendar.DAY_OF_MONTH, 1) }

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

/**
 * 时间选择弹层。
 *
 * @param initialHour/initialMinute 初始时分（新建时网页版默认「现在 + 10 分钟」）
 * @param initialDateMs  初始日期时间戳（修改时传原闹钟时间，让编辑停在原日期）
 * @param initialNote    初始备注
 * @param okText         确定按钮文案（新建「确定」/ 修改「保存」）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSheet(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    initialDateMs: Long?,
    initialNote: String?,
    okText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (PickedTime) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    // 日期（存 Calendar，便于比较与计算）
    val startCal = remember {
        if (initialDateMs != null) {
            Calendar.getInstance().apply { timeInMillis = initialDateMs }
        } else {
            todayCal()
        }
    }
    var selCal by remember { mutableStateOf(startCal) }
    var hour by remember { mutableStateOf(initialHour) }
    var minute by remember { mutableStateOf(initialMinute) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 计算选定时分对应的绝对时间戳
    fun absMs(): Long = Calendar.getInstance().apply {
        timeInMillis = selCal.timeInMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val abs = absMs()
    val diff = abs - System.currentTimeMillis()
    val expired = diff <= 0

    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = selCal.timeInMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let {
                        // DatePicker 返回 UTC 午夜，转成本地日期的 Calendar
                        val g = Calendar.getInstance().apply { timeInMillis = it }
                        selCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, g.get(Calendar.YEAR))
                            set(Calendar.MONTH, g.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, g.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        }
                    }
                    showDatePicker = false
                }) { Text("确定", color = c.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dpState)
        }
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间", style = ty.titleMedium) },
            text = { TimePicker(state = tpState) },
            confirmButton = {
                TextButton(onClick = {
                    hour = tpState.hour
                    minute = tpState.minute
                    showTimePicker = false
                }) { Text("确定", color = c.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = ty.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // ---- 日期：今天 / 明天 快捷 + 具体日期 ----
                Text("日期", style = ty.labelSmall, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaButton(
                        text = "今天",
                        onClick = {
                            selCal = todayCal().apply {
                                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                            }
                        },
                        style = if (sameDay(selCal, todayCal())) TaButtonStyle.Primary else TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                    TaButton(
                        text = "明天",
                        onClick = {
                            selCal = tomorrowCal().apply {
                                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                            }
                        },
                        style = if (sameDay(selCal, tomorrowCal())) TaButtonStyle.Primary else TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                    TaButton(
                        text = dateStr(selCal).substring(5),   // 只显示 MM-dd，省横向空间
                        onClick = { showDatePicker = true },
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- 时间：系统 TimePicker ----
                Text("时间", style = ty.labelSmall, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hh = if (hour < 10) "0$hour" else hour.toString()
                    val mm = if (minute < 10) "0$minute" else minute.toString()
                    Text(
                        "$hh:$mm",
                        style = ty.headlineSmall,
                        color = c.text,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    TaButton(
                        text = "改时间",
                        onClick = { showTimePicker = true },
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- 备注 ----
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = if (it.length <= 30) it else it.take(30) },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("如：起床") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                // ---- 预览（对应网页版 refresh()）----
                if (expired) {
                    Text("⚠ 这个时间已经过了，请重新选择", style = ty.bodySmall, color = c.warn)
                } else {
                    val mins = (diff / 60000).toInt()
                    val rel = when {
                        mins < 60 -> "$mins 分钟后"
                        mins < 60 * 24 -> "${mins / 60} 小时后"
                        else -> "${mins / 1440} 天后"
                    }
                    val label = when {
                        sameDay(selCal, todayCal()) -> "今天"
                        sameDay(selCal, tomorrowCal()) -> "明天"
                        else -> "${selCal.get(Calendar.MONTH) + 1}月${selCal.get(Calendar.DAY_OF_MONTH)}日"
                    }
                    val hh = if (hour < 10) "0$hour" else hour.toString()
                    val mm = if (minute < 10) "0$minute" else minute.toString()
                    Text(
                        "将在 $label $hh:$mm 响起　（$rel）",
                        style = ty.bodySmall,
                        color = c.text3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 选了过去时间：不关闭弹层，让用户自己改（与网页版一致）
                    if (!expired) {
                        onConfirm(
                            PickedTime(
                                hour = hour,
                                minute = minute,
                                date = dateStr(selCal),
                                alarmTimeMs = abs,
                                note = note.trim().takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                },
            ) {
                Text(okText, color = if (expired) c.text3 else c.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 「现在 + N 分钟」的初始时分（新建默认值，对应网页版 `Date.now()+10min`） */
fun defaultCreateTime(plusMinutes: Int = 10): Pair<Int, Int> {
    val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, plusMinutes) }
    return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
}
