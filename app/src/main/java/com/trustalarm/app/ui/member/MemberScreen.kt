package com.trustalarm.app.ui.member

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.DialogHost
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.onlineText
import com.trustalarm.app.ui.platformName
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 成员详情页 —— 对应 `web/js/pages/member.js`（核心部分）。
 *
 * 结构：
 *   ① 成员信息卡：头像(👑/👤) + 昵称[我] + 手机掩码 + 角色/加入时间
 *   ② 名下设备卡：每台一行（🤖/📱 + 名称[在线标签] + 平台 + 权限标签），
 *      点任意一行进设备详情
 *   ③ 权限概览卡：能否查看该成员全部设备 + 信任/授权计数
 *
 * ## 双向信任卡片（已迁移）
 *
 * 网页版的 `myDevicesTrustCard` / `hisDevicesTrustCard` 均已迁移：
 *   · 「我的设备对他的信任」—— 可加入/移出信任名单、开关自动下发
 *   · 「他的设备对我的信任」—— 只读（只有对方能改）
 * 依赖后端 member_detail 的 `my_devices` / `trust.target_trusts_detail` 字段。
 */
@Composable
fun MemberScreen(
    vm: MemberViewModel,
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val dialogs = rememberDialogHost()

    LaunchedEffect(Unit) { vm.load() }

    Box(modifier = modifier.fillMaxSize()) {
        val m = s.member
        when {
            s.loading && m == null -> Column(Modifier.fillMaxSize()) { Skeleton(3) }
            s.error != null && m == null -> ErrorState(
                message = s.error!!,
                onRetry = { vm.load() },
                onBack = onBack,
                backText = "返回家庭",
            )
            m != null -> MemberBody(
                member = m,
                busy = s.busy,
                onOpenDevice = onOpenDevice,
                vm = vm,
                dialogs = dialogs,
            )
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

@Composable
private fun MemberBody(
    member: com.trustalarm.app.data.MemberDetail,
    busy: Boolean,
    onOpenDevice: (String) -> Unit,
    vm: MemberViewModel,
    dialogs: DialogHost,
) {
    val c = TaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- ① 成员信息 ----
        TaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(c.bg, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (member.isOwner) "👑" else "👤", fontSize = 24.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            member.nickname,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        if (member.isMe) {
                            Spacer(Modifier.width(6.dp))
                            TaTag("我", TaTone.Neutral)
                        }
                    }
                    Text(
                        member.phoneMasked,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TaTag(
                            text = roleText(member.role, member.isOwner),
                            tone = if (member.isOwner) TaTone.Ok else TaTone.Neutral,
                        )
                        Spacer(Modifier.width(6.dp))
                        TaTag(text = "加入于 ${fmtRelative(member.joinedAt)}", TaTone.Neutral)
                    }
                }
            }
        }

        // ---- ② 名下设备 ----
        TaCard {
            Column {
                Text(
                    "名下设备（${member.deviceCount}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(8.dp))
                if (member.devices.isEmpty()) {
                    Text(
                        if (member.isMe) "名下还没有设备 · 去设备页绑定一台" else "该成员还没有绑定设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    member.devices.forEach { d ->
                        DeviceRow(device = d, onClick = { onOpenDevice(d.uid) })
                    }
                }
            }
        }

        // ---- ③ 权限概览 ----
        TaCard {
            Column {
                Text(
                    "你能查看他设备的权限",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(8.dp))
                if (member.canViewAllDevices) {
                    Text(
                        "✓ 你可以查看该成员名下全部设备的闹钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ok,
                    )
                } else {
                    Text(
                        "⚠ 你无法查看该成员的部分或全部设备。需向设备主人申请查看权限，或由设备主人把你加入信任名单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.warn,
                    )
                }
                if (member.trustMemberCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "该成员在其 ${member.trustMemberCount} 台设备的信任名单中",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                }
                if (member.grantCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "该成员持有 ${member.grantCount} 项查看授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                }
            }
        }

        // ---- ④ 我的设备对他的信任（可设置，双向语义之一）----
        MyDevicesTrustCard(member, busy, vm, dialogs)

        // ---- ⑤ 他的设备对我的信任（只读）----
        HisDevicesTrustCard(member)

        Text(
            if (member.isMe) {
                "这是你自己。你的设备在「设备」页可以看到全部。"
            } else {
                "设备归属是「个人」而非「家庭」—— 同一位成员的多台设备都在这里列出。点设备可查看它的闹钟、信任名单与查看授权。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 「我的设备对他的信任（可设置）」—— 对应网页版 `myDevicesTrustCard`。
 *
 * 用 `my_devices`（我名下设备）而不是 `devices`（该成员名下）：
 * 只有我自己的设备我才有权改信任设置。
 * 每台设备三个动作：加入信任名单 / 移出 / 开关自动下发。
 */
@Composable
private fun MyDevicesTrustCard(
    member: com.trustalarm.app.data.MemberDetail,
    busy: Boolean,
    vm: MemberViewModel,
    dialogs: DialogHost,
) {
    if (member.isMe) return
    val c = TaTheme.colors
    val him = member.nickname

    TaCard {
        Column {
            Text(
                "我的设备对他的信任（可设置）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))

            if (member.myDevices.isEmpty()) {
                Text(
                    "你名下还没有设备，因此无法信任他人。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            } else {
                member.myDevices.forEach { dev ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (dev.platform == "ios") "📱" else "🤖",
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dev.deviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                TaTag(
                                    text = if (dev.switchOn) "信任开关已开" else "信任开关已关",
                                    tone = if (dev.switchOn) TaTone.Ok else TaTone.Neutral,
                                )
                                Spacer(Modifier.width(4.dp))
                                TaTag(
                                    text = if (dev.trustedByMe) "已信任他" else "未信任 $him",
                                    tone = if (dev.trustedByMe) TaTone.Ok else TaTone.Neutral,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when {
                                    dev.trustedByMe && dev.switchOn ->
                                        "$him 可以在这台设备上免确认设置闹钟"
                                    dev.trustedByMe ->
                                        "已加入信任名单，但开关关闭 → 仍需你确认"
                                    else ->
                                        "加入信任名单并开启开关后，$him 可免确认在这台设备设闹钟"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.text3,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TaButton(
                                    text = if (dev.trustedByMe) "已在信任名单" else "加入信任名单",
                                    onClick = {
                                        vm.notify("已把【$him】加入信任名单")
                                        vm.addTrust(dev.uid)
                                    },
                                    enabled = !busy && !dev.trustedByMe,
                                    fillWidth = false,
                                )
                                TaButton(
                                    text = "移出信任名单",
                                    onClick = { askRemoveTrust(dialogs, vm, dev.uid, dev.deviceName, him) },
                                    enabled = !busy && dev.trustedByMe,
                                    style = TaButtonStyle.Ghost,
                                    fillWidth = false,
                                )
                                TaButton(
                                    text = if (dev.switchOn) "关闭信任开关" else "开启信任开关",
                                    onClick = {
                                        askToggleSwitch(dialogs, vm, dev.uid, dev.deviceName, !dev.switchOn)
                                    },
                                    enabled = !busy,
                                    style = if (dev.switchOn) TaButtonStyle.Ghost else TaButtonStyle.Primary,
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            Text(
                "这里是你名下设备对 $him 的信任设置 —— 设置后 $him 可以操作你的设备。\n" +
                    "与「$him 是否信任你」是两件独立的事，互不影响。",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
    }
}

/**
 * 「他的设备对我的信任（只读）」—— 对应网页版 `hisDevicesTrustCard`。
 *
 * 只有对方本人能改他的设备设置，这里只展示状态、不给开关。
 */
@Composable
private fun HisDevicesTrustCard(member: com.trustalarm.app.data.MemberDetail) {
    if (member.isMe) return
    if (member.devices.isEmpty()) return
    val c = TaTheme.colors
    val him = member.nickname
    val detailMap = member.targetTrustsDetail.associateBy { it.deviceUid }

    TaCard {
        Column {
            Text(
                "$him 的设备对你的信任（只读）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            member.devices.forEach { dev ->
                val det = detailMap[dev.uid]
                val trustedMe = det != null
                val on = det?.switchOn == true
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (dev.platform == "ios") "📱" else "🤖", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                dev.deviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(6.dp))
                            TaTag(
                                text = if (trustedMe) "已信任你" else "未信任你",
                                tone = if (trustedMe) TaTone.Ok else TaTone.Neutral,
                            )
                            if (trustedMe) {
                                Spacer(Modifier.width(4.dp))
                                TaTag(
                                    text = if (on) "已生效" else "开关关",
                                    tone = if (on) TaTone.Ok else TaTone.Neutral,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                trustedMe && on -> "你可以在这台设备上免确认设置闹钟"
                                trustedMe -> "他在信任名单中，但开关关闭 → 你设闹钟仍需他确认"
                                else -> "需要 $him 把他自己设备上的信任名单加入你，你才能操作"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.text3,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "这些是 $him 名下设备的设置，只有 TA 本人能改。\n" +
                    "你无法在这里开关 —— 需要 TA 在自己的设备里把你加入信任名单。",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
    }
}

/** 移出信任名单确认（对应网页版 confirmBox） */
private fun askRemoveTrust(
    dialogs: DialogHost,
    vm: MemberViewModel,
    deviceUid: String,
    deviceName: String,
    him: String,
) {
    dialogs.confirm(
        title = "移出信任名单",
        message = "移出后【$him】将无法操作【$deviceName】。",
        okText = "移出",
        danger = true,
    ) { yes ->
        if (yes) {
            vm.notify("已移出信任名单")
            vm.removeTrust(deviceUid)
        }
    }
}

/** 开关信任自动下发确认（对应网页版 confirmBox） */
private fun askToggleSwitch(
    dialogs: DialogHost,
    vm: MemberViewModel,
    deviceUid: String,
    deviceName: String,
    turnOn: Boolean,
) {
    dialogs.confirm(
        title = if (turnOn) "开启信任开关" else "关闭信任开关",
        message = if (turnOn) {
            "开启后，信任名单中的成员可在【$deviceName】上免确认设置闹钟。"
        } else {
            "关闭后，信任成员在【$deviceName】上设闹钟仍需你确认。"
        },
        okText = if (turnOn) "开启" else "关闭",
    ) { yes ->
        if (yes) {
            vm.notify(if (turnOn) "已开启信任开关" else "已关闭信任开关")
            vm.setSwitch(deviceUid, turnOn)
        }
    }
}

@Composable
private fun DeviceRow(device: com.trustalarm.app.data.MemberDevice, onClick: () -> Unit) {
    val c = TaTheme.colors
    val (onlineStr, online) = onlineText(device.onlineStatus)
    val (permText, permOk, permDesc) = permissionText(device.myPermission)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(c.bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (device.platform == "ios") "📱" else "🤖", fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                TaTag(text = onlineStr, tone = if (online) TaTone.Ok else TaTone.Neutral)
            }
            Text(
                platformName(device.platform),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaTag(text = permText, tone = if (permOk) TaTone.Ok else TaTone.Neutral)
                if (permDesc.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        permDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text("›", fontSize = 20.sp, color = c.text3)
    }
}
