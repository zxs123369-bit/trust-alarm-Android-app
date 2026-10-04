package com.trustalarm.app.ui.shell

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.trustalarm.app.BuildConfig
import com.trustalarm.app.data.Repository
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.TaTabBar
import com.trustalarm.app.ui.components.TaTopBar
import com.trustalarm.app.ui.components.TopAction
import com.trustalarm.app.ui.families.FamiliesScreen
import com.trustalarm.app.ui.families.FamiliesViewModel
import com.trustalarm.app.ui.family.FamilyScreen
import com.trustalarm.app.ui.family.FamilyViewModel
import com.trustalarm.app.ui.devicealarms.DeviceAlarmsScreen
import com.trustalarm.app.ui.devicealarms.DeviceAlarmsViewModel
import com.trustalarm.app.ui.localalarms.LocalAlarmsScreen
import com.trustalarm.app.ui.localalarms.LocalAlarmsViewModel
import com.trustalarm.app.ui.localalarms.openExactAlarmSettings
import com.trustalarm.app.ui.login.LoginScreen
import com.trustalarm.app.ui.login.LoginViewModel
import com.trustalarm.app.ui.nav.Dest
import com.trustalarm.app.ui.nav.NavController
import com.trustalarm.app.ui.nav.isProtected
import com.trustalarm.app.ui.nav.rememberNavController
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 原生主界面（Compose 壳）。
 *
 * ## 与旧 `WebMainActivity` 的关系
 *
 * 这是 **WebView 壳的替代品**。旧壳把 UI 交给 `assets/web` 里的网页版，
 * 新壳全部用 Kotlin Compose 渲染，不再加载 WebView。
 * 两者**不共存** —— 阶段 6 会删掉旧的（见 `docs/原生重写计划-Compose.md`）。
 *
 * 后台能力（闹钟注册、心跳、开机重排）**完全不经过这个类**，
 * 仍然由 `HeartbeatService` / `AlarmScheduler` / `BootReceiver` 负责。
 * 所以**即使这个界面还没写完，闹钟的可靠性链路也是完整的** ——
 * 这是刻意的：UI 重写不该动到已经真机验证过的能力层。
 *
 * ## 返回键语义（★ 与旧壳必须一致）
 *
 * 旧壳的实现是「先 `webView.canGoBack()` 回退页面，到底了才退出 App」。
 * 这里用 [NavController.canGoBack] 表达同一语义：
 *
 * ```
 * 返回栈 > 1  → popBackStack()，留在 App 内
 * 返回栈 = 1  → 交给系统（退出 App）
 * ```
 *
 * 这个行为在旧壳上被真机验证过，所以**不换实现**，只换载体。
 *
 * ## 启动流程（对应网页版 `main.js` 的 `boot()`）
 *
 * 1. 有 refresh_token 就**静默恢复会话**（`restoreSession`）
 * 2. 拉 `/auth/me` 确认账号仍有效
 * 3. 已登录 → 首页（本地闹钟）；未登录 → 登录页
 * 4. 拉起 [com.trustalarm.app.service.HeartbeatService]（心跳 + 闹钟同步）
 *
 * 第 4 步很关键：旧壳是在 `WebView.onPageFinished` 里拉起的。
 * 现在没有 WebView，**必须显式拉起**，否则会出现「装了新壳但设备永久离线」。
 */
class MainComposeActivity : AppCompatActivity() {

    companion object {
        /** 通知权限请求码（与旧壳 MainActivity 保持一致，便于排查） */
        private const val REQ_NOTIF = 1001
    }

    private val repo by lazy { Repository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知渠道要在任何可能发通知之前建好（响铃服务依赖它）
        com.trustalarm.app.alarm.AlarmNotifier.ensureChannels(this)

        // ★ 通知权限：必须主动申请，否则闹钟响铃时的全屏通知 / 横幅 /
        //   「关闭闹钟」按钮全部不显示 —— 用户实测「响了找不到在哪关」的根因之一。
        //   旧壳（WebView MainActivity）里有这段，Compose 壳漏了，这里补上。
        //   权限对话框关闭后会通过 onRequestPermissionsResult 触发更新检查。
        requestNotificationPermission()

        setContent {
            TaTheme {
                AppRoot(repo = repo, onExitApp = { finish() })
            }
        }

        // 启动就拉起后台服务：心跳 + 闹钟同步不依赖用户点进任何页面
        // （旧壳在 onPageFinished 里做，现在没有 WebView 必须显式做）
        startHeartbeatSafely()

        // ★ 启动自动检查更新（旧 WebView 壳有、Compose 壳迁移时漏了）。
        //   没有它，用户除非手动去「我的 → 检查更新」，否则永远不知道有新版本。
        //
        //   ★ 时序：不在 onCreate 直接弹 —— 统一交给 onResume（界面已就绪）弹，
        //     避免与「通知权限对话框」叠在一起，也避免 Activity resume 切换时丢对话框。
        pendingUpdateCheck = true
    }

