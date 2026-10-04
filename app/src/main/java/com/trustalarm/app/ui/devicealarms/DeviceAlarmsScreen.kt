package com.trustalarm.app.ui.devicealarms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustalarm.app.data.Alarm
import com.trustalarm.app.ui.components.DialogHost
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.fmtTime
import com.trustalarm.app.ui.theme.TaTheme
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 设备闹钟列表页 —— 对应 `web/js/pages/device_alarms.js`。
 *
 * ## 页面结构（与网页版逐块对应）
 *
 * ```
 * ┌ 页首：共 N 个闹钟 ｜ 我的权限：xxx
 * ├ 需要你确认（N）[这些人想为你的设备设闹钟]  ← 分组标题 + 卡片
 * ├ 将要响铃（N）[按时间先后]
 * ├ 已过去（N）
 * └ 关于确认的说明
 * ```
 *
 * 每条闹钟是一张独立卡片（网页版如此，不是列表行）：
 * ```
 * ┌ 30px 大字时间        标签组（待确认/已确认 + 他人设置/本机 + 已过期）
 * ├ 备注（无则「（无备注）」）
 * ├ 将于/已于 MM-dd HH:mm [今天]
 * └ [同意 拒绝] 或 [修改 删除]
 * ```
 *
 * ## 新建 / 修改走同一个时间选择弹层
 *
 * 对应网页版 `openCreateSheet` / `openEditSheet` 都调 `timeSheet()`。
 * 差别只在初始值与按钮文案（新建「确定」/ 修改「保存」）。
 */
@Composable
fun DeviceAlarmsScreen(
    vm: DeviceAlarmsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val resultDialog by vm.resultDialog.collectAsState()
    val dialogs = rememberDialogHost()

    // 时间选择弹层的状态：null=不显示；否则是「新建」或「修改某条」
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    // 顶栏「新建」信号
    val createReq by vm.createRequests.collectAsState()
    val consumed = remember { mutableStateOf(0) }
    LaunchedEffect(createReq) {
        if (createReq > consumed.value) {
            consumed.value = createReq
            sheet = SheetTarget.Create
        }
    }

    // 时间选择弹层
    sheet?.let { target ->
        val (h, m) = when (target) {
            is SheetTarget.Create -> defaultCreateTime()
            is SheetTarget.Edit -> target.hour to target.minute
        }
        TimeSheet(
            title = if (target is SheetTarget.Create) {
                "为「${s.deviceName}」设置闹钟"
            } else {
                "修改闹钟"
            },
            initialHour = h,
            initialMinute = m,
            initialDateMs = (target as? SheetTarget.Edit)?.alarmTimeMs,
            initialNote = (target as? SheetTarget.Edit)?.note,
            okText = if (target is SheetTarget.Create) "确定" else "保存",
            onDismiss = { sheet = null },
            onConfirm = { picked ->
                sheet = null
                if (target is SheetTarget.Create) {
                    vm.createAlarm(
                        alarmTimeMs = picked.alarmTimeMs,
                        date = picked.date,
                        hour = picked.hour,
                        minute = picked.minute,
                        note = picked.note,
                    )
                } else if (target is SheetTarget.Edit) {
                    vm.updateAlarm(
                        alarmUid = target.uid,
                        alarmTimeMs = picked.alarmTimeMs,
                        date = picked.date,
                        hour = picked.hour,
                        minute = picked.minute,
                        note = picked.note,
                    )
                }
            },
        )
    }

    // 新建结果说明弹窗（PRD §4.1 三种情况）
    resultDialog?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.clearResultDialog() },
            title = { Text(notice.title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(notice.body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { vm.clearResultDialog() }) {
                    Text("知道了", color = TaTheme.colors.primary)
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.total == 0 && s.error == null ->
                Column(Modifier.fillMaxSize()) { Skeleton(3) }
            s.error != null -> ErrorState(
                message = s.error!!,
                onRetry = { vm.load() },
                onBack = onBack,
                backText = "返回",
            )
            else -> DeviceAlarmsBody(
                s = s,
                onCreate = { sheet = SheetTarget.Create },
                onConfirm = { uid, approve ->
                    askConfirm(dialogs, vm, uid, approve)
                },
                onEdit = { a ->
                    val hh = a.displayTime.substringBefore(":").toIntOrNull() ?: 7
                    val mm = a.displayTime.substringAfter(":", "0").toIntOrNull() ?: 0
                    sheet = SheetTarget.Edit(a.uid, hh, mm, a.note, a.alarmTime)
                },
                onDelete = { uid -> askDelete(dialogs, vm, uid) },
            )
        }

        DialogHostView(host = dialogs)

        if (!toast.isNullOrBlank()) {
            LaunchedEffect(toast) {
                delay(2400)
                vm.clearToast()
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TaCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        toast!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TaTheme.colors.text,
                    )
                }
            }
        }
    }
}

/** 时间选择弹层的目标（新建 or 修改某条） */
private sealed interface SheetTarget {
    data object Create : SheetTarget
    data class Edit(
        val uid: String,
        val hour: Int,
        val minute: Int,
        val note: String?,
        val alarmTimeMs: Long,
    ) : SheetTarget
}

