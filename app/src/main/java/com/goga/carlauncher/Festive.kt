package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Calendar
import kotlin.math.*

/**
 * v1.7.8 «Праздничный режим» (approved design v3, /workspace/car-design/png-newyear-v3): sagging garlands with glass
 * bulbs, snow caps on the top edges of the cards, a snowdrift along the bottom and real fir branches (photos,
 * Pixabay Content License — credits in HANDOFF.md / release notes) placed asymmetrically at the edges.
 *
 * Cheap on the head unit: everything static is rendered ONCE (background thread, software canvas, blur allowed) into
 * two bitmaps — [NyStatic] under the page (drift, shadows, branches «behind») and over the page (caps, branches, wires,
 * bulbs). Only [NyLights] animates: one pre-rendered glow sprite per bulb with a changing alpha (hardware-accelerated
 * drawBitmap, ≤ 20 fps), and it runs only while the launcher is resumed, visible and the screen is on.
 *
 * Nothing here may cover text or controls: positions come from the real views of the page after layout (works for
 * «Крупный» and with / without the system bar). A garland that would touch a control gets less sag (or is dropped),
 * a branch that would touch one is moved towards the screen edge, otherwise drawn UNDER the interface; snow caps sit
 * only on the top edge of big cards. No touch handling, no keys, no system settings.
 */