    /** 权限对话框是否已关闭（用于避免重复检查更新）。 */
    private var updateChecked = false

    /** 是否需要在下次 onResume 时检查更新（onCreate 与权限回调都汇到这里）。 */
    private var pendingUpdateCheck = false

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 通知权限对话框已关闭。onResume 会统一消费 pendingUpdateCheck 弹更新提示
        // （此刻直接弹 AlertDialog 有概率因 Activity resume 切换而丢失）。
        if (requestCode == REQ_NOTIF) {
            pendingUpdateCheck = !updateChecked
        }
    }

    /**
     * 启动时静默检查更新；有新版本才弹系统 AlertDialog 询问是否更新。
     *
     * 用原生 AlertDialog 而非 Compose：此刻 Compose 可能还没组合完，
     * 且这是系统级提示，用原生对话框更稳（不依赖 Compose 生命周期）。
     */
    private fun checkUpdateOnStart() {
        if (updateChecked) return
        updateChecked = true
        val activity = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // ★ 复用一个实例：startUpdate() 依赖 checkUpdate() 缓存的 latest。
            val um = com.trustalarm.app.update.UpdateManager(applicationContext)
            val info = runCatching {
                org.json.JSONObject(um.checkUpdate())
            }.getOrNull() ?: return@launch
            if (!info.optBoolean("hasUpdate")) return@launch
            val name = info.optString("versionName")
            val notes = info.optString("notes")
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("发现新版本 v$name")
                    .setMessage(
                        (if (notes.isNotBlank()) notes + "\n\n" else "") +
                            "是否立即下载安装？",
                    )
                    .setPositiveButton("立即更新") { _, _ ->
                        runCatching { um.startUpdate() }
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    /**
     * 回到前台时检查全屏通知权限并引导。
     *
     * 为什么放 onResume 而不是 onCreate：onCreate 里紧接着会弹
     * 通知权限系统对话框，此时再弹全屏设置的跳转会把两个系统界面叠在一起，
     * 体验混乱。onResume 时机更稳（且覆盖「用户从设置页返回」的场景）。
     */
    override fun onResume() {
        super.onResume()
        // ★ 更新提示统一在 onResume 弹（界面已就绪，对话框不会丢）。
        //   两条来路都汇到这里：① 没弹过权限对话框 → onCreate 置 pending；
        //   ② 弹过权限对话框 → 权限回调置 pending。
        if (pendingUpdateCheck) {
            pendingUpdateCheck = false
            checkUpdateOnStart()
        }
        // 仅当通知权限已授予时才引导全屏权限 —— 否则用户会被连弹两次系统界面
        val notifOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (notifOk) {
            ensureFullScreenIntentPermission()
        }
    }

    /**
     * 申请通知权限（Android 13+ 运行时权限）。
     *
     * 没有它：响铃服务即便起来，setFullScreenIntent / 横幅 / 关闭按钮
     * 也都会被系统静默丢掉，用户只剩「杀进程」一条路。
     *
     * @return true 表示**弹出了系统权限对话框**（调用方据此决定是否延后其它弹窗）
     */
    private fun requestNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return false
        androidx.core.app.ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF,
        )
        return true
    }

    /**
     * 全屏通知权限（Android 14+）。
     *
     * ## 为什么必须处理（用户实测「闹钟响了没有界面、关不掉」的根因）
     *
     * Android 14 (API 34) 起，`USE_FULL_SCREEN_INTENT` **不再默认放行** ——
     * 即使 manifest 里声明了，appops 也只是 `default` 且会被系统 reject。
     * 表现：响铃时 setFullScreenIntent 被忽略，只剩一条普通通知，
     * 甚至在亮屏时降级为横幅；用户找不到全屏关闭界面。
     *
     * 正解：用 `NotificationManager.canUseFullScreenIntent()` 检测，
     * 未授权则跳系统设置页 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`
     * 引导用户手动开启。这是系统闹钟类 App 的标准做法。
     *
     * @return true 表示已可用（无需引导）
     */
    private fun ensureFullScreenIntentPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return true
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return true
        if (nm.canUseFullScreenIntent()) return true
        // 未授权 → 引导（不强制，用户可拒绝；拒绝后仍有通知横幅可关）
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w("MainComposeActivity", "无法打开全屏通知设置页：${e.message}")
        }
        return false
    }

    private fun startHeartbeatSafely() {
        try {
            com.trustalarm.app.service.HeartbeatService.start(applicationContext)
        } catch (e: Exception) {
            // 起不来不能影响界面 —— 用户至少要能进 App 看到问题
            android.util.Log.w("MainComposeActivity", "拉起后台服务失败：${e.message}")
        }
    }
}

/**
 * Compose 根节点：导航宿主 + 启动时的会话恢复。
 *
 * 用 [NavController] 自身的状态驱动渲染（不引入 Navigation 库）——
 * 见 `ui/nav/Nav.kt` 里的取舍说明。
 */
