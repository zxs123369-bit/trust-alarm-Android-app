package com.trustalarm.app.ui.devicetrust

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Repository
import com.trustalarm.app.data.TrustMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「信任名单」页状态 —— 对应 `web/js/pages/device_trust.js`（P4）。
 *
 * ## 信任 ≠ 查看授权（PRD §4.2）
 *
 * · 信任成员：可免确认下发闹钟（需配合「信任自动下发开关」）+ 可查看闹钟
 * · 查看授权：只读查看（在「查看授权」页管理）
 * 两套体系完全独立，本页只管前者。
 *
 * ## 只有设备归属人能改
 *
 * 非归属人进本页只读展示（`isOwner=false`），与网页版一致 ——
 * 判断依据是后端返回的 `owner_is_me`，不在客户端重算权限。
 */
data class DeviceTrustUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val deviceName: String = "设备",
    /** 我是否是设备归属人（决定能否增删成员/切开关） */
    val isOwner: Boolean = false,
    /** 「信任自动下发」开关状态 */
    val switchOn: Boolean = false,
    val members: List<TrustMember> = emptyList(),
    /** 正在进行的操作，用于禁用按钮 */
    val busy: Boolean = false,
)

class DeviceTrustViewModel(
    private val repo: Repository,
    private val deviceUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceTrustUiState())
    val state: StateFlow<DeviceTrustUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val d = repo.deviceDetail(deviceUid)
                val members = repo.listTrustMembers(deviceUid)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    deviceName = d.deviceName.ifBlank { "设备" },
                    isOwner = d.ownerIsMe,
                    switchOn = d.autoAlarmTrustSwitch == 1,
                    members = members,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /** 切「信任自动下发」开关（对应网页版 toggleSwitch）。 */
    fun setSwitch(enabled: Boolean) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.setTrustSwitch(deviceUid, enabled)
                _state.value = _state.value.copy(busy = false, switchOn = enabled)
                _toast.value = if (enabled) "已开启信任自动下发" else "已关闭"
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 按手机号添加信任成员（对应网页版 addMember）。
     *
     * 手机号格式校验放在这里（与网页版一致：`/^1[3-9]\d{9}$/`），
     * 不合规直接 toast，不发请求。
     */
    fun addMember(phone: String) {
        val p = phone.trim()
        if (!Regex("^1[3-9]\\d{9}$").matches(p)) {
            _toast.value = "请输入正确的 11 位手机号"
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.addTrustMember(deviceUid, p)
                _state.value = _state.value.copy(busy = false)
                _toast.value = "已添加"
                // 重新拉列表（与网页版一致，拿服务端返回的成员）
                val members = repo.listTrustMembers(deviceUid)
                _state.value = _state.value.copy(members = members)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false)
                // 后端文案已用户化（如「该成员已在信任名单中」），直接展示
                _toast.value = userMessage(e)
            }
        }
    }

    /** 移除信任成员（对应网页版 removeMember）。 */
    fun removeMember(memberUid: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.removeTrustMember(deviceUid, memberUid)
                _state.value = _state.value.copy(
                    busy = false,
                    members = _state.value.members.filter { it.uid != memberUid },
                )
                _toast.value = "已移除"
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false)
                _toast.value = userMessage(e)
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}
