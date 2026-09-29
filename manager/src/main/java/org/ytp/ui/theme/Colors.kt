/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.theme

import androidx.compose.ui.graphics.Color
import org.ytp.config.Configs

/**
 * 管理器界面共享的颜色令牌。
 *
 * 页面只负责表达颜色的用途，具体的颜色值统一放在这里维护，
 * 这样修改主题配色时可以同步影响所有页面。
 */
object YtpColors {
    // 由主题入口同步更新，普通情况下跟随系统深色模式。
    private var darkTheme = false

    /** 设置当前是否使用深色配色，供主题入口调用。 */
    internal fun setDarkTheme(enabled: Boolean) {
        darkTheme = enabled
    }

    /** 初音未来主题的两个标志色：初音青与初音粉。 */
    val MikuTeal = Color(0xFF39C5BB)
    val MikuPink = Color(0xFFE12885)

    /** 默认主色，取自当前配色方案。 */
    val DefaultPrimary = Color(Configs.DefaultPrimaryColor)

    /** 设置页调色盘中展示的主色预设，顺序对应调色盘的排列顺序。 */
    val PrimaryPresets = listOf(
        DefaultPrimary, // 初音青
        MikuPink, // 初音粉
        Color(0xFF4B8FEF), // 天空蓝
        Color(0xFF36B97B), // 薄荷绿
        Color(0xFFF48FB1), // 樱花粉
        Color(0xFF886CE0), // 紫罗兰
        Color(0xFFE53935), // 鲜红
        Color(0xFFEE9A4D) // 提灯橙
    )

    /** 用户当前选择的主色，其它主色系表面颜色都由它派生。 */
    val Primary: Color
        get() = Color(Configs.primaryColor)

    /** 深色模式下适当提亮的主色，用于按钮、导航栏等需要突出显示的控件。 */
    val ThemePrimary: Color
        get() = if (darkTheme) {
            if (isMonochrome) Color(0xFFE8E8E8) else Primary.mixWith(Color.White, 0.18f)
        } else {
            Primary
        }

    /** 主色的浅色、深色和高亮变体，用于渐变和装饰。 */
    val PrimaryLight: Color
        get() = if (darkTheme) Primary.mixWith(Color.White, 0.22f)
        else Primary.mixWith(Color.White, 0.46f)
    val PrimaryDeep: Color
        get() = Primary.mixWith(Color.Black, if (darkTheme) 0.18f else 0.10f)
    val PrimaryAccent: Color
        get() = Primary.mixWith(Color.White, if (darkTheme) 0.38f else 0.28f)
    val Success: Color
        get() = synchronizedAccent(Color(0xFF36B97B))
    val AccentPurple: Color
        get() = synchronizedAccent(Color(0xFF886CE0))
    /** 初音粉，用于需要和初音青形成呼应的强调色。 */
    val AccentPink: Color
        get() = synchronizedAccent(MikuPink)
    val Warning: Color
        get() = synchronizedAccent(Color(0xFFEE9A4D))
    val Error: Color
        get() = synchronizedAccent(Color(0xFFE56572))

    /** 模块仓库列表的状态颜色：已安装、可升级、未安装。 */
    val ModuleInstalledIcon: Color
        get() = Color(0xFF36B97B)
    val ModuleUpdateIcon: Color
        get() = Color(0xFF886CE0)
    val ModuleAvailableIcon: Color
        get() = ThemePrimary

