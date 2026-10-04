package com.trustalarm.app.ui.devicetrust

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.TrustMember
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
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 「信任名单」页 —— 对应 `web/js/pages/device_trust.js`（P4）。
 *
 * 两个区块：信任自动下发开关 + 信任成员列表（增删）。
 * 非归属人只读（显示锁定说明）。
 * 顶栏「添加」按钮通过 [DeviceTrustViewModel] 之外的回调触发 ——
 * 本页把「添加」放在卡片头，与网页版一致（网页版 card-hd 里也有一个「添加」）。
 */
@Composable
fun DeviceTrustScreen(
    vm: DeviceTrustViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()
    val c = TaTheme.colors

    LaunchedEffect(Unit) { vm.load() }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.members.isEmpty() && s.error == null ->
                Column(Modifier.fillMaxSize()) { Skeleton(3) }

            s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

            // 非归属人：只读
            !s.isOwner -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                EmptyState(
                    icon = "🔒",
                    title = "仅设备归属人可管理",
                    sub = "你不是「${s.deviceName}」的归属人，无法修改信任名单",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (s.members.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TaCard {
                        Column {
                            Text(
                                "信任成员（${s.members.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = c.text2,
                            )
                            Spacer(Modifier.height(6.dp))
                            s.members.forEach { MemberRow(it) { } }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // ---- 信任自动下发开关 ----
                TaCard {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "信任自动下发",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = c.text,
                                modifier = Modifier.weight(1f),
                            )
                            TaTag(
                                text = if (s.switchOn) "已开启" else "已关闭",
                                tone = if (s.switchOn) TaTone.Ok else TaTone.Neutral,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "开启后，信任成员可免确认直接为「${s.deviceName}」设置闹钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.text2,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            TaButton(
                                text = if (s.switchOn) "关闭" else "开启",
                                onClick = {
                                    if (s.switchOn) {
                                        vm.setSwitch(false)
                                    } else {
                                        askEnableSwitch(dialogs, vm)
                                    }
                                },
                                enabled = !s.busy,
                                style = if (s.switchOn) TaButtonStyle.Ghost
                                else TaButtonStyle.Primary,
                                fillWidth = false,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (s.switchOn) {
                                "当前：信任成员下发将免确认（iPhone 仍需在 App 内确认一次）"
                            } else {
                                "当前：所有人下发的闹钟都需要你确认"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.switchOn) c.ok else c.warn,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ---- 信任成员 ----
                TaCard {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "信任成员（${s.members.size}）",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = c.text,
                                modifier = Modifier.weight(1f),
                            )
                            TaButton(
                                text = "添加",
                                onClick = { askAddMember(dialogs, vm) },
                                enabled = !s.busy,
                                style = TaButtonStyle.Ghost,
                                fillWidth = false,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (s.members.isEmpty()) {
                            EmptyState(
                                icon = "🤝",
                                title = "还没有信任成员",
                                sub = "添加家人后，他们可免确认为你设置闹钟",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            s.members.forEach { m ->
                                MemberRow(m) { askRemove(dialogs, vm, m) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "信任成员可查看该设备的闹钟；开启上方开关后还可免确认下发。\n" +
                        "「信任」与「查看授权」是两套独立权限，互不影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                )
                Spacer(Modifier.height(20.dp))
            }
        }

        DialogHostView(host = dialogs)

        if (!toast.isNullOrBlank()) {
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(2200)
                vm.clearToast()
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TaCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(toast!!, style = MaterialTheme.typography.bodyMedium, color = c.text)
                }
            }
        }
    }
}

@Composable
private fun MemberRow(m: TrustMember, onRemove: () -> Unit) {
    val c = TaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👤", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.nickname.ifBlank { "未知" },
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${m.phoneMasked} · 加入于 ${fmtRelative(m.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
        TaButton(
            text = "移除",
            onClick = onRemove,
            style = TaButtonStyle.Ghost,
            fillWidth = false,
        )
    }
}

/** 开启信任自动下发的确认（对应网页版 confirmBox） */
private fun askEnableSwitch(dialogs: DialogHost, vm: DeviceTrustViewModel) {
    dialogs.confirm(
        title = "开启信任自动下发",
        message = "开启后，信任成员为你设置的闹钟将不再需要你确认，会直接生效。\n\n" +
            "iPhone 因系统限制，仍需在手机 App 内确认一次。",
        okText = "开启",
    ) { yes -> if (yes) vm.setSwitch(true) }
}

/** 添加信任成员（输入手机号 + 本地格式校验，对应网页版 promptBox） */
private fun askAddMember(dialogs: DialogHost, vm: DeviceTrustViewModel) {
    dialogs.prompt(
        title = "添加信任成员",
        label = "对方手机号",
        placeholder = "需是同一家庭的成员",
        okText = "添加",
    ) { input -> if (input != null) vm.addMember(input) }
}

/** 移除信任成员确认 */
private fun askRemove(dialogs: DialogHost, vm: DeviceTrustViewModel, m: TrustMember) {
    dialogs.confirm(
        title = "移除信任成员",
        message = "确定把「${m.nickname.ifBlank { "该成员" }}」移出信任名单？\n" +
            "移除后他将失去查看权限，也无法免确认下发。",
        okText = "移除",
        danger = true,
    ) { yes -> if (yes) vm.removeMember(m.uid) }
}
