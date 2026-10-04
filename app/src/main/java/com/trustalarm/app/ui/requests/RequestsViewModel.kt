package com.trustalarm.app.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Repository
import com.trustalarm.app.data.ViewRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「查看申请」页状态 —— 对应 `web/js/pages/requests.js`（P4）。
 *
 * 两个 Tab：
 *   received —— 我收到的申请（可同意/拒绝）
 *   mine     —— 我发起的申请（只读状态）
 *
 * 网页版一次性并发拉两个列表（Promise.all），本页同样。
 * 「待处理」角标只数 received 里的 pending（与网页版 `recvPending` 一致）。
 */
enum class RequestTab { Received, Mine }

data class RequestsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tab: RequestTab = RequestTab.Received,
    val received: List<ViewRequest> = emptyList(),
    val mine: List<ViewRequest> = emptyList(),
    /** 正在处理的申请 id（禁用按钮防重入） */
    val busyId: Long? = null,
)

class RequestsViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(RequestsUiState())
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun switchTab(t: RequestTab) {
        _state.value = _state.value.copy(tab = t)
    }

    /** 我收到的申请里待处理数量（用于 Tab 角标） */
    fun pendingCount(): Int = _state.value.received.count { it.status == "pending" }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // 与网页版一致：两个列表并发拉
                val recv = runCatching { repo.receivedViewRequests() }.getOrDefault(emptyList())
                val mine = runCatching { repo.myViewRequests() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    received = recv,
                    mine = mine,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 同意/拒绝申请（对应网页版 `handle(id, approve)`）。
     *
     * 确认弹窗由 UI 层负责（走 DialogHost），这里只做请求 + 刷新。
     */
    fun handle(id: Long, approve: Boolean) {
        if (_state.value.busyId != null) return
        _state.value = _state.value.copy(busyId = id)
        viewModelScope.launch {
            try {
                if (approve) repo.approveViewRequest(id) else repo.rejectViewRequest(id)
                _state.value = _state.value.copy(busyId = null)
                _toast.value = if (approve) "已同意" else "已拒绝"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyId = null)
                _toast.value = userMessage(e)
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}
