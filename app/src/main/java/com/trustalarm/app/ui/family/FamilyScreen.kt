package com.trustalarm.app.ui.family

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.FamilyDetail
import com.trustalarm.app.data.FamilyInvitation
import com.trustalarm.app.data.Member
import com.trustalarm.app.ui.components.DialogHost
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
import com.trustalarm.app.ui.theme.TaTheme
import kotlinx.coroutines.delay

/**
 * 家庭详情页 —— 对应 `web/js/pages/family.js`。
 *
 * ## 页面结构（与网页版逐块对应）
 *
 * ```
 * ┌ 成员卡片：标题「成员（N）」+ 群主可见的「加成员」按钮
 * │   · 每行：👑/👤 + 昵称[我][群主] + 手机掩码·加入时间 + 名下设备数
 * │     群主可「移出」（不能移自己）
 * ├ 待对方同意（仅群主、有邀请时）：✉️ + 昵称[等待同意] + 邀请时间 + 「撤销」
 * ├ 绑定新设备（📱）：显示本家庭设备总数
 * ├ 退出该家庭（仅非群主）
 * ├ 操作记录（📋）
 * └ 两段说明文字
 * ```
 *
 * ## 权限展示遵循「后端已判，前端只做展示控制」
 *
 * 网页版顶部注释明确：成员增删仅群主（`is_owner`），设备绑定家庭成员均可。
 * 前端**不重复造权限规则**，只按 `isOwner` 决定按钮是否渲染 —— 真正的拦截在后端。
 */
@Composable
fun FamilyScreen(
    vm: FamilyViewModel,
    onBack: () -> Unit,
    onOpenMember: (memberUid: String) -> Unit,
    onOpenDevice: (deviceUid: String) -> Unit,
    onOpenLogs: () -> Unit,
    onQuitDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val quitDone by vm.quitDone.collectAsState()
    val openDevice by vm.openDevice.collectAsState()
    val dialogs = rememberDialogHost()

    LaunchedEffect(Unit) { vm.load() }

    // 退出家庭成功 → 回家庭列表（对应网页版 go('/families', {replace:true})）
    LaunchedEffect(quitDone) {
        if (quitDone) {
            vm.clearQuitDone()
            onQuitDone()
        }
    }
    // 绑定成功 → 进设备详情（对应网页版 go(`/device/${d.uid}`)）
    LaunchedEffect(openDevice) {
        openDevice?.let {
            vm.clearOpenDevice()
            onOpenDevice(it)
        }
    }

    // 顶栏「加成员」信号 → 弹邀请对话框
    val inviteReq by vm.inviteRequests.collectAsState()
    val consumed = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0)
    }
    LaunchedEffect(inviteReq) {
        if (inviteReq > consumed.value) {
            consumed.value = inviteReq
            askInviteMember(dialogs, vm)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            s.loading && s.family == null -> Column(Modifier.fillMaxSize()) { Skeleton(4) }
            s.error != null -> ErrorState(
                message = s.error!!,
                onRetry = { vm.load() },
                onBack = onBack,
                backText = "返回家庭列表",
            )
            s.family != null -> FamilyBody(
                fam = s.family!!,
                invitations = s.invitations,
                busy = s.busy,
                quitting = s.quitting,
                deviceCountOf = vm::deviceCountOf,
                onOpenMember = onOpenMember,
                onOpenLogs = onOpenLogs,
                onRemoveMember = { uid, name ->
                    askRemoveMember(dialogs, vm, uid, name)
                },
                onCancelInvitation = { uid ->
                    askCancelInvitation(dialogs, vm, uid)
                },
                onBindDevice = { askBindDevice(dialogs, vm) },
                onQuit = { askQuit(dialogs, vm, s.family!!.name) },
                onAddMember = { askInviteMember(dialogs, vm) },
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
                    Text(toast!!, style = MaterialTheme.typography.bodyMedium, color = TaTheme.colors.text)
                }
            }
        }
    }
}

