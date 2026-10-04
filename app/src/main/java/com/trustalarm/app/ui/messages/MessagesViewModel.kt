package com.trustalarm.app.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Message
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 消息页状态 —— 对应 `web/js/pages/messages.js`。
 *
 * ## 核心设计：闹钟「处理状态」与消息「已读状态」是两件事
 *
 * 网页版注释写得很明确（也是 PRD 的一致性问题）：
 * ```
 * 消息的 is_read 表示「用户看过」，与「闹钟是否已处理」是两件事。
 * 若用 is_read 控制按钮显隐，用户点一下消息（标为已读）按钮就消失了，
 * 导致该请求永远无法处理。所以必须按闹钟的真实状态判断。
 * ```
 * 因此本 VM 额外拉一次「相关设备的闹钟列表」，建 `alarmUid → confirm_status`
 * 映射，用 [isResolved] 决定是否显示同意/拒绝按钮。
 *
 * ## 拿不到状态时的保守策略
 * `alarmStates == null`（拉取失败）→ [isResolved] 返回 false → **显示按钮**。
 * 宁可多给一次按钮，也不要让用户无从操作（照抄网页版注释）。
 */
data class MessagesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val messages: List<Message> = emptyList(),
    /** 闹钟处理状态：alarmUid → confirm_status；null = 尚未/无法获取 */
    val alarmStates: Map<String, Int>? = null,
    /** 正在处理的条目：消息 uid 或 alarmUid；用于禁用按钮防重入 */
    val busy: String? = null,
) {
    val unreadCount: Int get() = messages.count { !it.isRead }
}

