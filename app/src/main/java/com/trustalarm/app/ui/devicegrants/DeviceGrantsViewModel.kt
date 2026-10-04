package com.trustalarm.app.ui.devicegrants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Grant
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「查看授权」页状态 —— 对应 `web/js/pages/device_grants.js`（P4）。
 *
 * PRD §4.4：单独授权（≠ 信任），两套体系独立。授权是「只读查看」，
 * 不含免确认下发。申请在「查看申请」页处理，本页只管**已生效的授权**与撤销。
 * 非归属人只读。
 */
data class DeviceGrantsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val deviceName: String = "设备",
    val isOwner: Boolean = false,
    val grants: List<Grant> = emptyList(),
    val busy: Boolean = false,
)

class DeviceGrantsViewModel(
    private val repo: Repository,
    private val deviceUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceGrantsUiState())
    val state: StateFlow<DeviceGrantsUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val d = repo.deviceDetail(deviceUid)
                val grants = repo.listGrants(deviceUid)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    deviceName = d.deviceName.ifBlank { "设备" },
                    isOwner = d.ownerIsMe,
                    grants = grants,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /** 撤销授权（对应网页版 revoke）。 */
    fun revoke(memberUid: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.revokeGrant(deviceUid, memberUid)
                _state.value = _state.value.copy(
                    busy = false,
                    grants = _state.value.grants.filter { it.uid != memberUid },
                )
                _toast.value = "已撤销"
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
