package com.trustalarm.app.data

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 契约验证：用**真实后端**跑一遍所有数据模型，断言字段确实被解析出来。
 *
 * ## 为什么必须有这个测试（而不是只靠编译通过）
 *
 * 编译通过只证明「语法对」，不证明「字段名对」。本项目最贵的一类 bug 就是
 * 「接口返回了数据，但字段名不匹配 → 页面空白，且没有任何报错线索」——
 * 因为所有 `optString` / `optInt` 在键不存在时都**静默返回默认值**。
 *
 * JS 时期的对应问题是「`data.list` 只读一种形态」，导致 /families 永远为空。
 * 那次是靠真机抓包才发现的。现在改成 Kotlin，同样的错误会以同样的方式隐藏，
 * 所以必须有一个测试**主动断言非默认值**。
 *
 * ## 断言策略：断「非空/非零」而不是断具体值
 *
 * 不能写 `assertEquals("07:30", alarm.displayTime)` —— 那样每次跑都要造数据。
 * 改为断言「这个字段被解析出来了」（非空、非零、列表非空），
 * 这样任何字段名写错（拼错、大小写错、下划线位置错）都会立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class ContractVerificationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = Repository(ctx)

    // 从环境变量/参数注入的测试账号（不写死在代码里，避免凭据进版本库）
    private val phone = InstrumentationRegistry.getArguments().getString("phone") ?: ""
    private val password = InstrumentationRegistry.getArguments().getString("password") ?: ""

    private fun log(tag: String, msg: String) = Log.i("ContractVerify", "[$tag] $msg")

    /**
     * 全链路：登录 → 家庭 → 设备 → 闹钟 → 消息 → 日志。
     *
     * 每一步都断言关键字段被解析出来。任何一环失败都会指出具体是哪个模型。
     */
    @Test
    fun verifyAllModelsParseRealFields(): Unit = runBlocking {
        assumeCredentials()

        // ---------- 1. 登录 ----------
        val payload = repo.login(phone, password)
        assertTrue("登录应返回非空 access_token", payload.accessToken.isNotBlank())
        assertTrue("登录应返回非空 refresh_token", payload.refreshToken.isNotBlank())
        val user = payload.user
        assertNotNull("登录返回应内嵌 user 对象", user)
        assertTrue("user.uid 应被解析（非空）", user!!.uid.isNotBlank())
        assertTrue("user.nickname 应被解析（非空）", user.nickname.isNotBlank())
        log("login", "uid=${user.uid.take(8)} nick=${user.nickname} masked=${user.phoneMasked}")

        // ---------- 2. /auth/me ----------
        val me = repo.me()
        assertEquals("me.uid 应与登录返回一致", user.uid, me.uid)
        log("me", "nickname=${me.nickname}")

        // ---------- 3. 家庭列表（历史上踩过「只读 data.list」的坑）----------
        val families = repo.listFamilies()
        assertTrue("家庭列表不应为空（该账号应有家庭）", families.isNotEmpty())
        val f0 = families.first()
        assertTrue("Family.uid 应被解析", f0.uid.isNotBlank())
        assertTrue("Family.name 应被解析", f0.name.isNotBlank())
        assertTrue("Family.ownerUid 应被解析", f0.ownerUid.isNotBlank())
        assertTrue("Family.memberCount 应 > 0", f0.memberCount > 0)
        log("families", "共 ${families.size} 个；首个 name=${f0.name} members=${f0.memberCount}")

        // ---------- 4. 家庭详情（members 是嵌套数组，不是 list）----------
        val detail = repo.familyDetail(f0.uid)
        assertTrue("FamilyDetail.members 应非空", detail.members.isNotEmpty())
        val m0 = detail.members.first()
        assertTrue("Member.uid 应被解析", m0.uid.isNotBlank())
        assertTrue("Member.nickname 应被解析", m0.nickname.isNotBlank())
        assertTrue("Member.phoneMasked 应被解析（脱敏手机号）", m0.phoneMasked.isNotBlank())
        log("familyDetail", "members=${detail.members.size} deviceCount=${detail.deviceCount}")

        // ---------- 5. 设备列表 ----------
        val devices = repo.listDevices(f0.uid)
        assertTrue("设备列表不应为空", devices.isNotEmpty())
        val d0 = devices.first()
        assertTrue("Device.uid 应被解析", d0.uid.isNotBlank())
        assertTrue("Device.deviceName 应被解析", d0.deviceName.isNotBlank())
        assertTrue("Device.platform 应被解析", d0.platform.isNotBlank())
        // model_display 是脱敏后的展示串，必然非空
        assertTrue("Device.modelDisplay 应被解析（脱敏展示串）", d0.modelDisplay.isNotBlank())
        log("devices", "共 ${devices.size}；首个 name=${d0.deviceName} " +
            "platform=${d0.platform} display=${d0.modelDisplay} online=${d0.isOnline}")

        // ---------- 6. 闹钟列表 ----------
        val alarms = repo.listAlarms(d0.uid)
        log("alarms", "共 ${alarms.size} 条")
        if (alarms.isNotEmpty()) {
            val a = alarms.first()
            assertTrue("Alarm.uid 应被解析", a.uid.isNotBlank())
            assertTrue("Alarm.deviceUid 应被解析", a.deviceUid.isNotBlank())
            assertTrue("Alarm.displayTime 应被解析（如 07:30）", a.displayTime.isNotBlank())
            assertTrue("Alarm.alarmTime 应被解析（时间戳 > 0）", a.alarmTime > 0L)
            assertTrue("Alarm.source 应是 app_local 或 remote",
                a.source == Alarm.SOURCE_APP_LOCAL || a.source == Alarm.SOURCE_REMOTE)
            log("alarm[0]", "uid=${a.uid.take(8)} time=${a.displayTime} " +
                "src=${a.source} conf=${a.confirmStatus} note=${a.note}")
        } else {
            log("alarms", "该设备暂无闹钟 —— 闹钟模型的字段断言被跳过（不算失败）")
        }

        // ---------- 7. 消息（轮询接口，主页角标依赖它）----------
        val messages = repo.listMessages()
        log("messages", "共 ${messages.size} 条")
        if (messages.isNotEmpty()) {
            val msg = messages.first()
            assertTrue("Message.uid 应被解析", msg.uid.isNotBlank())
            assertTrue("Message.title 应被解析", msg.title.isNotBlank())
            assertTrue("Message.type 应被解析", msg.type.isNotBlank())
            log("message[0]", "type=${msg.type} title=${msg.title} read=${msg.isRead}")
        }

        // ---------- 8. 操作日志（无 Pydantic 模型，纯字典，最易错）----------
        val logs = repo.operationLogs(familyUid = f0.uid, limit = 10)
        log("logs", "共 ${logs.list.size} 条 (total=${logs.total})")
        if (logs.list.isNotEmpty()) {
            val l = logs.list.first()
            assertTrue("OpLog.id 应被解析（> 0）", l.id > 0L)
            assertTrue("OpLog.action 应被解析", l.action.isNotBlank())
            assertTrue("OpLog.createdAt 应被解析（> 0）", l.createdAt > 0L)
            assertTrue(
                "OpLog.summary 应由服务端生成（UI 优先展示它，缺失会导致日志页无文案）",
                !l.summary.isNullOrBlank(),
            )
            log("log[0]", "action=${l.action} summary=${l.summary}")
        }

        // ---------- 9. 本地闹钟聚合接口（主页用）----------
        // ★ 注意传的是 device_uid（必填查询参数），不是 family_uid ——
        //    传错会得到 400「填写内容有误」，且看不出少哪个参数。
        //    第一次跑本测试就是在这里失败的，见 Repository.localAlarms 的注释。
        val local = repo.localAlarms(d0.uid)
        assertNotNull("localAlarms 应返回对象", local)
        assertTrue("localAlarms.deviceUid 应回显请求的设备", local.deviceUid.isNotBlank())
        log("localAlarms", "deviceUid=${local.deviceUid.take(8)} " +
            "deviceName=${local.deviceName} alarms=${local.alarms.size} " +
            "cap(fg=${local.foregroundRing}, bg=${local.backgroundRing})")

        log("DONE", "全部模型契约验证通过")
    }

    /** 信任成员 / 查看申请 / 授权 是次要页面，单独一个测试便于定位 */
    @Test
    fun verifySecondaryModels(): Unit = runBlocking {
        assumeCredentials()
        repo.login(phone, password)
        val fam = repo.listFamilies().firstOrNull() ?: return@runBlocking
        val dev = repo.listDevices(fam.uid).firstOrNull() ?: return@runBlocking

        val trust = repo.listTrustMembers(dev.uid)
        log("trustMembers", "共 ${trust.size}")

        // ★ 这一条是「裸数组 vs {list,total}」形态的守门断言。
        //
        // /devices/{uid}/trust-members 返回**裸数组**（实测），而 /messages 返回
        // {list,total} —— 与直觉相反。若 unwrapList 只探 data.list，
        // 裸数组的接口会**静默返回空列表**，不报任何错。
        // 光看「共 0」分不清「真的没有」与「解析错了」，所以这里交叉核对：
        // 用后端原始响应确认条数，若 raw 有数据而解析为 0 → 立刻失败。
        val raw = repo.rawItemCount("/devices/${dev.uid}/trust-members")
        assertTrue(
            "trust-members 形态解析一致：原始响应 $raw 条，解析出 ${trust.size} 条" +
                "（裸数组形态没兼容时这里会是 0）",
            trust.size == raw,
        )
        trust.firstOrNull()?.let {
            assertTrue("TrustMember.uid 应被解析", it.uid.isNotBlank())
            log("trustMember[0]", "nick=${it.nickname} masked=${it.phoneMasked}")
        }

        val received = repo.receivedViewRequests()
        log("viewRequests/received", "共 ${received.size}")
        received.firstOrNull()?.let {
            assertTrue("ViewRequest.id 应被解析", it.id > 0L)
            assertTrue("ViewRequest.deviceUid 应被解析", it.deviceUid.isNotBlank())
            log("viewRequest[0]", "device=${it.deviceName} applicant=${it.applicantNickname}")
        }

        val grants = repo.listGrants(dev.uid)
        log("grants", "共 ${grants.size}")
        grants.firstOrNull()?.let {
            assertTrue("Grant.uid 应被解析", it.uid.isNotBlank())
            log("grant[0]", "nick=${it.nickname}")
        }

        val invites = repo.myInvitations()
        log("myInvitations", "共 ${invites.size}")
        invites.firstOrNull()?.let {
            assertTrue("Invitation.familyUid 应被解析", it.familyUid.isNotBlank())
            log("invitation[0]", "family=${it.familyName} inviter=${it.inviterNickname}")
        }

        val sessions = repo.listSessions()
        log("sessions", "共 ${sessions.size}")
        if (sessions.isNotEmpty()) {
            assertTrue("LoginSession.id 应被解析", sessions.first().id > 0L)
            assertTrue("应有一条 is_current=true（标识当前会话）",
                sessions.any { it.isCurrent })
            log("session[0]", "ua=${sessions.first().userAgent.take(40)} " +
                "current=${sessions.first().isCurrent}")
        }
    }

    private fun assumeCredentials() {
        org.junit.Assume.assumeTrue(
            "未提供 -Pphone / -Ppassword 测试账号，跳过契约验证",
            phone.isNotBlank() && password.isNotBlank(),
        )
    }
}
