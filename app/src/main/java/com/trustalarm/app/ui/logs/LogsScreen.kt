package com.trustalarm.app.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.OpLog
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.fmtRelative
import com.trustalarm.app.ui.fmtTime
import com.trustalarm.app.ui.theme.TaTheme
import java.util.Calendar

/**
 * 操作日志页 —— 对应 `web/js/pages/logs.js`（设备）与 `family_logs.js`（家庭）。
 *
 * 双视角：按时间（按天分组时间线）/ 按人（按操作人聚合）。
 * 一句话摘要优先用后端 summary（见 OpLog 注释），避免各端文案漂移。
 */
@Composable
fun LogsScreen(
    vm: LogsViewModel,
    scope: LogScope,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val c = TaTheme.colors

    LaunchedEffect(Unit) { vm.load() }

    when {
        s.loading && s.logs.isEmpty() && s.error == null ->
            Column(modifier.fillMaxSize()) { Skeleton(4) }

        s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

        s.logs.isEmpty() -> EmptyState(
            icon = "📋",
            title = if (scope == LogScope.Family) "暂无家庭操作记录" else "暂无操作记录",
            sub = if (scope == LogScope.Family) {
                "成员加入、被移出、邀请等操作会记录在这里"
            } else {
                "设置闹钟、开关信任等操作会记录在这里"
            },
            modifier = modifier.fillMaxSize(),
        )

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // ---- 视角切换 ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaButton(
                    text = "按时间",
                    onClick = { vm.setViewMode(LogViewMode.Time) },
                    style = if (s.viewMode == LogViewMode.Time) TaButtonStyle.Primary
                    else TaButtonStyle.Ghost,
                    modifier = Modifier.weight(1f),
                )
                TaButton(
                    text = "按人",
                    onClick = { vm.setViewMode(LogViewMode.Person) },
                    style = if (s.viewMode == LogViewMode.Person) TaButtonStyle.Primary
                    else TaButtonStyle.Ghost,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---- 概览行 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${s.total} 条" + if (s.logs.size < s.total) "（显示最近 ${s.logs.size} 条）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                    modifier = Modifier.weight(1f),
                )
                if (s.scopeName.isNotBlank()) {
                    Text(
                        s.scopeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text3,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- 内容 ----
            if (s.viewMode == LogViewMode.Person) {
                PersonView(s.logs)
            } else {
                TimeView(s.logs)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (scope == LogScope.Family) {
                    "「按人」视图把同一个人在这个家庭里的所有操作聚合在一起，方便回答「谁做了什么」。"
                } else {
                    "「按人」视图把同一个人在这台设备上的所有操作聚合在一起，方便回答「谁动过我的设备」。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 按人视角：每人一张卡，卡内是该人的操作（不再重复显示操作人） */
@Composable
private fun PersonView(list: List<OpLog>) {
    val c = TaTheme.colors
    groupByPerson(list).forEach { p ->
        TaCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("👤", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.nickname,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = c.text,
                        )
                        Text(
                            p.kindText,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.text3,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                p.items.forEach { LogRow(it, showWho = false) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 按时间视角：按天分组 */
@Composable
private fun TimeView(list: List<OpLog>) {
    val c = TaTheme.colors
    groupByDay(list).forEach { g ->
        Text(
            g.label,
            style = MaterialTheme.typography.labelSmall,
            color = c.text3,
            modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 4.dp),
        )
        TaCard {
            Column {
                g.items.forEach { LogRow(it, showWho = true) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 单条日志（对应网页版 row） */
@Composable
private fun LogRow(item: OpLog, showWho: Boolean) {
    val c = TaTheme.colors
    val meta = actionMetaOf(item.action)
    val who = item.operatorNickname?.ifBlank { "未知用户" } ?: "未知用户"

    // 优先用后端 summary；「按人」视图去掉开头的操作人名字（分组标题已有）
    var text = item.summary.orEmpty()
    if (text.isNotBlank() && !showWho) {
        val prefix = Regex("^" + Regex.escape(who) + "\\s*")
        text = text.replace(prefix, "")
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(meta.icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (text.isNotBlank()) {
                    text
                } else {
                    (if (showWho) "$who " else "") + meta.text
                },
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${fmtTime(item.createdAt)} · ${fmtRelative(item.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
    }
}

// ============================================================
// 分组（1:1 复刻网页版 groupByPerson / groupByDay / dayLabel）
// ============================================================

private fun groupByPerson(list: List<OpLog>): List<PersonGroup> {
    val ordered = LinkedHashMap<String, MutableList<OpLog>>()
    val names = HashMap<String, String>()
    for (it in list) {
        val key = it.operatorUid ?: "(unknown)"
        ordered.getOrPut(key) { mutableListOf() }.add(it)
        names[key] = it.operatorNickname?.ifBlank { "未知用户" } ?: "未知用户"
    }
    return ordered.entries
        .map { (key, items) ->
            val kinds = LinkedHashMap<String, Int>()
            items.forEach { log ->
                val t = actionMetaOf(log.action).text
                kinds[t] = (kinds[t] ?: 0) + 1
            }
            val kindText = kinds.entries
                .sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} ${it.value}" }
            PersonGroup(
                nickname = names[key] ?: "未知用户",
                count = items.size,
                kindText = kindText,
                items = items,
            )
        }
        .sortedByDescending { it.count }
}

private fun groupByDay(list: List<OpLog>): List<DayGroup> {
    val ordered = LinkedHashMap<String, MutableList<OpLog>>()
    val labels = HashMap<String, String>()
    for (it in list) {
        val cal = Calendar.getInstance().apply { timeInMillis = it.createdAt }
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        ordered.getOrPut(key) { mutableListOf() }.add(it)
        labels[key] = dayLabel(cal)
    }
    return ordered.entries.map { (key, items) ->
        DayGroup(label = labels[key] ?: key, items = items)
    }
}

private fun dayLabel(d: Calendar): String {
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(d, today)) return "今天"
    if (sameDay(d, yesterday)) return "昨天"
    val p = { n: Int -> n.toString().padStart(2, '0') }
    return "${d.get(Calendar.YEAR)}-${p(d.get(Calendar.MONTH) + 1)}-${p(d.get(Calendar.DAY_OF_MONTH))}"
}
