package com.trustalarm.app.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustalarm.app.data.Device
import com.trustalarm.app.ui.components.EmptyState
import com.trustalarm.app.ui.components.ErrorState
import com.trustalarm.app.ui.components.Skeleton
import com.trustalarm.app.ui.components.TaButton
import com.trustalarm.app.ui.components.TaButtonStyle
import com.trustalarm.app.ui.components.TaCard
import com.trustalarm.app.ui.components.TaTag
import com.trustalarm.app.ui.components.TaTone
import com.trustalarm.app.ui.onlineText
import com.trustalarm.app.ui.platformName
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 「我的设备」页 —— 对应 `web/js/pages/devices.js`。
 *
 * 展示当前用户可见的全部设备（跨家庭汇总），分「我的设备」与「家人的设备」两组。
 * 点设备进入设备详情（该页已迁移）。
 *
 * 空态引导与网页版一致：有家庭 → 「去家庭绑定」；无家庭 → 「创建家庭」（都跳 /families）。
 */
@Composable
fun DevicesScreen(
    vm: DevicesViewModel,
    onOpenDevice: (String) -> Unit,
    onGoFamilies: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val c = TaTheme.colors

    LaunchedEffect(Unit) { vm.load() }

    when {
        s.loading && s.devices.isEmpty() && s.error == null ->
            Box(modifier.fillMaxSize()) { Skeleton(3) }

        s.error != null -> ErrorState(message = s.error!!, onRetry = { vm.load() })

        !s.hasAny -> EmptyState(
            icon = "📱",
            title = "还没有设备",
            sub = if (s.hasFamily) "进入家庭即可绑定设备" else "先创建一个家庭，再绑定设备",
            modifier = modifier.fillMaxSize(),
        )

        else -> {
            val sorted = vm.sortedDevices()
            val mine = sorted.filter { it.device.ownerIsMe }
            val others = sorted.filter { !it.device.ownerIsMe }
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TaCard {
                    Column {
                        Text(
                            "我的设备（${mine.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.text2,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (mine.isEmpty()) {
                            Text(
                                "暂无",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.text3,
                            )
                        } else {
                            mine.forEach { row ->
                                DeviceItem(row) { onOpenDevice(row.device.uid) }
                            }
                        }
                    }
                }

                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TaCard {
                        Column {
                            Text(
                                "家人的设备（${others.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = c.text2,
                            )
                            Spacer(Modifier.height(6.dp))
                            others.forEach { row ->
                                DeviceItem(row) { onOpenDevice(row.device.uid) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "点设备进入详情，可查看闹钟、管理信任成员与查看授权。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // 空态的下方引导按钮（对应网页版 #go-fam / #go-create，都跳家庭页）
    if (!s.loading && s.error == null && !s.hasAny) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(240.dp))
            TaButton(
                text = if (s.hasFamily) "去家庭绑定" else "创建家庭",
                onClick = onGoFamilies,
                style = TaButtonStyle.Primary,
            )
        }
    }
}

/** 单台设备行（对应网页版 row(d)） */
@Composable
private fun DeviceItem(row: DeviceRow, onClick: () -> Unit) {
    val c = TaTheme.colors
    val d: Device = row.device
    val (onText, isOn) = onlineText(d.onlineStatus)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (d.platform == "ios") "📱" else "🤖",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(34.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.deviceName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                TaTag(text = onText, tone = if (isOn) TaTone.Ok else TaTone.Neutral)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${platformName(d.platform)} · ${row.familyName}",
                style = MaterialTheme.typography.labelSmall,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = c.text3)
    }
}
