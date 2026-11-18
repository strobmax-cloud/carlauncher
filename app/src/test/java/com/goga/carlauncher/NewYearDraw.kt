package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * New Year mockup layer (design px 1280×720, drawn above the page, below the overlays). Test sources only.
 * Twinkle is shown as a frozen frame: every bulb has its own phase (here: a random brightness 35–100 %).
 */
class NewYearView(c: Context, val spec: Spec) : View(c) {
    class Spec(
        val garland: List<PointF>,          // polyline the bulbs follow (swags or the lens line)
        val sag: Float,                     // >0: catenary swags between successive anchors
        val trees: List<Tree>,
        val balls: List<Ball>,
        val lens: Boolean = false,
    )
    class Tree(val x: Float, val baseY: Float, val h: Float, val line: Boolean)
    class Ball(val x: Float, val topY: Float, val y: Float, val r: Float, val color: Int)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random(7)
    private val warm = intArrayOf(0xFFFFD27A.toInt(), 0xFFFF6B6B.toInt(), 0xFFFFE9B8.toInt(), 0xFF6BE3A8.toInt(), 0xFFFFD27A.toInt(), 0xFF8FCBFF.toInt())

    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        garland(cv)
        for (b in spec.balls) ball(cv, b)
        for (t in spec.trees) if (t.line) lineTree(cv, t) else glassTree(cv, t)
        cv.restore()
    }

    // ---------------- garland ----------------
    private fun garlandPath(): Path {
        val g = spec.garland; val path = Path()
        if (g.isEmpty()) return path
        path.moveTo(g[0].x, g[0].y)
        for (i in 1 until g.size) {
            val a = g[i - 1]; val b = g[i]
            if (spec.sag > 0) path.quadTo((a.x + b.x) / 2, (a.y + b.y) / 2 + spec.sag * 2, b.x, b.y) else path.lineTo(b.x, b.y)
        }
        return path
    }
    private fun garland(cv: Canvas) {
        val path = garlandPath()
        if (!spec.lens) {   // thin wire (C uses the frame line itself as the wire)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = 0x73FFFFFF; p.shader = null; p.maskFilter = null
            cv.drawPath(path, p)
        }
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2); val tan = FloatArray(2)
        var len = 0f; do { len += pm.length } while (pm.nextContour())
        val pm2 = PathMeasure(path, false)
        val step = if (spec.lens) 30f else 34f
        var d = step / 2; var i = 0; var acc = 0f
        do {
            val L = pm2.length
            while (d - acc < L) {
                pm2.getPosTan(d - acc, pos, tan)
                // bulbs hang a little below the wire (C: just inside the frame line)
                val nx = -tan[1]; val ny = tan[0]
                val off = if (spec.lens) 7f else 6f
                val sx = pos[0] + nx * off * (if (ny < 0) -1 else 1); val sy = pos[1] + Math.abs(ny) * off
                bulb(cv, sx, sy, warm[i % warm.size], 0.5f + 0.5f * rnd.nextFloat())
                d += step; i++
            }
            acc += L
        } while (pm2.nextContour())
    }
    private fun bulb(cv: Canvas, x: Float, y: Float, c: Int, k: Float, z: Float = if (spec.lens) 1f else 1.3f) {
        p.style = Paint.Style.FILL; p.maskFilter = null
        val a = (k * 255).toInt(); val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        // halo (its strength = the twinkle phase)
        p.shader = RadialGradient(x, y, 14f * z, intArrayOf(Color.argb((a * .85f).toInt(), r, g, b), Color.argb((a * .25f).toInt(), r, g, b), Color.argb(0, r, g, b)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, 14f * z, p)
        p.shader = null
        p.color = Color.argb(255, Math.min(255, r + 30), Math.min(255, g + 30), Math.min(255, b + 30)); cv.drawCircle(x, y, 3.4f * z, p)
        p.color = Color.argb((140 + 115 * k).toInt(), 255, 255, 255); cv.drawCircle(x - .6f * z, y - .6f * z, 1.5f * z, p)
    }

    // ---------------- ball ----------------
    private fun ball(cv: Canvas, b: Ball) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.shader = null
        p.color = 0x8CFFFFFF.toInt(); cv.drawLine(b.x, b.topY, b.x, b.y - b.r - 5, p)
        // soft light around
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(b.x, b.y, b.r * 2.6f, intArrayOf(Color.argb(70, Color.red(b.color), Color.green(b.color), Color.blue(b.color)), 0), null, Shader.TileMode.CLAMP)
        cv.drawCircle(b.x, b.y, b.r * 2.6f, p)
        // cap
        p.shader = LinearGradient(b.x - 5, 0f, b.x + 5, 0f, intArrayOf(0xFFB8893A.toInt(), 0xFFFFE3A0.toInt(), 0xFFB8893A.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(b.x - 5f, b.y - b.r - 6f, b.x + 5f, b.y - b.r + 2f), 2f, 2f, p)
        // glass
        val hi = blend(b.color, Color.WHITE, .55f); val lo = blend(b.color, Color.BLACK, .55f)
        p.shader = RadialGradient(b.x - b.r * .35f, b.y - b.r * .4f, b.r * 1.5f, intArrayOf(hi, b.color, lo), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(b.x, b.y, b.r, p)
        // thin gold band + highlight
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = 0x99FFE3A0.toInt()
        cv.drawArc(RectF(b.x - b.r, b.y - b.r * .28f, b.x + b.r, b.y + b.r * .28f), 0f, 180f, false, p)
        p.style = Paint.Style.FILL; p.color = 0xB3FFFFFF.toInt()
        cv.drawOval(RectF(b.x - b.r * .55f, b.y - b.r * .7f, b.x - b.r * .2f, b.y - b.r * .3f), p)
    }
    private fun blend(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a) * (1 - t) + Color.red(b) * t).toInt(), (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(), (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())

    // ---------------- trees ----------------
    private fun tierPath(x: Float, base: Float, h: Float): List<Path> {
        // three stacked tiers, slightly rounded, narrow and elegant
        val w = h * 0.62f; val tiers = ArrayList<Path>()
        val top = base - h
        val spec = listOf(Triple(0f, .42f, .40f), Triple(.22f, .70f, .70f), Triple(.45f, 1f, 1f))   // tier top (frac), bottom (frac), width frac
        for ((t0, t1, wf) in spec) {
            val yt = top + h * t0; val yb = top + h * t1 * 0.9f; val hw = w / 2 * wf
            val pth = Path(); pth.moveTo(x, yt)
            pth.quadTo(x + hw * .35f, yt + (yb - yt) * .55f, x + hw, yb)
            pth.quadTo(x, yb + 5f, x - hw, yb)
            pth.quadTo(x - hw * .35f, yt + (yb - yt) * .55f, x, yt)
            pth.close(); tiers.add(pth)
        }
        return tiers
    }
    private fun star(cv: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, r * 3.2f, intArrayOf(0x66FFD27A, 0), null, Shader.TileMode.CLAMP); cv.drawCircle(x, y, r * 3.2f, p)
        p.shader = null; p.color = 0xFFFFE2A0.toInt()
        val s = Path()
        for (i in 0 until 10) { val rr = if (i % 2 == 0) r else r * .45f; val a = Math.PI / 2 + i * Math.PI / 5
            val px = x + (rr * Math.cos(a)).toFloat(); val py = y - (rr * Math.sin(a)).toFloat(); if (i == 0) s.moveTo(px, py) else s.lineTo(px, py) }
        s.close(); cv.drawPath(s, p)
    }
    private fun shadow(cv: Canvas, x: Float, y: Float, w: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, w, intArrayOf(0x66000000, 0), null, Shader.TileMode.CLAMP)
        cv.save(); cv.scale(1f, .22f, x, y); cv.drawCircle(x, y, w, p); cv.restore(); p.shader = null
    }
    /** D «Стекло»: frosted emerald glass tiers with a light rim, tiny warm lights, a gold star, a soft contact shadow */
    private fun glassTree(cv: Canvas, t: Tree) {
        shadow(cv, t.x, t.baseY, t.h * .42f)
        // trunk
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0xCC6B4A34.toInt()
        cv.drawRoundRect(RectF(t.x - 4f, t.baseY - t.h * .12f, t.x + 4f, t.baseY), 2f, 2f, p)
        for (tp in tierPath(t.x, t.baseY - t.h * .06f, t.h * .94f)) {
            val b = RectF(); tp.computeBounds(b, true)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(b.left, b.top, b.right, b.bottom, intArrayOf(0xE64FD39A.toInt(), 0xE61E8A62.toInt(), 0xE60E4D3A.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            cv.drawPath(tp, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f; p.color = 0x59FFFFFF; cv.drawPath(tp, p)
        }
        val top = t.baseY - t.h
        for ((fx, fy) in listOf(-.10f to .30f, .12f to .40f, -.18f to .58f, .20f to .66f, -.05f to .74f, -.24f to .86f, .26f to .88f, .04f to .90f))
            bulb(cv, t.x + fx * t.h, top + fy * t.h, warm[((fx * 100).toInt() and 7) % warm.size], .6f + .4f * rnd.nextFloat(), .75f)
        star(cv, t.x, top + 2f, t.h * .075f)
    }
    /** C «Линза»: the tree drawn with the same silver double line as the frame, coloured lights on it, glowing star */
    private fun lineTree(cv: Canvas, t: Tree) {
        shadow(cv, t.x, t.baseY, t.h * .45f)
        val all = Path()
        for (tp in tierPath(t.x, t.baseY - t.h * .08f, t.h * .92f)) all.addPath(tp)
        all.moveTo(t.x, t.baseY - t.h * .08f); all.lineTo(t.x, t.baseY)
        Lens.dbl(cv, all, p); p.shader = null
        val top = t.baseY - t.h
        for ((fx, fy) in listOf(-.08f to .34f, .10f to .44f, -.16f to .62f, .18f to .70f, -.25f to .86f, .02f to .80f, .27f to .88f))
            bulb(cv, t.x + fx * t.h, top + fy * t.h, warm[((fy * 100).toInt()) % warm.size], .55f + .45f * rnd.nextFloat(), .85f)
        star(cv, t.x, top + 2f, t.h * .08f)
    }
}

object NewYear {
    private fun swags(x0: Float, x1: Float, n: Int, y: Float) = (0..n).map { PointF(x0 + (x1 - x0) * it / n, y) }
    /** points of the lens frame line from its top-left corner over the dome to its top-right corner */
    private fun lensTop(): List<PointF> {
        val pm = PathMeasure(Lens.path, false); val out = ArrayList<PointF>(); val pos = FloatArray(2)
        var d = 0f
        while (d < pm.length) { pm.getPosTan(d, pos, null); if (d > 50 && pos[1] >= Lens.YT - 0.5f && pos[0] > 1000) { out.add(PointF(pos[0], pos[1])); break }; out.add(PointF(pos[0], pos[1])); d += 4f }
        return out
    }
    fun spec(name: String): NewYearView.Spec = when (name) {
        "C-home", "C-music" -> NewYearView.Spec(lensTop(), 0f,
            listOf(NewYearView.Tree(514f, 546f, 112f, true)),
            listOf(NewYearView.Ball(246f, 157f, 202f, 15f, 0xFFD42A3A.toInt()), NewYearView.Ball(1034f, 157f, 202f, 15f, 0xFFE0A33A.toInt())), lens = true)
        "D-home" -> NewYearView.Spec(swags(-10f, 1150f, 4, 0f), 13f,
            listOf(NewYearView.Tree(410f, 354f, 96f, false)),
            listOf(NewYearView.Ball(300f, 14f, 100f, 16f, 0xFFC8283C.toInt())))
        "D-music" -> NewYearView.Spec(swags(-10f, 1150f, 4, 0f), 13f,
            listOf(NewYearView.Tree(1132f, 490f, 100f, false)),
            listOf(NewYearView.Ball(503f, 16f, 168f, 16f, 0xFFC8283C.toInt())))
        else -> NewYearView.Spec(swags(-10f, 1150f, 4, 0f), 13f,                                  // D-fon
            listOf(NewYearView.Tree(860f, 570f, 84f, false)), emptyList())
    }
    fun attach(a: MainActivity, name: String) {
        val st = a.ck.root.stage
        val idx = st.indexOfChild(a.ck.page)
        st.addView(NewYearView(a, spec(name)), idx + 1, FrameLayout.LayoutParams(Ck.W, Ck.H))
    }

    // ---------------- settings mock ----------------
    fun settingsMock(a: MainActivity, root: View) {
        fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
        val title = all(root).filterIsInstance<TextView>().first { it.text == "Внешний вид" && it.textSize >= 28f }
        val col = title.parent as LinearLayout
        fun t(size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1) = Ck.text(a, size, color, Ck.face(bold), lines)
        val cap = t(17f, 0x8CFFFFFF.toInt(), true).apply { text = "ПРАЗДНИЧНЫЙ РЕЖИМ  ✦"; letterSpacing = 0.08f }
        val g = W.vbox(a).apply { setPadding(22, 6, 22, 6); background = Panel(0x14FFFFFF, 0x1FFFFFFF, 1f, floatArrayOf(20f, 20f, 20f, 20f), false) }
        val seg = W.vbox(a).apply { setPadding(0, 10, 0, 12) }
        seg.addView(t(22f).apply { text = "Праздничный режим" })
        seg.addView(t(18f, 0x99FFFFFF.toInt(), lines = 2).apply { text = "Провисающие гирлянды, снег на панелях, сугроб и еловые ветки. «Авто» — с 20 декабря по 14 января" }, W.lin(W.M, W.WR).apply { topMargin = 2 })
        seg.addView(W.seg(a, listOf("Выкл", "Вкл", "Авто · 20 дек – 14 янв"), 2) {}, W.lin(W.M, 54).apply { topMargin = 10 })
        g.addView(seg, W.lin(W.M, W.WR))
        g.addView(W.divider(a), W.lin(W.M, 1))
        val sl = W.vbox(a).apply { setPadding(0, 12, 0, 12) }
        sl.addView(t(22f).apply { text = "Мерцание гирлянды" })
        val sr = W.hbox(a)
        sr.addView(t(18f, 0x99FFFFFF.toInt()).apply { text = "Спокойно" }, W.lin(W.WR, W.WR))
        sr.addView(W.Slider(a, 45, 0, 100) { _, _ -> }, W.lin(0, 44, 1f).apply { leftMargin = 16; rightMargin = 16 })
        sr.addView(t(18f, 0x99FFFFFF.toInt()).apply { text = "Ярко" }, W.lin(W.WR, W.WR))
        sl.addView(sr, W.lin(W.M, 44).apply { topMargin = 10 })
        g.addView(sl, W.lin(W.M, W.WR))
        g.addView(W.divider(a), W.lin(W.M, 1))
        val lc = W.vbox(a).apply { setPadding(0, 12, 0, 12) }
        lc.addView(t(22f).apply { text = "Огоньки" })
        lc.addView(W.seg(a, listOf("Тёплые", "Разноцветные"), 0) {}, W.lin(W.M, 54).apply { topMargin = 10 })
        g.addView(lc, W.lin(W.M, W.WR))
        g.addView(W.divider(a), W.lin(W.M, 1))
        g.addView(W.row(a, "Снег и сугроб", "Снежинки, снег на верхних кромках панелей и сугроб внизу", W.Toggle(a, true) {}, 76, 42), W.lin(W.M, W.WR))
        g.addView(W.divider(a), W.lin(W.M, 1))
        g.addView(W.row(a, "Еловые ветки", "Веточки по углам — никогда не закрывают текст и кнопки", W.Toggle(a, true) {}, 76, 42), W.lin(W.M, W.WR))
        g.addView(W.divider(a), W.lin(W.M, 1))
        g.addView(W.row(a, "Сейчас", "Включится сам 20 декабря, выключится 14 января", null), W.lin(W.M, W.WR))
        col.addView(cap, 1, W.lin(W.M, W.WR).apply { topMargin = 4; bottomMargin = 8; leftMargin = 6 })
        col.addView(g, 2, W.lin(W.M, W.WR))
        // a mock of the setting must not hide that the rest of «Внешний вид» follows
    }
}
