package com.trustalarm.app.ui.requests

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.ViewRequest
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
 * 「查看申请」页 —— 对应 `web/js/pages/requests.js`（P4）。
 *
 * 两个 Tab：我收到的（可同意/拒绝）、我发起的（只读）。
 * 同意后对方可**只读**查看该设备闹钟；页面底部有同样的说明文字。
 */
@Composable
fun RequestsScreen(
    vm: RequestsViewModel,
    onGoGrants: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()
    val c = TaTheme.colors

    LaunchedEffect(Unit) { vm.load() }

    val list = if (s.tab == RequestTab.Received) s.received else s.mine

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.received.isEmpty() && s.mine.isEmpty() && s.error == null ->
                Column(Modifier.fillMaxSize()) { Skeleton(3) }

            s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // ---- Tab 切换 ----
                TaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pending = vm.pendingCount()
                        TaButton(
                            text = if (pending > 0) "我收到的 ($pending 待处理)" else "我收到的",
                            onClick = { vm.switchTab(RequestTab.Received) },
                            style = if (s.tab == RequestTab.Received) TaButtonStyle.Primary
                            else TaButtonStyle.Ghost,
                            modifier = Modifier.weight(1f),
                        )
                        TaButton(
                            text = "我发起的 (${s.mine.size})",
                            onClick = { vm.switchTab(RequestTab.Mine) },
                            style = if (s.tab == RequestTab.Mine) TaButtonStyle.Primary
                            else TaButtonStyle.Ghost,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (list.isEmpty()) {
                    EmptyState(
                        icon = if (s.tab == RequestTab.Received) "📥" else "📤",
                        title = if (s.tab == RequestTab.Received) "没有收到申请" else "你还没发起过申请",
                        sub = if (s.tab == RequestTab.Received) "他人申请查看你的设备时会出现在这里"
                        else "在设备详情页可申请查看他人设备",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    list.forEach { it0 ->
                        RequestCard(
                            item = it0,
                            isReceived = s.tab == RequestTab.Received,
                            busy = s.busyId == it0.id,
                            onApprove = { askHandle(dialogs, vm, it0.id, true) },
                            onReject = { askHandle(dialogs, vm, it0.id, false) },
                            onGoGrants = { onGoGrants(it0.deviceUid) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "同意后对方可只读查看该设备的闹钟列表，但不能为你设置闹钟。\n" +
                        "若同意后发现不妥，可到「查看授权」页撤销。",
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

/** 单条申请卡片（对应网页版 card(it)） */
@Composable
private fun RequestCard(
    item: ViewRequest,
    isReceived: Boolean,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onGoGrants: () -> Unit,
) {
    val c = TaTheme.colors
    val (stText, stTone) = statusMeta(item.status)
    TaCard {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (isReceived) "申请查看" else "我申请查看") +
                            "「${item.deviceName.ifBlank { "未知设备" }}」",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = c.text,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isReceived) {
                            "申请人：${item.applicantNickname.ifBlank { "未知" }}" +
                                "（${item.applicantPhoneMasked}）"
                        } else {
                            "设备归属人处理中"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        fmtRelative(item.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text3,
                    )
                }
                TaTag(text = stText, tone = stTone)
            }

            if (isReceived && item.status == "pending") {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaButton(
                        text = "同意",
                        onClick = onApprove,
                        enabled = !busy,
                        fillWidth = false,
                    )
                    TaButton(
                        text = "拒绝",
                        onClick = onReject,
                        enabled = !busy,
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                }
            }

            if (isReceived && item.status == "approved") {
                Spacer(Modifier.height(10.dp))
                TaButton(
                    text = "管理授权",
                    onClick = onGoGrants,
                    style = TaButtonStyle.Ghost,
                    fillWidth = false,
                )
            }
        }
    }
}

/** status → 文案与色标（对应网页版 statusMeta） */
private fun statusMeta(s: String): Pair<String, TaTone> = when (s) {
    "pending" -> "待处理" to TaTone.Warn
    "approved" -> "已同意" to TaTone.Ok
    "rejected" -> "已拒绝" to TaTone.Err
    else -> (s.ifBlank { "未知" }) to TaTone.Neutral
}

/** 同意/拒绝前确认（对应网页版 confirmBox） */
private fun askHandle(dialogs: DialogHost, vm: RequestsViewModel, id: Long, approve: Boolean) {
    dialogs.confirm(
        title = if (approve) "同意查看申请" else "拒绝查看申请",
        message = if (approve) {
            "同意后对方可只读查看该设备的闹钟列表（不能设置闹钟）。"
        } else {
            "拒绝后对方无法查看该设备。"
        },
        okText = if (approve) "同意" else "拒绝",
        danger = !approve,
    ) { yes -> if (yes) vm.handle(id, approve) }
}
