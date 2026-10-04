package com.trustalarm.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端契约的数据模型。
 *
 * ## 字段来源（不是猜的）
 *
 * 全部字段对照后端真实定义提取，共三处来源：
 *   1. `app/schemas/device.py` / `app/schemas/auth.py` 里的 Pydantic `*Out` 类
 *      —— 这是**权威来源**，字段名与可空性都以此为准
 *   2. `app/routers` 下各文件里直接 `return {...}` 的字面键（alarm / message /
 *      log / trust / grant / invitation 这些没有 Pydantic 模型，只有字典）
 *
 * 注意：写路径时**不要出现斜杠加星号**（例如 routers 目录下的 py 文件通配写法）——
 * Kotlin 的块注释是可嵌套的，KDoc 里出现斜杠星号会开启一个永不闭合的嵌套注释，
 * 报错是 `Unclosed comment`；又因为整个文件被当成注释，会连带抛出几十条
 * `Unresolved reference` —— 看起来像「所有模型都找不到」，实际只有一个字符的问题。
 * 这个坑在设计本文件时真实踩到过。
 *   3. `android/app/src/main/assets/web/js/api.js` 的调用方式（确认路径与参数）
 *
 * ## 为什么集中在这一个文件
 *
 * 重写 UI 层时最大的风险是「字段名拼错 → 页面静默空白」。
 * 把契约收敛到一个文件，改错时只需看一处，并且字段名与后端源码可以逐行对照。
 *
 * ## 可空性约定
 *
 * Kotlin 的 `String?` 与后端 `str | None` 一一对应。
 * 不可空字段若后端真返回 null，`optString` 会给出 ""（不抛异常），
 * 这是刻意的：宁可显示空文本，也不让整个页面因解析失败而崩掉。
 */

// ============================================================
// 通用
// ============================================================

/**
 * 后端列表返回的两种形态，统一成这个。
 *
 * 后端 `data` 有的是裸数组（`/families`、`/devices`），
 * 有的是 `{"list": [...], "total": n}`（`/alarms`、`/messages`）。
 * **两种都必须在同一处兼容** —— 历史上这里踩过坑：只读 `data.list`
 * 导致 `/families` 永远解析出空列表，表现为「账号下没有本机设备」。
 */
data class Paged<T>(
    val list: List<T>,
    val total: Int = list.size,
)

// ============================================================
// 认证
// ============================================================

