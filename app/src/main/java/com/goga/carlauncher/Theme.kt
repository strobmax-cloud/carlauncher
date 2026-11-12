package com.goga.carlauncher

import android.graphics.Typeface

/**
 * Five visual themes. The same values are mirrored in tools/themes.json → CarLauncher-preview.html,
 * so the HTML simulator shows exactly what the APK draws.
 */
class Theme(
    val id: String,
    val name: String,
    val wallpaper: String,      // built-in wallpaper id
    val accent: Int,
    val glass: Boolean,         // true: cards are frosted glass over the wallpaper; false: solid colour blocks (Bento)
    val tint: Int,              // ARGB card tint over the blurred wallpaper (glass) / solid card colour (blocks)
    val border: Int,            // 1px hairline, 0 = none
    val radius: Int,            // card corner radius, design px
    val clockFont: String,      // light | thin | condensed
    val clockSize: Int,
    val clockFg: Int,
    val clockBg: Int,           // 0 = card tint; else solid block colour (Bento)
    val dateFg: Int,
    val speedBg: Int,
    val speedFg: Int,
    val tileBlocks: Boolean,    // tiles drawn as bold colour blocks with large glyph (Bento)
    val bento: Boolean,         // varied tile widths
    val wallDim: Int            // ARGB dim over wallpaper
) {
    val clockTypeface: Typeface get() = when (clockFont) {
        "thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        "condensed" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
}

object Themes {
    private const val W = 0xFFFFFFFF.toInt()
    val ALL = listOf(
        Theme("glass", "Стекло", "dusk", 0xFF0A84FF.toInt(), true, 0x6B141418, 0x1AFFFFFF, 24,
            "light", 120, W, 0, 0xFFD1D1D6.toInt(), 0, W, false, false, 0x14000000),
        Theme("aurora", "Сияние", "aurora", 0xFF30D5C8.toInt(), true, 0x70061418, 0x2478FFDC, 24,
            "thin", 124, W, 0, 0xFF9FF5E8.toInt(), 0, W, false, false, 0x00000000),
        Theme("bento", "Бенто", "fluid", 0xFFFF9F0A.toInt(), false, 0xF0141416.toInt(), 0, 28,
            "condensed", 116, 0xFF000000.toInt(), 0xFFFF9F0A.toInt(), 0xCC000000.toInt(), 0xFFD4F84A.toInt(), 0xFF000000.toInt(), true, true, 0x1F000000),
        Theme("synth", "Синтвейв", "synth", 0xFFFF3EA5.toInt(), true, 0x7A1E0830, 0x59FF40AA, 14,
            "condensed", 116, W, 0, 0xFFFF8AD0.toInt(), 0, W, false, false, 0x1A000000),
        Theme("city", "Ночной город", "city", 0xFFFFB340.toInt(), true, 0x8C000000.toInt(), 0, 22,
            "light", 120, W, 0, 0xFFFFD08A.toInt(), 0, W, false, false, 0x26000000)
    )
    var current: Theme = ALL[0]
    /** v1.6.1 day look of a theme for the classic layouts: milky glass, ink type, white veil over the wallpaper. */
    fun lightOf(t: Theme): Theme = Theme(t.id, t.name, t.wallpaper, t.accent, t.glass,
        if (t.glass) 0xB8FFFFFF.toInt() else 0xF2F4F5F7.toInt(), 0x1F000000, t.radius, t.clockFont, t.clockSize,
        if (t.clockBg != 0) t.clockFg else LightPass.INK, t.clockBg, if (t.clockBg != 0) t.dateFg else LightPass.INK2,
        t.speedBg, if (t.speedBg != 0) t.speedFg else LightPass.INK, t.tileBlocks, t.bento, LIGHT_VEIL)
    const val LIGHT_VEIL = 0xC4EEF0F3.toInt()
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]
    /** v1.7.0: the theme with the user's accent colour (Settings → Тема → «Цвет акцента»). */
    fun withAccent(t: Theme, accent: Int?): Theme = if (accent == null || accent == t.accent) t else Theme(t.id, t.name, t.wallpaper, accent, t.glass,
        t.tint, t.border, t.radius, t.clockFont, t.clockSize, t.clockFg, if (t.clockBg != 0) accent else 0, t.dateFg, t.speedBg,
        t.speedFg, t.tileBlocks, t.bento, t.wallDim)
    /** Theme chosen in Settings + accent colour. */
    fun active(): Theme = withAccent(byId(Prefs.theme), Accents.color(Prefs.accent))
}