@Composable
private fun DeviceAlarmsBody(
    s: DeviceAlarmsUiState,
    onCreate: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    onEdit: (Alarm) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (s.total == 0) {
            EmptyState(
                icon = "⏰",
                title = "还没有闹钟",
                sub = "点右上角「新建」为该设备设置闹钟",
            )
            Spacer(Modifier.height(4.dp))
            TaButton(text = "新建闹钟", onClick = onCreate)
            Spacer(Modifier.height(12.dp))
            Text(
                "当前身份：${vsText(s.viewSource)}\n他人下发的闹钟需设备主人确认后才会生效。",
                style = ty.labelSmall,
                color = c.text3,
            )
            Spacer(Modifier.height(16.dp))
            return
        }

        // ---- 页首：总数 + 权限 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("共 ${s.total} 个闹钟", style = ty.labelSmall, color = c.text3)
            Text("我的权限：${vsText(s.viewSource)}", style = ty.labelSmall, color = c.text3)
        }

        // ---- 三个分组 ----
        Section("需要你确认", s.needAction, "这些人想为你的设备设闹钟", onConfirm, onEdit, onDelete)
        Section("将要响铃", s.upcoming, "按时间先后", onConfirm, onEdit, onDelete)
        Section("已过去", s.past, "", onConfirm, onEdit, onDelete)

        // ---- 说明 ----
        Spacer(Modifier.height(12.dp))
        Text(
            "关于确认：他人为你设置的闹钟，需你确认后才会在本机生效；" +
                "信任成员在你开启信任开关后可免确认（iPhone 仍需在 App 内确认一次）。",
            style = ty.labelSmall,
            color = c.text3,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** 一个分组（标题 + 若干闹钟卡片）。空分组不渲染 —— 与网页版 `section()` 一致。 */
@Composable
private fun Section(
    title: String,
    arr: List<Alarm>,
    hint: String,
    onConfirm: (String, Boolean) -> Unit,
    onEdit: (Alarm) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (arr.isEmpty()) return
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title（${arr.size}）", style = ty.titleMedium, color = c.text,
            modifier = Modifier.weight(1f))
        if (hint.isNotBlank()) {
            Text(hint, style = ty.labelSmall, color = c.text3)
        }
    }
    arr.forEach { a ->
        AlarmCard(a, onConfirm, onEdit, onDelete)
        Spacer(Modifier.height(10.dp))
    }
}

/** 单条闹钟卡片（对应网页版 `alarmCard()`） */
@Composable
private fun AlarmCard(
    a: Alarm,
    onConfirm: (String, Boolean) -> Unit,
    onEdit: (Alarm) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    val pending = a.confirmStatus == 0
    val isFuture = a.alarmTime > System.currentTimeMillis()

    TaCard {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    // 30px 大字时间（网页版 `font-size:30px`）
                    Text(
                        a.displayTime.ifBlank { "--:--" },
                        style = ty.headlineSmall.copy(
                            fontSize = 30.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        ),
                        color = c.text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.note?.takeIf { it.isNotBlank() } ?: "（无备注）",
                        style = ty.bodySmall,
                        color = c.text2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (pending) TaTag("待确认", TaTone.Warn) else TaTag("已确认", TaTone.Ok)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (a.isRemote) TaTag("他人设置", TaTone.Info) else TaTag("本机", TaTone.Neutral)
                    }
                    if (!isFuture) {
                        Spacer(Modifier.height(4.dp))
                        TaTag("已过期", TaTone.Neutral)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isFuture) "将于 ${fmtTime(a.alarmTime)}" else "已于 ${fmtTime(a.alarmTime)}",
                    style = ty.labelSmall,
                    color = c.text3,
                )
                if (isFuture && isToday(a.alarmTime)) {
                    Spacer(Modifier.width(6.dp))
                    TaTag("今天", TaTone.Ok)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending) {
                    TaButton(
                        text = "同意",
                        onClick = { onConfirm(a.uid, true) },
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    TaButton(
                        text = "拒绝",
                        onClick = { onConfirm(a.uid, false) },
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TaButton(
                        text = "修改",
                        onClick = { onEdit(a) },
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    TaButton(
                        text = "删除",
                        onClick = { onDelete(a.uid) },
                        style = TaButtonStyle.Danger,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 是否落在今天（对应网页版 `sameDay`） */
private fun isToday(ms: Long): Boolean {
    val d = Calendar.getInstance().apply { timeInMillis = ms }
    val n = Calendar.getInstance()
    return d.get(Calendar.YEAR) == n.get(Calendar.YEAR) &&
        d.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
}

/** 权限来源文案（对应网页版 `vsText`），逐字一致 */
internal fun vsText(vs: String): String = when (vs) {
    "owner" -> "设备归属人"
    "family_owner" -> "家庭群主"
    "trust_member" -> "信任成员"
    "granted" -> "已授权查看"
    "creator" -> "闹钟创建人"
    "none" -> "无查看权限"
    else -> vs.ifBlank { "—" }
}

private fun askConfirm(dialogs: DialogHost, vm: DeviceAlarmsViewModel, uid: String, approve: Boolean) {
    dialogs.confirm(
        title = if (approve) "同意该闹钟" else "拒绝该闹钟",
        message = if (approve) "同意后该闹钟将在设备上生效。" else "拒绝后该闹钟不会生效。",
        okText = if (approve) "同意" else "拒绝",
        danger = !approve,
    ) { yes -> if (yes) vm.confirmAlarm(uid, approve) }
}

private fun askDelete(dialogs: DialogHost, vm: DeviceAlarmsViewModel, uid: String) {
    dialogs.confirm(
        title = "删除闹钟",
        message = "确定删除该闹钟？删除后不再响起。",
        okText = "删除",
        danger = true,
    ) { yes -> if (yes) vm.deleteAlarm(uid) }
}
