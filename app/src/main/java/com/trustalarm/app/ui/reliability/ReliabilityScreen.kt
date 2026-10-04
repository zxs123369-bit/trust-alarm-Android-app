package com.trustalarm.app.ui.reliability

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustalarm.app.alarm.AlarmScheduler
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.localalarms.openExactAlarmSettings
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 「后台可靠性」页 —— 对应 `web/js/pages/reliability.js`。
 *
 * 闹钟要在息屏/后台/省电下都准时响，需要三项系统权限同时成立：
 *   ① 精确闹钟  ② 电池优化白名单  ③ 自启动
 * ①②可自测状态；③无法自测（诚实标注「需手动确认」，不假装能检测）。
 *
 * 状态每次进入页面重算（用户可能刚从系统设置返回）——用 [refreshKey] 驱动重组。
 */
@Composable
fun ReliabilityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val c = TaTheme.colors

    // 每次回到本页重新读系统状态（从设置页返回后要刷新）
    var refreshKey by remember { mutableStateOf(0) }
    val exactOk = remember(refreshKey) { AlarmScheduler.canScheduleExact(ctx) }
    val battOk = remember(refreshKey) { ReliabilityNative.isIgnoringBatteryOptimizations(ctx) }
    val brand = remember { ReliabilityNative.deviceManufacturer() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // ---- 说明 ----
        TaCard {
            Column {
                Text(
                    "为什么需要这些设置",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = c.text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "闹钟要在息屏、后台、被系统省电时都能准时响，需要下面几项系统权限。" +
                        "任何一项没开，都可能出现闹钟不响或晚响。这几项都在系统设置里，需要你手动确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text2,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- 1. 精确闹钟 ----
        TaCard {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "1. 精确闹钟权限",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(exactOk)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "没有它，系统会把闹钟「随便找个时间」触发，可能差几分钟甚至更久。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(10.dp))
                TaButton(
                    text = if (exactOk) "已开启，无需操作" else "去开启",
                    onClick = {
                        openExactAlarmSettings(ctx)
                        refreshKey++
                    },
                    enabled = !exactOk,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- 2. 电池优化白名单 ----
        TaCard {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "2. 电池优化白名单",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(battOk)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "加入白名单后，系统省电时不会冻结本应用，闹钟才能准时唤醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(10.dp))
                TaButton(
                    text = if (battOk) "已在白名单中" else "去加入白名单",
                    onClick = {
                        ReliabilityNative.openBatteryOptimizationSettings(ctx)
                        refreshKey++
                    },
                    enabled = !battOk,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- 3. 自启动（无法自测，诚实标注）----
        TaCard {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "3. 允许自启动 / 后台运行",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "需手动确认",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text3,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "国产手机（小米/华为/OPPO/vivo 等）会主动清理后台，被清理后闹钟会停。" +
                        "请按下面提示开启自启动：\n" +
                        ReliabilityNative.autoStartHint(brand),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text2,
                )
                Spacer(Modifier.height(10.dp))
                TaButton(
                    text = "打开设置",
                    onClick = {
                        // 先试厂商专有页；没有就退回应用详情（唯一稳定入口）
                        val opened = ReliabilityNative.openAutoStartSettings(ctx)
                        if (!opened) ReliabilityNative.openAppSettings(ctx)
                    },
                )
                Spacer(Modifier.height(8.dp))
                TaButton(
                    text = "打开应用详情（备选入口）",
                    onClick = { ReliabilityNative.openAppSettings(ctx) },
                    style = TaButtonStyle.Ghost,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- 提示 ----
        TaCard {
            Text(
                "提示：从「最近任务」里划掉本应用不影响闹钟；" +
                    "但点系统清理工具的「强制停止 / 一键清理」会清空闹钟，" +
                    "那种情况下重新打开本应用即可自动恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
            )
        }
        Spacer(Modifier.height(12.dp))

        TaButton(text = "返回设置", onClick = onBack, style = TaButtonStyle.Ghost)
        Spacer(Modifier.height(20.dp))
    }

    // 从系统设置返回时刷新（与网页版 visibilitychange 等价）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}

@Composable
private fun StatusChip(ok: Boolean) {
    Text(
        if (ok) "已开启" else "未开启",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (ok) TaTheme.colors.ok else TaTheme.colors.err,
    )
}
