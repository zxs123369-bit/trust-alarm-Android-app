package com.trustalarm.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * 轻量导航 —— 复刻网页版的 hash 路由语义。
 *
 * ## 为什么不引 androidx.navigation:navigation-compose
 *
 * 三个理由，按重要性排：
 *
 * 1. **返回键语义已经过真机验证，不想重冒风险。**
 *    网页版的行为是「先在页面内回退，到底了才退出 App」，`WebMainActivity`
 *    里用 `webView.canGoBack()` 实现。这个行为是实机确认过的；
 *    换成 Navigation 库等于把「返回栈什么时候为空」交给另一套实现重新推一遍。
 *
 * 2. **16 个页面基本都是「进入 → 返回」，用不上官方库的核心能力。**
 *    没有深链接、没有嵌套图、没有多返回栈、没有转场动画协调需求。
 *
 * 3. **零新依赖。** 少一个库就少一处版本冲突与体积。
 *
 * ## 语义对齐清单（与 web/js/router.js 逐一对应）
 *
 * | 网页版 | 这里 | 说明 |
 * |---|---|---|
 * | `go(path)` | [NavController.navigate] | 压栈 |
 * | `go(path, {replace:true})` | [NavController.replace] | 替换栈顶（不增长） |
 * | `back()`（history.back）| [NavController.popBackStack] | 出栈 |
 * | 栈空时退出 App | `canGoBack == false` | 交给 Activity 处理 |
 *
 * **`replace` 很关键**：网页版用它做两件事，都不能变成压栈：
 *   · 启动时 `/` → `/local-alarms`
 *   · 未登录访问受保护页 → `/login`
 * 若用压栈实现，用户按返回键会回到那个「本该被跳过」的中间页，
 * 表现是「按返回反而进不去/来回弹」。
 *
 * ## 参数传递
 *
 * 网页版路径形如 `/family/:uid/member/:memberUid`。这里不做字符串解析，
 * 直接让每个 [Dest] 自带类型化参数 —— 参数错误能在编译期发现，
 * 而网页版那种「路由参数忘了带 → 落到「缺少要查看的对象」页」的坑
 * （网页版真机上出现过）在原生侧根本不会发生。
 */
class NavController(initial: Dest) {

    private val stack = mutableStateListOf(initial)

    /** 当前页（栈顶） */
    val current: Dest get() = stack.last()

    /** 返回栈深度。供 UI 判断是否显示返回箭头 */
    val depth: Int get() = stack.size

    /**
     * 是否还能返回。
     *
     * **返回 false 时，Activity 应当把返回键交给系统（退出 App）** ——
     * 这正是网页版「到底了才退出」的语义。
     */
    val canGoBack: Boolean get() = stack.size > 1

    /** 压栈（对应网页版 `go(path)`） */
    fun navigate(dest: Dest) {
        // 与网页版一致：目标就是当前页时不做任何事
        // （网页版 `if (location.hash === target) return;`）
        if (stack.last() == dest) return
        stack.add(dest)
    }

    /** 替换栈顶（对应网页版 `go(path, {replace:true})`）—— 不增长栈 */
    fun replace(dest: Dest) {
        if (stack.last() == dest) return
        stack[stack.size - 1] = dest
    }

    /**
     * 出栈（对应网页版 `history.back()`）。
     *
     * @return true=已出栈；false=栈里只剩一页，调用方应退出 App
     */
    fun popBackStack(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    /**
     * 回到指定页（若栈里有），并把它上面的页面全部丢弃。
     *
     * 用于「退出登录 → 回登录页」这种要清掉中间所有页的场景 ——
     * 比连按多次返回更符合语义，也避免用户返回时看到已失效的页面。
     */
    fun popUpTo(dest: Dest, inclusive: Boolean = false) {
        val idx = stack.indexOfLast { it == dest }
        if (idx < 0) {
            // 栈里没有目标页 → 直接替换栈顶（避免栈被清空成空栈）
            replace(dest)
            return
        }
        val from = if (inclusive) idx else idx + 1
        while (stack.size > from) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) stack.add(dest)
    }

    /** 清空栈并只留一页（退出登录时用） */
    fun resetTo(dest: Dest) {
        stack.clear()
        stack.add(dest)
    }

    /** 当前完整栈（调试/测试用） */
    fun snapshot(): List<Dest> = stack.toList()

    companion object {
        /**
         * 保存/恢复：进程被回收后返回栈应当还在。
         *
         * 存的是 [Dest.route] 字符串（与网页版的 hash 同一形态），
         * 恢复时解析回类型化对象。**解析失败时退回到 [fallback]** ——
         * 宁可回到首页，也不能崩在恢复阶段。
         */
        fun saver(fallback: Dest): Saver<NavController, List<String>> = Saver(
            save = { it.snapshot().map { d -> d.route } },
            restore = { routes ->
                val dests = routes.mapNotNull { Dest.parse(it) }
                NavController(dests.firstOrNull() ?: fallback).also { nc ->
                    dests.drop(1).forEach { nc.navigate(it) }
                }
            },
        )
    }
}

