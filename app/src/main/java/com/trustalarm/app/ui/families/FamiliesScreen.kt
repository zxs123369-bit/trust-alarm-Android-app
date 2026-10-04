package com.trustalarm.app.ui.families

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.Family
import com.trustalarm.app.data.Invitation
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.LoadingBlock
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.fmtTime
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 家庭列表页 —— 对应 `web/js/pages/families.js`。
 *
 * ## 页面结构（与网页版逐块对应）
 *
 * ```
 * ┌ 待处理邀请卡片（有邀请才显示，即使没有家庭也显示）
 * ├ 家庭列表卡片（每项：🏠 + 名称 [+群主标签] + N 位成员·创建时间 + ›）
 * └ 底部说明（「家庭是设备与成员的归属单位…」）
 * ```
 *
 * 空态（没有家庭）时结构变为：
 * ```
 * ┌ 待处理邀请卡片（若有）
 * ├ 空态（🏠 / 还没有家庭 / 副文案随有无邀请变化）
 * └ 「创建家庭」大按钮
 * ```
 *
 * ## 顶栏「新建」与底部「创建家庭」是两个入口、同一动作
 *
 * 网页版顶栏有 `actions:[{text:'新建'}]`，空态里还有一个「创建家庭」按钮，
 * 两者都调 `createFamily()`。这里保持一致 —— 用户不需要先找个入口在哪。
 */
@Composable
fun FamiliesScreen(
    vm: FamiliesViewModel,
    onOpenFamily: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val openFamily by vm.openFamily.collectAsState()
    val dialogs = rememberDialogHost()
    val c = TaTheme.colors
    val ty = MaterialTheme.typography

    // 创建成功后跳转家庭详情（对应网页版 go(`/family/${uid}`)）
    LaunchedEffect(openFamily) {
        openFamily?.let {
            vm.clearOpenFamily()
            onOpenFamily(it)
        }
    }

    // 首次进入加载（对应网页版 render() 在路由切换时被调用）
    LaunchedEffect(Unit) { vm.load() }

    // 顶栏「新建」信号 → 弹创建对话框。只处理增量（见 VM 里 createRequests 的说明）。
    val createReq by vm.createRequests.collectAsState()
    val consumedCreateReq = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(createReq) {
        if (createReq > consumedCreateReq.value) {
            consumedCreateReq.value = createReq
            askCreateFamily(dialogs, vm)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // 首次加载：网页版用 skeleton(3)，这里保持
            s.loading && s.families.isEmpty() && s.error == null -> {
                Column(Modifier.fillMaxSize()) { Skeleton(rows = 3) }
            }

            // 错误态：必须有重试按钮（对应 errorState(e, () => render())）
            s.error != null -> {
                ErrorState(message = s.error!!, onRetry = { vm.load() })
            }

            // 空态 vs 有数据
            s.families.isEmpty() -> EmptyFamilies(
                invitations = s.invitations,
                busyInvitation = s.busyInvitation,
                creating = s.creating,
                onCreate = { askCreateFamily(dialogs, vm) },
                onInvite = { uid, accept ->
                    askInvitation(dialogs, vm, uid, accept, s.invitations)
                },
            )

            else -> FilledFamilies(
                families = s.families,
                invitations = s.invitations,
                ownerUid = s.me?.uid,
                busyInvitation = s.busyInvitation,
                onOpen = onOpenFamily,
                onInvite = { uid, accept ->
                    askInvitation(dialogs, vm, uid, accept, s.invitations)
                },
            )
        }

        DialogHostView(host = dialogs)

        // 一次性提示（对应 toastOk / showError）。用页面内浮层而非 Android Toast，
        // 便于与网页版一样在自动化测试里断言。
        if (!toast.isNullOrBlank()) {
            LaunchedEffect(toast) {
                kotlinx.coroutines.delay(2400)   // 网页版 toast 存活 2.4s
                vm.clearToast()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TaCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(toast!!, style = ty.bodyMedium, color = c.text)
                }
            }
        }
    }
}

