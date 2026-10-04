package com.trustalarm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 主题 —— **逐值复刻网页版的 CSS 变量**。
 *
 * ## 为什么不是「重新设计一套好看的配色」
 *
 * 本项目的既定要求是「手机端看起来跟网页版一致」。用户已经在真机上
 * 熟悉了网页版的观感，换一套配色等于凭空制造落差。
 * 所以这里的每个色值都直接对应 `web/css/base.css` 的 `:root` 变量，
 * 一一对得上，方便日后两端同步改。
 *
 * | 本文件的 Token | base.css 变量 | 值 |
 * |---|---|---|
 * | [TaColors.primary]       | `--c-primary`       | #2b6cb0 |
 * | [TaColors.primaryDark]   | `--c-primary-dark`  | #23548a |
 * | [TaColors.primaryLight]  | `--c-primary-light` | #ebf4ff |
 * | [TaColors.ok] / [TaColors.okBg]       | `--c-ok` / `--c-ok-bg`         | #2f855a / #f0fff4 |
 * | [TaColors.warn] / [TaColors.warnBg]   | `--c-warn` / `--c-warn-bg`     | #b7791f / #fffaf0 |
 * | [TaColors.err] / [TaColors.errBg]     | `--c-err` / `--c-err-bg`       | #c53030 / #fff5f5 |
 * | [TaColors.text] / [TaColors.text2] / [TaColors.text3] | `--c-text*` | #1f2329 / #646a73 / #8a9099 |
 * | [TaColors.border] / [TaColors.bg] / [TaColors.card]   | `--c-border/-bg/-card` | … |
 *
 * ## 为什么要自定义 Colors 而不只用 MaterialTheme.colorScheme
 *
 * Material3 的 colorScheme 槽位（primary/surface/error…）语义与本项目的
 * 语义色**不是一一对应**：我们有 4 组「状态色 + 状态底色」（ok/warn/err/primary），
 * Material3 的 error container 只覆盖其中一组。硬塞进去会在使用时到处写
 * 近似色，反而更容易漂移。
 *
 * 所以：**Material 槽位照常填（让 Material 组件长得对），
 * 同时用 [LocalTaColors] 提供项目语义色供自己的组件使用。**
 *
 * ## 尺寸 token
 *
 * `--radius: 10px` / `--radius-sm: 7px` / `--tabbar-h: 54px` / `--header-h: 52px`
 * 也都搬过来了。移动端 1px ≈ 1dp，所以直接用相同数值。
 */

/** 项目语义色（对应 base.css 的 CSS 变量） */
data class TaColors(
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val ok: Color,
    val okBg: Color,
    val warn: Color,
    val warnBg: Color,
    val err: Color,
    val errBg: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val border: Color,
    val bg: Color,
    val card: Color,
)

private val TaLightColors = TaColors(
    primary = Color(0xFF2B6CB0),
    primaryDark = Color(0xFF23548A),
    primaryLight = Color(0xFFEBF4FF),
    ok = Color(0xFF2F855A),
    okBg = Color(0xFFF0FFF4),
    warn = Color(0xFFB7791F),
    warnBg = Color(0xFFFFFAF0),
    err = Color(0xFFC53030),
    errBg = Color(0xFFFFF5F5),
    text = Color(0xFF1F2329),
    text2 = Color(0xFF646A73),
    text3 = Color(0xFF8A9099),
    border = Color(0xFFE5E7EB),
    bg = Color(0xFFF5F6F8),
    card = Color(0xFFFFFFFF),
)

/**
 * 深色配色。
 *
 * 网页版目前**没有**深色模式（base.css 里没有 prefers-color-scheme 分支），
 * 这里给一套推导值只为避免系统处于深色时 Compose 组件闪白。
 * **它不是网页版的复刻**，所以刻意保持保守：背景与卡片用中性深灰，
 * 语义色沿用浅色版的色相（提高亮度以在深底上可读）。
 *
 * 若日后网页版补了深色模式，应以网页版为准重做这一组。
 */
