package com.example.awake.ui.theme

/** 外观模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode(val key: String, val title: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色")
}