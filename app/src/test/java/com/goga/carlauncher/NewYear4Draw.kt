package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import kotlin.math.*

/**
 * New Year design v4 (test sources only, for approval). One coherent decoration system:
 *  - the decorations live on the BACKGROUND plane (under the page): one continuous garland strand from the top-left
 *    fir cluster along the whole top edge (on «Фон» also down the right edge) passes BEHIND cards and chips instead of
 *    being cut; even bulb spacing along the whole strand, twisted wire, soft bloom;
 *  - fir clusters are composed offline from cleaned (no fringe), desaturated, cool-graded photos and sit only in the
 *    top-left corner (lush) and bottom-right (small counterpart, sunk in the drift) — never over content;
 *  - only the snow caps (very thin, restrained) are drawn over the cards.
 * Variants: CLASSIC (warm C7 bulbs, branches with muted baubles, low drift) and MINIMAL (micro-LED fairy lights on a
 * copper wire in two depth layers, frosted corners, mist instead of a drift, a frost line instead of caps).
 */
object NY4 {
    enum class V { CLASSIC, MINIMAL }
    val WARM = intArrayOf(0xFFFFC46B.toInt(), 0xFFFFE1A6.toInt(), 0xFFFFB050.toInt(), 0xFFFFD58A.toInt())
    private val bmps = HashMap<String, Bitmap>()
    fun png(n: String): Bitmap = bmps.getOrPut(n) { BitmapFactory.decodeFile("/workspace/ny-assets/v4/$n.png") }

    fun noise(seed: Int, x: Float): Float = Festive.noise(seed, x)
    fun withA(c: Int, a: Int) = Festive.withA(c, a)

    class Strand(val pts: List<PointF>, val bulbs: List<PointF>, val dim: List<Boolean>)

    /** one continuous strand through [anchors] (sag per span), bulbs every [spacing] px along the WHOLE path;
     *  a span over free text (not on a card) gets a shallower sag — never cut */
    fun strand(anchors: List<PointF>, sags: List<Float>, spacing: Float, free: List<RectF>): Strand {
        val all = ArrayList<PointF>()
        for (i in 1 until anchors.size) {
            var sag = sags[i - 1]
            var seg: List<PointF>
            while (true) {
                seg = Festive.catenary(anchors[i - 1], anchors[i], sag, 36)
                val clear = seg.none { q -> free.any { RectF(it).apply { inset(-8f, -10f) }.contains(q.x, q.y + 8f) } }
                if (clear || sag <= 4f) break
                sag = max(4f, sag * .8f)
            }
            if (all.isNotEmpty()) all.addAll(seg.drop(1)) else all.addAll(seg)
        }
        val path = Path(); path.moveTo(all[0].x, all[0].y); for (q in all.drop(1)) path.lineTo(q.x, q.y)
        val pm = PathMeasure(path, false); val pos = FloatArray(2)
        val bulbs = ArrayList<PointF>(); val dim = ArrayList<Boolean>()
        var d = spacing * .5f
        while (d < pm.length) { pm.getPosTan(d, pos, null); val b = PointF(pos[0], pos[1]); bulbs.add(b)
            dim.add(free.any { RectF(it).apply { inset(-4f, -4f) }.intersects(b.x - 4, b.y, b.x + 4, b.y + 12) }); d += spacing }
        return Strand(all, bulbs, dim)
    }

    class Plan(val v: V, val name: String, val strands: List<Strand>, val back: List<Strand>, val caps: List<RectF>, val fon: Boolean, val curtain: List<Strand>,
               val scrims: List<RectF> = emptyList(), val spray: RectF? = null) {
        var protect: List<RectF> = emptyList()
    }