    /** 页面主标题和正文使用的高对比度文字颜色。 */
    val TextPrimary: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFFF0F0F0)
            darkTheme -> Color(0xFFF1FBFA)
            isMonochrome -> Color(0xFF202020)
            else -> Color(0xFF2B4B4D)
        }
    /** 描述、包名等次要信息使用的文字颜色。 */
    val TextSecondary: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFFA0A0A0)
            darkTheme -> Color(0xFFA7BFBD)
            isMonochrome -> Color(0xFF686868)
            else -> Color(0xFF637F7D)
        }
    /** 版本号、强调信息等中等强调级别的文字颜色。 */
    val TextStrong: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFFD1D1D1)
            darkTheme -> Color(0xFFD6EDEA)
            isMonochrome -> Color(0xFF343434)
            else -> Color(0xFF4A6F6D)
        }
    /** 辅助标签和弱提示使用的文字颜色。 */
    val TextSoft: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF888888)
            darkTheme -> Color(0xFF8CA5A3)
            isMonochrome -> Color(0xFF777777)
            else -> Color(0xFF6E8C89)
        }

    /** 页面背景、卡片表面、边框和分割线的基础颜色。 */
    val PageBackground: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF111111)
            darkTheme -> Color(0xFF0D1517)
            isMonochrome -> Color(0xFFFAFAFA)
            else -> Color(0xFFFFFFFF)
        }

    /**
     * 页面容器底色。用户设置了自定义背景图时透明，让底图透出来；
     * 卡片等前景内容仍然使用不透明的 [Surface]。
     */
    val PageContainer: Color
        get() = if (org.ytp.config.Configs.backgroundImage.isNotEmpty()) {
            Color.Transparent
        } else {
            PageBackground
        }

    /**
     * 顶栏、底栏和分段控件使用的底色。
     *
     * 设置了自定义背景图时跟随 [org.ytp.config.Configs.surfaceAlpha] 半透明，
     * 让背景图一直铺到屏幕边缘；没有背景图时和不透明的 [PageBackground] 一样。
     */
    val PageChrome: Color
        get() = cardSurface(PageBackground)
    /**
     * 卡片等前景表面的底色。
     *
     * 设置了自定义背景图时，会按 [org.ytp.config.Configs.surfaceAlpha] 变成半透明，
     * 让底图从卡片下面透出来；没有背景图时保持原来的不透明色。
     */
    val Surface: Color
        get() = cardSurface(
            when {
                darkTheme && isMonochrome -> Color(0xFF1A1A1A)
                darkTheme -> Color(0xFF151F22)
                else -> Color(0xFFFFFFFF)
            }
        )

    /** 自定义背景图存在时，按用户设定的不透明度返回表面色，否则原样返回。 */
    private fun cardSurface(color: Color): Color =
        if (org.ytp.config.Configs.backgroundImage.isNotEmpty()) {
            color.copy(alpha = color.alpha * org.ytp.config.Configs.surfaceAlpha)
        } else {
            color
        }
    val Border: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF363636)
            darkTheme -> Color(0xFF2B3A3D)
            isMonochrome -> Color(0xFFE1E1E1)
            else -> Color(0xFFE1F0EF)
        }
    val Divider: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF2C2C2C)
            darkTheme -> Color(0xFF223033)
            isMonochrome -> Color(0xFFEEEEEE)
            else -> Color(0xFFEFF8F7)
        }

    /** 加载占位区域的基础色和高亮色，避免在黑白主题下失去层次。 */
    val ShimmerBase: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF292929)
            darkTheme -> Color(0xFF1F2B2D)
            isMonochrome -> Color(0xFFE3E3E3)
            else -> PrimaryContainer.copy(alpha = 0.9f)
        }
    val ShimmerHighlight: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF414141)
            darkTheme -> Color(0xFF324549)
            isMonochrome -> Color(0xFFF8F8F8)
            else -> PrimaryContainer.copy(alpha = 0.2f)
        }

    /** 主色容器和浅色表面，用于图标背景、标签和卡片内区域。 */
    val PrimaryContainer: Color
        get() = cardSurface(
            if (darkTheme || isMonochrome) IconButtonSurface
            else Primary.mixWith(Color.White, 0.92f)
        )
    val PrimarySurface: Color
        get() = cardSurface(
            when {
                darkTheme && isMonochrome -> Color(0xFF242424)
                darkTheme -> Primary.mixWith(PageBackground, 0.86f)
                isMonochrome -> Color.White.copy(alpha = 0.72f)
                else -> Primary.mixWith(Color.White, 0.94f)
            }
        )
    val PrimarySurfaceHighlight: Color
        get() = if (darkTheme) Surface
        else if (isMonochrome) cardSurface(Color(0xFFFCFCFC)) else cardSurface(Color(0xFFFFFFFF))
    val AccentPurpleContainer: Color
        get() = cardSurface(
            when {
                isMonochrome -> PrimaryContainer
                darkTheme -> AccentPurple.mixWith(Surface, 0.84f)
                else -> Color(0xFFFAF8FE)
            }
        )
    val ErrorContainer: Color
        get() = cardSurface(
            when {
                isMonochrome -> PrimaryContainer
                darkTheme -> Error.mixWith(Surface, 0.84f)
                else -> Color(0xFFFFF8F8)
            }
        )
    val ControlTrackOff: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF454545)
            darkTheme -> Color(0xFF33484A)
            isMonochrome -> Color(0xFFE7E7E7)
            else -> Color(0xFFEAF4F3)
        }

    /** 主色背景上的前景色，以及操作控件的阴影颜色。 */
    val OnPrimary: Color
        get() = if (ThemePrimary.luminanceValue() > 0.62f) Color(0xFF11151B) else Color.White
    val PrimaryActionContent: Color
        get() = if (darkTheme && isMonochrome) Color(0xFF202020) else OnPrimary
    val Shadow: Color
        get() = if (darkTheme || isMonochrome) Color.Black else Color(0xFFA9D6D4)

    /** 当前主题状态，供需要直接处理图标或原生控件的组件使用。 */
    val IsMonochromeTheme: Boolean
        get() = isMonochrome
    val IsDarkTheme: Boolean
        get() = darkTheme

    /** 图标内容色和主要操作按钮的表面、边框颜色。 */
    val IconContent: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFFC4C4C4)
            darkTheme -> ThemePrimary
            isMonochrome -> Color(0xFF4A4A4A)
            else -> Primary
        }
    val PrimaryActionSurface: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFFE5E5E5)
            isMonochrome -> Color(0xFF303030)
            else -> Primary
        }
    val PrimaryActionBorder: Color
        get() = if (isMonochrome) {
            if (darkTheme) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.14f)
        } else if (darkTheme) {
            Color.White.copy(alpha = 0.16f)
        } else {
            Color.Transparent
        }
    val IconButtonSurface: Color
        get() = when {
            darkTheme && isMonochrome -> Color(0xFF2A2A2A)
            darkTheme -> Primary.mixWith(PageBackground, 0.78f)
            isMonochrome -> Color.White.copy(alpha = 0.78f)
            else -> PrimaryContainer
        }
    val IconButtonBorder: Color
        get() = when {
            darkTheme && isMonochrome -> Color.White.copy(alpha = 0.16f)
            darkTheme -> Color.White.copy(alpha = 0.12f)
            isMonochrome -> Color.Black.copy(alpha = 0.08f)
            else -> Color.Transparent
        }
    val FilledIconContent: Color
        get() = if (isMonochrome) IconContent else OnPrimary

    /** 图标背景色，深色和黑白主题使用独立的中性表面。 */
    fun iconBackground(accent: Color, alpha: Float): Color =
        if (isMonochrome || darkTheme) IconButtonSurface else accent.copy(alpha = alpha)

    /** 图标前景色，深色模式下使用提亮后的强调色。 */
    fun iconForeground(accent: Color): Color =
        when {
            isMonochrome -> IconContent
            darkTheme -> accent.mixWith(Color.White, 0.18f)
            else -> accent
        }

    /** 装饰性点缀色，黑白主题下隐藏彩色装饰以保持整体统一。 */
    fun decorativeAccent(accent: Color, alpha: Float): Color =
        if (isMonochrome) Color.Transparent else accent.copy(alpha = alpha)

    /** 饱和度很低的主色视为黑白主题，统一切换到中性色方案。 */
    private val isMonochrome: Boolean
        get() = Primary.isApproximatelyGrayscale()

    /** 深色或黑白主题下提亮功能色，保证小面积状态色仍然清晰。 */
    private fun synchronizedAccent(default: Color): Color =
        when {
            isMonochrome && darkTheme -> Color(0xFFBDBDBD)
            isMonochrome -> Primary
            darkTheme -> default.mixWith(Color.White, 0.18f)
            else -> default
        }

    /** 计算感知亮度，用于决定按钮文字使用黑色还是白色。 */
    private fun Color.luminanceValue(): Float =
        red * 0.299f + green * 0.587f + blue * 0.114f

    /** 判断颜色是否接近灰度色。 */
    private fun Color.isApproximatelyGrayscale(): Boolean =
        maxOf(red, green, blue) - minOf(red, green, blue) <= 0.04f

    /** 在当前颜色和目标颜色之间进行线性混合。 */
    private fun Color.mixWith(target: Color, amount: Float): Color = Color(
        red = red + (target.red - red) * amount,
        green = green + (target.green - green) * amount,
        blue = blue + (target.blue - blue) * amount,
        alpha = alpha + (target.alpha - alpha) * amount
    )
}
