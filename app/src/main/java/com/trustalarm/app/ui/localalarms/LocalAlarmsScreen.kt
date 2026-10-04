package com.trustalarm.app.ui.localalarms

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.trustalarm.app.ui.theme.TaTheme
import kotlinx.coroutines.delay

/**
 * 本地闹钟页 —— 对应 `web/js/pages/local_alarms.js`。
 *
 * ## 页面结构（与网页版逐块对应）
 *
 * ```
 * ┌ ⚠ 兜底提示条（有本地注册失败的闹钟时显示）
 * ├ 本地注册失败（N）卡片 [需兜底] —— 这些闹钟可能不会响
 * ├ 待生效闹钟（N）卡片 [设备名]
 * │   每行：⚠️/⏰ + HH:MM [本地注册失败｜即将响铃｜他人设置] + 倒计时·重试·备注
 * │         + 删除按钮
 * ├ 本机闹钟能力卡片（原生壳版：已获系统闹钟能力 / 精确闹钟权限提示）
 * └ 无设备时：空态 +「绑定这台手机」+「去家庭页绑定」
 * ```
 *
 * ## 与网页版的关键差异（都是原生壳带来的）
 *
 *   1. **响铃不由本页负责** —— 系统 AlarmManager 到点拉全屏响铃页。
 *      网页版要在这里用 Web Audio 兜底（浏览器没后台能力），原生侧不做。
 *   2. **能力卡片文案不同** —— 原生壳说的是「闹钟已注册到系统，App 关闭/息屏/
 *      重启后都会准时响」，而不是「需安装 App」。
 */
