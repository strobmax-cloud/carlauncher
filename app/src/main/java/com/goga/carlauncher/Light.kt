package com.goga.carlauncher

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Custom-drawn widgets that know their own light look. */
interface LightAware { fun toLight() }

/**
 * v1.6.1: day (light) look for every classic layout (Кокпит … Спорт). Instead of a second copy of each layout,
 * the finished view tree is recoloured once at build time: dark neutral surfaces → pale, white/grey text and
 * glyphs → ink, while vivid blocks (accent buttons, coloured tiles, album cards) and everything on them keep
 * their colours. Cards made with Ui.card come out light already via [Themes.lightOf]. No runtime cost.
 */
object LightPass {
    const val INK = 0xFF16171A.toInt()
    const val INK2 = 0xFF5B5E66.toInt()
    /** Classic layouts recoloured by this pass (the v1.6 dashboards have their own day look). */
    fun applies(layout: String) = layout !in setOf("cluster", "chrono", "radial", "showcase", "screens")

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    private fun sat(c: Int): Double { val mx = maxOf(Color.red(c), Color.green(c), Color.blue(c)); val mn = minOf(Color.red(c), Color.green(c), Color.blue(c)); return if (mx == 0) 0.0 else (mx - mn) / mx.toDouble() }
    /** Saturated and bright enough to be a "colour block" (accent, tile colour, album gradient). */
    fun vivid(c: Int) = Color.alpha(c) > 0x60 && sat(c) > 0.55 && maxOf(Color.red(c), Color.green(c), Color.blue(c)) > 110

    /** Foreground: white / light grey → ink, keeping alpha; anything coloured stays. */
    fun fg(c: Int): Int {
        if (Color.alpha(c) == 0 || sat(c) > 0.3 || lum(c) < 0.42) return c
        val ink = if (lum(c) > 0.75) INK else INK2
        return (Color.alpha(c) shl 24) or (ink and 0xFFFFFF)
    }
    /** Background: dark surface → pale (keeps a hint of its hue and its alpha); faint white overlays → faint black; null = keep. */
    fun bg(c: Int): Int? {
        val a = Color.alpha(c)
        if (a == 0 || vivid(c)) return null
        if (lum(c) < 0.35) {
            val v = Ui.blend(c or (0xFF shl 24), 0xE0F7F8FA.toInt())
            return (maxOf(a, 0x99) shl 24) or (v and 0xFFFFFF)
        }
        if (lum(c) > 0.8 && a < 0x90) return ((a * 0.55).toInt().coerceAtLeast(0x0E) shl 24)
        return null
    }

    /** Returns true if the drawable is a coloured block (children keep white text). */
    private fun drawable(d: Drawable?): Boolean {
        when (d) {
            null -> return false
            is ChamferDrawable -> { d.toLight(); return false }
            is android.graphics.drawable.ColorDrawable -> { val c = d.color; if (vivid(c)) return true; bg(c)?.let { d.color = it }; return false }
            is BitmapDrawable -> { d.colorFilter = PorterDuffColorFilter(0xFFD9DADF.toInt(), PorterDuff.Mode.SCREEN); return false }
            is GradientDrawable -> {
                val cols = d.colors
                if (cols != null) {
                    if (cols.any { vivid(it) }) return true
                    d.colors = IntArray(cols.size) { bg(cols[it]) ?: cols[it] }
                    return false
                }
                val c = d.color?.defaultColor ?: return false
                if (vivid(c)) return true
                bg(c)?.let { d.setColor(it) }
                return false
            }
            is StateListDrawable -> {
                if (android.os.Build.VERSION.SDK_INT < 29) return false
                var block = false
                for (k in 0 until d.stateCount) block = drawable(d.getStateDrawable(k)) || block
                return block
            }
            else -> return false
        }
    }

    fun apply(v: View, onBlock: Boolean = false) {
        if (v is LightAware && !onBlock) v.toLight()
        val block = onBlock || drawable(v.background)
        when (v) {
            is TextView -> if (!block) {
                v.setTextColor(fg(v.currentTextColor))
                if (v.shadowRadius > 0) v.setShadowLayer(0f, 0f, 0f, 0)
            }
            is ImageView -> if (!block) v.imageTintList?.defaultColor?.let { t -> val n = fg(t); if (n != t) v.imageTintList = ColorStateList.valueOf(n) }
        }
        if (v is ViewGroup) for (k in 0 until v.childCount) apply(v.getChildAt(k), block)
    }
}
