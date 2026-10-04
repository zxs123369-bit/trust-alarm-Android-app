package com.trustalarm.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 业务仓储层：把 [ApiClient] 的原始 JSON 调用，转成**强类型的按域接口**。
 *
 * ## 为什么需要这一层（而不是 UI 直接调 ApiClient）
 *
 * 现在 UI 层要从 JS 迁到 Kotlin。JS 侧原本有 `api.js` 里的 12 个域封装
 * （authApi / familyApi / deviceApi / alarmApi / trustApi / grantApi /
 * messageApi / logApi …），UI 只调这些语义化方法，不碰 URL 与字段名。
 *
 * 如果 Compose 页面直接写 `client.get("/devices/$uid/alarms")`，
 * 那么 URL 拼装会散落到十几个页面里 —— 后端一改路径就要全项目搜索替换，
 * 且字段名解析（`optString("display_time")`）也会重复实现。
 *
 * 所以保留 JS 的分层习惯：**UI 只调语义方法，URL 与字段名只在仓储层出现**。
 * 这样也保证重写前后「调了哪些接口」是可逐条对照的 —— 迁移漏接口会立刻暴露。
 *
 * ## 异常约定
 *
 * 所有方法抛 [ApiException]（网络失败、业务码非 0、401 刷新失败都在内）。
 * UI 层的 ViewModel 负责捕获并转成错误态 —— 仓储层不吞异常，
 * 因为「静默失败」在这个项目里造成过最贵的 bug（闹钟不注册却无任何日志）。
 */
class Repository(context: Context) {

    companion object {
        private const val TAG = "Repository"

        @Volatile
        private var instance: Repository? = null

        /** 单例：OkHttp 连接池与令牌状态都该全局共享 */
        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }

    private val client = ApiClient(context)

    // ============================================================
    // 会话
    // ============================================================

    val isLoggedIn: Boolean get() = client.isLoggedIn
    val hasValidAccess: Boolean get() = client.hasValidAccess()

    fun currentNickname(): String = client.currentNickname()
    fun currentUserUid(): String = client.currentUserUid()

    /** 登录；成功后令牌已落盘 */
    suspend fun login(phone: String, password: String): TokenPayload = withContext(Dispatchers.IO) {
        val data = client.post("/auth/login", JSONObject()
            .put("phone", phone)
            .put("password", password), needAuth = false)
        val payload = TokenPayload.fromJson(data)
        client.adoptTokens(payload.accessToken, payload.refreshToken)
        client.saveUser(
            uid = payload.user?.uid ?: "",
            nickname = payload.user?.nickname ?: "",
            phone = phone,
        )
        payload
    }

    suspend fun register(phone: String, password: String, nickname: String): TokenPayload =
        withContext(Dispatchers.IO) {
            val data = client.post("/auth/register", JSONObject()
                .put("phone", phone)
                .put("password", password)
                .put("nickname", nickname), needAuth = false)
            val payload = TokenPayload.fromJson(data)
            client.adoptTokens(payload.accessToken, payload.refreshToken)
            client.saveUser(
                uid = payload.user?.uid ?: "",
                nickname = payload.user?.nickname ?: nickname,
                phone = phone,
            )
            payload
        }

    /** 启动/切回前台时静默恢复会话（用 refresh 换 access） */
    suspend fun restoreSession(): Boolean = client.restoreSession()

    /** 当前登录用户资料 */
    suspend fun me(): User = withContext(Dispatchers.IO) {
        User.fromJson(client.get("/auth/me"))
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val rt = client.getRefreshToken()
        try {
            client.post("/auth/logout",
                if (rt != null) JSONObject().put("refresh_token", rt) else JSONObject())
        } finally {
            client.clearSession()
        }
    }

    suspend fun logoutAll() = withContext(Dispatchers.IO) {
        try {
            client.post("/auth/logout-all", JSONObject())
        } finally {
            client.clearSession()
        }
    }