    fun plan(v: V, name: String, sc: Festive.Scene): Plan {
        val fon = name.endsWith("fon")
        // free text = letters not sitting on a card or a chip (clock, weather, date …)
        val boxes = sc.cards.map { it.first } + sc.controls
        val free = sc.blocks.filter { b -> b.left > -100 && b.width() > 0 && boxes.none { it.contains(b.centerX(), b.centerY()) && it.width() < 1200 } }
        val caps = sc.cards.map { it.first to it.second }.filter { (r, rad) -> r.width() >= 200 && rad <= r.height() * .36f }.map { it.first }
        fun pf(x: Float, y: Float) = PointF(x, y)
        // bottom spray: centred in the free gap of the bottom row between the tabs and the right volume buttons
        // the volume buttons live in the bleed bars (not scanned): they start at x≈1108 on every screen
        val bottom = (sc.controls.filter { it.top >= 620f } + RectF(1108f, 646f, 1280f, 712f)).sortedBy { it.left }
        var gap: RectF? = null
        for (i in 1 until bottom.size) { val l = bottom[i - 1].right; val r = bottom[i].left
            if (l > 640f && r - l > (gap?.width() ?: 60f)) gap = RectF(l + 6f, 560f, r - 6f, 720f) }
        val spray = gap?.let { g -> val cx = g.centerX()
            // never peek out behind the content above it
            val above = (sc.cards.map { it.first } + sc.controls + free).filter { it.bottom < 640f && it.right > g.left && it.left < g.right }.maxOfOrNull { it.bottom } ?: 500f
            val w = min(min(g.width(), 280f), (720f - above - 10f) * 282f / 200f); val h = w * 200f / 282f; RectF(cx - w / 2, 720f - h, cx + w / 2, 720f) }
        // letters and chips over the top-left cluster get a soft dark backing (legibility)
        val scrims = (free + sc.controls).filter { it.top < 110f && it.left < 300f && it.left > -100 && it.bottom < 130f }
        // even scallops; extra anchors at the edges of free text in the top band so no bulb hangs over a letter
        fun anchors(base: List<PointF>, maxSag: Float): Pair<List<PointF>, List<Float>> {
            val xs = ArrayList(base.map { it.x })
            for (t in free) if (t.top < 130f && t.left > -100) { xs.add(t.left - 14f); xs.add(t.right + 14f) }
            xs.sort()
            val out = ArrayList<Float>(); for (x in xs) if (out.isEmpty() || x - out.last() > 70f) out.add(x)
            val first = base.first(); val y = base[1].y
            val pts = out.map { x -> if (x == first.x) first else PointF(x, y) }
            val sags = (1 until pts.size).map { i -> ((pts[i].x - pts[i - 1].x) * .16f).coerceIn(8f, maxSag) }
            return pts to sags
        }
        return if (v == V.CLASSIC) {
            val base = ArrayList(listOf(pf(250f, 28f), pf(430f, -2f), pf(620f, -2f), pf(810f, -2f), pf(1000f, -2f), pf(1190f, -2f), pf(1300f, -2f)))
            var (pts, sags) = anchors(base, 30f)
            if (fon) { pts = pts.filter { it.x < 1240f } + listOf(pf(1282f, 40f), pf(1262f, 190f), pf(1284f, 330f), pf(1262f, 470f))
                sags = (1 until pts.size).map { i -> if (pts[i].x > 1250f && pts[i - 1].x > 1250f) 24f else ((pts[i].x - pts[i - 1].x) * .16f).coerceIn(8f, 30f) } }
            Plan(v, name, listOf(strand(pts, sags, 27f, free)), emptyList(), caps, fon, emptyList(), scrims, spray)
        } else {
            val (pa, sa) = anchors(listOf(pf(-20f, -3f), pf(330f, -3f), pf(660f, -3f), pf(990f, -3f), pf(1300f, -3f)), 34f)
            val front = strand(pa, sa, 16f, free)
            val (pb, sb) = anchors(listOf(pf(-20f, -3f), pf(170f, -3f), pf(500f, -3f), pf(830f, -3f), pf(1160f, -3f), pf(1300f, -3f)), 20f)
            val back = strand(pb, sb, 16f, free)
            // «Фон»: light curtains hanging along both edges, outside the chips
            val curtain = if (!fon) emptyList() else listOf(8f to 260f, 22f to 190f, 1258f to 320f, 1270f to 240f).map { (x, len) ->
                val pts2 = (0..20).map { i -> pf(x + 1.5f * sin(i * .7f), -2f + len * i / 20f) }
                val bl = (1..(len / 20).toInt()).map { i -> pf(x + 1.5f * sin(i * 20f / len * 20 * .7f), i * 20f) }
                Strand(pts2, bl, bl.map { false }) }
            Plan(v, name, listOf(front), listOf(back), caps, fon, curtain)
        }
    }
}

