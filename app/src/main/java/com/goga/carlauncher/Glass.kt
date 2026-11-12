package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import java.util.WeakHashMap

/**
 * Frosted glass without live blur: draws the pre-blurred wallpaper (Wallpaper.blur) aligned to where the card sits,
 * then a flat theme tint and an optional 1px hairline. Cost ≈ two rounded-rect draws per card.
 */
object Glass {
    var root: View? = null
    private val hosts = WeakHashMap<View, Boolean>()
    fun register(v: View) { hosts[v] = true }
    fun invalidateAll() { hosts.keys.toList().forEach { it?.invalidate() } }
}

class GlassDrawable(
    private val host: View, private val radius: Float, private val tint: Int, private val border: Int,
    private val useBlur: Boolean, private val pressable: Boolean
) : Drawable() {
    private val rect = RectF()
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = border }
    private var shaderSrc: Bitmap? = null
    private var shader: BitmapShader? = null
    private val m = Matrix()
    private val loc = IntArray(2); private val rloc = IntArray(2)
    private var pressed = false

    override fun draw(c: Canvas) {
        rect.set(bounds)
        val src = if (useBlur) Wallpaper.blur else null
        val root = Glass.root
        if (src != null && root != null && root.width > 0) {
            if (src !== shaderSrc) { shaderSrc = src; shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); blurPaint.shader = shader }
            host.getLocationInWindow(loc); root.getLocationInWindow(rloc)
            m.setScale(root.width.toFloat() / src.width, root.height.toFloat() / src.height)
            m.postTranslate((rloc[0] - loc[0]).toFloat(), (rloc[1] - loc[1]).toFloat())
            shader!!.setLocalMatrix(m)
            c.drawRoundRect(rect, radius, radius, blurPaint)
        }
        c.drawRoundRect(rect, radius, radius, tintPaint)
        if (pressed) c.drawRoundRect(rect, radius, radius, pressPaint)
        if (border != 0) { rect.inset(0.5f, 0.5f); c.drawRoundRect(rect, radius, radius, borderPaint) }
    }

    override fun isStateful() = pressable
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p != pressed) { pressed = p; invalidateSelf(); return true }
        return false
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