    /** 我的登录会话列表（可在设置页踢掉其它设备） */
    suspend fun listSessions(): List<LoginSession> = withContext(Dispatchers.IO) {
        val data = client.get("/auth/sessions" + client.refreshTokenQuery())
        unwrapList(data).list.map { LoginSession.fromJson(it) }
    }

    suspend fun revokeSession(sessionId: Long) = withContext(Dispatchers.IO) {
        client.delete("/auth/sessions/$sessionId")
    }

    suspend fun forgotPassword(phone: String) = withContext(Dispatchers.IO) {
        client.post("/auth/password/forgot", JSONObject().put("phone", phone), needAuth = false)
    }

    suspend fun resetPassword(resetToken: String, newPassword: String) = withContext(Dispatchers.IO) {
        client.post("/auth/password/reset", JSONObject()
            .put("reset_token", resetToken)
            .put("new_password", newPassword), needAuth = false)
    }

    fun clearSession() = client.clearSession()

    // ============================================================
    // 家庭
    // ============================================================

    suspend fun listFamilies(): List<Family> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/families")).list.map { Family.fromJson(it) }
    }

    suspend fun createFamily(name: String): Family = withContext(Dispatchers.IO) {
        Family.fromJson(client.post("/families", JSONObject().put("name", name)))
    }

    suspend fun familyDetail(familyUid: String): FamilyDetail = withContext(Dispatchers.IO) {
        FamilyDetail.fromJson(client.get("/families/$familyUid"))
    }

    /** 邀请制：按手机号发出邀请（不是直接加人） */
    suspend fun inviteMember(familyUid: String, phone: String) = withContext(Dispatchers.IO) {
        client.post("/families/$familyUid/members", JSONObject().put("phone", phone))
    }

    /** 该家庭「待对方接受」的邀请 —— 邀请方（群主）视角，字段是 invitee_* */
    suspend fun listInvitations(familyUid: String): List<FamilyInvitation> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/families/$familyUid/invitations"))
            .list.map { FamilyInvitation.fromJson(it) }
    }

    suspend fun cancelInvitation(familyUid: String, invitationUid: String) = withContext(Dispatchers.IO) {
        client.delete("/families/$familyUid/invitations/$invitationUid")
    }

    suspend fun removeMember(familyUid: String, memberUid: String) = withContext(Dispatchers.IO) {
        client.delete("/families/$familyUid/members/$memberUid")
    }

    suspend fun quitFamily(familyUid: String) = withContext(Dispatchers.IO) {
        client.post("/families/$familyUid/quit", JSONObject())
    }

    /** 成员详情（成员详情页用，含名下设备与权限标签） */
    suspend fun memberDetail(familyUid: String, memberUid: String): MemberDetail =
        withContext(Dispatchers.IO) {
            MemberDetail.fromJson(client.get("/families/$familyUid/members/$memberUid"))
        }

    // ---- 我收到的邀请（被邀请方）----

    suspend fun myInvitations(): List<Invitation> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/invitations/mine")).list
            .map { Invitation.fromJson(it) }
    }

    suspend fun acceptInvitation(invitationUid: String) = withContext(Dispatchers.IO) {
        client.post("/invitations/$invitationUid/accept", JSONObject())
    }

    suspend fun rejectInvitation(invitationUid: String) = withContext(Dispatchers.IO) {
        client.post("/invitations/$invitationUid/reject", JSONObject())
    }

    // ============================================================
    // 设备
    // ============================================================

    suspend fun listDevices(familyUid: String): List<Device> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/devices?family_uid=$familyUid")).list
            .map { Device.fromJson(it) }
    }

    suspend fun deviceDetail(deviceUid: String): Device = withContext(Dispatchers.IO) {
        Device.fromJson(client.get("/devices/$deviceUid"))
    }

    /** 绑定本机为新设备 */
    suspend fun bindDevice(
        familyUid: String,
        deviceName: String,
        model: String?,
    ): Device = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("family_uid", familyUid)
            .put("device_name", deviceName)
            .put("platform", "android")
            .put("app_version", com.trustalarm.app.BuildConfig.VERSION_NAME)
        model?.takeIf { it.isNotBlank() }?.let { body.put("model", it) }
        Device.fromJson(client.post("/devices/bind", body))
    }

    suspend fun renameDevice(deviceUid: String, newName: String): Device = withContext(Dispatchers.IO) {
        Device.fromJson(client.patch("/devices/$deviceUid",
            JSONObject().put("device_name", newName)))
    }

    suspend fun unbindDevice(deviceUid: String) = withContext(Dispatchers.IO) {
        client.post("/devices/$deviceUid/unbind", JSONObject())
    }

    /** 心跳上报（让家人看到本机在线） */
    suspend fun heartbeat(deviceUid: String) = withContext(Dispatchers.IO) {
        client.post("/devices/$deviceUid/heartbeat", JSONObject()
            .put("app_version", com.trustalarm.app.BuildConfig.VERSION_NAME)
            .put("model", android.os.Build.MODEL.orEmpty()))
    }

    /**
     * 查出「本机」的设备 uid。
     *
     * 这段逻辑保留在 [ApiClient]（它有详细的踩坑记录与分级匹配策略）——
     * 那里是**唯一权威实现**，仓储层只做转发，避免出现第二份选设备的逻辑
     * （历史教训：`syncAlarms()` 里曾复制过一份，会覆盖主循环的正确结果）。
     */
    suspend fun findMyDeviceUid(): String = client.findMyDeviceUid()

    /** 校验某 device_uid 是否属于当前账号（见 ApiClient 的长注释） */
    suspend fun isMyDevice(deviceUid: String): Boolean? = client.isMyDevice(deviceUid)

    // ============================================================
    // 闹钟
    // ============================================================

    suspend fun listAlarms(deviceUid: String): List<Alarm> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/devices/$deviceUid/alarms")).list
            .map { Alarm.fromJson(it) }
    }

    /**
     * 设备闹钟列表 —— 连同 `view_source` 一起返回。
     *
     * 后端 `GET /devices/{uid}/alarms` 返回 `{list, total, view_source}`，
     * `view_source` 描述「我凭什么能看这台设备的闹钟」（设备归属人 / 家庭群主 /
     * 信任成员 / 已授权 / 闹钟创建人 / 无权限），设备闹钟页要在页首和空态展示它 ——
     * 否则用户会困惑「为什么我能看到/看不到这台设备的闹钟」。
     */
    suspend fun listAlarmsWithView(deviceUid: String): AlarmListResult =
        withContext(Dispatchers.IO) {
            val root = client.get("/devices/$deviceUid/alarms")
            AlarmListResult(
                alarms = parseArray(root.optJSONArray("list")) { Alarm.fromJson(it) },
                total = root.optInt("total", 0),
                viewSource = root.optString("view_source"),
            )
        }

    /**
     * 创建闹钟 —— 返回**完整结果**（不只是闹钟本身）。
     *
     * ## 为什么不直接返回 [Alarm]
     *
     * 后端 `POST /devices/{uid}/alarms` 返回的是：
     * ```
     * { alarm: {...}, need_dialog: bool, need_local_confirm: bool, dialog_text: str }
     * ```
     * 后三个字段描述「这次创建的结果属于三种情况中的哪一种」（PRD §4.1）：
     *   · 免确认成功（本人 / 已开启信任的 Android 信任成员）
     *   · 需对方确认（`need_dialog`）→ 必须把 `dialog_text` 原文给用户看
     *   · iOS 需 App 内确认（`need_local_confirm`）
     *
     * 若只解析 `alarm`，用户端会以为「设置成功」—— 而实际上对方还没同意，
     * 闹钟根本不会响。这是会误导用户的错误，所以必须把三个字段带出来。
     */
    suspend fun createAlarmResult(
        deviceUid: String,
        alarmTimeMs: Long? = null,
        date: String? = null,
        hour: Int? = null,
        minute: Int? = null,
        note: String? = null,
    ): CreateAlarmResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
        alarmTimeMs?.let { body.put("alarm_time_ms", it) }
        date?.let { body.put("date", it) }
        hour?.let { body.put("hour", it) }
        minute?.let { body.put("minute", it) }
        body.put("note", note ?: JSONObject.NULL)
        CreateAlarmResult.fromJson(client.post("/devices/$deviceUid/alarms", body))
    }

    /**
     * 创建闹钟。时间有三种写法，与后端 `AlarmCreateIn` 一一对应：
     *   · `alarmTimeMs`           显式时间戳（用户选了具体年月日时）
     *   · `date` + `hour`/`minute` 指定日期
     *   · `hour`/`minute`          今天的 HH:MM，已过则后端顺延
     *
     * 注：这是「只要闹钟对象」的简化版，内部走 [createAlarmResult]。
     * 新建流程请用 [createAlarmResult] 以拿到 need_dialog 等结果字段。
     */
    suspend fun createAlarm(
        deviceUid: String,
        alarmTimeMs: Long? = null,
        date: String? = null,
        hour: Int? = null,
        minute: Int? = null,
        note: String? = null,
    ): Alarm = createAlarmResult(deviceUid, alarmTimeMs, date, hour, minute, note).alarm

    /** 修改闹钟（只改传入的字段） */
    suspend fun updateAlarm(
        alarmUid: String,
        alarmTimeMs: Long? = null,
        date: String? = null,
        hour: Int? = null,
        minute: Int? = null,
        note: String? = null,
        clearNote: Boolean = false,
    ): Alarm = withContext(Dispatchers.IO) {
        val body = JSONObject()
        alarmTimeMs?.let { body.put("alarm_time_ms", it) }
        date?.let { body.put("date", it) }
        hour?.let { body.put("hour", it) }
        minute?.let { body.put("minute", it) }
        // note 显式传入才改；clearNote 用于「清空备注」这个独立语义
        if (clearNote) body.put("note", JSONObject.NULL)
        else note?.let { body.put("note", it) }
        Alarm.fromJson(client.patch("/alarms/$alarmUid", body))
    }

    suspend fun deleteAlarm(alarmUid: String) = withContext(Dispatchers.IO) {
        client.delete("/alarms/$alarmUid")
    }

    /** 家人下发闹钟后本机确认/拒绝 */
    suspend fun confirmAlarm(alarmUid: String, approve: Boolean) = withContext(Dispatchers.IO) {
        client.post("/alarms/$alarmUid/confirm", JSONObject().put("approve", approve))
    }

    /** 下发任务详情（失败重试用） */
    suspend fun dispatchTask(alarmUid: String): JSONObject = withContext(Dispatchers.IO) {
        client.get("/alarms/$alarmUid/dispatch-task")
    }

    /**
     * 「本地闹钟」页的聚合接口。
     *
     * 一次拿到：本机设备 + 闹钟列表 + 能否后台响铃的能力标记。
     *
     * ## ★ `device_uid` 是**必填查询参数**，不是 family_uid
     *
     * 这条契约踩过坑：后端 `local_alarm.py` 用 `DeviceOwnedByQuery` 依赖，
     * 要求 `?device_uid=...`；传 `family_uid` 会得到 **400「填写内容有误」**，
     * 且错误信息完全看不出是少了哪个参数。
     *
     * 这也解释了「本地闹钟」页的语义：它是**本机设备**的兜底响铃列表
     * （PWA 页面关掉后无法响铃，所以这个页要告诉用户哪些闹钟可能不响），
     * 天然以设备为单位，与家庭无关。
     *
     * @param deviceUid 本机设备 uid（用 [findMyDeviceUid] 或本地已存值）
     */
    suspend fun localAlarms(deviceUid: String): LocalAlarmsPayload = withContext(Dispatchers.IO) {
        LocalAlarmsPayload.fromJson(client.get("/local-alarms?device_uid=$deviceUid"))
    }

    // ============================================================
    // 信任成员（免确认下发）
    // ============================================================

    suspend fun listTrustMembers(deviceUid: String): List<TrustMember> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/devices/$deviceUid/trust-members")).list
            .map { TrustMember.fromJson(it) }
    }

    suspend fun addTrustMember(deviceUid: String, phone: String) = withContext(Dispatchers.IO) {
        client.post("/devices/$deviceUid/trust-members", JSONObject().put("phone", phone))
    }

    /** 按 uid 添加：成员详情页只有 uid（拿不到脱敏后的真实手机号） */
    suspend fun addTrustMemberByUid(deviceUid: String, uid: String) = withContext(Dispatchers.IO) {
        client.post("/devices/$deviceUid/trust-members/by-uid", JSONObject().put("uid", uid))
    }

    suspend fun removeTrustMember(deviceUid: String, memberUid: String) = withContext(Dispatchers.IO) {
        client.delete("/devices/$deviceUid/trust-members/$memberUid")
    }

    suspend fun setTrustSwitch(deviceUid: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        client.patch("/devices/$deviceUid/trust-switch", JSONObject().put("enabled", enabled))
    }

    // ============================================================
    // 查看授权
    // ============================================================

    /** 申请查看某设备（家人侧的入口） */
    suspend fun requestViewAccess(deviceUid: String) = withContext(Dispatchers.IO) {
        client.post("/devices/$deviceUid/view-requests", JSONObject())
    }

    /** 我收到的查看申请（我是设备归属人） */
    suspend fun receivedViewRequests(status: String? = null): List<ViewRequest> =
        withContext(Dispatchers.IO) {
            val q = status?.let { "?status=$it" } ?: ""
            unwrapList(client.get("/view-requests/received$q")).list
                .map { ViewRequest.fromJson(it) }
        }

    /** 我发出的查看申请 */
    suspend fun myViewRequests(): List<ViewRequest> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/view-requests/mine")).list
            .map { ViewRequest.fromJson(it) }
    }

    suspend fun approveViewRequest(requestId: Long) = withContext(Dispatchers.IO) {
        client.post("/view-requests/$requestId/approve", JSONObject())
    }

    suspend fun rejectViewRequest(requestId: Long) = withContext(Dispatchers.IO) {
        client.post("/view-requests/$requestId/reject", JSONObject())
    }

    /** 某设备已生效的授权名单 */
    suspend fun listGrants(deviceUid: String): List<Grant> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/devices/$deviceUid/grants")).list
            .map { Grant.fromJson(it) }
    }

    suspend fun revokeGrant(deviceUid: String, memberUid: String) = withContext(Dispatchers.IO) {
        client.delete("/devices/$deviceUid/grants/$memberUid")
    }

    // ============================================================
    // 消息
    // ============================================================

    suspend fun listMessages(): List<Message> = withContext(Dispatchers.IO) {
        unwrapList(client.get("/messages")).list.map { Message.fromJson(it) }
    }

    suspend fun markMessageRead(messageUid: String) = withContext(Dispatchers.IO) {
        client.post("/messages/$messageUid/read", JSONObject())
    }

    suspend fun markAllMessagesRead() = withContext(Dispatchers.IO) {
        client.post("/messages/read-all", JSONObject())
    }

    // ============================================================
    // 日志
    // ============================================================

    /**
     * 操作日志。
     *
     * @param deviceUid 与 familyUid **二选一**，两个都不传后端会返回 400。
     */
    suspend fun operationLogs(
        deviceUid: String? = null,
        familyUid: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): Paged<OpLog> = withContext(Dispatchers.IO) {
        val q = buildList {
            deviceUid?.let { add("device_uid=$it") }
            familyUid?.let { add("family_uid=$it") }
            add("limit=$limit")
            add("offset=$offset")
        }.joinToString("&")
        val data = client.get("/logs/operations?$q")
        Paged(
            list = unwrapList(data).list.map { OpLog.fromJson(it) },
            total = data.optInt("total", 0),
        )
    }

    // ============================================================
    // 应用更新
    // ============================================================

    /** 后端发布的最新版本信息（自更新用） */
    suspend fun appVersion(): JSONObject = withContext(Dispatchers.IO) {
        client.get("/app/version", needAuth = false)
    }

    /**
     * 直接问后端「这个接口的列表里到底有几条」，**不走任何解析**，仅供测试交叉核对。
     *
     * ## 为什么需要它
     *
     * 后端列表有两种返回形态（裸数组 / `{list,total}`）。若 [unwrapList] 漏掉其中一种，
     * 表现是**静默返回空列表** —— 不报错、日志也正常。
     * 于是测试里看到「共 0 条」时无法区分：
     *   (a) 后端确实没有数据
     *   (b) 我们的解析把它吃了
     *
     * 这个方法从原始响应里硬数条数，与解析结果比对，(b) 就会立刻暴露。
     * 属于「断言要打在真实数据上，而不是打在自己的解析结果上」。
     *
     * @return 原始响应里的元素个数；无法判定时返回 -1（调用方据此跳过断言）
     */
    suspend fun rawItemCount(path: String): Int = withContext(Dispatchers.IO) {
        try {
            val data = client.get(path)
            // 形态一：{"list": [...]}
            data.optJSONArray("list")?.let { return@withContext it.length() }
            data.optJSONArray("items")?.let { return@withContext it.length() }
            // 形态二：网络层已把裸数组包成 {"list": ...}，上面就命中了。
            // 若两个都没有，说明后端返回的是单个对象，不是列表接口。
            -1
        } catch (e: Exception) {
            Log.w(TAG, "rawItemCount 失败：${e.message}")
            -1
        }
    }

    // ============================================================
    // 调试辅助
    // ============================================================

    /**
     * 把一个原始 JSON 响应打日志（排查「字段名对不上」时用）。
     *
     * 保留它是因为本项目最贵的一类 bug 是「接口返回了但字段名不匹配 →
     * 界面空白且无任何线索」。有这个方法，排查时一行调用就能看到真实键名。
     */
    fun dumpJson(tag: String, o: JSONObject) {
        Log.d(TAG, "[$tag] ${o.toString().take(2000)}")
    }
}

