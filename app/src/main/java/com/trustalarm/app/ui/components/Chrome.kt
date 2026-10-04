package com.trustalarm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustalarm.app.ui.nav.Dest
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 顶栏 / 底栏 —— 对应 `web/js/layout.js` 的 `setLayout()`（appbar + tabbar）。
 *
 * ## 与网页版的对应关系
 *
 * ```
 * setLayout({ title, backBtn, actions, tabbar, activeTab })
 *   → TaTopBar(title, onBack?, actions) + TaTabBar(activeRoute, unread, onNavigate)
 * ```
 *
 * 网页版把顶栏和底栏都挂在全局 `#app` 上、由每个页面自己调 `setLayout` 声明；
 * Compose 侧没有这种全局挂载点，所以由**外层 Shell 统一渲染**，
 * 页面通过一个 `PageChrome` 数据类描述自己想要的样子。这样做的原因是：
 * 顶栏/底栏在页面内容之外、滚动时不跟着走，放在每个页面里会重复实现。
 */

/** 顶栏右侧的一个动作按钮（对应 setLayout 的 `actions: [{text, onClick}]`） */
data class TopAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * 页面外壳描述：由每个 Screen 声明，Shell 据此渲染顶栏/底栏。
 *
 * `tabbar = null` 表示不显示底栏（登录页、详情页用），对应网页版 `tabbar:false`。
 */
data class PageChrome(
    val title: String,
    val back: Boolean = false,
    val actions: List<TopAction> = emptyList(),
    val tabbar: Boolean = true,
    val activeRoute: String? = null,
)

/** 顶栏（对应 `.appbar`：返回按钮 + 标题 + 右侧动作）。高度对应 `--header-h: 52px` */
@Composable
fun TaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<TopAction> = emptyList(),
) {
    val c = TaTheme.colors
    val d = TaTheme.dimens
    val ty = androidx.compose.material3.MaterialTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.headerHeight.dp)
            .background(c.card)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // 网页版返回键是「‹」，热区 44dp —— 这里保持一致
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = d.minTouchTarget.dp, minHeight = d.minTouchTarget.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = ty.headlineSmall, color = c.primary)
            }
            Spacer(Modifier.width(4.dp))
        }

        Text(
            text = title,
            style = ty.titleMedium,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        actions.forEach { a ->
            TextButton(onClick = a.onClick, enabled = a.enabled) {
                Text(a.text, style = ty.bodyMedium, color = if (a.enabled) c.primary else c.text3)
            }
        }
    }
}

/** 底部导航的一个 Tab（对应 layout.js 的 `TABS`） */
data class TabItem(
    val route: String,
    val icon: String,
    val label: String,
    val badge: Boolean = false,
)

/** 底部四个 Tab，逐项对应 `layout.js :: TABS` */
val MAIN_TABS = listOf(
    TabItem(Dest.LocalAlarms.route, "⏰", "本地闹钟"),
    TabItem(Dest.Families.route, "🏠", "家庭"),
    TabItem(Dest.Messages.route, "🔔", "消息", badge = true),
    TabItem(Dest.Settings.route, "👤", "我的"),
)

/**
 * 底栏（对应 `.tabbar`，高度 `--tabbar-h: 54px`）。
 *
 * @param activeRoute 当前高亮的 [TabItem.route]
 * @param unread      未读角标数字（0 = 不显示，>99 显示「99+」—— 与网页版一致）
 */
@Composable
fun TaTabBar(
    activeRoute: String?,
    unread: Int,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TaTheme.colors
    val d = TaTheme.dimens
    val ty = androidx.compose.material3.MaterialTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.tabbarHeight.dp)
            .background(c.card),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MAIN_TABS.forEach { tab ->
            val on = tab.route == activeRoute
            val tint = if (on) c.primary else c.text3
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = d.minTouchTarget.dp)
                    .clickable { onNavigate(tab.route) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Text(tab.icon, style = ty.bodyLarge)
                        if (tab.badge && unread > 0) {
                            // 角标：与网页版一致「99+」封顶
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(c.err, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    if (unread > 99) "99+" else unread.toString(),
                                    style = ty.labelSmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    Text(tab.label, style = ty.labelSmall, color = tint)
                }
            }
        }
    }
}
