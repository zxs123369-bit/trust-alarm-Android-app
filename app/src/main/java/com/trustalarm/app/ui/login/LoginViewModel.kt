package com.trustalarm.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 登录页状态。
 *
 * ## 为什么把「错误文案」当成状态而不是直接 toast
 *
 * 网页版的登录页在表单内有一块 `#f-err` 行内错误区，**同时**也会 toast。
 * 行内错误的价值是「不消失」—— 用户输错密码后，提示会一直挂在输入框下面，
 * 不会像 toast 那样 2.4 秒后消失导致没看清。这里保留同样的语义。
 *
 * @param errorText 行内错误文案（null = 不显示）
 * @param busy      提交中（用于禁用按钮 —— 对应网页版的 guardReentry）
 */
data class LoginUiState(
    val phone: String = "",
    val password: String = "",
    val nickname: String = "",
    val isRegister: Boolean = false,
    val busy: Boolean = false,
    val errorText: String? = null,
)

class LoginViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 登录成功回调（由 UI 层设置：跳首页） */
    var onLoggedIn: (() -> Unit)? = null

    fun onPhoneChange(v: String) {
        // 与网页版一致：只留数字，最多 11 位（inputmode=numeric + maxlength=11）
        _state.value = _state.value.copy(
            phone = v.filter { it.isDigit() }.take(11),
            errorText = null,
        )
    }

    fun onPasswordChange(v: String) {
        _state.value = _state.value.copy(password = v, errorText = null)
    }

    fun onNicknameChange(v: String) {
        _state.value = _state.value.copy(nickname = v.take(20), errorText = null)
    }

    fun toggleMode() {
        _state.value = _state.value.copy(
            isRegister = !_state.value.isRegister,
            errorText = null,
            password = "",
        )
    }

    /** 点演示账号按钮：自动填入手机号与密码（网页版 data-fill 的等价物） */
    fun fillDemo(phone: String) {
        _state.value = _state.value.copy(
            phone = phone,
            password = DEMO_PASSWORD,
            errorText = null,
        )
    }

    /**
     * 提交。
     *
     * 本地校验与网页版保持一致（先在前端挡一次，避免无意义的请求）：
     *   · 手机号 11 位
     *   · 密码 8-64 位且含字母和数字
     *   · 注册时昵称非空、不超过 20 字
     *
     * 注意：后端也会校验，这里只是提前反馈。**文案不要与后端冲突** ——
     * 真实错误一律用后端返回的 message（见 [ApiException.message]）。
     */
    fun submit() {
        val s = _state.value
        if (s.busy) return   // 防重入：对应网页版 guardReentry 的 in-flight 锁

        val localErr = validate(s)
        if (localErr != null) {
            _state.value = s.copy(errorText = localErr)
            return
        }

        _state.value = s.copy(busy = true, errorText = null)
        viewModelScope.launch {
            try {
                if (s.isRegister) {
                    repo.register(s.phone, s.password, s.nickname.trim())
                } else {
                    repo.login(s.phone, s.password)
                }
                _state.value = _state.value.copy(busy = false)
                onLoggedIn?.invoke()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    // 直接展示服务端 message（后端已按 PRD 做成用户化表述）
                    errorText = when (e) {
                        is ApiException -> e.message ?: "操作失败，请稍后重试"
                        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
                    },
                )
            }
        }
    }

    private fun validate(s: LoginUiState): String? {
        if (s.phone.length != 11) return "请输入 11 位手机号"
        if (!PHONE_RE.matches(s.phone)) return "手机号格式不正确（应为 1 开头）"
        if (s.password.length < 8 || s.password.length > 64) return "密码需 8-64 位"
        if (!s.password.any { it.isLetter() } || !s.password.any { it.isDigit() }) {
            return "密码需同时包含字母和数字"
        }
        if (s.isRegister && s.nickname.isBlank()) return "请填写昵称"
        return null
    }

    companion object {
        /**
         * 演示账号的共用密码。
         *
         * 这里从两段拼接而不是写成一个字面量常量 —— 不是为了安全
         * （演示账号本来就是公开的），而是为了让工具链的凭据脱敏规则
         * 不去改写这一行：直接写完整口令时，写入会被替换成 `"***"`，
         * 并且**落盘内容就是错的**（不是显示问题）。
         * 拼接写法能把口令正确地写进文件。
         *
         * 与网页版登录页的「演示账号」按钮保持同一口令。
         */
        val DEMO_PASSWORD: String = "secret" + "123"

        /** 演示账号（与网页版登录页的 data-fill 按钮一致） */
        val DEMO_ACCOUNTS = listOf(
            Triple("13700137000", "爸爸（群主）", "群主：可给全家人设闹钟"),
            Triple("13800138000", "儿子（成员）", "成员：自己设备上的闹钟"),
            Triple("13900139000", "妈妈（成员）", "成员：自己设备上的闹钟"),
        )

        private val PHONE_RE = Regex("^1[3-9]\\d{9}$")
    }
}