@Composable
fun LocalAlarmsScreen(
    vm: LocalAlarmsViewModel,
    onGoFamilies: () -> Unit,
    onBindThisPhone: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()
    val resultDialog by vm.resultDialog.collectAsState()
    val creating by vm.creating.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    // 没有设备时点「绑定这台手机」的信号 → 就地一键绑定本机
    // （用户要求：点绑定就能绑上，自动读当前设备，不用输名字/选家庭）
    val bindReq by vm.bindRequests.collectAsState()
    val consumedBind = remember { mutableStateOf(0) }
    LaunchedEffect(bindReq) {
        if (bindReq > consumedBind.value) {
            consumedBind.value = bindReq
            vm.bindThisPhoneNow()
        }
    }

    // 「给自己设闹钟」的时间选择层开关
    var sheetOpen by remember { mutableStateOf(false) }
    // 顶栏「＋ 设闹钟」信号
    val createReq by vm.createRequests.collectAsState()
    val consumedCreate = remember { mutableStateOf(0) }
    LaunchedEffect(createReq) {
        if (createReq > consumedCreate.value) {
            consumedCreate.value = createReq
            sheetOpen = true
        }
    }
    if (sheetOpen) {
        val (h, m) = com.trustalarm.app.ui.devicealarms.defaultCreateTime()
        com.trustalarm.app.ui.devicealarms.TimeSheet(
            title = "给自己设闹钟（${s.deviceName}）",
            initialHour = h,
            initialMinute = m,
            initialDateMs = null,
            initialNote = null,
            okText = "确定",
            onDismiss = { sheetOpen = false },
            onConfirm = { picked ->
                sheetOpen = false
                vm.createAlarmForSelf(
                    alarmTimeMs = picked.alarmTimeMs,
                    date = picked.date,
                    hour = picked.hour,
                    minute = picked.minute,
                    note = picked.note,
                )
            },
        )
    }

    // 新建结果说明弹窗（与设备闹钟页同一套文案）
    resultDialog?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.clearResultDialog() },
            title = { Text(notice.title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(notice.body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.clearResultDialog() }) {
                    Text("知道了", color = TaTheme.colors.primary)
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.alarms.isEmpty() && s.error == null && s.hasDevice ->
                Column(Modifier.fillMaxSize()) { Skeleton(4) }

            s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

            // 无设备：引导绑定本机
            !s.hasDevice -> NoDeviceGuide(
                onBindHere = { vm.requestBind() },   // 一键绑定本机（自动取机型/家庭）
                onGoFamilies = onGoFamilies,
                binding = s.binding,
            )

            else -> AlarmsBody(
                s = s,
                isScheduled = vm::isScheduledLocally,
                onDelete = { uid, label -> askDelete(dialogs, vm, uid, label) },
                onOpenExact = onOpenExactAlarmSettings,
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

/** 我名下没有设备时的引导（对应网页版 `mine.length === 0` 分支） */
@Composable
private fun NoDeviceGuide(
    onBindHere: () -> Unit,
    onGoFamilies: () -> Unit,
    binding: Boolean = false,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        EmptyState(
            icon = "📱",
            title = "你名下还没有设备",
            sub = "本地闹钟用于你自己的设备；先绑定这台手机",
        )
        Spacer(Modifier.height(4.dp))
        TaButton(
            text = if (binding) "正在绑定…" else "一键绑定这台手机",
            onClick = onBindHere,
            loading = binding,
        )
        Spacer(Modifier.height(10.dp))
        TaButton(
            text = "去家庭页绑定",
            onClick = onGoFamilies,
            style = TaButtonStyle.Ghost,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AlarmsBody(
    s: LocalAlarmsUiState,
    isScheduled: (String) -> Boolean,
    onDelete: (String, String) -> Unit,
    onOpenExact: () -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    // 每 15 秒刷新倒计时（对应网页版 ticker）。用 now 触发重算。
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    val failed = s.alarms.filter { it.needsFallback }
    val upcoming = s.alarms.filter { !it.needsFallback }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- ⚠ 兜底提示条 ----
        if (s.needsFallbackCount > 0) {
            TaCard {
                Text(
                    "⚠ 有 ${s.needsFallbackCount} 个闹钟在【${s.deviceName}】本地注册失败 —— " +
                        "这些闹钟可能不会响。本页打开时会为你兜底提醒。",
                    style = ty.bodySmall,
                    color = c.warn,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (s.alarms.isEmpty()) {
            EmptyState(
                icon = "⏰",
                title = "没有待响的闹钟",
                sub = "收到他人的闹钟后会显示在这里，本地注册失败时会由本页兜底提醒",
            )
        } else {
            // ---- 本地注册失败卡片 ----
            if (failed.isNotEmpty()) {
                TaCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "本地注册失败（${failed.size}）",
                                style = ty.titleMedium,
                                color = c.text,
                                modifier = Modifier.weight(1f),
                            )
                            TaTag(text = "需兜底", tone = TaTone.Warn)
                        }
                        Spacer(Modifier.height(8.dp))
                        failed.forEach { a ->
                            AlarmRow(a, now, isScheduled(a.uid), onDelete)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- 待生效闹钟卡片 ----
            TaCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "待生效闹钟（${upcoming.size}）",
                            style = ty.titleMedium,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                        )
                        TaTag(text = s.deviceName, tone = TaTone.Neutral)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (upcoming.isEmpty()) {
                        Text("暂无", style = ty.bodySmall, color = c.text3)
                    } else {
                        upcoming.forEach { a ->
                            AlarmRow(a, now, isScheduled(a.uid), onDelete)
                        }
                    }
                }
            }
        }

        // ---- 能力边界卡片 ----
        Spacer(Modifier.height(12.dp))
        CapabilityCard(canScheduleExact = s.canScheduleExact, onOpenExact = onOpenExact)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 一行闹钟（对应网页版 `row()`）。
 *
 * 标签规则与网页版一致：
 *   · needs_fallback → [本地注册失败]（warn）
 *   · 1 小时内到点 → [即将响铃]（ok）
 *   · source == remote → [他人设置]
 * 副行：倒计时（已过则「已过时间」）+ 已重试次数 + 备注
 */
@Composable
private fun AlarmRow(
    a: Alarm,
    now: Long,
    scheduled: Boolean,
    onDelete: (String, String) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    val left = a.alarmTime - now
    val past = left < 0
    val soon = left in 0..(60 * 60 * 1000L)

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (a.needsFallback) "⚠️" else "⏰", style = ty.titleMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.displayTime, style = ty.titleMedium, color = c.text)
                    Spacer(Modifier.width(6.dp))
                    if (a.needsFallback) TaTag(text = "本地注册失败", tone = TaTone.Warn)
                    else if (soon) TaTag(text = "即将响铃", tone = TaTone.Ok)
                    if (a.isRemote) {
                        Spacer(Modifier.width(6.dp))
                        TaTag(text = "他人设置", tone = TaTone.Neutral)
                    }
                }
                Spacer(Modifier.height(2.dp))
                val sub = buildString {
                    append(if (past) "已过时间" else countdownText(left))
                    if (a.retryCount > 0) append(" · 已重试 ${a.retryCount} 次")
                    if (!a.note.isNullOrBlank()) append(" · ${a.note}")
                }
                Text(sub, style = ty.labelSmall, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            TaButton(
                text = "删除",
                onClick = { onDelete(a.uid, a.displayTime) },
                style = TaButtonStyle.Ghost,
                fillWidth = false,
            )
        }
    }
}

/**
 * 能力边界卡片 —— 原生壳版本（对应网页版 `capabilityCard()` 的 `isNativeShell` 分支）。
 *
 * 文案逐字对应网页版：不能对用户说「需安装 App」—— 原生壳已经具备系统闹钟能力，
 * 那样说是误导。有精确闹钟权限缺失时才提示去开。
 */
@Composable
private fun CapabilityCard(canScheduleExact: Boolean, onOpenExact: () -> Unit) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    TaCard {
        Column {
            Text("本机闹钟能力", style = ty.titleMedium, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "✅ 已获得系统闹钟能力：闹钟已注册到手机系统，App 关闭、息屏、重启后都会准时响。",
                style = ty.bodySmall,
                color = c.ok,
            )
            if (!canScheduleExact) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ 尚未获得「精确闹钟」权限，闹钟可能延迟几分钟。",
                    style = ty.bodySmall,
                    color = c.warn,
                )
                Spacer(Modifier.height(8.dp))
                TaButton(text = "去开启精确闹钟权限", onClick = onOpenExact)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "· 闹钟由系统唤醒，不依赖本页面是否打开\n· 到点会全屏响铃并震动，点「关闭」才停",
                style = ty.labelSmall,
                color = c.text3,
            )
        }
    }
}

/**
 * 倒计时文案（对应网页版 `countdownText`）。
 *
 * 分档逐字一致：≥24h →「N 天后」；≥1h →「N 小时 M 分后」；≥1min →「M 分后」；否则「N 秒后」。
 */
private fun countdownText(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h >= 24 -> "${h / 24} 天后"
        h > 0 -> "$h 小时 $m 分后"
        m > 0 -> "$m 分后"
        else -> "$s 秒后"
    }
}

private fun askDelete(
    dialogs: DialogHost,
    vm: LocalAlarmsViewModel,
    uid: String,
    timeLabel: String,
) {
    dialogs.confirm(
        title = "删除闹钟",
        message = "确定删除 $timeLabel 的闹钟？\n删除后本机系统闹钟也会一并取消。",
        okText = "删除",
        danger = true,
    ) { yes -> if (yes) vm.deleteAlarm(uid) }
}
