package com.trustalarm.app.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Device
import com.trustalarm.app.data.FamilyDetail
import com.trustalarm.app.data.FamilyInvitation
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 家庭详情页状态 —— 对应 `web/js/pages/family.js`。
 *
 * ## 加载的失败容忍度（照抄网页版的不对称设计）
 *
 * ```
 * fam         = await familyApi.detail(uid)        // 失败 → 整页错误态
 * devices     = await deviceApi.list(uid)          // 失败 → 静默空数组
 * invitations = await familyApi.listInvitations()  // 失败 → 静默空数组
 * ```
 * 只有「家庭主体」拿不到才是错误 —— 其余是锦上添花，缺了不该挡住成员列表。
 *
 * ## 设备数按 owner_uid 关联，不是按昵称
 *
 * 网页版注释写得很明确：昵称可重名、可改名，无法可靠匹配。无权查看的设备
 * `owner_uid` 为 null，自然不计入 —— 与后端脱敏口径一致。
 */
data class FamilyUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val family: FamilyDetail? = null,
    val devices: List<Device> = emptyList(),
    /** 待对方接受的邀请（仅群主可见） */
    val invitations: List<FamilyInvitation> = emptyList(),
    /** 正在忙碌的操作（用于禁用按钮）：移出成员 uid / 撤销邀请 uid / null */
    val busy: String? = null,
    /** 是否正在退出家庭 */
    val quitting: Boolean = false,
)

class FamilyViewModel(
    private val repo: Repository,
    private val familyUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 退出家庭成功后要回退到家庭列表（UI 消费后置空） */
    private val _quitDone = MutableStateFlow(false)
    val quitDone: StateFlow<Boolean> = _quitDone.asStateFlow()

    /** 绑定设备成功后要跳到该设备详情（UI 消费后置空） */
    private val _openDevice = MutableStateFlow<String?>(null)
    val openDevice: StateFlow<String?> = _openDevice.asStateFlow()

    /** 顶栏「加成员」信号（递增计数，理由同 FamiliesViewModel.createRequests） */
    private val _inviteRequests = MutableStateFlow(0)
    val inviteRequests: StateFlow<Int> = _inviteRequests.asStateFlow()
    fun requestInvite() { _inviteRequests.value = _inviteRequests.value + 1 }

    fun clearToast() { _toast.value = null }
    fun clearQuitDone() { _quitDone.value = false }
    fun clearOpenDevice() { _openDevice.value = null }

    /** 直接给定文案的提示（用于前端本地校验失败，对应网页版 showError({message})） */
    fun showToast(text: String) { _toast.value = text }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val fam = repo.familyDetail(familyUid)
                val devices = runCatching { repo.listDevices(familyUid) }.getOrDefault(emptyList())
                val invitations = runCatching { repo.listInvitations(familyUid) }
                    .getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    family = fam,
                    devices = devices,
                    invitations = invitations,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 某成员名下的设备数（对应网页版 `devCountOf`）。
     * 按 `owner_uid` 关联 —— 昵称不可靠。
     */
    fun deviceCountOf(memberUid: String): Int =
        _state.value.devices.count { it.ownerUid == memberUid }

    /** 邀请成员（对应 `addMember`）。手机号已由调用方校验格式。 */
    fun inviteMember(phone: String) {
        viewModelScope.launch {
            try {
                repo.inviteMember(familyUid, phone)
                // 网页版会把 invitee_nickname 塞进提示；原生侧 Repository 未返回该字段，
                // 用泛化文案（后端 message 已是用户化表述，这里成功路径自己拼）。
                _toast.value = "邀请已发送，等待对方同意"
                load()
            } catch (e: Exception) {
                _toast.value = userMessage(e)
            }
        }
    }

    /** 撤销邀请（对应 `cancelInvitation`） */
    fun cancelInvitation(invitationUid: String) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = invitationUid)
        viewModelScope.launch {
            try {
                repo.cancelInvitation(familyUid, invitationUid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已撤销邀请"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 移出成员（对应 `removeMember`） */
    fun removeMember(memberUid: String) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = memberUid)
        viewModelScope.launch {
            try {
                repo.removeMember(familyUid, memberUid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已移出"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 退出家庭（对应 `quitFamily`） */
    fun quitFamily() {
        if (_state.value.quitting) return
        _state.value = _state.value.copy(quitting = true)
        viewModelScope.launch {
            try {
                repo.quitFamily(familyUid)
                _state.value = _state.value.copy(quitting = false)
                _toast.value = "已退出"
                _quitDone.value = true
            } catch (e: Exception) {
                _state.value = _state.value.copy(quitting = false)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 绑定新设备（对应 `bindDevice`）。
     *
     * 与原生的差异：网页版让用户选平台（Android / iOS），原生壳**必然是 Android**，
     * 所以不提供选择 —— Repository.bindDevice 已固定 platform=android。
     *
     * ★ 必须上报真实机型（Build.MANUFACTURER + Build.MODEL）。
     *
     * 为什么（用户实测「绑了手机却一直显示离线」的根因）：
     *   「本机识别」`findMyDeviceUid()` 的第一判据就是 **机型完全一致**
     *   （`Build.MODEL == device.model`）。绑定时若不写 model，后端这条设备
     *   的 model 恒为空 → 机型匹配永远失败 → 退化成靠设备名/绑定时间猜，
     *   猜错就把心跳发给别的设备，这台真机则永远「从未心跳」→ 永久离线。
     *   之前这里传的是 `model = null`，等于亲手埋了这个坑。
     *
     * 与网页版 `MainActivity.bindThisPhone` 一致上报 `MANUFACTURER + MODEL`。
     */
    fun bindDevice(deviceName: String) {
        viewModelScope.launch {
            try {
                val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    .trim()
                    .takeIf { it.isNotBlank() }
                val d = repo.bindDevice(familyUid, deviceName, model = model)
                _toast.value = "绑定成功"
                _openDevice.value = d.uid
            } catch (e: Exception) {
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 一键绑定本机（用户要求：「点绑定设备就能绑上，不用输设备名」）。
     *
     * 与 [bindDevice] 的差别只有一处：**设备名自动生成**，不需要用户输入。
     *
     * 自动命名规则（与旧 WebView 壳 `MainActivity.bindThisPhone` 一致）：
     *   优先用机型名（Build.MODEL，如 SEA-AL10 / 24117RK2CC），
     *   为空则退到 MANUFACTURER，再为空兜底「我的手机」。
     *
     * 为什么用机型当名字：用户看到「SEA-AL10」能认出是自己的哪台手机；
     * 而固定叫「我的手机」时，账号下多台设备会重名，反而更难分辨
     *   （历史实测：多台都叫「我的手机」，本机识别与用户认知都混乱）。
     *
     * 机型同时作为 `model` 上报 —— 这是「本机识别」的第一判据，
     * 缺了它会退化成靠猜，进而心跳发错设备、永久显示离线。
     */
    fun bindThisPhone() {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = "bind")
        viewModelScope.launch {
            try {
                val modelRaw = android.os.Build.MODEL.orEmpty().trim()
                val maker = android.os.Build.MANUFACTURER.orEmpty().trim()
                val defaultName = when {
                    modelRaw.isNotBlank() -> modelRaw
                    maker.isNotBlank() -> "$maker 手机"
                    else -> "我的手机"
                }
                val model = "$maker $modelRaw".trim().takeIf { it.isNotBlank() }
                val d = repo.bindDevice(familyUid, defaultName, model = model)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已绑定：${d.deviceName}"
                _openDevice.value = d.uid
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