@Composable
private fun AppRoot(repo: Repository, onExitApp: () -> Unit) {
    val nav = rememberNavController(fallback = Dest.LocalAlarms)
    // 启动期的会话恢复状态。null = 还在恢复中（显示 loading）
    var booted by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(repo.isLoggedIn) }

    // ---- 启动恢复（对应网页版 boot()）----
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (repo.isLoggedIn) {
            // 有 refresh_token 才尝试静默恢复；失败就清掉（避免反复重试坏会话）
            val ok = runCatching { repo.restoreSession() }.getOrDefault(false)
            if (ok) {
                // 再确认账号仍有效
                val me = runCatching { repo.me() }.getOrNull()
                if (me == null) {
                    repo.clearSession()
                    loggedIn = false
                } else {
                    loggedIn = true
                }
            } else {
                repo.clearSession()
                loggedIn = false
            }
        } else {
            loggedIn = false
        }
        booted = true

        // 落地页：已登录进首页，未登录进登录页（对应网页版 boot 末尾的判定）
        if (loggedIn) {
            nav.replace(Dest.LocalAlarms)
        } else {
            nav.replace(Dest.Login)
        }
    }

    // ---- 返回键：与旧壳一致（先回退页面，到底了才退出）----
    //
    // 用 activity-compose 自带的 BackHandler，而不是自己注册
    // OnBackPressedCallback + 查 Activity —— 后者需要 LocalActivity（1.10 才有）
    // 或 LocalOnBackPressedDispatcherOwner，都要多一层兼容代码，
    // 而 BackHandler 的语义正是我们要的：
    //   enabled = true  → 由我们处理返回键
    //   enabled = false → 交回系统（此时系统会退出 Activity）
    //
    // ★ enabled 用 canGoBack 表达「到底了才退出」：
    //   栈里还有上一页 → 我们处理（出栈）；
    //   栈里只剩一页   → 不接管，系统退出 App。
    androidx.activity.compose.BackHandler(enabled = nav.canGoBack) {
        nav.popBackStack()
    }

    // ---- 受保护页面的拦截（对应网页版 guarded()）----
    androidx.compose.runtime.LaunchedEffect(nav.current, booted, loggedIn) {
        if (!booted) return@LaunchedEffect
        if (!loggedIn && isProtected(nav.current)) {
            // 未登录访问受保护页 → 替换到登录页（不压栈）
            nav.resetTo(Dest.Login)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        // 用 Box 让「正常内容」与「响铃兜底层」可以叠加（RingingOverlay 盖在最上）
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!booted) {
                    com.trustalarm.app.ui.components.LoadingBlock("正在恢复登录状态…")
                } else {
                    ScreenHost(
                        nav = nav,
                        repo = repo,
                        loggedIn = loggedIn,
                        onLoggedIn = { loggedIn = true; nav.replace(Dest.LocalAlarms) },
                        onLoggedOut = { loggedIn = false; nav.replace(Dest.Login) },
                    )
                }
            }

            // ★ App 内响铃兜底层（最后一道保险）。
            //
            // 为什么需要：Android 14 会拦截「后台启动 Activity」（BAL_BLOCK），
            // 响铃的全屏界面可能弹不出来；若用户又关掉了通知权限/全屏通知权限，
            // 就完全找不到关闭入口（用户实测「只能杀进程」）。
            // 这个层在**用户打开 App 时**检测「有闹钟正在响」并全屏展示关闭按钮，
            // 不依赖任何后台拉起界面的能力 —— 只要人能进 App 就能关掉。
            RingingOverlay()
        }
    }
}

/**
 * App 内响铃层：轮询 [AlarmRingService.currentRingingJson]，正在响铃时全屏覆盖。
 *
 * 轮询而非事件：响铃状态写在 AlarmRingService 的静态字段里（跨进程/跨组件），
 * Compose 侧没有回调通道；2 秒轮询足够及时且开销可忽略。
 */
@Composable
private fun RingingOverlay() {
    var ringing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val obj = runCatching {
                val raw = com.trustalarm.app.alarm.AlarmRingService.currentRingingJson()
                if (raw.isBlank() || raw == "{}") null else org.json.JSONObject(raw)
            }.getOrNull()
            ringing = obj
            kotlinx.coroutines.delay(2000)
        }
    }
    val r = ringing ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val time = r.optString("displayTime").ifBlank { "--:--" }
    val note = r.optString("note")

    // 全屏覆盖：盖住所有内容，只留「关闭闹钟」
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TaTheme.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏰", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                time,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = TaTheme.colors.text,
            )
            if (note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.bodyLarge, color = TaTheme.colors.text2)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "闹钟正在响铃",
                style = MaterialTheme.typography.bodySmall,
                color = TaTheme.colors.text3,
            )
            Spacer(Modifier.height(28.dp))
            com.trustalarm.app.ui.components.TaButton(
                text = "关闭闹钟",
                onClick = { com.trustalarm.app.alarm.AlarmRingService.stop(ctx) },
                style = com.trustalarm.app.ui.components.TaButtonStyle.Danger,
                fillWidth = false,
            )
        }
    }
}

