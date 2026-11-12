package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * v1.6.2 — album cover drawn by the view itself: rounded corners (or a full circle) with anti-aliased edges, a soft
 * drop shadow and a faint glass rim, so it never looks like a hard square cut-out. Works the same with hardware and
 * software rendering (no reliance on clipToOutline). The placeholder note icon uses the normal ImageView path.
 */
class CoverView(c: Context, private val square: Boolean) : ImageView(c) {
    var radiusPx = 0f; set(v) { field = v; invalidate() }
    var circle = false; set(v) { field = v; invalidate() }
    var shadow = true; set(v) { field = v; invalidate() }
    private var cover: Bitmap? = null
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mx = Matrix(); private val r = RectF()
    var placeholderBg = 0x14FFFFFF; set(v) { field = v; invalidate() }
    /** Placeholder icon inset (fraction of the cover size on each side). */
    var placeholderPad = 0.28f; set(v) { field = v; invalidate() }
    private var shadowBmp: Bitmap? = null; private var shadowKey = 0
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG); private val haloP = Paint(Paint.ANTI_ALIAS_FLAG); private val refP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimP = Paint(Paint.ANTI_ALIAS_FLAG); private val br = RectF(); private val clip = Path()

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (Prefs.musicFx == "bars") MusicFx.live.add(this) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); MusicFx.live.remove(this); shadowBmp?.recycle(); shadowBmp = null }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (square) { val h = MeasureSpec.getSize(heightMeasureSpec); setMeasuredDimension(h, h) }
        else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setCover(b: Bitmap?) {
        cover = b
        if (b != null) { setImageDrawable(null); setPadding(0, 0, 0, 0); ip.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        else ip.shader = null
        invalidate()
    }
    val hasCover get() = cover != null

    private fun shapeRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val m = if (shadow && !circle) min(w, h) * 0.045f else 0f
        r.set(m, m * 0.6f, w - m, h - m * 1.4f)
        if (circle) { val s = min(r.width(), r.height()); r.set((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2) }
        return r
    }
    private fun drawShape(cv: Canvas, rr: RectF, p: Paint) {
        if (circle) cv.drawOval(rr, p) else { val k = min(radiusPx, min(rr.width(), rr.height()) / 2); cv.drawRoundRect(rr, k, k, p) }
    }

    override fun onDraw(cv: Canvas) {
        val b = cover
        val rr = shapeRect()
        if (b == null) {     // placeholder: same soft shape behind the note icon
            bgP.color = placeholderBg; drawShape(cv, rr, bgP)
            val d = drawable ?: return
            val sz = (min(rr.width(), rr.height()) * (1 - 2 * placeholderPad)).toInt()
            val l = (rr.centerX() - sz / 2f).toInt(); val t = (rr.centerY() - sz / 2f).toInt()
            d.setBounds(l, t, l + sz, t + sz); d.draw(cv); return
        }
        if (shadow && !circle) {   // blurred shadow painted once per size (cheap to redraw at 30 fps with the bars)
            val key = width * 31 + height + radiusPx.toInt() * 7
            var sb = shadowBmp
            if (sb == null || shadowKey != key) {
                sb?.recycle(); sb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val m = min(width, height) * 0.045f
                sp.color = 0x73000000; sp.maskFilter = BlurMaskFilter(m * 1.1f, BlurMaskFilter.Blur.NORMAL)
                val c2 = Canvas(sb); c2.translate(0f, m * 0.55f); drawShape(c2, RectF(rr.left + m * 0.3f, rr.top + m * 0.3f, rr.right - m * 0.3f, rr.bottom), sp)
                shadowBmp = sb; shadowKey = key
            }
            cv.drawBitmap(sb, 0f, 0f, null)
        }
        // centre-crop the art into the shape
        val s = max(rr.width() / b.width, rr.height() / b.height)
        mx.setScale(s, s); mx.postTranslate(rr.centerX() - b.width * s / 2, rr.centerY() - b.height * s / 2)
        ip.shader?.setLocalMatrix(mx)
        drawShape(cv, rr, ip)
        if (circle) {   // set into its ring: soft inner shade towards the edge
            sheen.shader = RadialGradient(rr.centerX(), rr.centerY(), rr.width() / 2, intArrayOf(0, 0, 0x59000000), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
            cv.drawOval(rr, sheen)
        }
        // glass: soft top sheen + hairline rim (a touch darker at the bottom for depth)
        sheen.shader = LinearGradient(0f, rr.top, 0f, rr.top + rr.height() * 0.5f, 0x1FFFFFFF, 0, Shader.TileMode.CLAMP)
        drawShape(cv, rr, sheen)
        rim.strokeWidth = max(1f, min(width, height) / 160f)
        rim.shader = LinearGradient(0f, rr.top, 0f, rr.bottom, 0x40FFFFFF, 0x26000000, Shader.TileMode.CLAMP)
        val i = rim.strokeWidth / 2
        drawShape(cv, RectF(rr.left + i, rr.top + i, rr.right - i, rr.bottom - i), rim)
        if (MusicFx.on && Prefs.musicFx == "bars") drawBars(cv, rr)
    }

    /** v1.6.3 «Столбики»: thin rounded spectrum bars in the lower part of the cover, accent gradient, halo, faint reflection. */
    private fun drawBars(cv: Canvas, rr: RectF) {
        val lv = MusicFx.shown; val n = lv.size
        var peak = 0f; for (v in lv) if (v > peak) peak = v
        if (peak < 0.01f) return
        cv.save()
        clip.reset(); if (circle) clip.addOval(rr, Path.Direction.CW) else { val k = min(radiusPx, min(rr.width(), rr.height()) / 2); clip.addRoundRect(rr, k, k, Path.Direction.CW) }
        cv.clipPath(clip)
        val base = rr.top + rr.height() * (if (circle) 0.74f else 0.8f)
        val maxH = rr.height() * (if (circle) 0.26f else 0.3f)
        // scrim so the bars read on any artwork (fades in with the music)
        scrimP.shader = LinearGradient(0f, base - maxH * 1.4f, 0f, rr.bottom, 0, 0xA0000000.toInt(), Shader.TileMode.CLAMP)
        scrimP.alpha = (255 * (0.4f + 0.6f * peak).coerceAtMost(1f)).toInt()
        cv.drawRect(rr.left, base - maxH * 1.4f, rr.right, rr.bottom, scrimP)
        val span = rr.width() * (if (circle) 0.6f else 0.84f); val x0 = rr.centerX() - span / 2
        val step = span / n; val bw = step * 0.56f; val rad = bw / 2
        val ac = Dash.accent()
        barP.shader = LinearGradient(0f, base, 0f, base - maxH, ac, Ui.blend(ac, 0xB3FFFFFF.toInt()), Shader.TileMode.CLAMP)
        haloP.color = Ui.alpha(ac, 0.22f)
        refP.shader = LinearGradient(0f, base, 0f, base + maxH * 0.4f, Ui.alpha(ac, 0.35f), 0, Shader.TileMode.CLAMP)
        for (b in 0 until n) {
            val hgt = max(bw, maxH * lv[b])
            val cx = x0 + step * (b + 0.5f)
            br.set(cx - bw, base - hgt - bw * 0.5f, cx + bw, base + bw * 0.5f); cv.drawRoundRect(br, bw, bw, haloP)   // soft halo
            br.set(cx - bw / 2, base - hgt, cx + bw / 2, base); cv.drawRoundRect(br, rad, rad, barP)
            br.set(cx - bw / 2, base + bw * 0.6f, cx + bw / 2, base + bw * 0.6f + hgt * 0.4f); cv.drawRoundRect(br, rad, rad, refP)   // mirrored reflection
        }
        cv.restore()
    }
}