/** 没有家庭时的页面（含邀请卡片与创建按钮） */
@Composable
private fun EmptyFamilies(
    invitations: List<Invitation>,
    busyInvitation: String?,
    creating: Boolean,
    onCreate: () -> Unit,
    onInvite: (String, Boolean) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (invitations.isNotEmpty()) {
            InviteCard(invitations, busyInvitation, onInvite)
            Spacer(Modifier.height(12.dp))
        }
        EmptyState(
            icon = "🏠",
            title = "还没有家庭",
            sub = if (invitations.isNotEmpty()) {
                "有人邀请你加入家庭，到「消息」中同意即可"
            } else {
                "创建一个家庭，然后邀请家人加入"
            },
        )
        Spacer(Modifier.height(4.dp))
        TaButton(text = "创建家庭", onClick = onCreate, loading = creating)
        Spacer(Modifier.height(16.dp))
    }
}

/** 有家庭时的页面 */
@Composable
private fun FilledFamilies(
    families: List<Family>,
    invitations: List<Invitation>,
    ownerUid: String?,
    busyInvitation: String?,
    onOpen: (String) -> Unit,
    onInvite: (String, Boolean) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (invitations.isNotEmpty()) {
            InviteCard(invitations, busyInvitation, onInvite)
            Spacer(Modifier.height(12.dp))
        }

        TaCard {
            Column {
                families.forEachIndexed { idx, f ->
                    if (idx > 0) {
                        // 列表项分隔线（网页版 .item 之间的 1px 边框）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(c.border),
                        )
                    }
                    FamilyRow(family = f, isOwner = ownerUid != null && f.ownerUid == ownerUid) {
                        onOpen(f.uid)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "家庭是设备与成员的归属单位。设备绑定到家庭后，家庭成员才能看到它。",
            style = ty.labelSmall,
            color = c.text3,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** 单个家庭行（对应网页版 `<a class="item">`） */
@Composable
private fun FamilyRow(family: Family, isOwner: Boolean, onClick: () -> Unit) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🏠", style = ty.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    family.name,
                    style = ty.bodyLarge,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isOwner) {
                    Spacer(Modifier.width(6.dp))
                    TaTag(text = "群主", tone = TaTone.Info)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${family.memberCount} 位成员 · 创建于 ${fmtTime(family.createdAt)}",
                style = ty.labelSmall,
                color = c.text3,
            )
        }
        Text("›", style = ty.headlineSmall, color = c.text3)
    }
}

/** 待处理邀请卡片（对应网页版 `inviteCard`） */
@Composable
private fun InviteCard(
    list: List<Invitation>,
    busyUid: String?,
    onInvite: (String, Boolean) -> Unit,
) {
    val c = TaTheme.colors
    val ty = MaterialTheme.typography
    TaCard {
        Column {
            Text(
                "家庭邀请（${list.size}）",
                style = ty.titleMedium,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            list.forEach { iv ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✉️", style = ty.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(iv.familyName, style = ty.bodyLarge, color = c.text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${iv.inviterNickname} 邀请你加入 · ${fmtRelative(iv.createdAt)}",
                            style = ty.labelSmall,
                            color = c.text3,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaButton(
                        text = "同意加入",
                        onClick = { onInvite(iv.invitationUid, true) },
                        loading = busyUid == iv.invitationUid,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    TaButton(
                        text = "拒绝",
                        onClick = { onInvite(iv.invitationUid, false) },
                        style = TaButtonStyle.Ghost,
                        enabled = busyUid != iv.invitationUid,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---- 弹窗动作（对应网页版 createFamily / handleInvite 里的 confirmBox/promptBox）----

private fun askCreateFamily(dialogs: com.trustalarm.app.ui.components.DialogHost, vm: FamiliesViewModel) {
    dialogs.prompt(
        title = "创建家庭",
        label = "家庭名称",
        placeholder = "如：我们家",
        okText = "创建",
        maxLength = FamiliesViewModel.MAX_FAMILY_NAME,
    ) { name ->
        if (name != null && name.isNotEmpty()) vm.createFamily(name)
    }
}

private fun askInvitation(
    dialogs: com.trustalarm.app.ui.components.DialogHost,
    vm: FamiliesViewModel,
    invitationUid: String,
    accept: Boolean,
    list: List<Invitation>,
) {
    // 网页版文案逐字对应（见 families.js :: handleInvite）
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
        if (yes) vm.handleInvitation(invitationUid, accept)
    }
}