/** 页面分派。
 *
 * 阶段 2 实现了登录页；阶段 3 开始逐页迁移（当前：families）。
 * 未实现的目的地渲染 [NotYetBuilt] 占位 —— **占位而不是空屏**，
 * 空屏会让「还没做」和「做坏了」看起来一样。
 *
 * ## 顶栏/底栏由本函数统一提供
 *
 * 网页版是每个页面自己调 `setLayout({title, actions, tabbar, activeTab})`；
 * Compose 侧没有全局挂载点，改由这里根据 [Dest] 推导 chrome（标题/动作是固定的），
 * 再把内容交给各页 Screen。好处是「顶栏不属于页面内容、滚动时不跟着走」这件事
 * 只有一处实现，不会在 16 个页面里各写一遍。
 */
@Composable
private fun ScreenHost(
    nav: NavController,
    repo: Repository,
    loggedIn: Boolean,
    onLoggedIn: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val dest = nav.current

    // 登录页没有顶栏/底栏（网页版 login 页 tabbar=false 且不设 title）
    if (dest is Dest.Login) {
        val vm = androidx.lifecycle.viewmodel.compose.viewModel<LoginViewModel>(
            factory = LoginViewModelFactory(repo),
        )
        androidx.compose.runtime.LaunchedEffect(vm) { vm.onLoggedIn = onLoggedIn }
        LoginScreen(vm = vm, apiBase = BuildConfig.API_BASE)
        return
    }

    // 非登录页：顶栏 + 内容 +（可选）底栏
    Column(Modifier.fillMaxSize()) {
        // 页面对应需要跨页共享的 VM：按需在此创建，供「顶栏动作」与「页面内容」共用。
        // 网页版的顶栏动作是页面自己注册的闭包，能直接访问页面状态；
        // Compose 里把 VM 提在这里，效果等价。
        val familiesVm = if (dest is Dest.Families) {
            androidx.lifecycle.viewmodel.compose.viewModel<FamiliesViewModel>(
                factory = FamiliesViewModelFactory(repo),
            )
        } else {
            null
        }
        val familyVm = if (dest is Dest.Family) {
            androidx.lifecycle.viewmodel.compose.viewModel<FamilyViewModel>(
                key = "family-${dest.uid}",
                factory = FamilyViewModelFactory(repo, dest.uid),
            )
        } else {
            null
        }
        val localAlarmsVm = if (dest is Dest.LocalAlarms) {
            androidx.lifecycle.viewmodel.compose.viewModel<LocalAlarmsViewModel>(
                factory = LocalAlarmsViewModelFactory(repo, LocalContext.current.applicationContext),
            )
        } else {
            null
        }
        val deviceAlarmsVm = if (dest is Dest.DeviceAlarms) {
            androidx.lifecycle.viewmodel.compose.viewModel<DeviceAlarmsViewModel>(
                key = "device-alarms-${dest.uid}",
                factory = DeviceAlarmsViewModelFactory(repo, dest.uid),
            )
        } else {
            null
        }
        val deviceVm = if (dest is Dest.Device) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.device.DeviceViewModel>(
                key = "device-${dest.uid}",
                factory = DeviceViewModelFactory(repo, dest.uid),
            )
        } else {
            null
        }
        val memberVm = if (dest is Dest.Member) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.member.MemberViewModel>(
                key = "member-${dest.familyUid}-${dest.memberUid}",
                factory = MemberViewModelFactory(repo, dest.familyUid, dest.memberUid),
            )
        } else {
            null
        }
        val settingsVm = if (dest is Dest.Settings) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.settings.SettingsViewModel>(
                factory = SettingsViewModelFactory(repo),
            )
        } else {
            null
        }
        val messagesVm = if (dest is Dest.Messages) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.messages.MessagesViewModel>(
                factory = MessagesViewModelFactory(repo),
            )
        } else {
            null
        }
        val devicesVm = if (dest is Dest.Devices) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.devices.DevicesViewModel>(
                factory = DevicesViewModelFactory(repo),
            )
        } else {
            null
        }
        val requestsVm = if (dest is Dest.Requests) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.requests.RequestsViewModel>(
                factory = RequestsViewModelFactory(repo),
            )
        } else {
            null
        }
        val deviceTrustVm = if (dest is Dest.DeviceTrust) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.devicetrust.DeviceTrustViewModel>(
                key = "trust-${dest.uid}",
                factory = DeviceTrustViewModelFactory(repo, dest.uid),
            )
        } else {
            null
        }
        val deviceGrantsVm = if (dest is Dest.DeviceGrants) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.devicegrants.DeviceGrantsViewModel>(
                key = "grants-${dest.uid}",
                factory = DeviceGrantsViewModelFactory(repo, dest.uid),
            )
        } else {
            null
        }
        val deviceLogsVm = if (dest is Dest.DeviceLogs) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.logs.LogsViewModel>(
                key = "dlogs-${dest.uid}",
                factory = LogsViewModelFactory(
                    repo, com.trustalarm.app.ui.logs.LogScope.Device, dest.uid,
                ),
            )
        } else {
            null
        }
        val familyLogsVm = if (dest is Dest.FamilyLogs) {
            androidx.lifecycle.viewmodel.compose.viewModel<com.trustalarm.app.ui.logs.LogsViewModel>(
                key = "flogs-${dest.familyUid}",
                factory = LogsViewModelFactory(
                    repo, com.trustalarm.app.ui.logs.LogScope.Family, dest.familyUid,
                ),
            )
        } else {
            null
        }
        // 家庭详情页的标题是家庭名（来自数据），加载完才拿得到 —— 未加载时用通用标题
        val familyState = familyVm?.state?.collectAsState()?.value
        // 设备闹钟页标题是「<设备名> 的闹钟」（来自数据）
        val deviceAlarmsState = deviceAlarmsVm?.state?.collectAsState()?.value
        // 设备详情页标题是设备名（来自数据）
        val deviceState = deviceVm?.state?.collectAsState()?.value
        // 消息页未读数（决定顶栏「全部已读」是否显示）
        val messagesUnread = messagesVm?.state?.collectAsState()?.value?.unreadCount
        // 本地闹钟页顶栏「刷新」动作需要触发 VM 重载
        val localVmRef = localAlarmsVm
        // 本地闹钟页是否已识别本机设备（决定顶栏是否显示「＋ 设闹钟」）
        val localHasDevice = localAlarmsVm?.state?.collectAsState()?.value?.hasDevice

        val chrome = chromeFor(
            dest = dest,
            nav = nav,
            familiesVm = familiesVm,
            familyVm = familyVm,
            familyTitle = familyState?.family?.name,
            familyIsOwner = familyState?.family?.isOwner == true,
            localAlarmsVm = localVmRef,
            localAlarmsHasDevice = localHasDevice,
            deviceAlarmsVm = deviceAlarmsVm,
            deviceAlarmsTitle = deviceAlarmsState?.deviceName,
            deviceTitle = deviceState?.device?.deviceName,
            messagesVm = messagesVm,
            messagesUnread = messagesUnread,
        )
        TaTopBar(
            title = chrome.title,
            onBack = if (chrome.back) ({ nav.popBackStack(); Unit }) else null,
            actions = chrome.actions,
        )

        Box(Modifier.weight(1f)) {
            // 在 Composable 上下文里先取出 context（lambda 内不可调 LocalContext.current）
            val ctx = LocalContext.current
            when (dest) {
                is Dest.LocalAlarms -> LocalAlarmsScreen(
                    vm = localVmRef!!,
                    onGoFamilies = { nav.navigate(Dest.Families) },
                    // 「绑定这台手机」：跳到家庭列表让用户选家庭后绑定。
                    // 原生侧没有 window.prompt，绑定对话框在家庭详情页里做
                    // （与网页版「绑定新设备」同一处，见 FamilyScreen.askBindDevice）。
                    onBindThisPhone = { nav.navigate(Dest.Families) },
                    onOpenExactAlarmSettings = { openExactAlarmSettings(ctx) },
                )
                is Dest.Families -> FamiliesScreen(
                    vm = familiesVm!!,
                    onOpenFamily = { uid -> nav.navigate(Dest.Family(uid)) },
                )
                is Dest.Family -> FamilyScreen(
                    vm = familyVm!!,
                    onBack = { nav.popBackStack() },
                    onOpenMember = { mUid -> nav.navigate(Dest.Member(dest.uid, mUid)) },
                    onOpenDevice = { dUid -> nav.navigate(Dest.Device(dUid)) },
                    onOpenLogs = { nav.navigate(Dest.FamilyLogs(dest.uid)) },
                    // 退出家庭 → 回到家庭列表（对应网页版 go('/families',{replace:true})）
                    onQuitDone = { nav.resetTo(Dest.Families) },
                )
                is Dest.DeviceAlarms -> DeviceAlarmsScreen(
                    vm = deviceAlarmsVm!!,
                    onBack = { nav.popBackStack() },
                )
                is Dest.Device -> com.trustalarm.app.ui.device.DeviceScreen(
                    vm = deviceVm!!,
                    onBack = { nav.popBackStack() },
                    onOpenAlarms = { uid -> nav.navigate(Dest.DeviceAlarms(uid)) },
                    onOpenLogs = { uid -> nav.navigate(Dest.DeviceLogs(uid)) },
                    onOpenTrust = { uid -> nav.navigate(Dest.DeviceTrust(uid)) },
                    onOpenGrants = { uid -> nav.navigate(Dest.DeviceGrants(uid)) },
                    // 解绑后设备已不存在，回退到上一页（通常是家庭详情）
                    onUnbound = { nav.popBackStack() },
                )
                is Dest.Member -> com.trustalarm.app.ui.member.MemberScreen(
                    vm = memberVm!!,
                    onBack = { nav.popBackStack() },
                    onOpenDevice = { uid -> nav.navigate(Dest.Device(uid)) },
                )
                is Dest.Settings -> com.trustalarm.app.ui.settings.SettingsScreen(
                    vm = settingsVm!!,
                    onOpenFamilies = { nav.navigate(Dest.Families) },
                    onOpenDevices = { nav.navigate(Dest.Devices) },
                    onOpenMessages = { nav.navigate(Dest.Messages) },
                    onOpenRequests = { nav.navigate(Dest.Requests) },
                    onOpenReliability = { nav.navigate(Dest.Reliability) },
                    onLoggedOut = onLoggedOut,
                )
                is Dest.Messages -> com.trustalarm.app.ui.messages.MessagesScreen(
                    vm = messagesVm!!,
                    onGoRequests = { nav.navigate(Dest.Requests) },
                )
                is Dest.Devices -> com.trustalarm.app.ui.devices.DevicesScreen(
                    vm = devicesVm!!,
                    onOpenDevice = { uid -> nav.navigate(Dest.Device(uid)) },
                    onGoFamilies = { nav.navigate(Dest.Families) },
                )
                is Dest.Requests -> com.trustalarm.app.ui.requests.RequestsScreen(
                    vm = requestsVm!!,
                    onGoGrants = { uid -> nav.navigate(Dest.DeviceGrants(uid)) },
                )
                is Dest.Reliability -> com.trustalarm.app.ui.reliability.ReliabilityScreen(
                    onBack = { nav.navigate(Dest.Settings) },
                )
                is Dest.DeviceTrust -> com.trustalarm.app.ui.devicetrust.DeviceTrustScreen(
                    vm = deviceTrustVm!!,
                )
                is Dest.DeviceGrants -> com.trustalarm.app.ui.devicegrants.DeviceGrantsScreen(
                    vm = deviceGrantsVm!!,
                    onGoRequests = { nav.navigate(Dest.Requests) },
                )
                is Dest.FamilyLogs -> com.trustalarm.app.ui.logs.LogsScreen(
                    vm = familyLogsVm!!,
                    scope = com.trustalarm.app.ui.logs.LogScope.Family,
                )
                is Dest.DeviceLogs -> com.trustalarm.app.ui.logs.LogsScreen(
                    vm = deviceLogsVm!!,
                    scope = com.trustalarm.app.ui.logs.LogScope.Device,
                )
                // 裸 /logs（丢 uid 的入口，如旧链接/用户手输）：
                // 网页版这里也是「请选择要查看的设备」引导，而不是报错。
                // 操作日志按设备记录，没有 device_uid 无从展示 —— 给出去处而非空白。
                is Dest.Logs -> com.trustalarm.app.ui.components.EmptyState(
                    icon = "📄",
                    title = "请选择要查看的设备",
                    sub = "操作日志按设备记录。请从设备列表进入。",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> NotYetBuilt(dest = dest, onBack = { nav.popBackStack() })
            }
        }

        if (chrome.tabbar) {
            TaTabBar(
                activeRoute = chrome.activeRoute,
                unread = 0,   // 角标接消息页时再接入（阶段 4）
                onNavigate = { route ->
                    Dest.parse(route)?.let { nav.navigate(it) }
                },
            )
        }
    }
}