/**
 * ★ 关键：把一个**响应体**归一成 Paged，两种形态都兼容。
 *
 * 后端 `/api/v1` 的 `data` 有两种形态，且分布不均匀（实测，非推测）：
 *
 *   裸数组              → /families、/devices、
 *                         /devices/{uid}/trust-members、/devices/{uid}/grants
 *   {"list":[...],"total":n} → /messages、/devices/{uid}/alarms、
 *                         /view-requests/received、/view-requests/mine、
 *                         /invitations/mine、/logs/operations、/local-alarms
 *
 * ## 为什么必须写成「两种都探」而不是只取一种
 *
 * ApiClient 的 `requestSync` 已把裸数组包成 `{"list": ...}`，
 * 但**那是网络层的行为**；仓储层不该依赖它 —— 一旦哪天请求走了别的路径
 * （或后端统一成裸数组），只读 `data.list` 的实现会**静默返回空列表**。
 *
 * 这个形态的 bug 在本项目真实发生过：`/families` 永远解析出空列表 →
 * 界面显示「账号下没有本机设备」→ 心跳不发（显示离线）+
 * 闹钟一条都不注册（到点不响）。属于本项目最贵的一类故障。
 */
private fun unwrapList(data: JSONObject): Paged<JSONObject> {
    val arr = data.optJSONArray("list") ?: data.optJSONArray("items")
    val items = ArrayList<JSONObject>(arr?.length() ?: 0)
    if (arr != null) for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { items.add(it) }
    }
    val total = data.optInt("total", 0)
    return Paged(items, if (total > 0) total else items.size)
}
