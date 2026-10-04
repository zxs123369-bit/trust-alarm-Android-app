package com.trustalarm.app.ui.localalarms

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustalarm.app.alarm.AlarmScheduler
import com.trustalarm.app.data.Alarm
import com.trustalarm.app.data.AlarmItem
import com.trustalarm.app.data.ApiException
import com.trustalarm.app.data.Device
import com.trustalarm.app.data.Repository
import com.trustalarm.app.service.HeartbeatService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本地闹钟页状态 —— 对应 `web/js/pages/local_alarms.js`（643 行，本次迁移最重的一页）。
 *
 * ## 这一页在原生壳里做什么（与网页版的关键差异）
 *
 * 网页版的核心是「页面打开时用 Web Audio 兜底响铃」，因为它没有后台能力。
 * **原生壳里响铃由系统 AlarmManager 负责**（`AlarmScheduler` + `AlarmReceiver`
 * + `AlarmRingService`），所以本页在原生侧的职责变成：
 *
 *   1. 让用户看到**哪些闹钟在本机注册失败**（`needs_fallback`）—— 这些可能不会响
 *   2. 进入本页时**立刻触发一次同步**（体验优化 + 兜底拉起被系统回收的后台服务）
 *   3. 展示能力边界（精确闹钟权限是否已给）
 *
 * **不**在原生侧用 Web Audio 响铃 —— 系统闹钟已会响，再响一遍是重复打扰。
 * 网页版也是这么处理的（`checkDue()` 里 `if (isNativeShell) return`）。
 *
 * ## 本机设备识别
 *
 * 不再自己实现 `pickLocalDevice`（网页版那段 100 行有多级策略）。
 * 原生侧已有等价且更严谨的实现：`Repository.findMyDeviceUid()` ——
 * 它同样按「机型 → 名称 → 最近绑定 → 最近心跳」的可靠度挑选，
 * 并记录了「最近绑定 优先于 最近心跳」的原因（避免错误选择自我强化）。
 */
data class LocalAlarmsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** 我名下是否有任何设备（没有则显示「绑定这台手机」引导） */
    val hasDevice: Boolean = false,
    /** 本机设备 uid（null = 未识别出本机） */
    val deviceUid: String? = null,
    /** 本机设备名（用于「待生效闹钟」卡片标题） */
    val deviceName: String = "本机",
    /** 全部闹钟（含失败与待生效） */
    val alarms: List<Alarm> = emptyList(),
    /** 本地注册失败数量（后端 needs_fallback_count） */
    val needsFallbackCount: Int = 0,
    /** 系统是否已授予精确闹钟权限（决定是否显示「去开启」按钮） */
    val canScheduleExact: Boolean = true,
    /** 正在删除的闹钟 uid */
    val deleting: String? = null,
    /** 是否正在一键绑定本机（禁用按钮防重入） */
    val binding: Boolean = false,
)