/** under the page: corner clusters / frost, the garland (behind cards), drift or mist */
class NY4Back(c: Context, private val pl: NY4.Plan) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    /** «Иней по краям»: the procedural ice-fern edge, thinned out under every text and button — built once (static bitmap) */
    private val frostLayer: Bitmap? by lazy {
        if (!K.nyFrost) return@lazy null
        val b = NY4.png("ny4_frost").copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(b)
        val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); color = 0xD0000000.toInt(); maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
        for (r in pl.protect) c.drawRoundRect(RectF(r).apply { inset(-10f, -8f) }, 14f, 14f, q)
        b
    }
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        frostLayer?.let { cv.drawBitmap(it, 0f, 0f, p) }
        if (pl.v == NY4.V.CLASSIC) {
            cv.drawBitmap(NY4.png("ny4_tl"), 0f, 0f, p)
            for (r in pl.scrims) { p.style = Paint.Style.FILL; p.color = 0x9E0A0D16.toInt(); p.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
                cv.drawRoundRect(RectF(r).apply { inset(-8f, -6f) }, 18f, 18f, p); p.maskFilter = null }
            classicStrand(cv, pl.strands[0])
            drift(cv)
            pl.spray?.let { d -> val b = NY4.png("ny4_br"); sprayAt = d
                // the spray content occupies x 48…330 / y 60…260 of its bitmap → map that box onto [d]
                val k = d.width() / 282f
                cv.drawBitmap(b, null, RectF(d.left - 48f * k, 720f - 200f * k - 60f * k, d.left - 48f * k + b.width * k, 720f - 200f * k - 60f * k + b.height * k), p) }
            driftFront(cv)
        } else {
            frost(cv, 0f, 0f, 1f, 1f); frost(cv, 1280f, 720f, -1f, -1f)
            for (s in pl.back) microStrand(cv, s, .45f, .75f)
            for (s in pl.strands) microStrand(cv, s, 1f, 1f)
            for (s in pl.curtain) microStrand(cv, s, .9f, .9f, hanging = true)
            mist(cv)
        }
        cv.restore()
    }

    // ---------------- classic
    private fun classicStrand(cv: Canvas, s: NY4.Strand) {
        val w = Path(); w.moveTo(s.pts[0].x, s.pts[0].y); for (q in s.pts.drop(1)) w.lineTo(q.x, q.y)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        // shadow of the wire, then two twisted strands
        p.strokeWidth = 3f; p.color = 0x40000000; cv.save(); cv.translate(1.5f, 3f); cv.drawPath(w, p); cv.restore()
        p.strokeWidth = 1.9f; p.color = 0xFF1B2A20.toInt(); cv.drawPath(w, p)
        p.strokeWidth = 1f; p.color = 0xFF33493A.toInt(); p.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f); cv.drawPath(w, p); p.pathEffect = null
        val pal = NY4.WARM
        for ((i, b) in s.bulbs.withIndex()) {
            val c = pal[i % pal.size]; val k = if (s.dim[i]) .25f else .7f + .3f * NY4.noise(i, i * 13f)
            val by = b.y + 9f
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.shader = RadialGradient(b.x, by, 30f, intArrayOf(NY4.withA(c, (110 * k).toInt()), NY4.withA(c, (34 * k).toInt()), NY4.withA(c, 0)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(b.x, by, 30f, p)
            p.shader = LinearGradient(b.x - 2.6f, 0f, b.x + 2.6f, 0f, intArrayOf(0xFF1E2620.toInt(), 0xFF4B554E.toInt(), 0xFF161B18.toInt()), null, Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(b.x - 2.5f, b.y, b.x + 2.5f, b.y + 4.5f), 1.2f, 1.2f, p)
            val lit = Festive.lerpC(c, Color.WHITE, .5f)
            p.shader = RadialGradient(b.x - .8f, by - 1.8f, 7f, intArrayOf(Color.WHITE, lit, c, Festive.lerpC(c, Color.BLACK, .3f)), floatArrayOf(0f, .25f, .7f, 1f), Shader.TileMode.CLAMP)
            p.alpha = (255 * (.55f + .45f * k)).toInt()
            cv.drawPath(Path().apply { moveTo(b.x - 3.4f, b.y + 4.2f); cubicTo(b.x - 4.4f, b.y + 9f, b.x - 2.4f, b.y + 14.5f, b.x, b.y + 15f); cubicTo(b.x + 2.4f, b.y + 14.5f, b.x + 4.4f, b.y + 9f, b.x + 3.4f, b.y + 4.2f); close() }, p)
            p.alpha = 255; p.shader = null
        }
    }
    private val ridge = ArrayList<PointF>()
    private fun drift(cv: Canvas) {
        ridge.clear()
        var x = -10f
        while (x <= 1290f) { val e = (abs(x - 640f) / 640f).coerceIn(0f, 1f); ridge.add(PointF(x, 712f - 20f * e.pow(2f) - 5f * NY4.noise(11, x * .3f) - 2f * NY4.noise(23, x))); x += 8f }
        val path = Path(); path.moveTo(-10f, 720f); path.lineTo(ridge[0].x, ridge[0].y)
        for (i in 1 until ridge.size) { val a = ridge[i - 1]; val b = ridge[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        path.lineTo(1290f, 720f); path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 680f, 0f, 720f, intArrayOf(0x66E9EEF6, 0x55B5C1D4, 0x558E9AB0), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        driftPath = path
    }
    private var driftPath: Path? = null
    private var sprayAt: RectF? = null
    /** the front lip of the drift over the base of the bottom-right cluster (it sits IN the snow) */
    private fun driftFront(cv: Canvas) {
        val path = driftPath ?: return
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 690f, 0f, 720f, intArrayOf(0xF0EEF2F8.toInt(), 0xE6AEBACC.toInt()), null, Shader.TileMode.CLAMP)
        val front = Path(path)
        sprayAt?.let { sp -> front.addOval(RectF(sp.left + sp.width() * .08f, 700f, sp.right - sp.width() * .08f, 730f), Path.Direction.CW) }
        cv.save(); cv.translate(0f, 4f); cv.drawPath(front, p); cv.restore(); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = 0x99FFFFFF.toInt()
        val r = Path(); r.moveTo(ridge[0].x, ridge[0].y + 4); for (i in 1 until ridge.size) { val a = ridge[i - 1]; val b = ridge[i]; r.quadTo(a.x, a.y + 4, (a.x + b.x) / 2, (a.y + b.y) / 2 + 4) }
        cv.drawPath(r, p)
    }

    // ---------------- minimal
    private fun microStrand(cv: Canvas, s: NY4.Strand, alpha: Float, size: Float, hanging: Boolean = false) {
        val w = Path(); w.moveTo(s.pts[0].x, s.pts[0].y); for (q in s.pts.drop(1)) w.lineTo(q.x, q.y)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        p.strokeWidth = 1.1f * size; p.color = NY4.withA(0xFFC08A55.toInt(), (220 * alpha).toInt()); cv.drawPath(w, p)
        p.style = Paint.Style.FILL
        for ((i, b) in s.bulbs.withIndex()) {
            val k = (if (s.dim[i]) .3f else .75f + .25f * NY4.noise(i + 3, i * 9f)) * alpha
            p.shader = RadialGradient(b.x, b.y, 18f * size, intArrayOf(NY4.withA(0xFFFFE6B8.toInt(), (200 * k).toInt()), NY4.withA(0xFFFFD08A.toInt(), (60 * k).toInt()), 0x00FFD08A), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(b.x, b.y, 18f * size, p); p.shader = null
            p.color = NY4.withA(0xFFFFF8EC.toInt(), (255 * min(1f, k + .15f)).toInt()); cv.drawCircle(b.x, b.y, 2.3f * size, p)
        }
    }
    private fun frost(cv: Canvas, cx: Float, cy: Float, sx: Float, sy: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, 260f, intArrayOf(0x40D8E6FF, 0x16D8E6FF, 0x00D8E6FF), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, 260f, p); p.shader = null
        if (K.nyFrost) return      // the ice-fern edge replaces the loose crystals (same cold white, one family)
        // fine ice crystals: short feathered strokes, denser towards the corner
        val r = java.util.Random(if (sx > 0) 17 else 29)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        repeat(220) {
            val d = 240f * r.nextFloat().pow(1.8f); val a = r.nextFloat() * 1.5708f
            val x = cx + sx * d * cos(a); val y = cy + sy * d * sin(a)
            val len = 3f + 9f * (1 - d / 240f) * r.nextFloat(); val ang = r.nextFloat() * 6.283f
            p.strokeWidth = .7f + .5f * r.nextFloat(); p.color = NY4.withA(0xFFEAF2FF.toInt(), (45 + 110 * (1 - d / 240f)).toInt())
            cv.drawLine(x, y, x + len * cos(ang), y + len * sin(ang), p)
            if (r.nextFloat() < .35f) { val b = ang + 1.0f; cv.drawLine(x + len * .5f * cos(ang), y + len * .5f * sin(ang), x + len * .5f * cos(ang) + len * .4f * cos(b), y + len * .5f * sin(ang) + len * .4f * sin(b), p) }
        }
    }
    private fun mist(cv: Canvas) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 600f, 0f, 720f, intArrayOf(0x00DCE6F5, 0x14DCE6F5, 0x2EDCE6F5), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(-10f, 600f, 1290f, 720f, p); p.shader = null
        val r = java.util.Random(9)
        repeat(90) { p.color = NY4.withA(Color.WHITE, 40 + r.nextInt(80)); cv.drawCircle(r.nextFloat() * 1280f, 690f + r.nextFloat() * 30f, .5f + r.nextFloat() * .7f, p) }
    }
}

