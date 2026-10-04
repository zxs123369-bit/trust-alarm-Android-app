package com.trustalarm.app.ui.devicegrants

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.Grant
import com.trustalarm.app.ui.components.DialogHost
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 「查看授权」页 —— 对应 `web/js/pages/device_grants.js`（P4）。
 *
 * 管理已生效的只读授权（可撤销）。申请处理在「查看申请」页。
 * 非归属人只读。
 */
@Composable
fun DeviceGrantsScreen(
    vm: DeviceGrantsViewModel,
    onGoRequests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()
    val c = TaTheme.colors

    LaunchedEffect(Unit) { vm.load() }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.grants.isEmpty() && s.error == null ->
                Column(Modifier.fillMaxSize()) { Skeleton(3) }

            s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

            !s.isOwner -> EmptyState(
                icon = "🔒",
                title = "仅设备归属人可管理",
                sub = "你不是「${s.deviceName}」的归属人",
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TaCard {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已授权（${s.grants.size}）",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = c.text,
                                modifier = Modifier.weight(1f),
                            )
                            TaButton(
                                text = "看申请",
                                onClick = onGoRequests,
                                style = TaButtonStyle.Ghost,
                                fillWidth = false,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (s.grants.isEmpty()) {
                            EmptyState(
                                icon = "👁️",
                                title = "还没有单独授权",
                                sub = "当有人申请查看并被你同意后，会出现在这里",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            s.grants.forEach { g ->
                                GrantRow(g) { askRevoke(dialogs, vm, g) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "被授权者可只读查看该设备的闹钟列表，但不能为你设置闹钟。\n" +
                        "若要允许对方免确认设置闹钟，请到「信任名单」添加。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "撤销授权后，对方立即失去查看权限（含此前通过该授权看到的记录）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.warn,
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
private fun GrantRow(g: Grant, onRevoke: () -> Unit) {
    val c = TaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("👁️", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(
                g.nickname.ifBlank { "未知" },
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${g.phoneMasked} · 授权于 ${fmtRelative(g.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
        TaButton(
            text = "撤销",
            onClick = onRevoke,
            style = TaButtonStyle.Ghost,
            fillWidth = false,
        )
    }
}

private fun askRevoke(dialogs: DialogHost, vm: DeviceGrantsViewModel, g: Grant) {
    dialogs.confirm(
        title = "撤销查看授权",
        message = "确定撤销「${g.nickname.ifBlank { "该成员" }}」的查看权限？\n" +
            "撤销后对方立即无法查看该设备的闹钟。",
        okText = "撤销",
        danger = true,
    ) { yes -> if (yes) vm.revoke(g.uid) }
}
