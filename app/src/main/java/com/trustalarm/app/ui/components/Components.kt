package com.trustalarm.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trustalarm.app.ui.theme.TaTheme

/**
 * 通用 UI 组件 —— 复刻 `web/js/ui.js` 的行为与观感。
 *
 * ## 为什么每个组件都写「网页版对应物」
 *
 * 重写的验收标准是「功能与观感与网页版一致」，所以每个组件的**行为契约**
 * 都要能指回网页版的实现。有疑义时以网页版为准（它已经在真机上被用户用过）。
 *
 * ## 三条从网页版继承下来的硬约束
 *
 * 1. **错误态必须带重试按钮**（[ErrorState]）
 *    网页版原来 11 个页面写的是「只有文案、没有重试」，
 *    真机实测发现长辈用户看到错误后不知道怎么恢复，只能反复开关 App。
 *    所以重试入口是**必备参数**，不是可选项。
 *
 * 2. **触控热区不小于 44dp**（[TaButton] 的默认 minHeight）
 *    网页版为长辈误触问题把按钮从 32px 撑到 44px（真机量测过）。
 *
 * 3. **错误文案直接用服务端 message，不自己另造一套**
 *    后端已按 PRD §13 做成用户化表述（如「登录状态已失效，请重新登录」）。
 *    原生侧再包一层只会让两端口径漂移。
 */

// ============================================================
// 按钮
// ============================================================

/** 按钮视觉层级（对应网页版的 .btn / .btn.ghost / .btn.danger） */
enum class TaButtonStyle { Primary, Ghost, Danger }

/**
 * 主按钮。
 *
 * @param loading 处理中：显示转圈并禁用点击。
 *   ★ 这是网页版 `guardReentry()` 的等价物 —— 真机实测发现
 *   「刷新」按钮连点 10 次会发出 **30 次**请求（放大 10 倍）。
 *   网页版用 in-flight 锁实现；Compose 侧用 loading 状态 + 禁用点击，
 *   效果相同：上一次没结束就不再发。
 */
@Composable
fun TaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TaButtonStyle = TaButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    val c = TaTheme.colors
    val d = TaTheme.dimens
    // 加载中一律视为不可点 —— 这就是防重入的核心
    val clickable = enabled && !loading

    val shape = RoundedCornerShape(d.radiusSm.dp)
    // 所有按钮共用同一份「尺寸 + 热区」约束
    val base = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .defaultMinSize(minHeight = d.minTouchTarget.dp)

    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = when (style) {
                        TaButtonStyle.Primary -> Color.White
                        TaButtonStyle.Danger -> Color.White
                        TaButtonStyle.Ghost -> c.primary
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }

    when (style) {
        TaButtonStyle.Primary -> Button(
            onClick = onClick,
            modifier = base,
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.primary,
                contentColor = Color.White,
                disabledContainerColor = c.primary.copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
        ) { label() }

        TaButtonStyle.Danger -> Button(
            onClick = onClick,
            modifier = base,
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.err,
                contentColor = Color.White,
                disabledContainerColor = c.err.copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
        ) { label() }

        TaButtonStyle.Ghost -> OutlinedButton(
            onClick = onClick,
            modifier = base,
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = c.primary,
                disabledContentColor = c.primary.copy(alpha = 0.4f),
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
        ) { label() }
    }
}

// ============================================================
// 加载 / 空态 / 错误态
// ============================================================

/** 加载中（对应网页版 `loadingBlock()`：转圈 + 文案） */
@Composable
fun LoadingBlock(text: String = "加载中…", modifier: Modifier = Modifier) {
    val c = TaTheme.colors
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = c.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = c.text2)
    }
}

/**
 * 骨架屏（对应网页版 `skeleton(rows)`）。
 *
 * 用轻微呼吸的透明度动画代替网页版的 CSS shimmer ——
 * 目的是「让用户知道在加载」而不是视觉炫技。
 */
@Composable
fun Skeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    val c = TaTheme.colors
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .alpha(alpha)
                    .background(c.border.copy(alpha = 0.5f),
                        RoundedCornerShape(TaTheme.dimens.radiusSm.dp)),
            )
        }
    }
}

/**
 * 空态（对应网页版 `emptyState(icon, title, sub)`）。
 *
 * 用于「确实没有数据」——与 [ErrorState] 严格区分：
 * 空态**不该**有重试按钮（没有数据不是错误，重试也不会变），
 * 而错误态**必须**有。
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = TaTheme.colors
    val ty = androidx.compose.material3.MaterialTheme.typography
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = ty.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(title, style = ty.titleMedium, color = c.text, textAlign = TextAlign.Center)
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub,
                style = ty.bodySmall,
                color = c.text3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 加载失败态（对应网页版 `errorState(err, onRetry)`）。
 *
 * ## ★ `onRetry` 是必需参数，不是可选的
 *
 * 网页版最初 11 个页面都写成「只有文案、没有重试」，
 * 真机实测发现用户（尤其长辈）看到错误后唯一的出路是退出去重进，
 * 很多人根本不知道怎么恢复。所以这里把重试做成**签名的一部分** ——
 * 想构造错误态就必须提供重试回调，从类型上杜绝再漏掉。
 *
 * @param message 直接传服务端返回的 message（后端已做成用户化文案，
 *   例如「请求超时（超过 15 秒无响应）。请检查网络后重试。」）。
 *   不要在这里另造一套文案，否则与网页版口径漂移。
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backText: String = "返回",
    retrying: Boolean = false,
) {
    val c = TaTheme.colors
    val ty = androidx.compose.material3.MaterialTheme.typography
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠️", style = ty.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text("加载失败", style = ty.titleMedium, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = ty.bodySmall,
            color = c.text3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TaButton(
            text = "重试",
            onClick = onRetry,
            loading = retrying,
            fillWidth = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onBack != null) {
            Spacer(Modifier.height(10.dp))
            TaButton(
                text = backText,
                onClick = onBack,
                style = TaButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ============================================================
// 状态标签
// ============================================================

/** 语义色调（对应网页版 .tag.ok / .warn / .err / .info） */
enum class TaTone { Ok, Warn, Err, Info, Neutral }

@Composable
fun TaTag(text: String, tone: TaTone = TaTone.Neutral, modifier: Modifier = Modifier) {
    val c = TaTheme.colors
    val (fg, bg) = when (tone) {
        TaTone.Ok -> c.ok to c.okBg
        TaTone.Warn -> c.warn to c.warnBg
        TaTone.Err -> c.err to c.errBg
        TaTone.Info -> c.primary to c.primaryLight
        TaTone.Neutral -> c.text2 to c.bg
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = fg)
    }
}

/** 一个可点击的卡片行（网页版 .card / .row 的等价物） */
@Composable
fun TaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = TaTheme.colors
    val m = modifier
        .fillMaxWidth()
        .background(c.card, RoundedCornerShape(TaTheme.dimens.radius.dp))
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(14.dp)
    Box(m) { content() }
}