/** over the page: only very thin snow caps (classic) / a frost line (minimal) on the wide cards */
class NY4Front(c: Context, private val pl: NY4.Plan) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        for (r in pl.caps) if (pl.v == NY4.V.CLASSIC) cap(cv, r) else frostLine(cv, r)
        cv.restore()
    }
    private fun cap(cv: Canvas, r: RectF) {
        val rad = 22f; val x0 = r.left + rad * .6f; val x1 = r.right - rad * .6f; val span = x1 - x0
        val top = ArrayList<PointF>()
        var x = x0
        while (x <= x1) { val u = (x - x0) / span; val ends = (min(u, 1 - u) * span / 40f).coerceIn(0f, 1f); val t = ends * ends * (3 - 2 * ends)
            top.add(PointF(x, r.top - (1.2f + 3.2f * NY4.noise((r.left + r.top).toInt(), x * .5f)) * t)); x += 3f }
        val path = Path(); path.moveTo(top[0].x, r.top + .8f)
        for (q in top) path.lineTo(q.x, q.y)
        path.lineTo(top.last().x, r.top + .8f); path.close()
        p.style = Paint.Style.FILL; p.color = 0x55060A14; p.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
        cv.save(); cv.translate(0f, 1.6f); cv.drawPath(path, p); cv.restore(); p.maskFilter = null
        p.shader = LinearGradient(0f, r.top - 5f, 0f, r.top + 1f, intArrayOf(0xFFF6F9FD.toInt(), 0xFFC9D3E2.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
    }
    private fun frostLine(cv: Canvas, r: RectF) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.shader = LinearGradient(r.left + 20, 0f, r.right - 20, 0f, intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .25f, .75f, 1f), Shader.TileMode.CLAMP)
        p.strokeWidth = 1.3f; cv.drawLine(r.left + 20, r.top + .7f, r.right - 20, r.top + .7f, p)
        p.strokeWidth = 5f; p.alpha = 40; cv.drawLine(r.left + 30, r.top + 1f, r.right - 30, r.top + 1f, p); p.alpha = 255; p.shader = null
    }
}

object NewYear4 {
    fun attach(a: MainActivity, v: NY4.V, name: String) {
        val st = a.ck.root.stage
        val sc = Festive.scan(a.ck.page)
        val pl = NY4.plan(v, name, sc)
        // frost never sits under text or buttons (volume buttons live in the bleed bars → added by hand)
        pl.protect = sc.blocks + sc.controls + listOf(RectF(28f, 640f, 170f, 704f), RectF(1110f, 640f, 1252f, 704f))
        st.addView(NY4Back(a, pl), st.indexOfChild(a.ck.page), FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(NY4Front(a, pl), st.indexOfChild(a.ck.page) + 1, FrameLayout.LayoutParams(Ck.W, Ck.H))
    }
}