/**
 * 根据目的地推导顶栏/底栏配置。
 *
 * 对应网页版各页面 `setLayout({...})` 的参数。**标题与动作在此集中管理**，
 * 好处是「同一页面的标题」不会出现两处定义而不一致。
 *
 * @param familiesVm  家庭列表页 VM（顶栏「新建」动作需要）
 * @param familyVm    家庭详情页 VM（顶栏「加成员」动作需要，仅群主显示）
 * @param familyTitle 家庭名（加载完后才有；null 时用通用标题）
 * @param familyIsOwner 当前用户是否该家庭群主（决定「加成员」是否出现）
 */
@Composable
private fun chromeFor(
    dest: Dest,
    nav: NavController,
    familiesVm: FamiliesViewModel? = null,
    familyVm: FamilyViewModel? = null,
    familyTitle: String? = null,
    familyIsOwner: Boolean = false,
    localAlarmsVm: LocalAlarmsViewModel? = null,
    localAlarmsHasDevice: Boolean? = null,
    deviceAlarmsVm: DeviceAlarmsViewModel? = null,
    deviceAlarmsTitle: String? = null,
    deviceTitle: String? = null,
    messagesVm: com.trustalarm.app.ui.messages.MessagesViewModel? = null,
    messagesUnread: Int? = null,
): ChromeSpec = when (dest) {
    // ---- 带底栏的主 Tab 页（对应 layout.js 的 TABS）----

    // 主页：底栏高亮「本地闹钟」。
    Dest.LocalAlarms -> ChromeSpec(
        title = "本地闹钟",
        back = false,
        tabbar = true,
        activeRoute = Dest.LocalAlarms.route,
        // 顶栏动作：
        //   ·「＋ 设闹钟」：给自己的设备直接建闹钟（用户反馈「同账号下不能直接
        //     给自己设闹钟」—— 后端本就允许，只是原入口要绕设备详情好几层）。
        //     仅在已识别出本机设备时才显示（无设备时没有可下发的目标）。
        //   ·「刷新」：对应网页版 draw() 的 actions。
        // 网页版还有「通知权限」按钮 —— 原生壳里通知权限在首次运行时请求、
        // 由「可靠性引导」页统一处理，顶栏不再重复放（避免与系统弹窗语义重叠）。
        actions = buildList {
            if (localAlarmsHasDevice == true) {
                add(TopAction(text = "＋ 设闹钟", onClick = { localAlarmsVm?.requestCreate() }))
            }
            add(TopAction(text = "刷新", onClick = { localAlarmsVm?.load() }))
        },
    )

    is Dest.Families -> ChromeSpec(
        title = "家庭",
        back = false,
        tabbar = true,
        activeRoute = Dest.Families.route,
        // 顶栏「新建」：与空态里的「创建家庭」同一个动作（网页版两个入口都调 createFamily）
        actions = listOf(
            TopAction(text = "新建", onClick = { familiesVm?.requestCreate() }),
        ),
    )

    // 其余主 Tab（消息 / 我的）：底栏保留，页面待迁移
    Dest.Messages -> ChromeSpec(
        title = "消息",
        back = false,
        tabbar = true,
        activeRoute = Dest.Messages.route,
        // 有未读才显示「全部已读」（对应网页版 draw() 的 actions 条件）
        actions = if ((messagesUnread ?: 0) > 0) {
            listOf(TopAction(text = "全部已读", onClick = { messagesVm?.requestReadAll() }))
        } else {
            emptyList()
        },
    )
    // ---- 我的设备：有返回键、无底栏（从「我的」页进入）----
    is Dest.Devices -> ChromeSpec(
        title = "设备",
        back = true,
        tabbar = false,
    )

    // ---- 查看申请：有返回键、无底栏 ----
    is Dest.Requests -> ChromeSpec(
        title = "查看申请",
        back = true,
        tabbar = false,
    )

    // ---- 后台可靠性：有返回键、无底栏 ----
    is Dest.Reliability -> ChromeSpec(
        title = "后台可靠性",
        back = true,
        tabbar = false,
    )

    Dest.Settings -> ChromeSpec(
        title = "我的",
        back = false,
        tabbar = true,
        activeRoute = Dest.Settings.route,
    )

    // ---- 家庭详情：有返回键、无底栏，标题为家庭名，群主可「加成员」 ----
    is Dest.Family -> ChromeSpec(
        title = familyTitle ?: "家庭",
        back = true,
        tabbar = false,
        actions = if (familyIsOwner) {
            listOf(TopAction(text = "加成员", onClick = { familyVm?.requestInvite() }))
        } else {
            emptyList()
        },
    )

    // ---- 设备闹钟：有返回键、无底栏，标题「<设备名> 的闹钟」，顶栏「新建」 ----
    is Dest.DeviceAlarms -> ChromeSpec(
        title = "${deviceAlarmsTitle ?: "设备"} 的闹钟",
        back = true,
        tabbar = false,
        actions = listOf(
            TopAction(text = "新建", onClick = { deviceAlarmsVm?.requestCreate() }),
        ),
    )

    // ---- 设备信任名单：有返回键、无底栏 ----
    is Dest.DeviceTrust -> ChromeSpec(
        title = "信任名单",
        back = true,
        tabbar = false,
    )

    // ---- 查看授权：有返回键、无底栏 ----
    is Dest.DeviceGrants -> ChromeSpec(
        title = "查看授权",
        back = true,
        tabbar = false,
    )

    // ---- 设备日志：有返回键、无底栏 ----
    is Dest.DeviceLogs -> ChromeSpec(
        title = "设备日志",
        back = true,
        tabbar = false,
    )

    // ---- 家庭日志：有返回键、无底栏 ----
    is Dest.FamilyLogs -> ChromeSpec(
        title = "家庭日志",
        back = true,
        tabbar = false,
    )

    // ---- 裸 /logs：有返回键、无底栏 ----
    is Dest.Logs -> ChromeSpec(
        title = "操作日志",
        back = true,
        tabbar = false,
    )

    // ---- 设备详情：有返回键、无底栏，标题为设备名 ----
    // 网页版归属人还有顶栏「设置」菜单（重命名/解绑）——
    // 原生侧这两项已放在页面「设备管理」卡里，顶栏不再重复，
    // 避免同一动作两处入口造成的状态不一致。
    is Dest.Device -> ChromeSpec(
        title = deviceTitle ?: "设备",
        back = true,
        tabbar = false,
    )

    // ---- 成员详情：有返回键，无底栏（标题固定「成员详情」）----
    is Dest.Member -> ChromeSpec(
        title = "成员详情",
        back = true,
        tabbar = false,
    )

    // ---- 其余详情/次级页：有返回键，无底栏 ----
    else -> ChromeSpec(title = dest.route, back = nav.canGoBack, tabbar = false)
}

