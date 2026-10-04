package com.trustalarm.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dest.parse 路由恢复的回归测试。
 *
 * ## 为什么重要
 *
 * Nav 用 route 字符串做进程重建恢复（见 NavController.saver）。
 * 解析器与网页版 hash 路由一一对应，一旦漏掉分支，用户杀进程回前台
 * 就会**掉到首页或错误页**（表现为"返回后位置丢了"），是典型的静默回归。
 *
 * 同时锁定：非法 / 未知 / 缺参数的旧路由必须返回 null（调用方退回首页），
 * 而不是抛异常或返回一个半残的 Dest。
 */
class DestParseTest {

    // ---------- 1. 无参路由 ----------

    @Test
    fun `top level routes parse`() {
        assertEquals(Dest.Login, Dest.parse("/login"))
        assertEquals(Dest.LocalAlarms, Dest.parse("/local-alarms"))
        assertEquals(Dest.Families, Dest.parse("/families"))
        assertEquals(Dest.Devices, Dest.parse("/devices"))
        assertEquals(Dest.Messages, Dest.parse("/messages"))
        assertEquals(Dest.Requests, Dest.parse("/requests"))
        assertEquals(Dest.Logs, Dest.parse("/logs"))
        assertEquals(Dest.Settings, Dest.parse("/settings"))
        assertEquals(Dest.Reliability, Dest.parse("/reliability"))
    }

    // ---------- 2. 带参路由 ----------

    @Test
    fun `parameterized routes parse with correct args`() {
        assertEquals(Dest.Family("f1"), Dest.parse("/family/f1"))
        assertEquals(Dest.Member("f1", "m1"), Dest.parse("/family/f1/member/m1"))
        assertEquals(Dest.FamilyLogs("f1"), Dest.parse("/family/f1/logs"))
        assertEquals(Dest.Device("d1"), Dest.parse("/device/d1"))
        assertEquals(Dest.DeviceAlarms("d1"), Dest.parse("/device/d1/alarms"))
        assertEquals(Dest.DeviceTrust("d1"), Dest.parse("/device/d1/trust"))
        assertEquals(Dest.DeviceGrants("d1"), Dest.parse("/device/d1/grants"))
        assertEquals(Dest.DeviceLogs("d1"), Dest.parse("/device/d1/logs"))
    }

    // ---------- 3. 容错：首尾空白与多余斜杠 ----------

    @Test
    fun `leading trailing spaces and trailing slash tolerated`() {
        assertEquals(Dest.Families, Dest.parse("  /families  "))
        assertEquals(Dest.Families, Dest.parse("/families/"))
        assertEquals(Dest.Device("d1"), Dest.parse("/device/d1/"))
    }

    // ---------- 4. 非法输入返回 null（调用方退回首页）----------

    @Test
    fun `empty and blank yield null`() {
        assertNull(Dest.parse(""))
        assertNull(Dest.parse("   "))
        assertNull(Dest.parse("/"))
    }

    @Test
    fun `unknown routes yield null`() {
        assertNull(Dest.parse("/nope"))
        assertNull(Dest.parse("/device"))
        assertNull(Dest.parse("/family"))
        assertNull(Dest.parse("/device/d1/unknown"))
        // 参数数量不对
        assertNull(Dest.parse("/family/f1/member"))
        assertNull(Dest.parse("/device/d1/alarms/extra"))
    }

    // ---------- 5. 往返：route -> parse -> route 等价 ----------

    @Test
    fun `roundtrip route parse route is stable`() {
        val routes = listOf(
            "/login", "/local-alarms", "/families", "/family/f1",
            "/family/f1/member/m1", "/family/f1/logs", "/devices",
            "/device/d1", "/device/d1/alarms", "/device/d1/trust",
            "/device/d1/grants", "/device/d1/logs",
            "/messages", "/requests", "/logs", "/settings", "/reliability",
        )
        for (r in routes) {
            val d = Dest.parse(r)
            assertEquals("route $r 应能往返", r, d?.route)
        }
    }
}