private val TaDarkColors = TaLightColors.copy(
    primary = Color(0xFF63A0E0),
    primaryDark = Color(0xFF4A87C4),
    primaryLight = Color(0xFF1A2A3D),
    ok = Color(0xFF5FBF8A),
    okBg = Color(0xFF14261C),
    warn = Color(0xFFE0B45C),
    warnBg = Color(0xFF2A2416),
    err = Color(0xFFE06C6C),
    errBg = Color(0xFF2B1A1A),
    text = Color(0xFFE8EAED),
    text2 = Color(0xFFA8AEB8),
    text3 = Color(0xFF7A8089),
    border = Color(0xFF2E3338),
    bg = Color(0xFF14171A),
    card = Color(0xFF1C2024),
)

/** 尺寸 token（对应 base.css 的 --radius / --tabbar-h / --header-h） */
data class TaDimens(
    val radius: Int = 10,
    val radiusSm: Int = 7,
    val tabbarHeight: Int = 54,
    val headerHeight: Int = 52,
    /** 触控热区下限。网页版为此把按钮从 32px 撑到 44px（真机实测过） */
    val minTouchTarget: Int = 44,
)

val LocalTaColors = staticCompositionLocalOf { TaLightColors }
val LocalTaDimens = staticCompositionLocalOf { TaDimens() }

/** 方便在 Composable 里取语义色：`TaTheme.colors.err` */
object TaTheme {
    val colors: TaColors
        @Composable get() = LocalTaColors.current
    val dimens: TaDimens
        @Composable get() = LocalTaDimens.current
}

/**
 * 字号体系。
 *
 * 网页版是 `font-size: 15px` 为基准的连续缩放（14/15/16/20/24…），
 * 没有离散的 typography 阶梯。这里按网页版**实际用到**的字号建一套，
 * 而不是套用 Material3 默认阶梯（它的 bodyLarge 是 16sp、titleMedium 16sp，
 * 与网页版对不上，会让同一段文字在两端大小不同）。
 *
 * 中文字体权重：网页版标题用 600（不是 700）—— 700 在部分安卓 ROM 的
 * 中文字体上会触发伪粗体，显得糊。这里保持 600。
 */
val TaTypography = Typography(
    // 页面主标题（网页版 .page-title，20px/600）
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    // 卡片标题 / 区块标题（17px/600）
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    // 列表项主文本（15px/400）
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    // 次要文本（14px/400）
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    // 辅助/说明文本（13px/400）
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    // 小字标签（12px/400）
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    // 按钮（15px/500 —— 网页版 .btn 是 500，不是 600）
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
)

@Composable
fun TaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val taColors = if (darkTheme) TaDarkColors else TaLightColors

    // Material3 槽位：只填会被 Material 组件（Button/TextField/Card…）
    // 直接用到的那几个，映射到项目语义色，保证默认组件不跑偏。
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = taColors.primary,
            onPrimary = Color.White,
            primaryContainer = taColors.primaryLight,
            onPrimaryContainer = taColors.primaryDark,
            background = taColors.bg,
            onBackground = taColors.text,
            surface = taColors.card,
            onSurface = taColors.text,
            surfaceVariant = taColors.bg,
            onSurfaceVariant = taColors.text2,
            outline = taColors.border,
            error = taColors.err,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = taColors.primary,
            onPrimary = Color.White,
            primaryContainer = taColors.primaryLight,
            onPrimaryContainer = taColors.primaryDark,
            background = taColors.bg,
            onBackground = taColors.text,
            surface = taColors.card,
            onSurface = taColors.text,
            surfaceVariant = taColors.bg,
            onSurfaceVariant = taColors.text2,
            outline = taColors.border,
            error = taColors.err,
            onError = Color.White,
        )
    }

    CompositionLocalProvider(
        LocalTaColors provides taColors,
        LocalTaDimens provides TaDimens(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TaTypography,
            content = content,
        )
    }
}
