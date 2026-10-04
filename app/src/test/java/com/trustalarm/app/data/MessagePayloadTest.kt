package com.trustalarm.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Message.payload 解析的回归测试。
 *
 * ## 背景（真实踩过的坑，P0 级）
 *
 * 后端 `models/message.py` 的 `payload` 列是 `Text`，序列化后是 **JSON 字符串**
 * （形如 `"{\"alarm_uid\":\"...\"}"`），而不是内嵌 JSON 对象。
 * 早期 Kotlin 用 `optJSONObject("payload")` 解析 → 对字符串**静默返回 null**
 * → 「消息里的同意/拒绝按钮永远不渲染」（整页零按钮）。
 *
 * 修复方式是「字符串走二次 JSONObject 解析，对象形态兼容」。
 * 本测试锁死这个契约，防止将来有人把 parsePayload 改回 optJSONObject-only。
 */
class MessagePayloadTest {

    // ---------- 1. 后端现状：payload 是 JSON 字符串 ----------

    @Test
    fun `payload as JSON string is parsed into object`() {
        // 后端真实返回形态：payload 字段的值本身是一段 JSON 文本
        val raw = JSONObject(
            """
            {
              "uid": "m1",
              "type": "alarm_confirm",
              "title": "确认闹钟",
              "content": "爸爸给你设了闹钟",
              "is_read": false,
              "created_at": 1790000000000,
              "payload": "{\"alarm_uid\":\"a1\",\"device_uid\":\"d1\"}"
            }
            """.trimIndent()
        )

        val msg = Message.fromJson(raw)

        assertNotNull("payload 字符串必须被二次解析出来（这是按钮渲染的前提）", msg.payload)
        assertEquals("a1", msg.payload!!.optString("alarm_uid"))
        assertEquals("d1", msg.payload!!.optString("device_uid"))
    }

    // ---------- 2. 兼容：payload 已经是内嵌对象 ----------

    @Test
    fun `payload as embedded JSON object still works`() {
        // 将来后端若改成内嵌对象，也要能解析（两种形态都兼容）
        val raw = JSONObject(
            """
            {
              "uid": "m2",
              "type": "family_invite",
              "payload": {"family_uid":"f9"}
            }
            """.trimIndent()
        )

        val msg = Message.fromJson(raw)

        assertNotNull(msg.payload)
        assertEquals("f9", msg.payload!!.optString("family_uid"))
    }

    // ---------- 3. 缺失 / 空 / 非法 payload 一律安静返回 null ----------

    @Test
    fun `missing payload yields null`() {
        val raw = JSONObject("""{"uid":"m3","type":"notice","title":"t","content":"c"}""")
        assertNull(Message.fromJson(raw).payload)
    }

    @Test
    fun `blank and literal-null payload yield null`() {
        val blank = JSONObject("""{"uid":"m4","payload":""}""")
        val literal = JSONObject("""{"uid":"m5","payload":"null"}""")
        assertNull(Message.fromJson(blank).payload)
        assertNull(Message.fromJson(literal).payload)
    }

    @Test
    fun `malformed payload string does not crash and yields null`() {
        // 后端给了坏数据也不能让页面崩（与网页版 parsePayload 行为一致）
        val raw = JSONObject("""{"uid":"m6","payload":"{not json"}""")
        assertNull(Message.fromJson(raw).payload)
    }

    // ---------- 4. 其它字段照常解析 ----------

    @Test
    fun `other fields parse alongside payload`() {
        val raw = JSONObject(
            """
            {"uid":"m7","type":"alarm_confirm","title":"T","content":"C",
             "is_read":true,"created_at":1790123456789,
             "payload":"{\"alarm_uid\":\"x\"}"}
            """.trimIndent()
        )
        val msg = Message.fromJson(raw)
        assertEquals("m7", msg.uid)
        assertEquals("alarm_confirm", msg.type)
        assertEquals("T", msg.title)
        assertEquals("C", msg.content)
        assertEquals(true, msg.isRead)
        assertEquals(1790123456789L, msg.createdAt)
    }
}
