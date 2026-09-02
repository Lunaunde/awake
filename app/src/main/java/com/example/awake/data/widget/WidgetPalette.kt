package com.example.awake.data.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import com.example.awake.data.repository.ThemeModeStore
import com.example.awake.ui.theme.ThemeMode

/**
 * 小组件侧配色：全部与 App 内保持同源。
 *
 * - 课程格配色复刻 [com.example.awake.ui.components.CourseCard.paletteForAccent] 的
 *   HSV 算法：同一色相，浅色模式浅底 + 高饱和 accent，深色模式低明度深底 + 亮 accent；
 * - 主题主色在 Android 12+ 直接读取系统动态取色（system_accent1_600 / system_accent1_200），
 *   与 App 内 `dynamicLightColorScheme` / `dynamicDarkColorScheme` 的 primary 一致；
 * - 深浅判断遵循 App 内 ThemeModeStore（跟随系统 / 强制浅色 / 强制深色）。
 */
internal object WidgetPalette {

    /** 单个课程格配色：填充底色 + 描边色。 */
    data class CellPalette(val background: Int, val accent: Int)

    private const val ACCENT_BORDER_ALPHA = 0.62f

    /** 非本周课程的淡化透明度，与 App 内课程卡 alpha 0.42 一致。 */
    const val DIM_ALPHA = 0.42f

    /**
     * 非本周课程整卡淡化：RemoteViews 的逐层染色是不透明的，无法设置 view alpha，
     * 这里把透明度预混到组件底色上（0.42·前景 + 0.58·背景），视觉与 App 内半透明等价。
     */
    fun fadeOnto(color: Int, widgetBackground: Int, alpha: Float = DIM_ALPHA): Int = blend(color, alpha, widgetBackground)

    /** 通用混色：fg 以 alpha 叠在 bg 上，返回不透明结果。 */
    private fun blend(fg: Int, alpha: Float, bg: Int): Int {
        val r = (Color.red(fg) * alpha + Color.red(bg) * (1 - alpha)).toInt()
        val g = (Color.green(fg) * alpha + Color.green(bg) * (1 - alpha)).toInt()
        val b = (Color.blue(fg) * alpha + Color.blue(bg) * (1 - alpha)).toInt()
        return Color.argb(0xFF, r, g, b)
    }

    /** 是否按深色渲染：App 内手动覆盖（浅色/深色）优先，否则跟随系统 uiMode。 */
    fun isDark(context: Context): Boolean =
        when (ThemeModeStore(context).read()) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_MASK
        }

    /**
     * 课程存储色 → 单元格配色（与 App 内 `paletteForAccent` 完全同源，仅换成 ARGB Int）。
     * color == 0 时与 App 一样按色相 0（红）处理，保证手动课程与 App 内观感一致。
     */
    fun paletteForAccent(color: Int, dark: Boolean): CellPalette {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        return if (dark) {
            CellPalette(
                background = Color.HSVToColor(floatArrayOf(hue, 0.28f, 0.36f)),
                accent = Color.HSVToColor(floatArrayOf(hue, 0.80f, 0.90f))
            )
        } else {
            CellPalette(
                background = Color.HSVToColor(floatArrayOf(hue, 0.20f, 1f)),
                accent = Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.78f))
            )
        }
    }

    /**
     * App 内描边是 accent @ 62% 透明度叠在卡片底色上；RemoteViews 染色是不透明滤镜，
     * 这里把透明度预先混入组件底色，得到视觉等价的不透明描边色。
     */
    fun borderColor(accent: Int, widgetBackground: Int): Int =
        blend(accent, ACCENT_BORDER_ALPHA, widgetBackground)

    /** 主题主色：A12+ 动态取色（与 App 内 dynamic scheme 的 primary 同一 tone），低版本回退静态主题色。 */
    fun primary(context: Context, dark: Boolean): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 框架色号与 tone 反向对应：tone40→system_accent1_600（浅色）、tone80→system_accent1_200（深色）。
            val id = if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            runCatching { return context.getColor(id) }
        }
        return if (dark) 0xFFD0BCFF.toInt() else 0xFF6650A4.toInt()
    }

    /** onSurface 文字色：动态方案 neutral1 tone10/tone90，低版本回退 M3 默认。 */
    fun onSurface(context: Context, dark: Boolean): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val id = if (dark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900
            runCatching { return context.getColor(id) }
        }
        return if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
    }

    /**
     * 组件文字/控件底色的字面值（与 res/widget_colors.xml 同值）。
     * XML 资源只在桌面 inflate 视图时解析一次，切主题后不会跟随；
     * 这些颜色必须在每次组件更新时代码里按深浅显式设置。
     */
    fun textPrimary(dark: Boolean): Int = if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()

    fun textSecondary(dark: Boolean): Int = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()

    fun chipBackground(dark: Boolean): Int = if (dark) 0xFF2B2930.toInt() else 0xFFFFFFFF.toInt()

    fun chipText(dark: Boolean): Int = textPrimary(dark)

    /** 组件卡片底色：浅色固定为 App 主界面底色 #E9ECF8；深色取动态 neutral 底，低版本回退 #1C1B1F。 */
    fun surface(context: Context, dark: Boolean): Int {
        if (dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { return context.getColor(android.R.color.system_neutral1_900) }
            }
            return 0xFF1C1B1F.toInt()
        }
        return 0xFFE9ECF8.toInt()
    }
}