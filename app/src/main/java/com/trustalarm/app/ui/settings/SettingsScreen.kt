package com.trustalarm.app.ui.settings

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustalarm.app.BuildConfig
import com.trustalarm.app.ui.components.DialogHostView
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.components.DialogHost
import com.trustalarm.app.ui.components.rememberDialogHost
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.fmtTime
import com.trustalarm.app.ui.theme.TaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「我的 / 设置」页 —— 对应 `web/js/pages/settings.js`。
 *
 * 结构：
 *   ① 账号卡：👤 + 昵称 + 手机掩码 + [免限流][N 个家庭][N 条未读] + 注册于
 *   ② 快捷入口：我的家庭 / 我的设备 / 消息 / 查看申请 / 后台可靠性设置
 *   ③ 登录设备（N）：每行 📲/🤖/💻 + 归类名 + [当前] + 登录于/至 +（非当前）下线
 *   ④ 退出登录 / 退出所有设备
 *   ⑤ 应用更新（原生壳专属）：当前版本 + 检查更新
 *   ⑥ 关于本客户端（说明 PWA 限制）
 *
 * ## 与网页版的差异（有意的）
 * 网页版脚注写「PWA 版…以下功能需安装原生 App」。原生 Compose 版**就是原生 App**，
 * 这段「需装原生 App」的说明对原生用户没有意义。但为保持 1:1 与信息完整，
 * 这里保留「关于本客户端」小节，文案改为说明原生能力（见 [AboutNote]）。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenFamilies: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenReliability: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val loggedOut by vm.loggedOut.collectAsState()
    val dialogs = rememberDialogHost()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            vm.clearLoggedOut()
            onLoggedOut()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (s.loading && s.me == null && s.sessions.isEmpty()) {
            Column(Modifier.fillMaxSize()) { Skeleton(3) }
        } else {
            SettingsBody(
                state = s,
                onOpenFamilies = onOpenFamilies,
                onOpenDevices = onOpenDevices,
                onOpenMessages = onOpenMessages,
                onOpenRequests = onOpenRequests,
                onOpenReliability = onOpenReliability,
                onRefreshSessions = { vm.refreshSessions() },
                onKillSession = { id ->
                    dialogs.confirm(
                        title = "下线该设备",
                        message = "该设备上的登录将立即失效，需要重新登录。",
                        okText = "下线",
                        danger = true,
                    ) { yes -> if (yes) vm.killSession(id) }
                },
                onLogout = {
                    dialogs.confirm(
                        title = "退出登录",
                        message = "退出后需要重新输入手机号和密码。",
                        okText = "退出",
                    ) { yes -> if (yes) vm.logout() }
                },
                onLogoutAll = {
                    dialogs.confirm(
                        title = "退出所有设备",
                        message = "所有设备（含当前）的登录都会失效，需要重新登录。",
                        okText = "全部退出",
                        danger = true,
                    ) { yes -> if (yes) vm.logoutAll() }
                },
                ctx = ctx,
                dialogs = dialogs,
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
                    Text(toast!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(
    state: SettingsUiState,
    onOpenFamilies: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenReliability: () -> Unit,
    onRefreshSessions: () -> Unit,
    onKillSession: (Long) -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    ctx: android.content.Context,
    dialogs: DialogHost,
) {
    val c = TaTheme.colors
    var updateMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val me = state.me

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- ① 账号卡 ----
        TaCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(c.bg, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("👤", fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            me?.nickname ?: "—",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        Text(
                            me?.phoneMasked ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.text3,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (me?.isUnlimited == true) {
                                TaTag("免限流", TaTone.Ok)
                                Spacer(Modifier.width(6.dp))
                            }
                            TaTag("${state.familyCount} 个家庭", TaTone.Neutral)
                            if (state.unread > 0) {
                                Spacer(Modifier.width(6.dp))
                                TaTag("${state.unread} 条未读", TaTone.Err)
                            }
                        }
                    }
                }
                if (me != null && me.createdAt > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "注册于 ${fmtTime(me.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                }
                if (state.meError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.meError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.err,
                    )
                }
            }
        }

        // ---- ② 快捷入口 ----
        TaCard {
            Column {
                Text(
                    "快捷入口",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(6.dp))
                EntryRow("🏠", "我的家庭", "${state.familyCount} 个家庭", onOpenFamilies)
                EntryRow("📱", "我的设备", "查看与管理设备", onOpenDevices)
                EntryRow(
                    "🔔", "消息",
                    if (state.unread > 0) "${state.unread} 条未读" else "暂无未读",
                    onOpenMessages,
                )
                EntryRow("✋", "查看申请", "同意或拒绝他人的查看申请", onOpenRequests)
                EntryRow("⏰", "后台可靠性设置", "闹钟不响？先检查这几项系统权限", onOpenReliability)
            }
        }

        // ---- ③ 登录设备 ----
        TaCard {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "登录设备（${state.sessions.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.text2,
                    )
                    Text(
                        "刷新",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.primary,
                        modifier = Modifier.clickable(enabled = state.busy == null) {
                            onRefreshSessions()
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (state.sessions.isEmpty()) {
                    Text(
                        if (state.sessionsError != null) state.sessionsError!! else "暂无其他登录设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    state.sessions.forEach { ses ->
                        SessionRow(
                            session = ses,
                            busy = state.busy == "kill-${ses.id}",
                            onKill = { onKillSession(ses.id) },
                        )
                    }
                }
            }
        }

        // ---- ④ 退出 ----
        TaCard {
            Column {
                TaButton(
                    text = "退出登录",
                    onClick = onLogout,
                    style = TaButtonStyle.Ghost,
                    enabled = state.busy == null,
                    loading = state.busy == "logout",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TaButton(
                    text = "退出所有设备",
                    onClick = onLogoutAll,
                    style = TaButtonStyle.Danger,
                    enabled = state.busy == null,
                    loading = state.busy == "logoutAll",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "退出所有设备会立即失效所有端的登录状态",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
        }

        // ---- ⑤ 应用更新（原生壳专属，浏览器版无此卡）----
        TaCard {
            Column {
                Text(
                    "应用更新",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
                updateMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2)
                }
                Spacer(Modifier.height(8.dp))
                TaButton(
                    text = "检查更新",
                    onClick = {
                        // ★ 必须在 IO 线程：UpdateManager.checkUpdate() 是**同步** OkHttp 调用，
                        // 直接在 onClick（主线程）里跑会抛 NetworkOnMainThreadException，
                        // 表现为「检查失败：网络异常」而后端根本没收到请求。
                        coroutineScope.launch {
                            // ★ 必须复用同一个 UpdateManager 实例：
                            //   startUpdate() 依赖 checkUpdate() 里缓存的 latest 版本信息。
                            //   若两处各 new 一个实例，startUpdate 会因 latest==null
                            //   直接返回 false —— 表现为「点了立即更新没反应」。
                            val um = com.trustalarm.app.update.UpdateManager(ctx)
                            val info = withContext(Dispatchers.IO) {
                                runCatching { JSONObject(um.checkUpdate()) }.getOrNull()
                            }
                            // 网络/解析失败
                            if (info == null) {
                                updateMsg = "检查更新失败"
                                return@launch
                            }
                            val err = info.optString("error")
                            if (err.isNotBlank()) {
                                updateMsg = "检查失败：$err"
                                return@launch
                            }
                            // 已是最新
                            if (!info.optBoolean("hasUpdate")) {
                                updateMsg = "已是最新版本"
                                return@launch
                            }
                            // ★ 发现新版本 → 必须有「下载」入口。
                            //   修复前这里只把文案写进 updateMsg，用户看到
                            //   「发现新版本」却没有任何按钮可点，等于检查到了却下不了
                            //   （用户反馈「检查有新版本但是不弹出下载」）。
                            val name = info.optString("versionName")
                            val notes = info.optString("notes")
                            val sizeMb = info.optInt("size", 0) / 1024.0 / 1024.0
                            updateMsg = "发现新版本 v$name（当前 v${BuildConfig.VERSION_NAME}）"
                            dialogs.confirm(
                                title = "发现新版本 v$name",
                                message = buildString {
                                    if (notes.isNotBlank()) appendLine(notes).appendLine()
                                    if (sizeMb > 0) {
                                        appendLine("安装包约 %.1f MB".format(sizeMb))
                                    }
                                    append("是否现在下载并安装？")
                                }.trim(),
                                okText = "立即更新",
                                onResult = { yes ->
                                    if (yes) {
                                        // 下载交给 DownloadManager（后台可靠、可续传）
                                        val ok = um.startUpdate()
                                        updateMsg = if (ok) {
                                            "已开始下载，完成后会拉起安装界面"
                                        } else {
                                            "启动下载失败，请稍后重试"
                                        }
                                    }
                                },
                            )
                        }
                    },
                    style = TaButtonStyle.Primary,
                )
            }
        }

        // ---- ⑥ 关于 ----
        TaCard {
            Column {
                Text(
                    "关于本客户端",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "本机为原生客户端（v${BuildConfig.VERSION_NAME}），已具备以下能力：\n" +
                        "· 静默创建手机系统闹钟\n" +
                        "· 关闭页面后仍上报在线状态\n" +
                        "· 到点全屏响铃并震动",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SessionRow(
    session: com.trustalarm.app.data.LoginSession,
    busy: Boolean,
    onKill: () -> Unit,
) {
    val c = TaTheme.colors
    val cur = session.isCurrent
    val (icon, text) = uaKind(session.userAgent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(c.bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (cur) "📲" else icon, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.text,
                )
                if (cur) {
                    Spacer(Modifier.width(6.dp))
                    TaTag("当前", TaTone.Ok)
                }
            }
            val when_ = if (session.createdAt > 0L) fmtRelative(session.createdAt) else ""
            val exp = if (session.expiresAt > 0L) fmtTime(session.expiresAt) else ""
            val sub = buildString {
                if (when_.isNotEmpty()) append("登录于 $when_")
                if (exp.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("至 $exp")
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!cur) {
            Text(
                "下线",
                style = MaterialTheme.typography.labelMedium,
                color = c.err,
                modifier = Modifier.clickable(enabled = !busy) { onKill() },
            )
        }
    }
}

/** 一行「图标 + 标题 + 说明 + ›」的入口，整行可点。 */
@Composable
private fun EntryRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val c = TaTheme.colors
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
            Text(icon, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", fontSize = 20.sp, color = c.text3)
    }
}
