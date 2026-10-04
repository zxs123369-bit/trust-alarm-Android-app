package com.trustalarm.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Device
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设备详情页状态 —— 对应 `web/js/pages/device.js`。
 *
 * 这一页是「设备链路」的枢纽：它同时是以下子页的父页
 *   /device/{uid}/alarms   设备闹钟（P0，已迁移）
 *   /device/{uid}/logs     设备操作日志（P2）
 *   /device/{uid}/trust    信任名单（P1）
 *   /device/{uid}/grants   查看授权（P2）
 * 在此之前这些子页在原生壳里点不到 —— 因为父页还是占位。
 *
 * ## 权限判断照抄后端，不自己重算
 *
 * 网页版注释明确：「展示控制依据后端返回的 view_source / owner_is_me，
 * 不自己重算权限规则（规则只在 app/core/permissions.py 里）」。
 * 原生侧同样只读 [Device.ownerIsMe] 与 viewSource，
 * **绝不**在客户端拼一套权限判断 —— 那会与服务端漂移。
 */
data class DeviceUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val device: Device? = null,
    /** 正在进行的操作（用于禁用按钮）："rename" / "unbind" / "request" / null */
    val busy: String? = null,
)

class DeviceViewModel(
    private val repo: Repository,
    private val deviceUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceUiState())
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 解绑成功后回退到设备列表（UI 消费后置空） */
    private val _unbound = MutableStateFlow(false)
    val unbound: StateFlow<Boolean> = _unbound.asStateFlow()

    fun clearToast() { _toast.value = null }
    fun clearUnbound() { _unbound.value = false }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val d = repo.deviceDetail(deviceUid)
                _state.value = _state.value.copy(loading = false, error = null, device = d)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 重命名设备（对应 `rename`）。
     *
     * 长度校验放在这里（与调用方无关）：网页版在 rename() 里判 `name.length > 20`，
     * 原生侧同样在 VM 内判 —— 保证无论从哪个入口（详情页按钮 / 后续设置菜单）进来
     * 都走同一条校验，不会漏。
     *
     * @return true 表示已发起重命名请求（成功与否看 toast）
     */
    fun rename(newName: String): Boolean {
        val cur = _state.value.device ?: return false
        val name = newName.trim()
        if (name.isEmpty() || name == cur.deviceName) return false
        if (name.length > 20) {
            _toast.value = "设备名称最多 20 个字"
            return false
        }
        if (_state.value.busy != null) return false
        _state.value = _state.value.copy(busy = "rename")
        viewModelScope.launch {
            try {
                val updated = repo.renameDevice(deviceUid, name)
                _state.value = _state.value.copy(busy = null, device = updated)
                _toast.value = "已保存"
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
        return true
    }

    /** 解绑设备（对应 `unbind`）。成功后置 [unbound]。 */
    fun unbind() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "unbind")
        viewModelScope.launch {
            try {
                repo.unbindDevice(deviceUid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已解绑"
                _unbound.value = true
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 申请只读查看（对应 `requestView`）。 */
    fun requestView() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "request")
        viewModelScope.launch {
            try {
                repo.requestViewAccess(deviceUid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "申请已发送，等待设备主人处理"
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}

/**
 * `view_source` → 用户能懂的说法（PRD §3.2 的六个来源）。
 * 逐字复刻 `web/js/pages/device.js :: viewSourceText`。
 */
fun viewSourceText(vs: String?): String = when (vs) {
    "owner" -> "设备归属人"
    "family_owner" -> "家庭群主"
    "trust_member" -> "信任成员"
    "granted" -> "已授权查看"
    "creator" -> "闹钟创建人"
    "none" -> "无查看权限"
    else -> vs?.takeIf { it.isNotBlank() } ?: "—"
}
