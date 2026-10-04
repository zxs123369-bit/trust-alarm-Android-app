package com.trustalarm.app.ui.devicealarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.data.Alarm
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.CreateAlarmResult
import com.trustalarm.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设备闹钟列表页状态 —— 对应 `web/js/pages/device_alarms.js`。
 *
 * ## 三组分类是「用户反馈驱动」的设计，必须保留
 *
 * 网页版顶部注释写明：用户反馈「查看自己的闹钟非常杂乱」——
 * 原来所有闹钟平铺，待确认的、将来的、已过去的混在一起，看不出该关注什么。
 * 所以按「需要你动手 > 将要响 > 已过去」分三组：
 *
 * ```
 * 需要你确认   confirm_status == 0          唯一需要用户动手的
 * 将要响铃     confirm_status == 1 && 时间在未来   按时间升序（最近的在前）
 * 已过去       其余                          按时间降序（最近的在前）
 * ```
 *
 * 排序方向不同是刻意的：将要响的关注「下一个」所以升序；已过去的关注「最近一次」所以降序。
 */
data class DeviceAlarmsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val deviceName: String = "设备",
    /** 我的查看权限（后端 view_source），用于页首与空态展示 */
    val viewSource: String = "",
    val total: Int = 0,
    val needAction: List<Alarm> = emptyList(),
    val upcoming: List<Alarm> = emptyList(),
    val past: List<Alarm> = emptyList(),
    /** 正在处理的操作 uid（确认/拒绝/删除），用于禁用按钮 */
    val busy: String? = null,
)

class DeviceAlarmsViewModel(
    private val repo: Repository,
    private val deviceUid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceAlarmsUiState())
    val state: StateFlow<DeviceAlarmsUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 新建/修改后要呈现的「结果说明」（PRD §4.1 三种情况的完整文案，UI 弹窗展示） */
    private val _resultDialog = MutableStateFlow<CreateResultNotice?>(null)
    val resultDialog: StateFlow<CreateResultNotice?> = _resultDialog.asStateFlow()

    /** 顶栏「新建」信号 */
    private val _createRequests = MutableStateFlow(0)
    val createRequests: StateFlow<Int> = _createRequests.asStateFlow()
    fun requestCreate() { _createRequests.value = _createRequests.value + 1 }

    fun clearToast() { _toast.value = null }
    fun clearResultDialog() { _resultDialog.value = null }
    fun showToast(t: String) { _toast.value = t }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            // 设备名先取（取不到就用默认名，不阻塞列表）—— 对应网页版 try/catch 忽略
            val name = runCatching { repo.deviceDetail(deviceUid).deviceName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: _state.value.deviceName

            try {
                val res = repo.listAlarmsWithView(deviceUid)
                val list = res.alarms
                val now = System.currentTimeMillis()
                val needAction = list.filter { it.confirmStatus == 0 }
                val upcoming = list.filter { it.confirmStatus != 0 && it.alarmTime > now }
                    .sortedBy { it.alarmTime }
                val past = list.filter { it.confirmStatus != 0 && it.alarmTime <= now }
                    .sortedByDescending { it.alarmTime }

                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    deviceName = name,
                    viewSource = res.viewSource,
                    total = res.total,
                    needAction = needAction,
                    upcoming = upcoming,
                    past = past,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = userMessage(e),
                    deviceName = name,
                )
            }
        }
    }

    /**
     * 新建闹钟（对应网页版 `openCreateSheet` + `showCreateResult`）。
     *
     * 时间用显式时间戳（用户已在时间选择器里选好年月日时分），
     * 与网页版传 `alarm_time_ms` 一致。
     */
    fun createAlarm(
        alarmTimeMs: Long,
        date: String,
        hour: Int,
        minute: Int,
        note: String?,
    ) {
        viewModelScope.launch {
            try {
                val res = repo.createAlarmResult(
                    deviceUid = deviceUid,
                    alarmTimeMs = alarmTimeMs,
                    date = date,
                    hour = hour,
                    minute = minute,
                    note = note,
                )
                _resultDialog.value = noticeFor(res)
                load()
            } catch (e: Exception) {
                _toast.value = userMessage(e)
            }
        }
    }

    /** 修改闹钟（对应网页版 `openEditSheet`） */
    fun updateAlarm(
        alarmUid: String,
        alarmTimeMs: Long,
        date: String,
        hour: Int,
        minute: Int,
        note: String?,
    ) {
        viewModelScope.launch {
            try {
                repo.updateAlarm(
                    alarmUid = alarmUid,
                    alarmTimeMs = alarmTimeMs,
                    date = date,
                    hour = hour,
                    minute = minute,
                    note = note,
                )
                _toast.value = "已保存"
                load()
            } catch (e: Exception) {
                _toast.value = userMessage(e)
            }
        }
    }

    /** 删除（对应网页版 `doDelete`） */
    fun deleteAlarm(uid: String) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = uid)
        viewModelScope.launch {
            try {
                repo.deleteAlarm(uid)
                _state.value = _state.value.copy(busy = null)
                _toast.value = "已删除"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /** 确认 / 拒绝他人下发的闹钟（对应网页版 `doConfirm`） */
    fun confirmAlarm(uid: String, approve: Boolean) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = uid)
        viewModelScope.launch {
            try {
                repo.confirmAlarm(uid, approve)
                _state.value = _state.value.copy(busy = null)
                _toast.value = if (approve) "已同意" else "已拒绝"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = null)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 把创建结果翻成用户能看懂的说明（对应网页版 `showCreateResult` 的三分支）。
     *
     * 文案与网页版逐字对齐 —— 尤其是 `dialogText` 必须原文用服务端的，
     * 不自己另造（后端已按 PRD §4.1 定稿）。
     */
    private fun noticeFor(res: CreateAlarmResult): CreateResultNotice {
        val time = res.alarm.displayTime
        return when {
            res.needLocalConfirm -> CreateResultNotice(
                title = "已发送，需在手机上确认",
                kind = NoticeKind.Warn,
                body = "闹钟 $time 已发送到「${_state.value.deviceName}」。\n\n" +
                    "iPhone 不允许应用静默创建闹钟，需要在手机 App 内手动确认一次。\n" +
                    "（PWA 版无法完成这一步，属原生 App 的功能）",
                toast = "已发送，需在手机上确认",
            )
            res.needDialog -> CreateResultNotice(
                title = "已发送申请，等待确认",
                kind = NoticeKind.Warn,
                body = (res.dialogText ?: "已向设备主人发送确认申请") +
                    "\n\n设备主人在「消息」中同意后，该闹钟才会生效。",
                toast = "已发送申请，等待确认",
            )
            else -> CreateResultNotice(
                title = "设置成功",
                kind = NoticeKind.Ok,
                body = "闹钟 $time 已设置到「${_state.value.deviceName}」。",
                toast = "闹钟 $time 已设置到「${_state.value.deviceName}」",
            )
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}

/** 「新建」结果的提示等级（对应网页版 `kind`） */
enum class NoticeKind { Ok, Warn }

/** 新建/修改后弹窗展示的完整说明 */
data class CreateResultNotice(
    val title: String,
    val kind: NoticeKind,
    val body: String,
    /** toast 用的首行短文案（网页版 `body.split('\n')[0]`） */
    val toast: String,
)
