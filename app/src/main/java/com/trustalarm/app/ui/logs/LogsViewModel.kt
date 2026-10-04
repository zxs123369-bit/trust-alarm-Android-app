package com.trustalarm.app.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.OpLog
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 操作日志页（设备日志 / 家庭日志）—— 对应 `web/js/pages/logs.js` 与 `family_logs.js`。
 *
 * 两页结构几乎一致（同样的动作图标表、同样的「按人/按时间」双视角），
 * 只有三个差异，用 [LogScope] 参数化，避免写两份走样的代码：
 *   1. 作用域：device_uid vs family_uid（后端 `/logs/operations` 二选一）
 *   2. 默认视角：设备日志默认「按时间」，家庭日志默认「按人」
 *      （网页版注释：家庭日志按人看更有信息量）
 *
 * ## summary 优先
 *
 * 后端在服务端拼好一句话摘要，UI **优先用 summary**，缺失才用 action +
 * 字段自拼 —— 否则 PWA 与 App 文案会漂移（见 [OpLog] 注释）。
 */
enum class LogScope { Device, Family }

/** 日志视角：按时间线 / 按操作人聚合 */
enum class LogViewMode { Time, Person }

data class LogsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** 标题副行显示的名字（设备名 / 家庭名） */
    val scopeName: String = "",
    val logs: List<OpLog> = emptyList(),
    val total: Int = 0,
    val viewMode: LogViewMode = LogViewMode.Time,
)

class LogsViewModel(
    private val repo: Repository,
    private val scope: LogScope,
    private val uid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LogsUiState(
            // 家庭日志默认按人（与网页版一致）
            viewMode = if (scope == LogScope.Family) LogViewMode.Person else LogViewMode.Time,
        ),
    )
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    /** 每页条数：设备日志 50，家庭日志 100（对齐网页版常量） */
    private val pageSize = if (scope == LogScope.Device) 50 else 100

    fun setViewMode(m: LogViewMode) {
        _state.value = _state.value.copy(viewMode = m)
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // 先取名字（取不到用默认，不阻断日志展示 —— 与网页版 catch 一致）
                val name = runCatching {
                    if (scope == LogScope.Device) {
                        repo.deviceDetail(uid).deviceName
                    } else {
                        repo.familyDetail(uid).name
                    }
                }.getOrNull().orEmpty()

                val paged = if (scope == LogScope.Device) {
                    repo.operationLogs(deviceUid = uid, limit = pageSize)
                } else {
                    repo.operationLogs(familyUid = uid, limit = pageSize)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    scopeName = name.ifBlank { "" },
                    logs = paged.list,
                    total = if (paged.total > 0) paged.total else paged.list.size,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}

/**
 * 分组结果：操作人聚合（对应网页版 groupByPerson）。
 *
 * @param nickname 操作人昵称
 * @param count    该人操作次数
 * @param kindText 动作种类统计（如「创建了闹钟 3 · 添加了信任成员 1」）
 * @param items    该人的日志（已按原顺序）
 */
data class PersonGroup(
    val nickname: String,
    val count: Int,
    val kindText: String,
    val items: List<OpLog>,
)

/** 按天分组（对应网页版 groupByDay） */
data class DayGroup(val label: String, val items: List<OpLog>)

/**
 * 动作类型 → 图标 + 中文描述（1:1 复刻网页版 ACTION_META）。
 * 未知类型回退为显示原始 action，不崩。
 */
data class ActionMeta(val icon: String, val text: String)

val ACTION_META: Map<String, ActionMeta> = mapOf(
    "alarm_create" to ActionMeta("➕", "创建了闹钟"),
    "alarm_update" to ActionMeta("✏️", "修改了闹钟"),
    "alarm_delete" to ActionMeta("🗑️", "删除了闹钟"),
    "dispatch" to ActionMeta("📤", "下发闹钟到设备"),
    "confirm" to ActionMeta("✅", "确认了闹钟"),
    "reject" to ActionMeta("🚫", "拒绝了闹钟"),
    "switch_change" to ActionMeta("🔀", "变更了信任自动下发开关"),
    "trust_add" to ActionMeta("🤝", "添加了信任成员"),
    "trust_remove" to ActionMeta("💔", "移除了信任成员"),
    "view_request" to ActionMeta("✋", "发起了查看申请"),
    "view_approve" to ActionMeta("👁️", "同意了查看申请"),
    "view_revoke" to ActionMeta("⛔", "撤销了查看授权"),
    "bind" to ActionMeta("📱", "绑定了设备"),
    "unbind" to ActionMeta("🔌", "解绑了设备"),
    "rename" to ActionMeta("🏷️", "重命名了设备"),
)

fun actionMetaOf(action: String): ActionMeta =
    ACTION_META[action] ?: ActionMeta("•", action.ifBlank { "操作" })