class LocalAlarmsViewModel(
    private val repo: Repository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAlarmsUiState())
    val state: StateFlow<LocalAlarmsUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 顶栏/引导的「一键绑定这台手机」信号（递增计数，供 UI 触发）。
     *
     * 与旧实现（只跳家庭列表让用户自己找）不同：用户明确要求
     * 「点绑定就能绑上，不用输设备名，自动读取当前设备」，
     * 所以现在真的**就地完成绑定**，见 [bindThisPhoneNow]。
     */
    private val _bindRequests = MutableStateFlow(0)
    val bindRequests: StateFlow<Int> = _bindRequests.asStateFlow()
    fun requestBind() { _bindRequests.value = _bindRequests.value + 1 }

    /**
     * 一键绑定本机 —— **自动选家庭 + 自动机型命名**，全程无需用户输入。
     *
     * 流程（与旧 WebView 壳 `MainActivity.bindThisPhone` 的思路一致）：
     *   1. 取我名下的家庭；没有就**自动建一个「我的家庭」**
     *      （旧壳也是这么做的：用户第一次用不该被「先建家庭」拦住）
     *   2. 设备名自动取机型（Build.MODEL），并把它作为 model 上报
     *   3. 绑定成功 → 刷新本页（识别到本机后即显示闹钟列表与在线状态）
     *
     * 选哪个家庭：取列表第一个。因为此处入口语义是「绑本机」，
     * 用户若真有多个家庭，可去家庭详情页用「绑定这台手机」精确指定 ——
     * 但那种情况少见，不该让所有用户都先做选择。
     */
    fun bindThisPhoneNow() {
        if (_state.value.binding) return
        _state.value = _state.value.copy(binding = true)
        viewModelScope.launch {
            try {
                val families = repo.listFamilies()
                val famUid = if (families.isEmpty()) {
                    repo.createFamily("我的家庭").uid
                } else {
                    families.first().uid
                }

                val modelRaw = android.os.Build.MODEL.orEmpty().trim()
                val maker = android.os.Build.MANUFACTURER.orEmpty().trim()
                val name = when {
                    modelRaw.isNotBlank() -> modelRaw
                    maker.isNotBlank() -> "$maker 手机"
                    else -> "我的手机"
                }
                val model = "$maker $modelRaw".trim().takeIf { it.isNotBlank() }

                val d = repo.bindDevice(famUid, name, model = model)
                _state.value = _state.value.copy(binding = false)
                _toast.value = "已绑定：${d.deviceName}"
                load()   // 重新识别本机 → 拉闹钟 → 显示在线状态
            } catch (e: Exception) {
                _state.value = _state.value.copy(binding = false)
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 顶栏「＋ 设闹钟」信号（递增计数，供 chrome 动作触发打开时间选择层）。
     *
     * 为什么用「计数信号」而不是把 sheet 状态放 VM：与 FamiliesViewModel.createRequests、
     * DeviceAlarmsViewModel.createRequests 同一套模式 —— 顶栏动作在 MainComposeActivity 里，
     * 页面状态在 Screen 里，用一个只增不减的计数当「请求」传递，Screen 消费后记录已读值。
     */
    private val _createRequests = MutableStateFlow(0)
    val createRequests: StateFlow<Int> = _createRequests.asStateFlow()
    fun requestCreate() { _createRequests.value = _createRequests.value + 1 }

    fun clearToast() { _toast.value = null }

    /**
     * 加载（对应网页版 `render()`）。
     *
     * 流程照抄网页版，但「本机设备选择」交给 `findMyDeviceUid()`：
     *   1. 取我名下的设备（跨全部家庭汇总）
     *   2. 没有 → 空态（引导绑定本机）
     *   3. 有 → 识别本机 → 拉 `/local-alarms` → 原生侧立即同步
     */
    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val deviceUid = repo.findMyDeviceUid()
                if (deviceUid.isBlank()) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        hasDevice = false,
                    )
                    return@launch
                }

                val payload = repo.localAlarms(deviceUid)
                // 精确闹钟权限状态（决定能力卡片里是否提示去开启）
                val exactOk = AlarmScheduler.canScheduleExact(appContext)

                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    hasDevice = true,
                    deviceUid = payload.deviceUid.ifBlank { deviceUid },
                    // 后端没给名字时退回到设备自己的展示名
                    deviceName = payload.deviceName.ifBlank { "本机" },
                    alarms = payload.alarms,
                    needsFallbackCount = payload.needsFallbackCount,
                    canScheduleExact = exactOk,
                )

                // 原生侧：确保后台服务在跑 + 立刻同步一次 + 本地先注册一遍。
                // 对应网页版的 startHeartbeat() + syncAlarmsNow() + registerAllToSystem()。
                pullUpBackgroundAndSync()
                registerAllToSystem()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }

    /**
     * 拉起后台服务并立刻同步一次（对应 `startHeartbeat()` + `syncAlarmsNow()`）。
     *
     * HeartbeatService.start 是幂等的（已在跑则无副作用）；
     * syncNow 触发一次立即同步，让「刚进页面」就能把闹钟注册好。
     *
     * ★ 闹钟注册的正确性**不依赖本页面** —— 后台服务每分钟自己拉列表全量注册。
     *   这里只是体验优化，以及兜底把被系统回收后没恢复的服务重新拉起。
     */
    private fun pullUpBackgroundAndSync() {
        try {
            HeartbeatService.start(appContext)
            HeartbeatService.syncNow(appContext)
        } catch (_: Exception) {
            // 起不来不影响页面展示（用户至少能看到闹钟列表）
        }
    }

    /**
     * 把所有待生效闹钟注册进系统 AlarmManager（对应 `registerAllToSystem()`）。
     *
     * 只注册**未来 24 小时内**的（后端 `within_24h`）—— 过远的时间没必要占用
     * 系统资源，到点前再次进本页会补注册。这与网页版策略一致。
     *
     * 注：这只是「进页面尽快生效」的补充，权威注册仍由 HeartbeatService 全量做。
     */
    private fun registerAllToSystem() {
        val now = System.currentTimeMillis()
        _state.value.alarms.forEach { a ->
            if (a.alarmTime <= now) return@forEach
            runCatching {
                AlarmScheduler.schedule(
                    appContext,
                    AlarmItem(
                        uid = a.uid,
                        deviceUid = a.deviceUid,
                        displayTime = a.displayTime,
                        triggerAtMillis = a.alarmTime,
                        note = a.note,
                        source = a.source,
                        confirmStatus = a.confirmStatus,
                        creatorIsMe = a.creatorIsMe,
                    ),
                )
            }
        }
    }

    /**
     * 删除闹钟（对应网页版 `bindRowActions` 里的 `data-cancel` 处理）。
     *
     * 删两步：后端删除 + **本机系统闹钟取消**。
     * 漏掉第二步的后果：后端已删，但系统里那个闹钟到点还会响。
     */
    fun deleteAlarm(uid: String) {
        if (_state.value.deleting != null) return
        _state.value = _state.value.copy(deleting = uid)
        viewModelScope.launch {
            try {
                repo.deleteAlarm(uid)
                // 本机系统闹钟一并取消 —— 否则删了还会响
                runCatching { AlarmScheduler.cancel(appContext, uid) }
                _state.value = _state.value.copy(deleting = null)
                _toast.value = "已删除"
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(deleting = null)
                _toast.value = userMessage(e)
            }
        }
    }

    // ============================================================
    // 给自己设闹钟（本机设备）
    // ============================================================
    //
    // ★ 为什么加这个（用户反馈「同账号下不能直接给自己设闹钟」）：
    //
    //   后端本来就允许「家庭成员给自己设备设闹钟」—— 权限是
    //   `require_device_in_family`，自己的设备当然满足。
    //   但前端入口只挂在「设备详情 → 闹钟」上，自己给自己设要绕：
    //     设备列表 → 自己的设备 → 设备详情 → 闹钟 → 新建
    //   不直观，用户以为「不能」。
    //
    //   这里在「本地闹钟」页加直达入口：直接用本机 deviceUid 建闹钟。
    //   与「设备详情 → 闹钟」走**同一个** Repository.createAlarmResult，
    //   契约完全一致，不新增后端行为。

    /** 新建结果说明弹窗（复用设备闹钟页的 CreateResultNotice） */
    private val _resultDialog =
        MutableStateFlow<com.trustalarm.app.ui.devicealarms.CreateResultNotice?>(null)
    val resultDialog:
        StateFlow<com.trustalarm.app.ui.devicealarms.CreateResultNotice?> =
        _resultDialog.asStateFlow()

    fun clearResultDialog() { _resultDialog.value = null }

    /** 是否正在提交新建（用于禁用按钮防重入） */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    /**
     * 给本机建一个闹钟（对应「设备详情 → 闹钟 → 新建」，但设备固定为本机）。
     *
     * 本机 deviceUid 尚未识别（hasDevice=false）时不动作 —— UI 侧应隐藏该入口。
     */
    fun createAlarmForSelf(
        alarmTimeMs: Long,
        date: String,
        hour: Int,
        minute: Int,
        note: String?,
    ) {
        val deviceUid = _state.value.deviceUid
        if (deviceUid.isNullOrBlank()) {
            _toast.value = "尚未识别本机设备，请先绑定这台手机"
            return
        }
        if (_creating.value) return
        _creating.value = true
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
                _creating.value = false
                _resultDialog.value = noticeForSelf(res)
                load()
            } catch (e: Exception) {
                _creating.value = false
                _toast.value = userMessage(e)
            }
        }
    }

    /**
     * 创建结果 → 用户说明（文案与设备闹钟页逐字一致，只是设备名用本机的）。
     *
     * 不直接复用 devicealarms 的私有 noticeFor（它读那个 VM 的 state），
     * 这里按同样的三分支规则生成，保证两处文案一致。
     */
    private fun noticeForSelf(
        res: com.trustalarm.app.data.CreateAlarmResult,
    ): com.trustalarm.app.ui.devicealarms.CreateResultNotice {
        val time = res.alarm.displayTime
        val dev = _state.value.deviceName
        return when {
            res.needLocalConfirm -> com.trustalarm.app.ui.devicealarms.CreateResultNotice(
                title = "已发送，需在手机上确认",
                kind = com.trustalarm.app.ui.devicealarms.NoticeKind.Warn,
                body = "闹钟 $time 已发送到「$dev」。\n\n" +
                    "iPhone 不允许应用静默创建闹钟，需要在手机 App 内手动确认一次。",
                toast = "已发送，需在手机上确认",
            )
            res.needDialog -> com.trustalarm.app.ui.devicealarms.CreateResultNotice(
                title = "已发送申请，等待确认",
                kind = com.trustalarm.app.ui.devicealarms.NoticeKind.Warn,
                body = (res.dialogText ?: "已向设备主人发送确认申请") +
                    "\n\n设备主人在「消息」中同意后，该闹钟才会生效。",
                toast = "已发送申请，等待确认",
            )
            else -> com.trustalarm.app.ui.devicealarms.CreateResultNotice(
                title = "设置成功",
                kind = com.trustalarm.app.ui.devicealarms.NoticeKind.Ok,
                body = "闹钟 $time 已设置到「$dev」。",
                toast = "闹钟 $time 已设置到「$dev」",
            )
        }
    }

    /** 系统是否已注册某闹钟（对应网页版 `systemStateOf`，用于行内显示注册状态） */
    fun isScheduledLocally(uid: String): Boolean =
        runCatching { AlarmScheduler.isScheduled(appContext, uid) }.getOrDefault(false)

    private fun userMessage(e: Exception): String = when (e) {
        is ApiException -> e.message ?: "操作失败，请稍后重试"
        else -> "无法连接到服务器。请确认：服务已启动、手机与电脑在同一网络、电脑未休眠。"
    }
}
