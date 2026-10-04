package com.trustalarm.app.ui.families

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Family
import com.trustalarm.app.data.Invitation
import com.trustalarm.app.data.Repository
import com.trustalarm.app.data.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 家庭列表页状态 —— 对应 `web/js/pages/families.js`。
 *
 * ## 网页版的两条关键逻辑，这里必须原样保留
 *
 * 1. **邀请的获取失败不影响主列表**。网页版是：
 *    ```
 *    families = await familyApi.list()        // 失败 → 整页错误态
 *    try { invitations = (await invitationApi.mine()).list } catch { [] }
 *    ```
 *    即：家庭列表失败 = 整页错误；邀请失败 = 静默忽略（空数组）。
 *    这个不对称是刻意的 —— 邀请接口出问题时不该让用户连自家家庭都看不到。
 *
 * 2. **邀请在列表为空时也要显示**。网页版的空态是：
 *    ```
 *    invitations.length ? inviteCard : ''
 *    ${emptyState('🏠', '还没有家庭', ...)}
 *    ```
 *    即：没有家庭 ≠ 什么都没有 —— 有人邀请你时必须让你看到，否则无从得知。
 *    空态的副文案还会随「有无邀请」变化。
 */
data class FamiliesUiState(
    val loading: Boolean = true,
    /** 加载失败的文案（null = 无错误）。取服务端 message —— 与网页版口径一致。 */
    val error: String? = null,
    val families: List<Family> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    /** 当前登录用户（用于判断「群主」标签 —— 对应网页版 `f.owner_uid === me.uid`） */
    val me: User? = null,
    /** 正在处理的邀请 uid（用于禁用该行按钮，防重复点击） */
    val busyInvitation: String? = null,
    /** 是否正在创建家庭（防重入） */
    val creating: Boolean = false,
)

class FamiliesViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(FamiliesUiState())
    val state: StateFlow<FamiliesUiState> = _state.asStateFlow()

    /** 一次性提示（成功/失败），UI 消费后置空。对应网页版的 toastOk / showError。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 创建成功后要跳转到的家庭 uid（UI 消费后置空） */
    private val _openFamily = MutableStateFlow<String?>(null)
    val openFamily: StateFlow<String?> = _openFamily.asStateFlow()

    /**
     * 「请求弹出创建家庭对话框」的信号（来自顶栏「新建」按钮）。
     *
     * 为什么用递增计数而不是 Boolean：顶栏动作由 Shell 触发，弹窗宿主在 Screen 里，
     * 两者不在同一层。用计数表达「一次请求」能避免：
     *   · Boolean 复位时机不对导致对话框反复弹出
     *   · 连续点两次「新建」第二次被吞掉
     * Screen 记录已消费到的序号，只看增量。
     */
    private val _createRequests = MutableStateFlow(0)
    val createRequests: StateFlow<Int> = _createRequests.asStateFlow()

    fun requestCreate() {
        _createRequests.value = _createRequests.value + 1
    }

    fun clearToast() { _toast.value = null }
    fun clearOpenFamily() { _openFamily.value = null }

    /** 加载（对应网页版 `render()`）。也可作为错误态的「重试」入口。 */
    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // 1. 家庭列表：失败即整页错误
                val families = repo.listFamilies()
                // 2. 我的邀请：失败静默为空（刻意的不对称，见类注释）
                val invitations = runCatching { repo.myInvitations() }.getOrDefault(emptyList())
                // 3. 当前用户：只用于「群主」标签判断，失败不影响列表
                val me = runCatching { repo.me() }.getOrNull()
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    families = families,
                    invitations = invitations,
                    me = me,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userMessage(e),
                )
            }
        }
    }

    /**
     * 创建家庭（对应网页版 `createFamily()`）。
     *
     * @param rawName 用户输入的名称。网页版在调用处校验 ≤ 20 字，
     *   这里也做同样校验并给出**与网页版一致**的文案。
     */
    fun createFamily(rawName: String) {
        if (_state.value.creating) return   // 防重入（对应 guardReentry）
        val name = rawName.trim()
        if (name.isEmpty()) return
        if (name.length > 20) {
            _toast.value = "家庭名称最多 20 个字"
            return
        }
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            try {
                val fam = repo.createFamily(name)
                _state.value = _state.value.copy(creating = false)
                _toast.value = "创建成功"
                // 对应网页版：go(`/family/${fam.uid}`) —— 建完直接进详情
                _openFamily.value = fam.uid
            } catch (e: Exception) {
                _state.value = _state.value.copy(creating = false)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 处理邀请（对应网页版 `handleInvite()`）。
     *
     * @param accept true=同意加入 false=拒绝
     */
    fun handleInvitation(invitationUid: String, accept: Boolean) {
        if (_state.value.busyInvitation != null) return
        _state.value = _state.value.copy(busyInvitation = invitationUid)
        viewModelScope.launch {
            try {
                if (accept) repo.acceptInvitation(invitationUid)
                else repo.rejectInvitation(invitationUid)
                _state.value = _state.value.copy(busyInvitation = null)
                _toast.value = if (accept) "已加入家庭" else "已拒绝"
                load()   // 对应网页版处理完 reload
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyInvitation = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 把异常转成给用户看的文案。
     *
     * 与网页版 `showError` 的规则一致：
     *   · 服务端返回的 message 直接用（后端已按 PRD 做成用户化表述）
     *   · 网络层异常（连不上/超时）用统一的排查提示
     */
    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }

    companion object {
        /** 家庭名长度上限（与网页版硬编码的 20 一致） */
        const val MAX_FAMILY_NAME = 20
    }
}