/** 对应后端 `schemas/auth.py :: UserOut` */
data class User(
    val uid: String,
    val phoneMasked: String,
    val nickname: String,
    val avatarUrl: String?,
    val isUnlimited: Boolean,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = User(
            uid = o.optString("uid"),
            phoneMasked = o.optString("phone_masked"),
            nickname = o.optString("nickname"),
            avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
            isUnlimited = o.optBoolean("is_unlimited", false),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

/** 对应后端 `schemas/auth.py :: TokenOut`（登录/刷新的返回） */
data class TokenPayload(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val user: User?,
) {
    companion object {
        fun fromJson(o: JSONObject) = TokenPayload(
            accessToken = o.optString("access_token"),
            refreshToken = o.optString("refresh_token"),
            tokenType = o.optString("token_type", "Bearer"),
            expiresIn = o.optInt("expires_in", 0),
            user = o.optJSONObject("user")?.let { User.fromJson(it) },
        )
    }
}

/** 对应后端 `auth.py :: _session_out`（登录会话，用于「我的设备」踢下线） */
data class LoginSession(
    val id: Long,
    val userAgent: String,
    val createdAt: Long,
    val expiresAt: Long,
    val isCurrent: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = LoginSession(
            id = o.optLong("id"),
            userAgent = o.optString("user_agent"),
            createdAt = o.optLong("created_at"),
            expiresAt = o.optLong("expires_at"),
            isCurrent = o.optBoolean("is_current", false),
        )
    }
}

// ============================================================
// 家庭与成员
// ============================================================

/** 对应后端 `schemas/device.py :: FamilyOut`（家庭列表项） */
data class Family(
    val uid: String,
    val name: String,
    val ownerUid: String,
    val memberCount: Int,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = Family(
            uid = o.optString("uid"),
            name = o.optString("name"),
            ownerUid = o.optString("owner_uid"),
            memberCount = o.optInt("member_count", 0),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

/** 对应后端 `schemas/device.py :: MemberOut` */
data class Member(
    val uid: String,
    val nickname: String,
    val phoneMasked: String,
    val role: String,
    val isOwner: Boolean,
    val joinedAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = Member(
            uid = o.optString("uid"),
            nickname = o.optString("nickname"),
            phoneMasked = o.optString("phone_masked"),
            role = o.optString("role"),
            isOwner = o.optBoolean("is_owner", false),
            joinedAt = o.optLong("joined_at", 0L),
        )
    }
}

/**
 * 成员详情页里「该成员名下的一台设备」。
 * 对应后端 `routers/family.py :: member_detail` 的 `devices[]` 元素。
 *
 * `myPermission` 是**服务端算好的权限标签**（取值与 `permissions.view_source` 对齐）：
 *   owner / family_owner / trusted / granted / alarm_creator / need_request / unknown
 * 前端只做映射，**不自己推演权限优先级** —— 规则只在后端。
 */
data class MemberDevice(
    val uid: String,
    val deviceName: String,
    val platform: String,
    val onlineStatus: Int,
    val ownerIsMe: Boolean,
    val ownerNickname: String?,
    val myPermission: String,
) {
    companion object {
        fun fromJson(o: JSONObject) = MemberDevice(
            uid = o.optString("uid"),
            deviceName = o.optString("device_name"),
            platform = o.optString("platform"),
            onlineStatus = o.optInt("online_status", 0),
            ownerIsMe = o.optBoolean("owner_is_me", false),
            ownerNickname = o.optStringOrNull("owner_nickname"),
            myPermission = o.optString("my_permission", "unknown"),
        )
    }
}

/**
 * 成员详情（`GET /families/{fid}/members/{uid}`）。
 *
 * 与 [Member] 的区别：[Member] 是家庭详情里嵌的轻量成员（只有基本信息），
 * 本模型是**成员详情页**的完整数据 —— 多了名下设备、权限标签、
 * 信任/授权计数，以及双向信任明细（`my_devices` / `trust`）。
 */
data class MemberDetail(
    val uid: String,
    val nickname: String,
    val phoneMasked: String,
    val role: String,
    val isOwner: Boolean,
    val joinedAt: Long,
    val isMe: Boolean,
    val deviceCount: Int,
    val devices: List<MemberDevice>,
    /** 我名下设备（用于「我的设备对他的信任」卡片 —— 只有自己的设备我能改） */
    val myDevices: List<MyDeviceTrust>,
    /** 双向信任明细（他信任我 = 我能操作他的设备） */
    val targetTrustsDetail: List<TrustDetailItem>,
    val trustMemberCount: Int,
    val grantCount: Int,
    val canViewAllDevices: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = MemberDetail(
            uid = o.optString("uid"),
            nickname = o.optString("nickname"),
            phoneMasked = o.optString("phone_masked"),
            role = o.optString("role"),
            isOwner = o.optBoolean("is_owner", false),
            joinedAt = o.optLong("joined_at", 0L),
            isMe = o.optBoolean("is_me", false),
            deviceCount = o.optInt("device_count", 0),
            devices = parseArray(o.optJSONArray("devices")) { MemberDevice.fromJson(it) },
            myDevices = parseArray(o.optJSONArray("my_devices")) { MyDeviceTrust.fromJson(it) },
            targetTrustsDetail = parseArray(
                o.optJSONObject("trust")?.optJSONArray("target_trusts_detail"),
            ) { TrustDetailItem.fromJson(it) },
            trustMemberCount = o.optInt("trust_member_count", 0),
            grantCount = o.optInt("grant_count", 0),
            canViewAllDevices = o.optBoolean("can_view_all_devices", true),
        )
    }
}

/**
 * 「我名下设备」在某成员视角下的信任状态（对应后端 `my_devices[]`）。
 *
 * 用途：「我的设备对他的信任」卡片 —— 可加入/移出信任名单、开关自动下发。
 * 只有设备归属人（= 我）能改，所以这些设备天然可操作。
 */
data class MyDeviceTrust(
    val uid: String,
    val deviceName: String,
    val platform: String,
    val onlineStatus: Int,
    /** 该设备的「信任自动下发」开关：1 开 / 0 关 */
    val autoAlarmTrustSwitch: Int,
    /** 在我的这台设备上，是否已把对方加入信任名单 */
    val trustedByMe: Boolean,
) {
    val switchOn: Boolean get() = autoAlarmTrustSwitch == 1

    companion object {
        fun fromJson(o: JSONObject) = MyDeviceTrust(
            uid = o.optString("uid"),
            deviceName = o.optString("device_name"),
            platform = o.optString("platform"),
            onlineStatus = o.optInt("online_status", 0),
            autoAlarmTrustSwitch = o.optInt("auto_alarm_trust_switch", 0),
            trustedByMe = o.optBoolean("trusted_by_me", false),
        )
    }
}

/**
 * 一条双向信任明细（对应后端 `trust.target_trusts_detail[]`）。
 *
 * 「他信任我」方向：对方名下的某台设备把我加入了信任名单，
 * 我因此可在该设备上免确认设闹钟（还需该设备开关开启才真正生效）。
 */
data class TrustDetailItem(
    val deviceUid: String,
    val switchOn: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = TrustDetailItem(
            deviceUid = o.optString("device_uid"),
            switchOn = o.optBoolean("switch_on", false),
        )
    }
}