/** 页面外壳配置（内部用，最终传给 TaTopBar / TaTabBar） */
private data class ChromeSpec(
    val title: String,
    val back: Boolean = false,
    val actions: List<TopAction> = emptyList(),
    val tabbar: Boolean = false,
    val activeRoute: String? = null,
)

/** 尚未实现的页面占位 */
@Composable
private fun NotYetBuilt(dest: Dest, onBack: () -> Unit) {
    EmptyState(
        icon = "🚧",
        title = "此页面正在从网页版迁移到原生",
        sub = "路由：${dest.route}\n阶段 3-5 会补齐。返回键可退回上一页。",
        modifier = Modifier.fillMaxSize(),
    )
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        com.trustalarm.app.ui.components.TaButton(
            text = "返回",
            onClick = onBack,
            style = com.trustalarm.app.ui.components.TaButtonStyle.Ghost,
        )
    }
}

/** 手工 ViewModel 工厂（不引 Hilt，几十行的事不值得加依赖注入框架） */
private class LoginViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return LoginViewModel(repo) as T
    }
}

/** 家庭页 ViewModel 工厂（理由同上：不引 DI 框架） */
private class FamiliesViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FamiliesViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return FamiliesViewModel(repo) as T
    }
}

/** 家庭详情页 ViewModel 工厂（需要 familyUid 参数） */
private class FamilyViewModelFactory(
    private val repo: Repository,
    private val familyUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FamilyViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return FamilyViewModel(repo, familyUid) as T
    }
}

