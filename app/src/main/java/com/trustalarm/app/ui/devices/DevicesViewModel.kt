package com.trustalarm.app.ui.devices

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
 * 「我的设备」页状态 —— 对应 `web/js/pages/devices.js`。
 *
 * ## 数据来源（1:1 平移网页版）
 *
 * 后端 `/devices` 需要 family_uid 参数，没有「跨家庭一次拉全」的接口。
 * 网页版的做法是：先拉家庭列表，再 `Promise.all` 并发逐个家庭拉设备，
 * 最后把设备带着家庭名摊平成一个列表。这里照抄同一策略。
 *
 * 失败语义与网页版一致：**单个家庭拉设备失败不整体失败**，只是那台家庭
 * 贡献 0 台设备（catch 里返回空数组）。只有「拉家庭列表」失败才显示错误页 ——
 * 因为连家庭都拿不到时，页面没有可展示的东西。
 */
data class DevicesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** 全部设备（跨家庭，带家庭名） */
    val devices: List<DeviceRow> = emptyList(),
    /** 我名下是否有任何设备（对应网页版 devices.length === 0 分支） */
    val hasAny: Boolean = false,
    /** 用户是否至少有一个家庭（决定空态引导文案：去家庭绑定 / 创建家庭） */
    val hasFamily: Boolean = false,
)

/**
 * 一台设备 + 它所属家庭的名字。
 *
 * 为什么需要这层：后端设备对象本身不带家庭名（`/devices?family_uid=` 只知道这一台
 * 属于被查询的那个家庭）。网页版用 `{...d, _familyName: f.name}` 摊平带上家庭名，
 * 原生侧不能改 [Device] 数据类（它是接口映射，加字段会与 JSON 解析责任混淆），
 * 所以在这里做一层轻量包装。
 */
data class DeviceRow(
    val device: Device,
    val familyName: String,
)

class DevicesViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val families = repo.listFamilies()
                // 并发拉各家庭设备；单个失败不影响整体（与网页版 catch 返回 [] 一致）
                val perFamily = families.map { f ->
                    runCatching { repo.listDevices(f.uid) }
                        .getOrDefault(emptyList())
                        .map { d -> DeviceRow(d, f.name) }
                }
                val all = perFamily.flatten()
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    devices = all,
                    hasAny = all.isNotEmpty(),
                    hasFamily = families.isNotEmpty(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 我拥有的设备排前面（对应网页版 `devices.sort((a,b) => b.owner_is_me - a.owner_is_me)`）。
     * 用稳定排序，保持同组内原有顺序。
     */
    fun sortedDevices(): List<DeviceRow> =
        _state.value.devices.sortedByDescending { if (it.device.ownerIsMe) 1 else 0 }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}