/**
 * 对应后端 `schemas/device.py :: FamilyDetailOut`（家庭详情）。
 *
 * `members` 是嵌套数组，注意不是 `list`。
 */
data class FamilyDetail(
    val uid: String,
    val name: String,
    val ownerUid: String,
    val members: List<Member>,
    val memberCount: Int,
    val deviceCount: Int,
    val isOwner: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = FamilyDetail(
            uid = o.optString("uid"),
            name = o.optString("name"),
            ownerUid = o.optString("owner_uid"),
            members = parseArray(o.optJSONArray("members")) { Member.fromJson(it) },
            memberCount = o.optInt("member_count", 0),
            deviceCount = o.optInt("device_count", 0),
            isOwner = o.optBoolean("is_owner", false),
        )
    }
}

/** 我用到的家庭邀请（被邀请方视角），对应 `invitation.py` 的字面键 */
data class Invitation(
    val invitationUid: String,
    val familyUid: String,
    val familyName: String,
    val inviterNickname: String,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = Invitation(
            invitationUid = o.optString("invitation_uid"),
            familyUid = o.optString("family_uid"),
            familyName = o.optString("family_name"),
            inviterNickname = o.optString("inviter_nickname"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

/**
 * 家庭邀请 —— **邀请方（群主）视角**，对应后端
 * `GET /families/{uid}/invitations` 的返回（`family.py` 里字面构键）。
 *
 * ## 为什么与上面的 [Invitation] 是两个类型
 *
 * 两者是同一张表的两面，但**字段集不同、语义相反**：
 *   · [Invitation]（被邀请方）：`family_name` / `inviter_nickname` —— 「谁邀请了我」
 *   · [FamilyInvitation]（邀请方）：`invitee_uid` / `invitee_nickname` —— 「我邀请了谁」
 *
 * 合并成一个类型会留下两个永远为空的字段，读取方分不清「空」和「不该读」。
 * 本项目在这类「字段名/形态搞错」上付过代价（列表静默为空、页面空白且零报错），
 * 所以宁可多一个类型。
 */
data class FamilyInvitation(
    val invitationUid: String,
    val inviteeUid: String,
    val inviteeNickname: String,
    val status: String,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = FamilyInvitation(
            invitationUid = o.optString("invitation_uid"),
            inviteeUid = o.optString("invitee_uid"),
            inviteeNickname = o.optString("invitee_nickname"),
            status = o.optString("status"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

// ============================================================
// 设备
// ============================================================

/**
 * 对应后端 `schemas/device.py :: DeviceOut`。
 *
 * ## 两个「脱敏」字段必须区分清楚
 *
 *   · `modelDisplay` —— 给**所有人**看的泛化机型（如「安卓手机」），
 *     原始 model 按 PRD §9 脱敏，不能直接展示。
 *   · `modelDigest`  —— **仅归属人本人可见**的机型摘要，
 *     用途是让 App 比对「哪台才是当前手机」（多设备账号的关键）。
 *
 * 早期版本把这两个搞混，导致本地闹钟页选错设备 —— 见
 * `ApiClient.findMyDeviceUid()` 的长注释。
 */
data class Device(
    val uid: String,
    val deviceName: String,
    val platform: String,
    val modelDisplay: String,
    val onlineStatus: Int,
    val autoAlarmTrustSwitch: Int,
    val lastHeartbeatAt: Long?,
    val ownerIsMe: Boolean,
    val ownerNickname: String?,
    val ownerUid: String?,
    val bindAt: Long?,
    val modelDigest: String?,
    /**
     * 查看权限来源（后端 `view_source`）。
     *
     * ★ 只有 **设备详情** `/devices/{uid}` 会返回该字段；
     * 设备列表 `/devices?family_uid=` **不返回**（见 `routers/device.py`：
     * 详情在 `_device_out(...).model_dump()` 之后补写 `data["view_source"]`，
     * 列表分支没有这一步）。所以这里是可空 —— 列表来源的 [Device] 里它为 null，
     * 不能当「无权限」解释。
     */
    val viewSource: String?,
) {
    /** 在线判据：后端 online_status == 1 */
    val isOnline: Boolean get() = onlineStatus == 1

    companion object {
        fun fromJson(o: JSONObject) = Device(
            uid = o.optString("uid"),
            deviceName = o.optString("device_name"),
            platform = o.optString("platform"),
            modelDisplay = o.optString("model_display"),
            onlineStatus = o.optInt("online_status", 0),
            autoAlarmTrustSwitch = o.optInt("auto_alarm_trust_switch", 0),
            lastHeartbeatAt = o.optLongOrNull("last_heartbeat_at"),
            ownerIsMe = o.optBoolean("owner_is_me", false),
            ownerNickname = o.optStringOrNull("owner_nickname"),
            ownerUid = o.optStringOrNull("owner_uid"),
            bindAt = o.optLongOrNull("bind_at"),
            modelDigest = o.optStringOrNull("model_digest"),
            viewSource = o.optStringOrNull("view_source"),
        )
    }
}

// ============================================================
// 闹钟
// ============================================================

/**
 * 一条待注册的闹钟（后端 `/devices/{uid}/alarms` 的 list 元素 + 本机注册状态）。
 *
 * 对应后端 `routers/alarm.py :: _alarm_out` 的字面键：
 *   uid, device_uid, creator_is_me, alarm_time, display_time,
 *   note, source, confirm_status, status, created_at, updated_at
 */
data class Alarm(
    val uid: String,
    val deviceUid: String,
    val creatorIsMe: Boolean,
    /** 触发时间戳（毫秒） */
    val alarmTime: Long,
    /** 形如 "07:30"，后端已格式化好，UI 直接展示 */
    val displayTime: String,
    val note: String?,
    /** app_local（本机自己设的）/ remote（家人下发的） */
    val source: String,
    /** 1=已确认（可注册），0=待确认 */
    val confirmStatus: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 本机是否已注册到系统闹钟（客户端侧状态，非后端字段） */
    val scheduledLocally: Boolean = false,
    /**
     * 本机本地注册是否失败 —— 仅 `/local-alarms` 返回此字段。
     *
     * 语义：该闹钟在**本机**没能注册进系统闹钟，可能不会响，
     * 需要本地闹钟页兜底提醒。`/devices/{uid}/alarms` 不返回它，默认 false。
     */
    val needsFallback: Boolean = false,
    /** 注册失败类型（仅 `/local-alarms` 有值，如权限缺失 / 厂商拦截） */
    val failureType: String? = null,
    /** 已重试次数（仅 `/local-alarms` 有值） */
    val retryCount: Int = 0,
    /** 是否在未来 24 小时内（仅 `/local-alarms` 有值） */
    val within24h: Boolean = false,
) {
    /**
     * 是否应当注册到系统闹钟：已确认 + 时间未过（含宽限期）。
     *
     * ## 为什么要留「宽限期」（用户实测「刚设的闹钟不响」的根因之一）
     *
     *   实测场景：20:45:36 设了 20:46:00 的闹钟（提前 23 秒）。
     *   若同步发生在那之后（哪怕只晚 2 秒），原判定 `> now` 会认为
     *   「已过期」→ 直接跳过 → **闹钟永不注册、到点不响**。
     *   而用户刚设完闹钟时，晚几秒响是完全可以接受的；
     *   直接丢掉才是真正的问题。
     *
     *   所以宽限 [GRACE_MS]：刚过期不久（默认 90 秒）仍注册，
     *   由 AlarmManager 立刻触发（它内部对过去时间会立即投递）。
     *   这样「设 1 分钟后的闹钟」在任何同步时点都不会被漏掉。
     */
    val shouldSchedule: Boolean
        get() = confirmStatus == 1 &&
            alarmTime > System.currentTimeMillis() - GRACE_MS

    /** 是否为家人下发的闹钟（UI 上要显示「来自谁」） */
    val isRemote: Boolean get() = source == "remote"

    companion object {
        const val SOURCE_APP_LOCAL = "app_local"
        const val SOURCE_REMOTE = "remote"

        /**
         * 过期宽限期（毫秒）。
         *
         * 90 秒的选择依据：
         *   · 足够覆盖「同步周期 10 秒 + 网络往返」的最坏延迟
         *   · 又远小于「用户会认为是陈年旧闹钟」的量级（分钟以上）
         */
        const val GRACE_MS = 90_000L

        fun fromJson(o: JSONObject, scheduled: Boolean = false) = Alarm(
            uid = o.optString("uid"),
            deviceUid = o.optString("device_uid"),
            creatorIsMe = o.optBoolean("creator_is_me", false),
            alarmTime = o.optLong("alarm_time", 0L),
            displayTime = o.optString("display_time"),
            note = o.optStringOrNull("note"),
            source = o.optString("source", SOURCE_REMOTE),
            confirmStatus = o.optInt("confirm_status", 0),
            status = o.optString("status"),
            createdAt = o.optLong("created_at", 0L),
            updatedAt = o.optLong("updated_at", 0L),
            scheduledLocally = scheduled,
            // 以下 4 个仅 /local-alarms 返回；/devices/{uid}/alarms 上没有，取默认值
            needsFallback = o.optBoolean("needs_fallback", false),
            failureType = o.optStringOrNull("failure_type"),
            retryCount = o.optInt("retry_count", 0),
            within24h = o.optBoolean("within_24h", false),
        )
    }
}

// ============================================================
// 消息
// ============================================================

/**
 * 对应后端 `routers/message.py` 的字面键。
 *
 * ## ★ payload 是「JSON 字符串」，不是 JSON 对象（踩过坑）
 *
 * 后端 `models/message.py` 的 `payload` 列类型是 `Text`，序列化后是字符串
 * （如 `"{\"alarm_uid\": \"...\", \"device_uid\": \"...\"}"`）。
 * 网页版 `messages.js` 的注释写得很明确：「payload 是 **JSON 字符串**，需二次解析」。
 *
 * 早期 Kotlin 侧用 `optJSONObject("payload")` 解析 → 对字符串**静默返回 null**，
 * 表现为「消息里的闹钟/邀请按钮永远不出现」（与所有 optXxx 静默失败的 bug 同类）。
 * 这里改为：字符串走二次 `JSONObject(...)` 解析，对象直接用（兼容两种形态）。
 */
data class Message(
    val uid: String,
    val type: String,
    val title: String,
    val content: String,
    val isRead: Boolean,
    val createdAt: Long,
    /** 附加载荷（已解析为对象；解析失败或为空时为 null） */
    val payload: JSONObject?,
) {
    companion object {
        fun fromJson(o: JSONObject) = Message(
            uid = o.optString("uid"),
            type = o.optString("type"),
            title = o.optString("title"),
            content = o.optString("content"),
            isRead = o.optBoolean("is_read", false),
            createdAt = o.optLong("created_at", 0L),
            payload = parsePayload(o),
        )

        /**
         * 兼容两种 payload 形态：
         *   · 后端现状：JSON **字符串** → 二次解析
         *   · 若将来改回内嵌对象 → 直接取
         * 解析失败一律返回 null（不让页面崩，与网页版 `parsePayload` 一致）。
         */
        private fun parsePayload(o: JSONObject): JSONObject? {
            o.optJSONObject("payload")?.let { return it }
            val raw = o.optString("payload").takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}

// ============================================================
// 查看授权（谁能看我的设备）
// ============================================================

/**
 * 查看申请 / 授权记录。
 *
 * 对应后端 `routers/grant.py :: _req_out` 的字面键。
 */
data class ViewRequest(
    val id: Long,
    val deviceUid: String,
    val deviceName: String,
    val applicantUid: String,
    val applicantNickname: String,
    val applicantPhoneMasked: String,
    val ownerUid: String,
    val status: String,
    val handledAt: Long?,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = ViewRequest(
            id = o.optLong("id"),
            deviceUid = o.optString("device_uid"),
            deviceName = o.optString("device_name"),
            applicantUid = o.optString("applicant_uid"),
            applicantNickname = o.optString("applicant_nickname"),
            applicantPhoneMasked = o.optString("applicant_phone_masked"),
            ownerUid = o.optString("owner_uid"),
            status = o.optString("status"),
            handledAt = o.optLongOrNull("handled_at"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

/** 已生效的授权（device_grants 页），对应 grant.py 的字面键 */
data class Grant(
    val uid: String,
    val nickname: String,
    val phoneMasked: String,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = Grant(
            uid = o.optString("uid"),
            nickname = o.optString("nickname"),
            phoneMasked = o.optString("phone_masked"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

// ============================================================
// 信任成员（免确认直接下发闹钟）
// ============================================================

/** 对应后端 `routers/trust.py` 的字面键 */
data class TrustMember(
    val uid: String,
    val nickname: String,
    val phoneMasked: String,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = TrustMember(
            uid = o.optString("uid"),
            nickname = o.optString("nickname"),
            phoneMasked = o.optString("phone_masked"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

// ============================================================
// 操作日志
// ============================================================

/**
 * 操作日志条目。
 *
 * 字段对照后端 `services/oplog_service.py :: _log_out` 实际组装出的 dict 键：
 *   id, action, operator_uid, operator_nickname, device_id, device_name,
 *   device_uid, family_id, target_id, target_nickname, alarm_time,
 *   detail, created_at  —— 外加 _summarize 生成的 `summary`。
 *
 * ## 为什么要有 `summary`
 *
 * 后端刻意在服务端拼好一句话摘要（「张三 向【妈妈的手机】设置了 07:30 的闹钟」），
 * 目的是保证 PWA 与原生 App 展示一致。UI **应当优先用 summary**，
 * 只有 summary 缺失时才用 action + 字段自行拼装 —— 否则两端文案会漂移。
 */
data class OpLog(
    val id: Long,
    val action: String,
    /** 服务端生成的一句话摘要，UI 优先展示 */
    val summary: String?,
    val operatorUid: String?,
    val operatorNickname: String?,
    val deviceUid: String?,
    val deviceName: String?,
    /** 目标用户昵称（信任增删/成员变更类日志才有） */
    val targetNickname: String?,
    /** 闹钟时间（如 "07:30"），仅闹钟类日志有 */
    val alarmTime: String?,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = OpLog(
            id = o.optLong("id"),
            action = o.optString("action"),
            summary = o.optStringOrNull("summary"),
            operatorUid = o.optStringOrNull("operator_uid"),
            operatorNickname = o.optStringOrNull("operator_nickname"),
            deviceUid = o.optStringOrNull("device_uid"),
            deviceName = o.optStringOrNull("device_name"),
            targetNickname = o.optStringOrNull("target_nickname"),
            alarmTime = o.optStringOrNull("alarm_time"),
            createdAt = o.optLong("created_at", 0L),
        )
    }
}

// ============================================================
// 本地闹钟页专用（/local-alarms）
// ============================================================
/**
 * 「本地闹钟」页的聚合返回。
 *
 * 后端 `routers/local_alarm.py` 返回：
 *   { device_uid, device_name, list, total, needs_fallback_count,
 *     capability: { foreground_ring, background_ring } }
 *
 * `capability` 描述本机能否真正后台响铃 —— 不能时要提示用户去开权限，
 * 这是国产 ROM 上闹钟可靠性的关键判断。
 */
data class LocalAlarmsPayload(
    val deviceUid: String,
    val deviceName: String,
    val alarms: List<Alarm>,
    val needsFallbackCount: Int,
    val foregroundRing: Boolean,
    val backgroundRing: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject): LocalAlarmsPayload {
            val cap = o.optJSONObject("capability")
            return LocalAlarmsPayload(
                deviceUid = o.optString("device_uid"),
                deviceName = o.optString("device_name"),
                alarms = parseArray(o.optJSONArray("list")) { Alarm.fromJson(it) },
                needsFallbackCount = o.optInt("needs_fallback_count", 0),
                foregroundRing = cap?.optBoolean("foreground_ring", false) ?: false,
                backgroundRing = cap?.optBoolean("background_ring", false) ?: false,
            )
        }
    }
}

// ============================================================
// 设备闹钟列表结果
// ============================================================

/**
 * `GET /devices/{uid}/alarms` 的完整返回。
 *
 * `viewSource` 描述查看权限来源（后端 `view_source` 字段）：
 *   owner / family_owner / trust_member / granted / creator / none
 * 设备闹钟页用它向用户解释「我凭什么能看这台设备的闹钟」。
 */
data class AlarmListResult(
    val alarms: List<Alarm>,
    val total: Int = alarms.size,
    val viewSource: String = "",
)

// ============================================================
// 创建闹钟的完整结果
// ============================================================

/**
 * `POST /devices/{uid}/alarms` 的完整返回（对应后端 `alarm.py :: create_alarm`）。
 *
 * 覆盖 PRD §4.1 的三种结果：
 *   · 免确认成功          → needDialog=false, needLocalConfirm=false
 *   · 需设备主人确认       → needDialog=true，`dialogText` 是服务端定稿文案
 *   · iOS 需 App 内确认    → needLocalConfirm=true（PWA 无法代劳）
 *
 * `dialogText` 是**服务端定稿的用户化文案**，客户端应原文展示 ——
 * 不要自己另写一套（两端口径会漂移）。
 */
data class CreateAlarmResult(
    val alarm: Alarm,
    val needDialog: Boolean,
    val needLocalConfirm: Boolean,
    val dialogText: String?,
) {
    companion object {
        fun fromJson(o: JSONObject) = CreateAlarmResult(
            // 后端把闹钟放在 alarm 键下
            alarm = o.optJSONObject("alarm")?.let { Alarm.fromJson(it) }
                ?: Alarm.fromJson(o),
            needDialog = o.optBoolean("need_dialog", false),
            needLocalConfirm = o.optBoolean("need_local_confirm", false),
            dialogText = o.optStringOrNull("dialog_text"),
        )
    }
}

// ============================================================
// JSON 工具（后端返回的 null 处理，统一收口）
// ============================================================

/**
 * 取可能为 null 的字符串。
 *
 * 为什么要专门写：`JSONObject.optString(k)` 在值是 JSON null 时返回**字符串 "null"**，
 * 而不是空串 —— 这是 Android 上非常容易踩的坑。若不处理，
 * 界面上会出现字面量 "null"，或者 `note == "null"` 被当成合法备注。
 */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    val s = optString(key, "")
    return s.takeIf { it.isNotBlank() && it != "null" }
}

/** 取可能为 null 的长整数（后端 `int | None`） */
internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key, 0L)

/** 安全地把 JSONArray 映射成 List（元素可能是 null，一律跳过） */
internal fun <T> parseArray(arr: JSONArray?, f: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    val out = ArrayList<T>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { out.add(f(it)) }
    }
    return out
}