/**
 * 所有页面目的地。
 *
 * 与网页版路由表一一对应（见 `web/js/main.js` 的 `route(...)` 列表），
 * 顺序不重要 —— 这里是类型化分派，不存在网页版那种
 * 「更具体的路径必须注册在前面，否则 `:uid` 会吞掉 `alarms`」的坑。
 */
sealed class Dest {

    /** 路由字符串（唯一标识，也用于持久化恢复） */
    abstract val route: String

    // ---- 无需登录 ----
    data object Login : Dest() { override val route = "/login" }

    // ---- 主页与家庭 ----
    data object LocalAlarms : Dest() { override val route = "/local-alarms" }
    data object Families : Dest() { override val route = "/families" }
    data class Family(val uid: String) : Dest() {
        override val route get() = "/family/$uid"
    }
    data class Member(val familyUid: String, val memberUid: String) : Dest() {
        override val route get() = "/family/$familyUid/member/$memberUid"
    }
    data class FamilyLogs(val familyUid: String) : Dest() {
        override val route get() = "/family/$familyUid/logs"
    }

    // ---- 设备 ----
    data object Devices : Dest() { override val route = "/devices" }
    data class Device(val uid: String) : Dest() {
        override val route get() = "/device/$uid"
    }
    data class DeviceAlarms(val uid: String) : Dest() {
        override val route get() = "/device/$uid/alarms"
    }
    data class DeviceTrust(val uid: String) : Dest() {
        override val route get() = "/device/$uid/trust"
    }
    data class DeviceGrants(val uid: String) : Dest() {
        override val route get() = "/device/$uid/grants"
    }
    data class DeviceLogs(val uid: String) : Dest() {
        override val route get() = "/device/$uid/logs"
    }

    // ---- 其它 ----
    data object Messages : Dest() { override val route = "/messages" }
    data object Requests : Dest() { override val route = "/requests" }
    data object Logs : Dest() { override val route = "/logs" }
    data object Settings : Dest() { override val route = "/settings" }
    data object Reliability : Dest() { override val route = "/reliability" }

    companion object {
        /**
         * 从路由字符串解析回 [Dest]（用于进程恢复）。
         *
         * 与网页版路由匹配的差异：这里**不需要关心顺序**，
         * 因为每段路径的分支是显式的。返回 null 表示无法识别
         * （例如版本升级后旧路由已不存在）—— 调用方应退回首页。
         */
        fun parse(route: String): Dest? {
            val p = route.trim().trimEnd('/').ifEmpty { return null }
            val seg = p.split('/').filter { it.isNotEmpty() }
            return when {
                p == "/login" -> Login
                p == "/local-alarms" -> LocalAlarms
                p == "/families" -> Families
                p == "/devices" -> Devices
                p == "/messages" -> Messages
                p == "/requests" -> Requests
                p == "/logs" -> Logs
                p == "/settings" -> Settings
                p == "/reliability" -> Reliability

                seg.size == 2 && seg[0] == "family" -> Family(seg[1])
                seg.size == 4 && seg[0] == "family" && seg[2] == "member" ->
                    Member(seg[1], seg[3])
                seg.size == 3 && seg[0] == "family" && seg[2] == "logs" ->
                    FamilyLogs(seg[1])

                seg.size == 2 && seg[0] == "device" -> Device(seg[1])
                seg.size == 3 && seg[0] == "device" -> when (seg[2]) {
                    "alarms" -> DeviceAlarms(seg[1])
                    "trust" -> DeviceTrust(seg[1])
                    "grants" -> DeviceGrants(seg[1])
                    "logs" -> DeviceLogs(seg[1])
                    else -> null
                }
                else -> null
            }
        }
    }
}

/**
 * 记住一个 [NavController]，并在进程重建后恢复返回栈。
 *
 * @param fallback 恢复失败时的落地页（应当传首页）
 */
@Composable
fun rememberNavController(fallback: Dest = Dest.LocalAlarms): NavController =
    rememberSaveable(saver = NavController.saver(fallback)) {
        NavController(fallback)
    }

/**
 * 受登录保护的页面集合。
 *
 * 对应网页版的 `guarded(renderFn)`：未登录访问这些页面会被 `replace` 到登录页。
 * 用集合而不是逐个包装，是因为原生侧可以在一处统一判断 ——
 * 网页版那种「每个页面各自 guarded 包装」的写法容易漏掉一个。
 */
private val PROTECTED: Set<String> = setOf(
    Dest.LocalAlarms.route, Dest.Families.route, Dest.Devices.route,
    Dest.Messages.route, Dest.Requests.route, Dest.Logs.route,
    Dest.Settings.route, Dest.Reliability.route,
) // 带参数的页面（/family/:uid 等）前缀匹配，见 isProtected

fun isProtected(dest: Dest): Boolean =
    dest.route in PROTECTED ||
        dest.route.startsWith("/family/") ||
        dest.route.startsWith("/device/")