/** 本地闹钟页 ViewModel 工厂（需要 applicationContext 来调度系统闹钟） */
private class LocalAlarmsViewModelFactory(
    private val repo: Repository,
    private val appContext: android.content.Context,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LocalAlarmsViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return LocalAlarmsViewModel(repo, appContext) as T
    }
}

/** 设备闹钟页 ViewModel 工厂（需要 deviceUid 参数） */
private class DeviceAlarmsViewModelFactory(
    private val repo: Repository,
    private val deviceUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DeviceAlarmsViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return DeviceAlarmsViewModel(repo, deviceUid) as T
    }
}

/** 设备详情页 ViewModel 工厂（需要 deviceUid 参数） */
private class DeviceViewModelFactory(
    private val repo: Repository,
    private val deviceUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.device.DeviceViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.device.DeviceViewModel(repo, deviceUid) as T
    }
}

/** 成员详情页 ViewModel 工厂（需要 familyUid + memberUid 参数） */
private class MemberViewModelFactory(
    private val repo: Repository,
    private val familyUid: String,
    private val memberUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.member.MemberViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.member.MemberViewModel(repo, familyUid, memberUid) as T
    }
}

/** 设置页 ViewModel 工厂 */
private class SettingsViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.settings.SettingsViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.settings.SettingsViewModel(repo) as T
    }
}

