package com.trustalarm.app.ui.device

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.onlineText
import com.trustalarm.app.ui.platformName
import com.trustalarm.app.ui.theme.TaTheme
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState

/**
 * 设备详情页 —— 对应 `web/js/pages/device.js`。
 *
 * 页面结构（1:1 平移）：
 *   ① 设备信息卡：图标 + 名称 + 平台·在线状态 + 型号/归属人/最近心跳/信任自动下发/我的权限
 *   ② 功能卡：闹钟 / 操作日志 /（归属人）信任名单 · 查看授权 /（非归属人）申请查看
 *   ③ 设备管理（仅归属人）：重命名 / 解绑
 *   ④ 设备 ID
 *
 * ## 展示控制只读后端返回值
 * `isOwner = device.owner_is_me`，非归属人显示不同文案 —— 与网页版一致，
 * 不在客户端重算权限（规则只在后端 permissions.py）。
 *
 * ## 子页入口
 * 闹钟 / 日志 / 信任 / 授权 四行分别导航到 `/device/{uid}/alarms|logs|trust|grants`。
 * 四个子页均已迁移完成（不再是占位）。
 */
@Composable
fun DeviceScreen(
    vm: DeviceViewModel,
    onBack: () -> Unit,
    onOpenAlarms: (String) -> Unit,
    onOpenLogs: (String) -> Unit,
    onOpenTrust: (String) -> Unit,
    onOpenGrants: (String) -> Unit,
    onUnbound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val unbound by vm.unbound.collectAsState()
    val dialogs = rememberDialogHost()

    LaunchedEffect(Unit) { vm.load() }

    // 解绑成功 → 回退（消费后置空，避免重组重复触发）
    LaunchedEffect(unbound) {
        if (unbound) {
            vm.clearUnbound()
            onUnbound()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val d = s.device
        when {
            s.loading && d == null -> Column(Modifier.fillMaxSize()) { Skeleton(4) }
            s.error != null && d == null -> ErrorState(
                message = s.error!!,
                onRetry = { vm.load() },
                onBack = onBack,
                backText = "返回",
            )
            d != null -> DeviceBody(
                state = s,
                onOpenAlarms = { onOpenAlarms(d.uid) },
                onOpenLogs = { onOpenLogs(d.uid) },
                onOpenTrust = { onOpenTrust(d.uid) },
                onOpenGrants = { onOpenGrants(d.uid) },
                onRename = {
                    dialogs.prompt(
                        title = "重命名设备",
                        label = "新名称",
                        initial = d.deviceName,
                        okText = "保存",
                        maxLength = 20,
                    ) { name ->
                        if (name != null) vm.rename(name)
                    }
                },
                onUnbind = {
                    dialogs.confirm(
                        title = "解绑设备",
                        message = "确定解绑「${d.deviceName}」？\n该设备上的闹钟将不再可见，此操作不可撤销。",
                        okText = "解绑",
                        danger = true,
                    ) { yes -> if (yes) vm.unbind() }
                },
                onRequestView = { vm.requestView() },
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
                    Text(toast!!, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DeviceBody(
    state: DeviceUiState,
    onOpenAlarms: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTrust: () -> Unit,
    onOpenGrants: () -> Unit,
    onRename: () -> Unit,
    onUnbind: () -> Unit,
    onRequestView: () -> Unit,
) {
    val d = state.device ?: return
    val c = TaTheme.colors
    val isOwner = d.ownerIsMe
    val (onlineStr, online) = onlineText(d.onlineStatus)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- ① 设备信息卡 ----
        TaCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(c.bg, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (d.platform == "ios") "📱" else "🤖",
                            fontSize = 24.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            d.deviceName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                platformName(d.platform),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = c.text3,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("·", color = c.text3)
                            Spacer(Modifier.width(6.dp))
                            TaTag(
                                text = onlineStr,
                                tone = if (online) TaTone.Ok else TaTone.Neutral,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                InfoRow("型号", d.modelDisplay.ifBlank { "—" })
                InfoRow("归属人", if (d.ownerIsMe) "我" else (d.ownerNickname ?: "—"))
                InfoRow(
                    "最近心跳",
                    d.lastHeartbeatAt?.takeIf { it > 0L }?.let { fmtRelative(it) } ?: "从未上报",
                )
                InfoRow(
                    "信任自动下发",
                    if (d.autoAlarmTrustSwitch == 1) "已开启" else "已关闭",
                )
                InfoRow("我的权限", viewSourceText(d.viewSource))
            }
        }

        // ---- ② 功能卡 ----
        TaCard {
            Column {
                SectionHeader("功能")
                Spacer(Modifier.height(6.dp))
                EntryRow("⏰", "闹钟", "查看与设置该设备的闹钟", onOpenAlarms)
                EntryRow("📋", "操作日志", "谁在什么时候做了哪些操作", onOpenLogs)
                if (isOwner) {
                    EntryRow("🤝", "信任名单", "信任成员可免确认下发并查看闹钟", onOpenTrust)
                    EntryRow("👁️", "查看授权", "单独授权他人只读查看", onOpenGrants)
                } else {
                    EntryRow("✋", "申请查看", "向设备主人申请只读查看权限", onRequestView)
                }
            }
        }

        // ---- ③ 设备管理（仅归属人）----
        if (isOwner) {
            TaCard {
                Column {
                    SectionHeader("设备管理")
                    Spacer(Modifier.height(10.dp))
                    TaButton(
                        text = "重命名设备",
                        onClick = onRename,
                        style = TaButtonStyle.Ghost,
                        enabled = state.busy == null,
                        loading = state.busy == "rename",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TaButton(
                        text = "解绑设备",
                        onClick = onUnbind,
                        style = TaButtonStyle.Danger,
                        enabled = state.busy == null,
                        loading = state.busy == "unbind",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "解绑后该设备及其闹钟将从家庭中移除",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                }
            }
        } else {
            TaCard {
                Text(
                    "你是通过「${viewSourceText(d.viewSource)}」获得访问权限的。仅设备归属人可管理该设备。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = c.warn,
                )
            }
        }

        // ---- ④ 设备 ID ----
        Text(
            "设备 ID：${d.uid}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = TaTheme.colors.text2,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = TaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Text(
            value,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一行「图标 + 标题 + 说明 + ›」的入口（网页版 `.item` 的等价物），整行可点。 */
@Composable
private fun EntryRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val c = TaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .androidxClickable(onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(c.bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = c.text,
            )
            Text(
                subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", fontSize = 20.sp, color = c.text3)
    }
}

/** 小工具：给 Row 加点击（避免逐处 import clickable）。 */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable { onClick() })
