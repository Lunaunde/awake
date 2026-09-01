package com.example.awake.domain.model

import kotlin.math.abs
import kotlin.random.Random

/**
 * 详情页颜色选择器的固定预设色板（马卡龙风格）。
 * 已去除色相过近的颜色（靛蓝/蓝绿/兰紫/橙/手写粉等），保证任意两个候选肉眼可辨。
 * 存储：课程主记录字段 color 以 ARGB int 保存（RGB + 透明度分量恒为 0xFF）。
 */
val DefaultCourseColors: List<Int> = listOf(
    0xFF4778E8.toInt(), // 蓝
    0xFF238FA3.toInt(), // 青
    0xFF2A9D74.toInt(), // 薄荷
    0xFF719A2C.toInt(), // 黄绿
    0xFFD99616.toInt(), // 琥珀
    0xFFE56B55.toInt(), // 珊瑚
    0xFFD84F78.toInt(), // 玫红
    0xFF7654D6.toInt()  // 紫
)

/**
 * 稳定的默认取色算法：以课程身份键为种子做散列，色相按黄金角分布展开，
 * 饱和度/明度固定为 (0.72, 0.78) —— 与详情页色板同一风格，
 * 因此周视图统一渲染出浅背景 + 高饱和描边的卡片观感。
 * 同一门课种子固定 → 跨端口/跨次同步颜色稳定，不同课程色相自然分开。
 */
fun defaultCourseColor(seed: String): Int {
    var mixed = seed.hashCode()
    mixed = (mixed xor (mixed ushr 16)) * 0x045D9F3B
    mixed = (mixed xor (mixed ushr 16)) * 0x045D9F3B
    val normalized = abs(mixed xor (mixed ushr 13)).let { it % 0x1000000 } / 16777216f
    val hue = (normalized * 137.508f) % 360f
    return hsvToRgbInt(hue, saturation = 0.72f, value = 0.78f)
}

/** 随机取一个保持当前风格（统一饱和度/明度）的课程色。 */
fun randomCourseColor(): Int = hsvToRgbInt(kotlin.random.Random.nextFloat() * 360f, saturation = 0.72f, value = 0.78f)

/**
 * 新课程默认颜色：优先按顺序取色板中未被占用（色相距离 ≥ 25°）的候选，
 * 预设耗尽或都过近时随机生成，仍然保持统一风格。
 */
fun pickNewCourseColor(usedColors: Collection<Int>): Int =
    DefaultCourseColors.firstOrNull { candidate ->
        val candidateHue = hueOf(candidate)
        usedColors.none { used -> hueDistance(hueOf(used), candidateHue) < 25f }
    } ?: randomCourseColor()

/** 提取颜色的色相（0..360）。 */
fun hueOf(color: Int): Float {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta <= 0f) return 0f
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return (hue % 360f + 360f) % 360f
}

private fun hueDistance(a: Float, b: Float): Float {
    val diff = abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

private fun hsvToRgbInt(hue: Float, saturation: Float, value: Float): Int {
    val normalizedHue = (hue % 360f + 360f) % 360f / 60f
    val chroma = value * saturation
    val secondary = chroma * (1 - Math.abs(normalizedHue % 2f - 1f))
    val offset = value - chroma
    val sector = normalizedHue.toInt().mod(6)
    val rgb: Triple<Float, Float, Float> = when (sector) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val r = ((rgb.first + offset) * 255f).toInt().coerceIn(0, 255)
    val g = ((rgb.second + offset) * 255f).toInt().coerceIn(0, 255)
    val b = ((rgb.third + offset) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}