/** 消息页 ViewModel 工厂 */
private class MessagesViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.messages.MessagesViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.messages.MessagesViewModel(repo) as T
    }
}

/** 「我的设备」页 ViewModel 工厂 */
private class DevicesViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.devices.DevicesViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.devices.DevicesViewModel(repo) as T
    }
}

/** 「查看申请」页 ViewModel 工厂 */
private class RequestsViewModelFactory(
    private val repo: Repository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(com.trustalarm.app.ui.requests.RequestsViewModel::class.java)) {
            "未知的 ViewModel：${modelClass.name}"
        }
        return com.trustalarm.app.ui.requests.RequestsViewModel(repo) as T
    }
}

/** 「信任名单」页 ViewModel 工厂（需要 deviceUid） */
private class DeviceTrustViewModelFactory(
    private val repo: Repository,
    private val deviceUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(
            modelClass.isAssignableFrom(
                com.trustalarm.app.ui.devicetrust.DeviceTrustViewModel::class.java,
            ),
        ) { "未知的 ViewModel：${modelClass.name}" }
        return com.trustalarm.app.ui.devicetrust.DeviceTrustViewModel(repo, deviceUid) as T
    }
}

/** 「查看授权」页 ViewModel 工厂（需要 deviceUid） */
private class DeviceGrantsViewModelFactory(
    private val repo: Repository,
    private val deviceUid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(
            modelClass.isAssignableFrom(
                com.trustalarm.app.ui.devicegrants.DeviceGrantsViewModel::class.java,
            ),
        ) { "未知的 ViewModel：${modelClass.name}" }
        return com.trustalarm.app.ui.devicegrants.DeviceGrantsViewModel(repo, deviceUid) as T
    }
}

/** 操作日志页 ViewModel 工厂（设备/家庭共用，靠 scope 区分） */
private class LogsViewModelFactory(
    private val repo: Repository,
    private val scope: com.trustalarm.app.ui.logs.LogScope,
    private val uid: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(
            modelClass.isAssignableFrom(com.trustalarm.app.ui.logs.LogsViewModel::class.java),
        ) { "未知的 ViewModel：${modelClass.name}" }
        return com.trustalarm.app.ui.logs.LogsViewModel(repo, scope, uid) as T
    }
}