object Festive {
    /** «Авто»: 20 December … 14 January inclusive (across the new year) */
    fun inSeason(month: Int, day: Int): Boolean = (month == 12 && day >= 20) || (month == 1 && day <= 14)
    fun inSeason(c: Calendar): Boolean = inSeason(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    fun on(now: Calendar = Calendar.getInstance()): Boolean = when (K.nyMode) { "on" -> true; "off" -> false; else -> inSeason(now) }
    /** «Сейчас» line of the settings */
    fun status(now: Calendar = Calendar.getInstance()): String = when (K.nyMode) {
        "on" -> "Включено вручную"
        "off" -> "Выключено"
        else -> if (inSeason(now)) "Включено (авто) — выключится само после 14 января" else "Включится само 20 декабря, выключится после 14 января"
    }
    val WARM = intArrayOf(0xFFFFC56B.toInt(), 0xFFFFE2A8.toInt(), 0xFFFFB35C.toInt(), 0xFFFFD891.toInt())
    val MULTI = intArrayOf(0xFFFF5D5D.toInt(), 0xFFFFC56B.toInt(), 0xFF5DE0A0.toInt(), 0xFF6FB8FF.toInt(), 0xFFFF8AD8.toInt(), 0xFFFFE2A8.toInt())

    fun noise(seed: Int, x: Float): Float {
        val r = java.util.Random(seed.toLong()); val a = FloatArray(4) { r.nextFloat() * 6.28f }
        return (0.5f + 0.22f * sin(x * 0.045f + a[0]) + 0.16f * sin(x * 0.11f + a[1]) + 0.08f * sin(x * 0.27f + a[2]) + 0.04f * sin(x * 0.6f + a[3])).coerceIn(0f, 1f)
    }
    fun lerpC(a: Int, b: Int, t: Float) = Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    fun withA(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** catenary through A and B with [sag] extra at the middle */
    fun catenary(a: PointF, b: PointF, sag: Float, n: Int = 40): List<PointF> {
        val w = b.x - a.x
        var lo = 0.01f; var hi = 100000f
        repeat(50) { val m = (lo + hi) / 2; if (m * (cosh(w / (2 * m)) - 1) > sag) lo = m else hi = m }
        val k = (lo + hi) / 2
        return (0..n).map { i -> val t = i / n.toFloat(); val x = a.x + w * t; val dx = x - (a.x + w / 2)
            PointF(x, a.y + (b.y - a.y) * t + sag - (k * (cosh(dx / k) - 1))) }
    }

    // ------------------------------------------------------------------ design (v3 mockups, design px)
    class Garland(val anchors: List<PointF>, val sags: List<Float>, val spacing: Float = 30f)
    class Prop(val asset: String, val cx: Float, val cy: Float, val w: Float, val rot: Float = 0f, val flip: Boolean = false,
               val behind: Boolean = false, val lights: Int = 0, val cones: List<PointF> = emptyList(), val seed: Int = 1)
    class Spec(val garlands: List<Garland>, val props: List<Prop>, val lensSnow: Boolean, val driftTint: Int = 0, val driftRise: Float = 34f,
               val mounds: List<RectF> = emptyList())
    private fun pf(x: Float, y: Float) = PointF(x, y)
    /** [name]: C-home, C-music, D-home, D-music, fon */
    fun spec(name: String): Spec = when (name) {
        "C-home" -> Spec(listOf(Garland(listOf(pf(330f, 10f), pf(530f, 16f), pf(750f, 16f), pf(950f, 10f)), listOf(46f, 62f, 46f))),
            listOf(Prop("ny_fir_bough", 125f, 176f, 320f, 6f, behind = true, lights = 4, seed = 1),
                Prop("ny_fir_sprig", 1226f, 546f, 150f, -10f, flip = true, cones = listOf(pf(1204f, 566f)), seed = 2)),
            lensSnow = true, driftTint = 0xFFAAB6CE.toInt())
        "C-music" -> Spec(listOf(Garland(listOf(pf(300f, 10f), pf(462f, 16f), pf(624f, 16f), pf(786f, 10f)), listOf(42f, 54f, 42f))),
            listOf(Prop("ny_fir_cones", 1210f, 200f, 170f, -4f, behind = true, seed = 3),
                Prop("ny_fir_sprig", 58f, 560f, 150f, -20f, lights = 3, seed = 4)),
            lensSnow = true, driftTint = 0xFFAAB6CE.toInt())
        "D-home" -> Spec(listOf(Garland(listOf(pf(-10f, 0f), pf(226f, 2f), pf(530f, 2f), pf(752f, 2f), pf(928f, 2f), pf(1162f, 0f)), listOf(12f, 64f, 8f, 52f, 12f))),
            listOf(Prop("ny_fir_baubles", 918f, 118f, 200f, -4f, seed = 5),
                Prop("ny_fir_bough", 100f, 210f, 300f, 8f, behind = true, lights = 4, seed = 6)),
            lensSnow = false)
        "D-music" -> Spec(listOf(Garland(listOf(pf(306f, 6f), pf(560f, 8f), pf(814f, 6f)), listOf(44f, 44f))),
            listOf(Prop("ny_fir_sprig", 1190f, 330f, 230f, 15f, flip = true, lights = 3, seed = 7),
                Prop("ny_fir_baubles", 438f, 94f, 160f, 5f, seed = 8)),
            lensSnow = false)
        else -> Spec(listOf(                                                                // «Фон»: the most festive screen
                Garland(listOf(pf(-10f, 0f), pf(260f, 2f), pf(514f, 2f), pf(728f, 2f), pf(1150f, 0f)), listOf(9f, 9f, 62f, 10f)),
                Garland(listOf(pf(1292f, 84f), pf(1226f, 196f), pf(1290f, 300f), pf(1214f, 420f), pf(1292f, 540f)), listOf(26f, 22f, 30f, 24f), 28f),
                Garland(listOf(pf(-8f, 520f), pf(150f, 600f), pf(330f, 612f)), listOf(22f, 26f), 28f)),
            listOf(Prop("ny_fir_bough", 170f, 185f, 420f, 7f, lights = 5, seed = 9),
                Prop("ny_fir_baubles", 1000f, 652f, 200f, -3f, seed = 10)),
            lensSnow = false, driftRise = 62f, mounds = listOf(RectF(900f, 664f, 1100f, 700f)))
    }

    // ------------------------------------------------------------------ the plan (what is drawn where, stage px)
    data class Bulb(val x: Float, val y: Float, val ang: Float, val color: Int)
    /** glow centre for the animated layer; [r] radius, [base] alpha 0..1 */
    data class Glow(val x: Float, val y: Float, val r: Float, val color: Int, val base: Float, val phase: Float)
    data class Cap(val x0: Float, val x1: Float, val y: Float, val r: Float, val h: Float)
    data class Placed(val asset: String, val cx: Float, val cy: Float, val w: Float, val rot: Float, val flip: Boolean, val behind: Boolean,
                      val lights: List<PointF>, val cones: List<PointF>, val seed: Int)
    data class Plan(val vw: Int, val vh: Int, val ox: Int, val oy: Int, val bl: Int, val br: Int, val bb: Int,
                    val snow: Boolean, val driftRise: Float, val driftTint: Int,
                    val wires: List<List<PointF>>, val bulbs: List<Bulb>, val caps: List<Cap>, val lens: Boolean,
                    val props: List<Placed>, val mounds: List<RectF>, val shadows: List<RectF>, val glows: List<Glow>)

    /** what the page shows: big cards (snow caps) and everything that must stay uncovered (text, icons, buttons) */
    class Scene(val cards: List<Pair<RectF, Float>>, val blocks: List<RectF>, val controls: List<RectF>)

    /** walks the page after layout (stage coordinates) */
    fun scan(page: ViewGroup): Scene {
        val cards = ArrayList<Pair<RectF, Float>>(); val blocks = ArrayList<RectF>(); val controls = ArrayList<RectF>()
        val big = Ck.W * Ck.H * 0.22f
        fun rectOf(v: View): RectF { val r = android.graphics.Rect(0, 0, v.width, v.height); page.offsetDescendantRectToMyCoords(v, r); return RectF(r) }
        fun walk(v: View, inCard: Boolean) {
            if (v.visibility != View.VISIBLE || v.alpha < 0.05f || v.width <= 0 || v.height <= 0) return
            val r = rectOf(v)
            val panel = v.background as? Panel
            var card = inCard
            if (panel != null && v !== page) {
                if (!inCard && r.width() >= 100 && r.height() >= 60 && r.top >= 60f && r.top <= 600f) { cards.add(r to panel.radii[0]); card = true }
                else if (r.width() * r.height() < big) blocks.add(r)
            }
            if (v.isClickable && v !== page && r.width() * r.height() < big) controls.add(r)
            if (v is ViewGroup) { for (i in 0 until v.childCount) walk(v.getChildAt(i), card) }
            else if (r.width() * r.height() < big && v !== page) {
                // text: only the part with letters (a wide TextView with short text must not block a whole row)
                if (v is TextView && v.layout != null && v.text.isNotEmpty()) {
                    val l = v.layout; var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
                    for (i in 0 until l.lineCount) { x0 = min(x0, l.getLineLeft(i)); x1 = max(x1, l.getLineRight(i)) }
                    val tx = r.left + v.totalPaddingLeft - v.scrollX
                    val ty = r.top + v.totalPaddingTop
                    blocks.add(RectF(max(r.left, tx + x0), max(r.top, ty), min(r.right, tx + x1), min(r.bottom, ty + l.height)))
                } else if (v !is TextView) blocks.add(r)
            }
        }
        for (i in 0 until page.childCount) walk(page.getChildAt(i), false)
        return Scene(cards, blocks, controls)
    }

    fun plan(c: Context, name: String, sc: Scene, vw: Int, vh: Int, bl: Int, bt: Int, br: Int, bb: Int): Plan {
        val sp = spec(name)
        val snow = K.nySnow; val fir = K.nyFir
        val pal = if (K.nyLights == "multi") MULTI else WARM
        val obst = (sc.blocks + sc.controls + sc.cards.map { it.first }).map { RectF(it).apply { inset(-6f, -6f) } }
        fun hits(r: RectF) = obst.any { RectF.intersects(it, r) }
        fun edgeX(x: Float) = when { x <= 0f -> x - bl; x >= Ck.W.toFloat() -> x + br; else -> x }
        // garlands: sag shrinks until nothing is touched; a segment that cannot be cleared is left out
        val wires = ArrayList<List<PointF>>(); val bulbs = ArrayList<Bulb>(); val glows = ArrayList<Glow>()
        var k = 0
        val rnd = java.util.Random(3)
        for (g in sp.garlands) {
            val an = g.anchors.map { PointF(edgeX(it.x), it.y) }
            for (i in 1 until an.size) {
                var sag = g.sags[i - 1]
                var pts: List<PointF>? = null; var bs: List<Bulb>? = null
                while (true) {
                    val p = catenary(an[i - 1], an[i], sag)
                    val b = bulbsOn(p, g.spacing, pal, k)
                    val clear = b.none { hits(RectF(it.x - 5f, it.y - 2f, it.x + 5f, it.y + 14f)) } &&
                        p.none { q -> obst.any { it.contains(q.x, q.y) } }
                    if (clear) { pts = p; bs = b; break }
                    if (sag <= 6f) break
                    sag = max(6f, sag * 0.75f)
                }
                if (pts == null || bs == null) continue
                wires.add(pts); bulbs.addAll(bs); k += bs.size
                for (b in bs) { val gx = b.x - sin(Math.toRadians(b.ang.toDouble())).toFloat() * 7.5f; val gy = b.y + cos(Math.toRadians(b.ang.toDouble())).toFloat() * 7.5f
                    glows.add(Glow(gx, gy, 26f, b.color, .55f + .45f * rnd.nextFloat(), rnd.nextFloat() * 6.283f)) }
            }
        }
        // caps on the top edge of every big card (not where they would touch a letter or an icon)
        val caps = ArrayList<Cap>()
        if (snow) for ((r, rad) in sc.cards) {
            val h = (r.width() / 60f).coerceIn(5f, 8f)
            val cr = RectF(r.left + rad * .35f, r.top - h * 1.6f, r.right - rad * .35f, r.top + 6f)
            if (sc.blocks.any { b -> RectF.intersects(b, cr) && !sameRect(b, r) }) continue
            caps.add(Cap(r.left, r.right, r.top, min(rad, r.height() / 2), h))
        }
        // branches: as designed; if one would cover a control → slide it towards the nearest edge, else put it under the page
        val props = ArrayList<Placed>()
        if (fir) for (p in sp.props) {
            val m = NyAssets.mask(c, p.asset) ?: continue
            val h = p.w * m.fh / m.fw
            val ex = when { p.cx < 260f -> -bl.toFloat(); p.cx > Ck.W - 260f -> br.toFloat(); else -> 0f }
            var cx = p.cx + ex; var cy = p.cy; var behind = p.behind
            if (!behind) {
                val dirX = if (p.cx < Ck.W / 2) -1f else 1f; val dirY = if (p.cy < Ck.H / 2) -1f else 1f
                var ok = !propHits(m, cx, cy, p.w, h, p.rot, p.flip, obst)
                if (!ok) loop@ for (s in 1..8) for ((fx, fy) in listOf(1f to 0f, 0f to 1f, 1f to 1f)) {
                    val nx = cx + dirX * fx * s * 15f; val ny = cy + dirY * fy * s * 12f
                    if (!propHits(m, nx, ny, p.w, h, p.rot, p.flip, obst)) { cx = nx; cy = ny; ok = true; break@loop }
                }
                if (!ok) behind = true
            }
            val lights = ArrayList<PointF>()
            if (p.lights > 0) {
                val r = java.util.Random(p.seed * 7919L); var guard = 0
                while (lights.size < p.lights && guard++ < 3000) {
                    val u = .12f + .8f * r.nextFloat(); val v = .1f + .8f * r.nextFloat()
                    if (m.at(u, v) < 220) continue
                    val q = toStage(u, v, cx, cy, p.w, h, p.rot, p.flip)
                    if (lights.any { hypot(it.x - q.x, it.y - q.y) < p.w / (p.lights + 1.5f) }) continue
                    lights.add(q)
                }
                lights.sortBy { it.x }
                if (!behind) for ((i, q) in lights.withIndex()) glows.add(Glow(q.x, q.y, 22f, pal[(i + p.seed) % pal.size], .65f + .35f * r.nextFloat(), r.nextFloat() * 6.283f))
            }
            val cones = p.cones.map { PointF(it.x + (cx - p.cx), it.y + (cy - p.cy)) }
            props.add(Placed(p.asset, cx, cy, p.w, p.rot, p.flip, behind, lights, if (behind) emptyList() else cones, p.seed))
        }
        val mounds = if (snow) sp.mounds.filter { !hits(it) } else emptyList()
        // soft shadows under the controls standing on the drift (white labels stay readable)
        val shadows = ArrayList<RectF>()
        if (snow) for (r in sc.controls) if (r.bottom >= 600f && r.height() < 140f && shadows.none { s -> RectF.intersects(s, r) }) shadows.add(RectF(r))
        return Plan(vw, vh, bl, bt, bl, br, bb, snow, sp.driftRise, sp.driftTint, wires, bulbs, caps, snow && sp.lensSnow && Ck.isC, props, mounds, shadows, glows)
    }
    private fun sameRect(a: RectF, b: RectF) = abs(a.left - b.left) < 1 && abs(a.top - b.top) < 1 && abs(a.right - b.right) < 1 && abs(a.bottom - b.bottom) < 1

    private fun bulbsOn(pts: List<PointF>, spacing: Float, pal: IntArray, k0: Int): List<Bulb> {
        val path = Path(); path.moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) path.lineTo(q.x, q.y)
        val pm = PathMeasure(path, false); val pos = FloatArray(2); val tan = FloatArray(2)
        val out = ArrayList<Bulb>(); var d = spacing * .5f; var k = k0
        while (d < pm.length - 4) { pm.getPosTan(d, pos, tan)
            out.add(Bulb(pos[0], pos[1], Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat() * .35f, pal[k % pal.size])); k++; d += spacing }
        return out
    }
    fun toStage(u: Float, v: Float, cx: Float, cy: Float, w: Float, h: Float, rot: Float, flip: Boolean): PointF {
        var lx = (u - .5f) * w; val ly = (v - .5f) * h
        if (flip) lx = -lx
        val a = Math.toRadians(rot.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        return PointF(cx + lx * ca - ly * sa, cy + lx * sa + ly * ca)
    }
    /** does the visible (opaque) part of a branch touch any rect? */
    fun propHits(m: NyAssets.Mask, cx: Float, cy: Float, w: Float, h: Float, rot: Float, flip: Boolean, rects: List<RectF>): Boolean {
        val rad = hypot(w, h) / 2
        val a = Math.toRadians(-rot.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        for (r in rects) {
            if (r.right < cx - rad || r.left > cx + rad || r.bottom < cy - rad || r.top > cy + rad) continue
            var y = max(r.top, cy - rad)
            while (y <= min(r.bottom, cy + rad)) {
                var x = max(r.left, cx - rad)
                while (x <= min(r.right, cx + rad)) {
                    val dx = x - cx; val dy = y - cy
                    var lx = dx * ca - dy * sa; val ly = dx * sa + dy * ca
                    if (flip) lx = -lx
                    val u = lx / w + .5f; val v = ly / h + .5f
                    if (u in 0f..1f && v in 0f..1f && m.at(u, v) > 60) return true
                    x += 4f
                }
                y += 4f
            }
        }
        return false
    }
}

/** the photo assets (assets/newyear, webp): decoded on demand, small alpha masks for placement, released when off */
object NyAssets {
    class Mask(val w: Int, val h: Int, val a: ByteArray, val fw: Int, val fh: Int) {
        fun at(u: Float, v: Float): Int { val x = (u * (w - 1)).toInt().coerceIn(0, w - 1); val y = (v * (h - 1)).toInt().coerceIn(0, h - 1); return a[y * w + x].toInt() and 0xFF }
    }
    private val masks = HashMap<String, Mask>()
    private val bmps = HashMap<String, Bitmap>()
    @Synchronized fun mask(c: Context, n: String): Mask? = masks[n] ?: try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.assets.open("newyear/$n.webp").use { BitmapFactory.decodeStream(it, null, o) }
        val o2 = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = c.assets.open("newyear/$n.webp").use { BitmapFactory.decodeStream(it, null, o2) }!!
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        Mask(b.width, b.height, ByteArray(px.size) { (px[it] ushr 24).toByte() }, o.outWidth, o.outHeight).also { b.recycle(); masks[n] = it }
    } catch (t: Throwable) { CrashLog.soft(t); null }
    /** halved with filtering until close to [w] (bilinear alone would alias the thin needles) */
    @Synchronized fun sized(c: Context, n: String, w: Float): Bitmap? = bmps["$n@${w.toInt()}"] ?: try {
        var b = c.assets.open("newyear/$n.webp").use { BitmapFactory.decodeStream(it) }!!
        while (b.width > w * 1.5f) { val s = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true); if (s !== b) b.recycle(); b = s }
        b.also { bmps["$n@${w.toInt()}"] = it }
    } catch (t: Throwable) { CrashLog.soft(t); null }
    @Synchronized fun release() { bmps.clear() }
}

/** software rendering of the static layers into bitmaps (background thread) */
object NyRender {
    fun back(c: Context, p: Festive.Plan): Bitmap {
        val b = Bitmap.createBitmap(p.vw, p.vh, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        cv.translate(p.ox.toFloat(), p.oy.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        if (p.snow) drift(cv, paint, p)
        for (pr in p.props) if (pr.behind) prop(c, cv, pr, true)
        return b
    }
    fun front(c: Context, p: Festive.Plan): Bitmap {
        val b = Bitmap.createBitmap(p.vw, p.vh, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        cv.translate(p.ox.toFloat(), p.oy.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        for (cp in p.caps) cap(cv, paint, cp)
        if (p.lens) lensSnow(cv, paint)
        for (pr in p.props) if (!pr.behind) prop(c, cv, pr, false)
        for (m in p.mounds) mound(cv, paint, m)
        for (w in p.wires) wire(cv, paint, w)
        for (bl in p.bulbs) bulb(cv, paint, bl)
        return b
    }

    private fun drift(cv: Canvas, p: Paint, pl: Festive.Plan) {
        val x0 = -pl.bl - 10f; val x1 = Ck.W + pl.br + 10f; val bottom = Ck.H + pl.bb.toFloat()
        val path = Path(); path.moveTo(x0, bottom)
        val pts = ArrayList<PointF>()
        var x = x0
        while (x <= x1) {
            val edge = (abs(x - 640f) / 640f).coerceIn(0f, 1f)
            val base = 706f - pl.driftRise * edge.pow(1.8f)
            pts.add(PointF(x, base - 9f * Festive.noise(11, x * .35f) - 4f * Festive.noise(23, x * .8f))); x += 8f
        }
        path.lineTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        path.lineTo(x1, bottom); path.close()
        val tint = if (pl.driftTint != 0) pl.driftTint else 0xFFB9C6E0.toInt()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 640f, 0f, 720f, intArrayOf(0x00FFFFFF, 0x26DDE6FF, 0x00FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(x0, 640f, x1, bottom, p)
        p.shader = LinearGradient(0f, 660f, 0f, 720f, intArrayOf(0xF2F5F8FF.toInt(), Festive.withA(tint, 0xE6), Festive.withA(Festive.lerpC(tint, 0xFF2A3350.toInt(), .45f), 0xF0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = 0xCCFFFFFF.toInt()
        val ridge = Path(); ridge.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; ridge.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        cv.drawPath(ridge, p)
        val r = java.util.Random(5); p.style = Paint.Style.FILL
        repeat(80) { val sx = x0 + r.nextFloat() * (x1 - x0); val idx = ((sx - x0) / 8f).toInt().coerceIn(0, pts.size - 1); val sy = pts[idx].y + 4 + r.nextFloat() * (bottom - 4 - pts[idx].y)
            p.color = Festive.withA(Color.WHITE, 90 + r.nextInt(140)); cv.drawCircle(sx, sy, .7f + r.nextFloat() * .9f, p) }
        for (rc in pl.shadows) {
            val cx = rc.centerX(); val cy = rc.centerY(); val rx = rc.width() * .62f; val ry = rc.height() * .75f; val m = max(rx, ry)
            p.shader = RadialGradient(cx, cy, m, intArrayOf(0x8C141A2E.toInt(), 0x59141A2E, 0x00141A2E), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            cv.save(); cv.scale(rx / m, ry / m, cx, cy); cv.drawCircle(cx, cy, m, p); cv.restore()
        }
        p.shader = null
    }

    private fun wire(cv: Canvas, p: Paint, pts: List<PointF>) {
        val w = Path(); w.moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) w.lineTo(q.x, q.y)
        p.style = Paint.Style.STROKE; p.shader = null; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.7f; p.color = 0xD9141A16.toInt(); cv.drawPath(w, p)
        p.strokeWidth = .7f; p.color = 0x33FFFFFF; cv.save(); cv.translate(0f, -.7f); cv.drawPath(w, p); cv.restore()
    }
    /** a small glass bulb: dark socket, glass with a hotspot (the glow around it is animated by [NyLights]) */
    private fun bulb(cv: Canvas, p: Paint, b: Festive.Bulb) {
        val x = b.x; val y = b.y; val c = b.color
        cv.save(); cv.rotate(b.ang, x, y)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        p.shader = LinearGradient(x - 2.6f, 0f, x + 2.6f, 0f, intArrayOf(0xFF2A2F2C.toInt(), 0xFF5A615C.toInt(), 0xFF1E2220.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(x - 2.4f, y - .5f, x + 2.4f, y + 3.6f), 1.2f, 1.2f, p)
        val lit = Festive.lerpC(c, Color.WHITE, .45f)
        p.shader = RadialGradient(x - .8f, y + 5.9f, 6.5f, intArrayOf(Color.WHITE, lit, c, Festive.lerpC(c, Color.BLACK, .25f)), floatArrayOf(0f, .25f, .7f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(RectF(x - 3.3f, y + 3f, x + 3.3f, y + 12.2f), p)
        p.shader = null; p.color = 0xCCFFFFFF.toInt(); cv.drawOval(RectF(x - 1.9f, y + 4.4f, x - .5f, y + 7.4f), p)
        cv.restore()
    }
    private fun mound(cv: Canvas, p: Paint, m: RectF) {
        val path = Path(); path.moveTo(m.left, m.bottom)
        val n = 14
        for (i in 0..n) { val u = i / n.toFloat(); val x = m.left + m.width() * u
            path.lineTo(x, m.bottom - m.height() * sin(PI.toFloat() * u).pow(.7f) * (.82f + .18f * Festive.noise(41, x * 1.3f))) }
        path.lineTo(m.right, m.bottom); path.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0x55050A18; p.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        cv.save(); cv.translate(0f, -2f); cv.drawPath(path, p); cv.restore(); p.maskFilter = null
        p.color = Color.WHITE
        p.shader = LinearGradient(0f, m.top, 0f, m.bottom, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9EFF8.toInt(), 0xFFC2CEE2.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
    }
    /** a natural snow cap: thick in the middle, tapering at the rounded corners, soft mounds, small lumps over the lip */
    private fun cap(cv: Canvas, p: Paint, c: Festive.Cap) {
        val seed = (c.x0 * 7 + c.y * 13).toInt()
        fun edgeY(x: Float): Float {
            val l = c.x0 + c.r; val rr = c.x1 - c.r
            return when { x < l -> c.y + c.r - sqrt(max(0f, c.r * c.r - (l - x) * (l - x))); x > rr -> c.y + c.r - sqrt(max(0f, c.r * c.r - (x - rr) * (x - rr))); else -> c.y }
        }
        val xa = c.x0 + c.r * .35f; val xb = c.x1 - c.r * .35f; val span = xb - xa
        if (span < 20) return
        val top = ArrayList<PointF>(); val bot = ArrayList<PointF>()
        var x = xa
        while (x <= xb + .1f) {
            val u = (x - xa) / span
            val ends = (min(x - xa, xb - x) / (c.r * 1.2f + 10f)).coerceIn(0f, 1f); val taper = ends * ends * (3 - 2 * ends)
            val belly = .78f + .22f * sin(PI.toFloat() * u)
            val mound = Festive.noise(seed, x * .55f) * .7f + Festive.noise(seed + 9, x * 1.4f) * .3f
            val h = c.h * belly * (.55f + .7f * mound) * taper + .4f
            val e = edgeY(x)
            val lump = Festive.noise(seed + 5, x * 2.2f); val drip = if (lump > .66f) (lump - .66f) * 7f * taper else 0f
            top.add(PointF(x, e - h)); bot.add(PointF(x, e + .9f + drip))
            x += 2f
        }
        fun smooth(pts: List<PointF>, path: Path, first: Boolean) {
            if (first) path.moveTo(pts[0].x, pts[0].y) else path.lineTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
            path.lineTo(pts.last().x, pts.last().y)
        }
        val path = Path(); smooth(top, path, true); smooth(bot.reversed(), path, false); path.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0x66050A18
        p.maskFilter = BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL)
        cv.save(); cv.translate(0f, 2.6f); cv.drawPath(path, p); cv.restore(); p.maskFilter = null
        p.color = Color.WHITE
        p.shader = LinearGradient(0f, c.y - c.h * 1.3f, 0f, c.y + 2.5f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF4F8FE.toInt(), 0xFFD5DFEE.toInt(), 0xFFB3C1D8.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        val rim = Path(); smooth(top.map { PointF(it.x, it.y + .6f) }, rim, true)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xB3FFFFFF.toInt(); cv.drawPath(rim, p)
        p.style = Paint.Style.FILL
        val r = java.util.Random(seed.toLong())
        repeat((span / 55).toInt()) { val q = top[r.nextInt(top.size)]
            p.color = 0xF2FFFFFF.toInt(); cv.drawCircle(q.x, q.y + 1.4f + r.nextFloat() * 1.5f, .55f + r.nextFloat() * .4f, p) }
    }
    /** C «Линза»: snow on the upper side of the silver frame line */
    private fun lensSnow(cv: Canvas, p: Paint) {
        val pm = PathMeasure(Lens.path, false); val pos = FloatArray(2); val tan = FloatArray(2)
        val outer = ArrayList<PointF>(); val inner = ArrayList<PointF>()
        var d = 0f; var started = false
        while (d < pm.length) {
            pm.getPosTan(d, pos, tan)
            if (d > 60 && pos[0] > 1000 && pos[1] >= Lens.YT - .5f) break
            val up = tan[0].coerceAtLeast(0f).pow(1.6f)
            val h = (2.2f + 6.5f * Festive.noise(77, d)) * up
            if (h > .4f || started) { started = true
                outer.add(PointF(pos[0] + tan[1] * (h + 2.6f), pos[1] - tan[0] * (h + 2.6f))); inner.add(PointF(pos[0] + tan[1] * 1.4f, pos[1] - tan[0] * 1.4f)) }
            d += 3f
        }
        if (outer.size < 3) return
        val path = Path(); path.moveTo(outer[0].x, outer[0].y)
        for (i in 1 until outer.size) { val a = outer[i - 1]; val b = outer[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        for (i in inner.indices.reversed()) path.lineTo(inner[i].x, inner[i].y)
        path.close()
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.shader = LinearGradient(0f, 120f, 0f, 240f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE4F0.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
    }
    /** a photo branch with a soft drop shadow, warm micro-lights on a thin wire and hanging cones */
    private fun prop(c: Context, cv: Canvas, pr: Festive.Placed, behind: Boolean) {
        val sb = NyAssets.sized(c, pr.asset, pr.w) ?: return
        val h = sb.height * pr.w / sb.width
        val dst = RectF(-pr.w / 2, -h / 2, pr.w / 2, h / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val a = sb.extractAlpha()
        cv.save(); cv.translate(pr.cx + 4f, pr.cy + 9f); cv.rotate(pr.rot); if (pr.flip) cv.scale(-1f, 1f)
        p.color = 0x8C02060E.toInt(); p.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        cv.drawBitmap(a, null, dst, p); p.maskFilter = null
        cv.restore(); a.recycle()
        cv.save(); cv.translate(pr.cx, pr.cy); cv.rotate(pr.rot); if (pr.flip) cv.scale(-1f, 1f)
        p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
        cv.drawBitmap(sb, null, dst, p)
        cv.restore()
        if (pr.lights.isNotEmpty()) {
            val wire = Path(); wire.moveTo(pr.lights[0].x, pr.lights[0].y)
            for (i in 1 until pr.lights.size) { val u = pr.lights[i - 1]; val v = pr.lights[i]; wire.quadTo((u.x + v.x) / 2, max(u.y, v.y) + 7f, v.x, v.y) }
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = .9f; p.color = 0x9E101410.toInt(); cv.drawPath(wire, p)
            val pal = if (K.nyLights == "multi") Festive.MULTI else Festive.WARM
            p.style = Paint.Style.FILL
            for ((i, q) in pr.lights.withIndex()) {
                val col = pal[(i + pr.seed) % pal.size]
                if (behind) {   // under the page nothing animates: a fixed soft glow
                    p.shader = RadialGradient(q.x, q.y, 20f, intArrayOf(Festive.withA(col, 130), Festive.withA(col, 40), Festive.withA(col, 0)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
                    cv.drawCircle(q.x, q.y, 20f, p)
                }
                p.shader = RadialGradient(q.x - .5f, q.y - .6f, 3.6f, intArrayOf(Color.WHITE, Festive.lerpC(col, Color.WHITE, .45f), col), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(q.x, q.y, 3.4f, p); p.shader = null
            }
        }
        for ((i, q) in pr.cones.withIndex()) {
            val cb = NyAssets.sized(c, "ny_cone", 24f) ?: break
            val cw = 24f; val ch = cb.height * cw / cb.width
            val d = RectF(-cw / 2, 0f, cw / 2, ch)
            cv.save(); cv.translate(q.x, q.y); cv.rotate(if (i % 2 == 0) -9f else 7f)
            p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG; cv.drawBitmap(cb, null, d, p)
            p.color = 0xE6F4F8FF.toInt(); p.maskFilter = BlurMaskFilter(1.2f, BlurMaskFilter.Blur.NORMAL)
            cv.drawOval(RectF(-cw * .32f, ch * .06f, cw * .3f, ch * .16f), p); p.maskFilter = null
            cv.restore()
        }
    }
}

/** one of the two static layers (a bitmap rendered once); bleeds to the window edges like the background */
class NyStatic(c: Context) : View(c) {
    var bmp: Bitmap? = null
        set(v) { val old = field; field = v; invalidate(); if (old != null && old !== v) post { old.recycle() } }
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
    override fun onDraw(cv: Canvas) { val b = bmp ?: return; if (!b.isRecycled) cv.drawBitmap(b, null, Rect(0, 0, width, height), p) }
}

/**
 * The only animated part: a glow sprite per bulb, alpha follows a slow sine per bulb («Мерцание гирлянды»).
 * ≤ 20 fps, stopped when the launcher is paused / hidden / the screen is off, or when the twinkle is 0 (static glow).
 */
class NyLights(c: Context) : View(c) {
    var glows: List<Festive.Glow> = emptyList(); set(v) { field = v; invalidate(); update() }
    var ox = 0f; var oy = 0f
    var resumed = false; set(v) { field = v; update() }
    var running = false; private set
    private val sprites = HashMap<Int, Bitmap>()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun sprite(color: Int): Bitmap = sprites.getOrPut(color) {
        val s = 64; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Festive.withA(color, 170), Festive.withA(color, 56), Festive.withA(color, 0)), floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(s / 2f, s / 2f, s / 2f, gp); b
    }
    private fun interactive(): Boolean = try { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Throwable) { true }
    fun update() {
        val want = glows.isNotEmpty() && K.nyTwinkle > 0 && resumed && isShown && windowVisibility == VISIBLE && interactive()
        if (want && !running) { running = true; postInvalidateOnAnimation() } else if (!want) running = false
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); update() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); update() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
    /** intensity of glow [g] at time [t] seconds, 0..1 — smooth (no blinking), deeper and faster with more twinkle */
    fun level(g: Festive.Glow, t: Float, tw: Int = K.nyTwinkle): Float {
        val amt = tw / 100f
        val f = 0.25f + 0.9f * amt
        val s = 0.5f + 0.5f * sin(t * f * 6.283f * (0.7f + 0.3f * (g.phase / 6.283f)) + g.phase)
        return (g.base * (1f - amt * 0.75f * s)).coerceIn(0f, 1f)
    }
    override fun onDraw(cv: Canvas) {
        if (glows.isEmpty()) return
        try {
            val t = SystemClock.uptimeMillis() / 1000f
            cv.save(); cv.translate(ox, oy)
            for (g in glows) {
                val k = if (K.nyTwinkle == 0) g.base else level(g, t)
                p.alpha = (255 * k).toInt()
                dst.set(g.x - g.r, g.y - g.r, g.x + g.r, g.y + g.r); cv.drawBitmap(sprite(g.color), null, dst, p)
                core.color = Festive.withA(Color.WHITE, (90 * k).toInt()); cv.drawCircle(g.x, g.y, 2.2f, core)
            }
            cv.restore()
        } catch (t: Throwable) { CrashLog.soft(t); running = false }
        if (running) { if (!interactive()) running = false else postInvalidateDelayed(50) }
    }
}

/** the three layers of one Cockpit + when to re-plan (layout change, settings, auto date) */
class NyLayer(private val ck: Cockpit) {
    private val a get() = ck.a
    val back = NyStatic(ck.a)
    val front = NyStatic(ck.a)
    val lights = NyLights(ck.a)
    var enabled = false; private set
    var plan: Festive.Plan? = null; private set
    private var gen = 0
    private var lastName = ""
    private var lastAuto = 0L
    private val replan = Runnable { replanNow() }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { schedule() }

    fun attach(st: StageBox, page: View) {
        val i = st.indexOfChild(page)
        st.addView(back, i, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        val j = st.indexOfChild(page) + 1
        st.addView(front, j, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(lights, j + 1, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.bleed.add(back); st.bleed.add(front); st.bleed.add(lights)
        page.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        apply()
    }
    /** settings changed / screen rebuilt */
    fun apply() {
        enabled = try { Festive.on() } catch (_: Throwable) { false }
        val vis = if (enabled) View.VISIBLE else View.GONE
        back.visibility = vis; front.visibility = vis; lights.visibility = vis
        if (!enabled) { plan = null; back.bmp = null; front.bmp = null; lights.glows = emptyList(); NyAssets.release(); return }
        schedule()
    }
    fun schedule() { if (!enabled) return; AppRepo.main.removeCallbacks(replan); if (Robo.sync) replanNow() else AppRepo.main.postDelayed(replan, 150) }
    fun onResume() { lights.resumed = true; checkAuto(true) }
    fun onPause() { lights.resumed = false }
    /** «Авто»: the date is checked once a minute (from the Cockpit tick) */
    fun checkAuto(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuto < 60_000) return
        lastAuto = now
        if (Festive.on() != enabled) { apply(); ck.refreshFx() }
    }
    fun name(): String = if (ck.fon) "fon" else (if (Ck.isC) "C-" else "D-") + (if (ck.screen == 1) "music" else "home")

    private fun replanNow() {
        if (!enabled) return
        try {
            val st = ck.root.stage
            if (ck.page.width == 0) return
            val vw = Ck.W + st.bl + st.br; val vh = Ck.H + st.bt + st.bb
            val p = Festive.plan(a, name(), Festive.scan(ck.page), vw, vh, st.bl, st.bt, st.br, st.bb)
            if (p == plan && back.bmp != null) return
            plan = p; lastName = name()
            lights.ox = st.bl.toFloat(); lights.oy = st.bt.toFloat()
            val g = ++gen
            val app = a.applicationContext
            val job = Runnable {
                val b = try { NyRender.back(app, p) } catch (t: Throwable) { CrashLog.soft(t); null }
                val f = try { NyRender.front(app, p) } catch (t: Throwable) { CrashLog.soft(t); null }
                val done = Runnable { if (g == gen && enabled) { back.bmp = b; front.bmp = f; lights.glows = p.glows } else { b?.recycle(); f?.recycle() } }
                if (Robo.sync) done.run() else AppRepo.main.post(done)
            }
            if (Robo.sync) job.run() else AppRepo.exec.execute(job)
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
}
