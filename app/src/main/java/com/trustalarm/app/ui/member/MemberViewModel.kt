package com.trustalarm.app.ui.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.MemberDetail
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 成员详情页状态 —— 对应 `web/js/pages/member.js`。
 *
 * 用户的产品要求（写在网页版注释里）：设备与成员是绑定的 ——
 * 点成员应看到「他名下的设备」，而不是「成员一列、设备一列」两个割裂列表。
 * 所以本页核心是 [MemberDetail.devices]（一人可多台），每台可点进设备详情。
 *
 * ## 双向信任（本次补齐）
 *
 * 网页版还有 `myDevicesTrustCard` / `hisDevicesTrustCard`：
 *   · 我的设备对他的信任 —— 可加入/移出信任名单、开关自动下发（我可改）
 *   · 他的设备对我的信任 —— 只读（只有他能改）
 * 本次把这两块迁完，双向语义与网页版逐字对齐。
 */
data class MemberUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val member: MemberDetail? = null,
    /** 信任操作进行中（禁用按钮防重入） */
    val busy: Boolean = false,
)

class MemberViewModel(
    private val repo: Repository,
    private val familyUid: String,
    private val memberUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MemberUiState())
    val state: StateFlow<MemberUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val m = repo.memberDetail(familyUid, memberUid)
                _state.value = MemberUiState(loading = false, error = null, member = m)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    // ---- 双向信任操作（对应网页版 bindTrustActions）----

    /** 把我的某台设备的信任名单加入该成员（对应 `trustApi.addByUid`）。 */
    fun addTrust(deviceUid: String) {
        runTrustOp { repo.addTrustMemberByUid(deviceUid, memberUid) }
    }

    /** 把我的某台设备的信任名单移出该成员（对应 `trustApi.remove`）。 */
    fun removeTrust(deviceUid: String) {
        runTrustOp { repo.removeTrustMember(deviceUid, memberUid) }
    }

    /** 开关我的某台设备的「信任自动下发」（对应 `trustApi.setSwitch`）。 */
    fun setSwitch(deviceUid: String, on: Boolean) {
        runTrustOp { repo.setTrustSwitch(deviceUid, on) }
    }

    /** 统一的信任操作执行体：置 busy → 执行 → toast → 重新拉成员详情刷新双向状态。 */
    private fun runTrustOp(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                block()
                _state.value = _state.value.copy(busy = false)
                // 操作成功后必须重载：双向信任状态在多张卡片间联动，
                // 只改本地状态容易与后端不一致（网页版也是 after() 重新 render）。
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 操作成功后由调用方设置 toast 文案（供 UI 在确认框后给出反馈）。 */
    fun notify(msg: String) { _toast.value = msg }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}

/**
 * `my_permission` → 展示文案与色调（逐字复刻 `member.js :: PERM`）。
 *
 * @return (文案, 是否用 Ok 色调强调, 说明)
 */
fun permissionText(p: String): Triple<String, Boolean, String> = when (p) {
    "owner" -> Triple("设备归属人", true, "这是你的设备")
    "family_owner" -> Triple("群主", false, "你是群主，可查看该设备")
    "trusted" -> Triple("信任成员", true, "你在此设备信任名单中")
    "granted" -> Triple("已授权查看", false, "设备主人已授权你查看")
    "alarm_creator" -> Triple("闹钟创建者", false, "你在该设备上建过闹钟")
    "need_request" -> Triple("需申请", false, "你尚未获得该设备的查看权限")
    else -> Triple("未知", false, "")
}

/** 角色 → 展示文案（对应 `member.js :: ROLE`） */
fun roleText(role: String, isOwner: Boolean): String =
    if (isOwner || role == "owner") "群主" else "成员"
