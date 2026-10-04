package com.trustalarm.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.LoginSession
import com.trustalarm.app.data.Repository
import com.trustalarm.app.data.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「我的 / 设置」页状态 —— 对应 `web/js/pages/settings.js`。
 *
 * ## 加载的失败容忍度（照抄网页版 Promise.allSettled 的不对称设计）
 *
 * ```
 * meR    = authApi.me()        // 失败 → showError（主信息，缺了页面没内容）
 * famR   = familyApi.list()    // 失败 → 静默空数组
 * msgR   = messageApi.list()   // 失败 → 静默空数组（未读数=0）
 * sesR   = authApi.sessions()  // 失败 → 静默空数组（有的部署不开该接口）
 * ```
 *
 * ★ 关键：`me` 失败**不阻断页面渲染** —— 网页版是「showError + 继续画」，
 * 不是「整页错误态」。因为设置页即使拿不到资料，退出登录/会话管理仍应可用。
 * 这与家庭详情页「主体失败=整页错误」不同，不能套用同一模式。
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val me: User? = null,
    /** 家庭数量（用于「N 个家庭」标签与快捷入口副标题） */
    val familyCount: Int = 0,
    /** 未读消息数 */
    val unread: Int = 0,
    val sessions: List<LoginSession> = emptyList(),
    /** me 拉取失败的提示（非整页错误） */
    val meError: String? = null,
    /** 正在进行的操作：会话 id 的 "kill-<id>" / "logout" / "logoutAll" / "refreshSessions" / null */
    val busy: String? = null,
    /** 会话列表刷新失败提示（不影响主展示） */
    val sessionsError: String? = null,
)

class SettingsViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 登出成功信号（UI 消费后置空 → 回登录页） */
    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    fun clearToast() { _toast.value = null }
    fun clearLoggedOut() { _loggedOut.value = false }

    fun load() {
        _state.value = _state.value.copy(loading = true, meError = null, sessionsError = null)
        viewModelScope.launch {
            // 四路并发，互不阻断（对应 Promise.allSettled）
            val me = runCatching { repo.me() }.getOrNull()
            val famCount = runCatching { repo.listFamilies().size }.getOrDefault(0)
            val unread = runCatching { repo.listMessages().count { !it.isRead } }.getOrDefault(0)
            val sessions = runCatching { repo.listSessions() }.getOrNull()

            _state.value = _state.value.copy(
                loading = false,
                me = me,
                familyCount = famCount,
                unread = unread,
                sessions = sessions ?: emptyList(),
                meError = if (me == null) "无法获取账号信息，请检查网络后重试" else null,
                sessionsError = if (sessions == null) "会话列表获取失败" else null,
            )
        }
    }

    /** 刷新会话列表（对应顶栏/卡片「刷新」按钮，走 guardReentry 防重入） */
    fun refreshSessions() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "refreshSessions")
        viewModelScope.launch {
            try {
                val sessions = repo.listSessions()
                _state.value = _state.value.copy(
                    busy = null, sessions = sessions, sessionsError = null,
                )
                _toast.value = "已刷新"
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 踢掉某设备（对应 `killSession`） */
    fun killSession(sessionId: Long) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "kill-$sessionId")
        viewModelScope.launch {
            try {
                repo.revokeSession(sessionId)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已下线"
                // 重新拉列表（对应 refreshSessions）
                val sessions = runCatching { repo.listSessions() }.getOrNull()
                _state.value = _state.value.copy(sessions = sessions ?: _state.value.sessions)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 退出登录（对应 `logout`）。
     *
     * ★ 与网页版一致：**即使服务端调用失败，本地也要清干净** ——
     * Repository.logout() 的 finally 已 clearSession()，这里再兜一层 clearSession。
     * 否则会出现「点了退出但下次打开还是登录态」。
     */
    fun logout() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "logout")
        viewModelScope.launch {
            runCatching { repo.logout() }
            repo.clearSession()
            _state.value = _state.value.copy(busy = null)
            _toast.value = "已退出"
            _loggedOut.value = true
        }
    }

    /** 退出所有设备（对应 `logoutAll`） */
    fun logoutAll() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "logoutAll")
        viewModelScope.launch {
            runCatching { repo.logoutAll() }
            repo.clearSession()
            _state.value = _state.value.copy(busy = null)
            _toast.value = "已退出"
            _loggedOut.value = true
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}

/**
 * `user_agent` → 用户看得懂的说法（逐字复刻 `settings.js :: uaKind`）。
 * PRD §13：界面不出现技术标识。
 *
 * @return (图标, 文案)
 */
fun uaKind(ua: String?): Pair<String, String> {
    val s = (ua ?: "").lowercase()
    if (s.isEmpty()) return "❓" to "未知设备"
    if (s.contains("iphone") || s.contains("ipad") || s.contains("ios")) {
        return "📱" to "iPhone / iPad"
    }
    if (s.contains("android")) return "🤖" to "Android 手机"
    if (s.contains("windows")) return "💻" to "Windows 电脑"
    if (s.contains("mac os") || s.contains("macintosh")) return "💻" to "Mac 电脑"
    if (s.contains("linux")) return "💻" to "Linux 设备"
    if (s.contains("python") || s.contains("curl") || s.contains("httpx")) {
        return "🧪" to "测试客户端"
    }
    return "💻" to "其他设备"
}