@Composable
private fun FamilyBody(
    fam: FamilyDetail,
    invitations: List<FamilyInvitation>,
    busy: String?,
    quitting: Boolean,
    deviceCountOf: (String) -> Int,
    onOpenMember: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onRemoveMember: (String, String) -> Unit,
    onCancelInvitation: (String) -> Unit,
    onBindDevice: () -> Unit,
    onQuit: () -> Unit,
    onAddMember: () -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    val isOwner = fam.isOwner

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 成员卡片 ----
        TaCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("成员（${fam.memberCount}）", style = ty.titleMedium, color = c.text,
                        modifier = Modifier.weight(1f))
                    if (isOwner) {
                        TaButton(
                            text = "加成员",
                            onClick = onAddMember,
                            style = TaButtonStyle.Ghost,
                            fillWidth = false,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                fam.members.forEachIndexed { idx, m ->
                    if (idx > 0) Divider(c.border)
                    MemberRow(
                        member = m,
                        deviceCount = deviceCountOf(m.uid),
                        canRemove = isOwner && !m.isOwner,
                        removing = busy == m.uid,
                        onOpen = { onOpenMember(m.uid) },
                        onRemove = { onRemoveMember(m.uid, m.nickname) },
                    )
                }
            }
        }

        // ---- 待对方同意（仅群主）----
        if (invitations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TaCard {
                Column {
                    Text("待对方同意（${invitations.size}）", style = ty.titleMedium, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    invitations.forEach { iv ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("✉️", style = ty.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(iv.inviteeNickname, style = ty.bodyLarge, color = c.text)
                                    Spacer(Modifier.width(6.dp))
                                    TaTag(text = "等待同意", tone = TaTone.Warn)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "邀请发送于 ${fmtRelative(iv.createdAt)}",
                                    style = ty.labelSmall,
                                    color = c.text3,
                                )
                            }
                            TaButton(
                                text = "撤销",
                                onClick = { onCancelInvitation(iv.invitationUid) },
                                style = TaButtonStyle.Ghost,
                                loading = busy == iv.invitationUid,
                                fillWidth = false,
                            )
                        }
                    }
                }
            }
        }

        // ---- 绑定新设备 ----
        Spacer(Modifier.height(12.dp))
        TaCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onBindDevice() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📱", style = ty.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("绑定新设备", style = ty.bodyLarge, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "本家庭共 ${fam.deviceCount} 台设备，点成员查看各自名下的设备",
                        style = ty.labelSmall,
                        color = c.text3,
                    )
                }
                Text("＋", style = ty.headlineSmall, color = c.text3)
            }
        }

        // ---- 退出家庭（仅非群主）----
        if (!isOwner) {
            Spacer(Modifier.height(12.dp))
            TaCard {
                Column {
                    TaButton(
                        text = "退出该家庭",
                        onClick = onQuit,
                        style = TaButtonStyle.Danger,
                        loading = quitting,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "退出后将失去该家庭内所有设备的访问权限",
                        style = ty.labelSmall,
                        color = c.text3,
                    )
                }
            }
        }

        // ---- 操作记录 ----
        Spacer(Modifier.height(12.dp))
        TaCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenLogs() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📋", style = ty.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("操作记录", style = ty.bodyLarge, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "谁邀请过谁、谁加入或被移出、谁设过闹钟",
                        style = ty.labelSmall,
                        color = c.text3,
                    )
                }
                Text("›", style = ty.headlineSmall, color = c.text3)
            }
        }

        // ---- 说明 ----
        Spacer(Modifier.height(12.dp))
        if (isOwner) {
            Text(
                "邀请成员后，需对方在「消息」中同意才会加入。未同意前会显示在「待对方同意」分组，可随时撤销。",
                style = ty.labelSmall,
                color = c.text3,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "点成员可查看他名下的设备与权限 —— 设备归属是「个人」而非「家庭」，同一位成员可能有多台设备。",
            style = ty.labelSmall,
            color = c.text3,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Divider(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 成员行（对应网页版 `.item` + `.member-hit` 结构） */
@Composable
private fun MemberRow(
    member: Member,
    deviceCount: Int,
    canRemove: Boolean,
    removing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点主体进详情（对应 member-hit），「移出」在右侧独立，避免误触
        Row(
            modifier = Modifier.weight(1f).clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (member.isOwner) "👑" else "👤", style = ty.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        member.nickname,
                        style = ty.bodyLarge,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (member.isOwner) {
                        Spacer(Modifier.width(6.dp))
                        TaTag(text = "群主", tone = TaTone.Info)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${member.phoneMasked} · 加入于 ${fmtRelative(member.joinedAt)}",
                    style = ty.labelSmall,
                    color = c.text3,
                )
                Spacer(Modifier.height(2.dp))
                if (deviceCount > 0) {
                    TaTag(text = "$deviceCount 台设备", tone = TaTone.Neutral)
                } else {
                    Text("暂无设备", style = ty.labelSmall, color = c.text3)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("›", style = ty.headlineSmall, color = c.text3)
        }

        if (canRemove) {
            Spacer(Modifier.width(8.dp))
            TaButton(
                text = "移出",
                onClick = onRemove,
                style = TaButtonStyle.Ghost,
                loading = removing,
                fillWidth = false,
            )
        }
    }
}

// ---- 弹窗动作（对应网页版 addMember / removeMember / quitFamily / bindDevice）----

private fun askInviteMember(dialogs: DialogHost, vm: FamilyViewModel) {
    dialogs.prompt(
        title = "邀请成员",
        label = "对方手机号",
        placeholder = "11 位手机号",
        okText = "发送邀请",
        inputType = KeyboardType.Phone,
        maxLength = 11,
    ) { phone ->
        if (phone != null && phone.isNotEmpty()) {
            // 与网页版一致：先在前端挡一次格式（真正拦截在后端）。
            // 文案照抄网页版 `addMember` —— 「请输入正确的 11 位手机号」。
            if (Regex("^1[3-9]\\d{9}$").matches(phone)) {
                vm.inviteMember(phone)
            } else {
                vm.showToast("请输入正确的 11 位手机号")
            }
        }
    }
}

private fun askCancelInvitation(dialogs: DialogHost, vm: FamilyViewModel, invitationUid: String) {
    dialogs.confirm(
        title = "撤销邀请",
        message = "撤销后对方将不再看到该邀请。若之后仍需邀请，可重新发送。",
        okText = "撤销",
        danger = true,
    ) { yes -> if (yes) vm.cancelInvitation(invitationUid) }
}

private fun askRemoveMember(
    dialogs: DialogHost,
    vm: FamilyViewModel,
    memberUid: String,
    memberName: String,
) {
    dialogs.confirm(
        title = "移出成员",
        message = "确定把「$memberName」移出家庭？\n他将失去该家庭所有设备的访问权限。",
        okText = "移出",
        danger = true,
    ) { yes -> if (yes) vm.removeMember(memberUid) }
}

private fun askQuit(dialogs: DialogHost, vm: FamilyViewModel, familyName: String) {
    dialogs.confirm(
        title = "退出家庭",
        message = "确定退出「$familyName」？\n退出后需群主重新邀请才能回来。",
        okText = "退出",
        danger = true,
    ) { yes -> if (yes) vm.quitFamily() }
}

/**
 * 一键绑定本机（对应网页版 `bindThisPhone`）。
 *
 * 用户明确要求：「点绑定设备就能绑上，不用输设备名，自动读取当前设备」。
 * 所以这里**不再弹输入框** —— 直接读本机机型当设备名并绑定。
 *
 * 保留一个确认弹窗是为了让用户知道「即将把当前这台手机绑到本家庭」，
 * 而不是被悄无声息地绑上（绑定会出现在家庭设备列表里，属可见副作用）。
 */
private fun askBindDevice(dialogs: DialogHost, vm: FamilyViewModel) {
    val model = android.os.Build.MODEL.orEmpty().trim()
    val maker = android.os.Build.MANUFACTURER.orEmpty().trim()
    val willName = when {
        model.isNotBlank() -> model
        maker.isNotBlank() -> "$maker 手机"
        else -> "我的手机"
    }
    dialogs.confirm(
        title = "绑定这台手机",
        message = "将把当前手机（$willName）绑定到本家庭。\n\n" +
            "设备名会自动读取机型，无需手动填写；绑定后可在设备详情页改名。",
        okText = "绑定",
    ) { yes -> if (yes) vm.bindThisPhone() }
}
