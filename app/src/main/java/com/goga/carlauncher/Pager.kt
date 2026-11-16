package com.goga.carlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * v1.7.1 home screens pager (no AndroidX): pages side by side, horizontal swipe with snap (≈160 ms), vertical
 * gestures and taps go to the children as usual. [onPage] is called when the page changes (swipe or [go]).
 */
class HomePager(c: Context) : ViewGroup(c) {
    var page = 0; private set
    var onPage: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (anim == null && !dragging) scrollTo(page * w, 0)
    }

    fun go(i: Int, animate: Boolean = true) {
        val n = i.coerceIn(0, maxOf(0, childCount - 1))
        val changed = n != page
        page = n
        anim?.cancel(); anim = null
        val target = n * width
        if (!animate || width == 0) scrollTo(target, 0)
        else ValueAnimator.ofInt(scrollX, target).apply {
            duration = 160
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { if (anim === this@apply) anim = null } })
            anim = this; start()
        }
        if (changed) onPage?.invoke(n)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.4f) { dragging = true; lastX = e.x; parent?.requestDisallowInterceptTouchEvent(true) }
            }
        }
        return dragging
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) dragging = true
                if (dragging) {
                    anim?.cancel(); anim = null
                    val max = (childCount - 1) * width
                    scrollTo((scrollX - (e.x - lastX)).toInt().coerceIn(-width / 6, max + width / 6), 0); lastX = e.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000); val v = vt?.xVelocity ?: 0f
                val w = maxOf(1, width)
                var target = Math.round(scrollX / w.toFloat())
                if (abs(v) > 900) target = if (v < 0) page + 1 else page - 1
                dragging = false; vt?.recycle(); vt = null
                go(target)
            }
        }
        return true
    }
}

/** Page dots under the switcher. */
class PageDots(c: Context, private val n: Int, private val on: Int, private val off: Int) : View(c) {
    var current = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(cv: Canvas) {
        if (n <= 1) return
        val d = height * 0.6f; val gap = d * 0.9f; val wActive = d * 2.6f
        val total = (n - 1) * (d + gap) + wActive
        var x = (width - total) / 2f; val y = (height - d) / 2f
        for (i in 0 until n) {
            val w = if (i == current) wActive else d
            p.color = if (i == current) on else off
            r.set(x, y, x + w, y + d); cv.drawRoundRect(r, d / 2, d / 2, p)
            x += w + gap
        }
    }
}

/**
 * v1.7.1 «Столбики» moved OUT of the album cover: vertical stacks of horizontal bars along the left / right edge of
 * the content area, growing inwards from the edge. Never over the artwork. Optional, off by default (Prefs.musicFx).
 */
class SideBars(c: Context, private val leftEdge: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var shaderW = -1; private var shaderC = 0
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (Prefs.musicFx == "bars") MusicFx.live.add(this) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); MusicFx.live.remove(this) }
    override fun onDraw(cv: Canvas) {
        if (!MusicFx.on || Prefs.musicFx != "bars") return
        val lv = MusicFx.shown; val n = lv.size
        val ac = Dash.accent()
        if (shaderW != width || shaderC != ac) {
            shaderW = width; shaderC = ac
            val solid = (ac and 0x00FFFFFF) or (0xE6 shl 24); val clear = ac and 0x00FFFFFF
            p.shader = if (leftEdge) LinearGradient(0f, 0f, width.toFloat(), 0f, solid, clear, Shader.TileMode.CLAMP)
                       else LinearGradient(width.toFloat(), 0f, 0f, 0f, solid, clear, Shader.TileMode.CLAMP)
        }
        val top = height * 0.08f; val span = height * 0.84f
        val step = span / n; val bh = step * 0.55f
        for (i in 0 until n) {
            // bass at the bottom, treble at the top
            val lvl = lv[n - 1 - i]
            val len = maxOf(bh, width * lvl)
            val y = top + step * i + (step - bh) / 2
            if (leftEdge) r.set(0f, y, len, y + bh) else r.set(width - len, y, width.toFloat(), y + bh)
            cv.drawRoundRect(r, bh / 2, bh / 2, p)
        }
    }
}

@Suppress("unused") private fun unusedPager(v: View) {}