class MessagesViewModel(private val repo: Repository) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 「全部已读」信号（递增计数，供顶栏动作触发） */
    private val _readAllRequests = MutableStateFlow(0)
    val readAllRequests: StateFlow<Int> = _readAllRequests.asStateFlow()
    fun requestReadAll() { _readAllRequests.value = _readAllRequests.value + 1 }

    fun clearToast() { _toast.value = null }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val list = repo.listMessages()
                // 先取闹钟状态再进入完成态（与网页版顺序一致）
                val states = runCatching { loadAlarmStates(list) }.getOrNull()
                _state.value = _state.value.copy(
                    loading = false, error = null, messages = list, alarmStates = states,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 按设备分组拉闹钟，建 alarmUid → confirm_status 映射（对应 `loadAlarmStates`）。
     *
     * 按设备分组是为了**一次拉该设备全部闹钟**，避免 N 次请求。
     * 无权查看某设备时跳过（该设备的闹钟状态标为未知 → 保守显示按钮）。
     */
    private suspend fun loadAlarmStates(list: List<Message>): Map<String, Int> {
        val byDevice = mutableMapOf<String, MutableList<String>>()
        list.forEach { m ->
            val pl = m.payload ?: return@forEach
            val alarmUid = pl.optStringOrNull("alarm_uid") ?: return@forEach
            val deviceUid = pl.optStringOrNull("device_uid") ?: return@forEach
            byDevice.getOrPut(deviceUid) { mutableListOf() }.add(alarmUid)
        }
        val out = mutableMapOf<String, Int>()
        byDevice.keys.forEach { dev ->
            runCatching {
                repo.listAlarms(dev).forEach { out[it.uid] = it.confirmStatus }
            } // 失败跳过 → 该设备闹钟状态未知
        }
        return out
    }

    /**
     * 该消息对应的闹钟是否已处理（对应 `isResolved`）。
     *
     * 语义：
     *   · 家庭邀请消息 → 恒 false（状态由服务端接口保证唯一性）
     *   · 无关联闹钟   → true（无动作可做）
     *   · 状态未知     → false（保守显示按钮）
     *   · 状态为 1     → true（已确认；确认或拒绝都会置位）
     */
    fun isResolved(m: Message): Boolean {
        val pl = m.payload ?: return true
        if (pl.optStringOrNull("invitation_uid") != null) return false
        val alarmUid = pl.optStringOrNull("alarm_uid") ?: return true
        val states = _state.value.alarmStates ?: return false
        val st = states[alarmUid] ?: return false
        return st == 1
    }

    /** 点击消息正文 → 标为已读（对应 `markRead`，失败静默不影响主流程） */
    fun markRead(messageUid: String) {
        val cur = _state.value
        val m = cur.messages.find { it.uid == messageUid } ?: return
        if (m.isRead) return
        // 乐观更新：立即在本地标已读，再发请求
        _state.value = cur.copy(
            messages = cur.messages.map {
                if (it.uid == messageUid) it.copy(isRead = true) else it
            },
        )
        viewModelScope.launch {
            runCatching { repo.markMessageRead(messageUid) } // 失败静默
        }
    }

    /** 全部已读（对应顶栏「全部已读」动作） */
    fun markAllRead() {
        if (_state.value.unreadCount == 0) return
        viewModelScope.launch {
            try {
                repo.markAllMessagesRead()
                _toast.value = "已全部标为已读"
                load()
            } catch (e: Exception) {
                _toast.value = userMessage(e)
            }
        }
    }

    /** 在消息里直接同意/拒绝闹钟（对应 `act`，PRD §4.1 闭环） */
    fun confirmAlarm(alarmUid: String, approve: Boolean, messageUid: String?) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = alarmUid)
        viewModelScope.launch {
            try {
                repo.confirmAlarm(alarmUid, approve)
                _state.value = _state.value.copy(busy = null)
                _toast.value = if (approve) "已同意" else "已拒绝"
                if (messageUid != null) {
                    runCatching { repo.markMessageRead(messageUid) }
                }
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 同意/拒绝家庭邀请（对应 `handleInvite`，邀请制核心交互） */
    fun handleInvite(invitationUid: String, accept: Boolean, messageUid: String?) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = invitationUid)
        viewModelScope.launch {
            try {
                if (accept) repo.acceptInvitation(invitationUid)
                else repo.rejectInvitation(invitationUid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = if (accept) "已加入家庭" else "已拒绝"
                if (messageUid != null) {
                    runCatching { repo.markMessageRead(messageUid) }
                }
                load()
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

/** JSONObject 取可空字符串（内部工具，避免各页重复实现） */
private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

/**
 * 消息类型 → 图标 / 默认标题 / 是否有内联动作（逐字复刻 `messages.js :: typeMeta`）。
 *
 * @return (图标, 默认标题, action)  action ∈ alarm_confirm / invite_act / go_requests / ""
 */
fun messageTypeMeta(type: String?): Triple<String, String, String> = when (type) {
    "alarm_confirm" -> Triple("⏰", "闹钟确认", "alarm_confirm")
    "alarm_result" -> Triple("✅", "闹钟结果", "")
    "alarm_removed" -> Triple("🗑️", "闹钟已删除", "")
    "view_request" -> Triple("✋", "查看申请", "go_requests")
    "view_approved" -> Triple("👁️", "查看申请已同意", "")
    "view_rejected" -> Triple("🚫", "查看申请被拒绝", "")
    "view_revoked" -> Triple("⛔", "查看权限已撤销", "")
    "family_invite" -> Triple("✉️", "家庭邀请", "invite_act")
    "family_joined" -> Triple("🎉", "新成员加入", "")
    "family_invite_rejected" -> Triple("🙅", "邀请被拒绝", "")
    "family_kicked" -> Triple("👋", "已被移出家庭", "")
    "family_quit" -> Triple("🚪", "成员退出家庭", "")
    "family_dismissed" -> Triple("🏚️", "家庭已解散", "")
    "ops_dead_letter" -> Triple("⚠️", "运维告警", "")
    "trust_changed" -> Triple("🤝", "信任变更", "")
    else -> Triple("🔔", "通知", "")
}
