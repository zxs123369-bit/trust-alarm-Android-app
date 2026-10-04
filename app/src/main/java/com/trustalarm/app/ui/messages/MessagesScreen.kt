package com.trustalarm.app.ui.messages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustalarm.app.data.Message
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
import kotlinx.coroutines.delay

/**
 * 消息页 —— 对应 `web/js/pages/messages.js`。
 *
 * 结构：顶部「共 N 条，M 条未读」 + 消息卡片列表。每张卡：
 *   图标 + 标题[未读] + 相对时间 + 正文（保留换行）
 *   +（alarm_confirm 且未处理）同意/拒绝
 *   +（family_invite 且未处理）同意加入/拒绝
 *   +（view_request）去处理
 *
 * 点击卡片正文（非按钮）标为已读（与网页版一致）。
 * 顶栏「全部已读」由 MainComposeActivity 的 chrome 提供（走 requestReadAll 信号）。
 */
@Composable
fun MessagesScreen(
    vm: MessagesViewModel,
    onGoRequests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()

    LaunchedEffect(Unit) { vm.load() }

    // 顶栏「全部已读」信号
    val readAllReq by vm.readAllRequests.collectAsState()
    val consumed = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(readAllReq) {
        if (readAllReq > consumed.value) {
            consumed.value = readAllReq
            vm.markAllRead()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.messages.isEmpty() && s.error == null ->
                Column(Modifier.fillMaxSize()) { Skeleton(4) }
            s.error != null && s.messages.isEmpty() -> ErrorState(
                message = s.error!!,
                onRetry = { vm.load() },
                onBack = null,
                backText = "",
            )
            s.messages.isEmpty() -> EmptyState(
                icon = "🔔",
                title = "暂无消息",
                sub = "他人为你设置闹钟、申请查看设备时，会在这里通知你",
                modifier = Modifier.fillMaxSize(),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "共 ${s.messages.size} 条" +
                        if (s.unreadCount > 0) "，${s.unreadCount} 条未读" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TaTheme.colors.text3,
                )
                s.messages.forEach { m ->
                    MessageCard(
                        message = m,
                        resolved = vm.isResolved(m),
                        busy = s.busy != null,
                        onMarkRead = { vm.markRead(m.uid) },
                        onConfirm = { alarmUid, approve ->
                            dialogs.confirm(
                                title = if (approve) "同意该闹钟" else "拒绝该闹钟",
                                message = if (approve) {
                                    "同意后该闹钟将在你的设备上生效。"
                                } else {
                                    "拒绝后该闹钟不会生效。"
                                },
                                okText = if (approve) "同意" else "拒绝",
                                danger = !approve,
                            ) { yes ->
                                if (yes) vm.confirmAlarm(alarmUid, approve, m.uid)
                            }
                        },
                        onInvite = { invUid, accept ->
                            dialogs.confirm(
                                title = if (accept) "同意加入家庭" else "拒绝邀请",
                                message = if (accept) {
                                    "同意后你将成为该家庭成员，可以看到家庭内的设备与闹钟。"
                                } else {
                                    "拒绝后不会加入该家庭。"
                                },
                                okText = if (accept) "同意加入" else "拒绝",
                                danger = !accept,
                            ) { yes ->
                                if (yes) vm.handleInvite(invUid, accept, m.uid)
                            }
                        },
                        onGoRequests = onGoRequests,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
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
                    Text(toast!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: Message,
    resolved: Boolean,
    busy: Boolean,
    onMarkRead: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    onInvite: (String, Boolean) -> Unit,
    onGoRequests: () -> Unit,
) {
    val c = TaTheme.colors
    val unread = !message.isRead
    val (icon, defaultTitle, action) = messageTypeMeta(message.type)
    val pl = message.payload
    val alarmUid = pl?.optString("alarm_uid")?.takeIf { it.isNotBlank() }
    val invUid = pl?.optString("invitation_uid")?.takeIf { it.isNotBlank() }

    TaCard(
        // 点卡片正文标已读（按钮点击用各自的 clickable 截胡）
        onClick = onMarkRead,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(c.bg, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message.title.ifBlank { defaultTitle },
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (unread) {
                            Spacer(Modifier.width(6.dp))
                            TaTag("未读", TaTone.Err)
                        }
                    }
                    Text(
                        fmtRelative(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text3,
                    )
                }
                if (message.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text2,
                    )
                }

                // 内联动作：闹钟确认
                if (action == "alarm_confirm" && alarmUid != null && !resolved) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaButton(
                            text = "同意",
                            onClick = { onConfirm(alarmUid, true) },
                            style = TaButtonStyle.Primary,
                            fillWidth = false,
                            enabled = !busy,
                        )
                        TaButton(
                            text = "拒绝",
                            onClick = { onConfirm(alarmUid, false) },
                            style = TaButtonStyle.Ghost,
                            fillWidth = false,
                            enabled = !busy,
                        )
                    }
                }

                // 内联动作：家庭邀请
                if (action == "invite_act" && invUid != null && !resolved) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaButton(
                            text = "同意加入",
                            onClick = { onInvite(invUid, true) },
                            style = TaButtonStyle.Primary,
                            fillWidth = false,
                            enabled = !busy,
                        )
                        TaButton(
                            text = "拒绝",
                            onClick = { onInvite(invUid, false) },
                            style = TaButtonStyle.Ghost,
                            fillWidth = false,
                            enabled = !busy,
                        )
                    }
                }

                // 内联动作：去处理查看申请
                if (action == "go_requests") {
                    Spacer(Modifier.height(12.dp))
                    TaButton(
                        text = "去处理",
                        onClick = onGoRequests,
                        style = TaButtonStyle.Ghost,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}
