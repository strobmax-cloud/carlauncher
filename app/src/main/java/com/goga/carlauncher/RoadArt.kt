package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
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
import android.graphics.Typeface
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * v1.6.2 — the user's car (2nd-gen Suzuki SX4 S-Cross, lime metallic, black cladding, silver roof rails) seen from
 * BEHIND, chase-camera height, driving away along a three-lane road that runs to the horizon. Never sideways, never
 * facing the viewer. Asset: res/drawable-nodpi/sx4_rear.webp (alpha, soft contact shadow baked in).
 * v1.6.3 — the ROAD has its own time of day (Prefs.roadMode auto / day / night; auto = sunrise–sunset in Astana),
 * independent from the manual UI day / night, and the car carries the user's Kazakhstan plate (Prefs.plate).
 * Everything is painted ONCE per size (CachedArt); RoadView repaints only when the road switches day ↔ night.
 */
object RoadArt {
    private const val CONTACT = 0.905f      // tyre contact line in sx4_rear.webp, fraction of its height
    private val TAIL = floatArrayOf(0.11f, 0.55f, 0.89f, 0.55f)   // tail-light centres (fraction of car w / h)
    // plate recess on the tailgate (fraction of car w / h): centre and size of a 520×112 mm plate on a ~1.78 m wide car
    private const val PLATE_CX = 0.5f; private const val PLATE_CY = 0.655f
    // v1.7.1: a touch larger than to scale (+15 %) so the plate stays readable on a 1280×720 screen
    private const val PLATE_W = 0.315f; private const val PLATE_H = 0.062f

    // ---------------------------------------------------------------- time of day for the road only
    const val ASTANA_LAT = 51.17; const val ASTANA_LON = 71.45

    /** Sunrise / sunset in local minutes after midnight for [cal]'s date (NOAA approximation, ±2 min). null = polar day/night. */
    fun sunTimes(cal: Calendar, lat: Double = ASTANA_LAT, lon: Double = ASTANA_LON): Pair<Double, Double>? {
        val n = cal.get(Calendar.DAY_OF_YEAR).toDouble()
        val tz = cal.timeZone.getOffset(cal.timeInMillis) / 3_600_000.0
        fun rad(d: Double) = Math.toRadians(d)
        fun deg(r: Double) = Math.toDegrees(r)
        fun calc(rising: Boolean): Double? {
            val lngHour = lon / 15
            val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24
            val m = 0.9856 * t - 3.289
            var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
            l = ((l % 360) + 360) % 360
            var ra = deg(atan(0.91764 * tan(rad(l)))); ra = ((ra % 360) + 360) % 360
            ra += floor(l / 90) * 90 - floor(ra / 90) * 90; ra /= 15
            val sinDec = 0.39782 * sin(rad(l)); val cosDec = cos(asin(sinDec))
            val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
            if (cosH > 1 || cosH < -1) return null
            val hh = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
            val tt = hh + ra - 0.06571 * t - 6.622
            val ut = ((tt - lngHour) % 24 + 24) % 24
            return ((ut + tz) % 24 + 24) % 24 * 60
        }
        val r = calc(true) ?: return null; val s = calc(false) ?: return null
        return r to s
    }

    /** Should the road show daylight now? Only the road scene follows the sun; the UI day / night stays manual. */
    fun isDay(now: Calendar = Calendar.getInstance()): Boolean = when (Prefs.roadMode) {
        "day" -> true
        "night" -> false
        else -> {
            val st = sunTimes(now)
            val min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (st == null) min in 7 * 60 until 18 * 60 else min >= st.first && min < st.second
        }
    }

    // ---------------------------------------------------------------- painting
    /**
     * @param light  UI is in its light look (only affects the night palette: dusk instead of deep night)
     * @param day    daylight road (lit asphalt, steppe, sky haze, no tail-light bloom)
     */
    fun paint(c: Context, cv: Canvas, w: Int, h: Int, accent: Int, light: Boolean,
              carH: Float = 0.62f, maxCarW: Float = 0.44f, horizon: Float = 0.14f, ground: Float = 0.9f, fade: Boolean = true,
              day: Boolean = isDay(), plate: String = Prefs.plate) {
        if (w <= 0 || h <= 0) return
        val car = Dash.car(c, R.drawable.sx4_rear)
        val ar = if (car != null) car.width / car.height.toFloat() else 0.87f
        var ch = h * carH; var cw = ch * ar
        if (cw > w * maxCarW) { cw = w * maxCarW; ch = cw / ar }
        val vx = w / 2f; val vy = h * horizon
        val yc = h * ground                                    // where the tyres touch the road
        val laneAtCar = cw * 1.7f                              // the car fills ~60 % of its lane, like on a real road
        fun lane(y: Float) = laneAtCar * (y - vy) / (yc - vy)  // lane width at height y (0 at the horizon)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val yb = h.toFloat()

        // 1) scene layer (faded at the sides, top and bottom, so it melts into any background — dark UI included)
        val layer = cv.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        if (day) {
            // sky: pale haze at the horizon → soft blue above; steppe below the horizon, warm and muted
            p.shader = LinearGradient(0f, 0f, 0f, vy, intArrayOf(0xFF7FA7D2.toInt(), 0xFFA7C2DC.toInt(), 0xFFC6D4E0.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), vy + 1, p)
            p.shader = LinearGradient(0f, vy, 0f, yb, intArrayOf(0xFFB9BEA4.toInt(), 0xFF97A07A.toInt(), 0xFF6E7752.toInt()), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, vy, w.toFloat(), yb, p)
        }
        val half = { y: Float -> lane(y) * 1.5f + lane(y) * 0.28f }   // 3 lanes + shoulders
        val road = Path().apply { moveTo(vx, vy); lineTo(vx + half(yb), yb); lineTo(vx - half(yb), yb); close() }
        p.shader = LinearGradient(0f, vy, 0f, yb,
            when {
                day -> intArrayOf(0xFFB3B7BC.toInt(), 0xFF8A8F96.toInt(), 0xFF60656D.toInt())
                light -> intArrayOf(0x00C3C8CF, 0xFFB4BAC2.toInt(), 0xFF9AA1AB.toInt())
                else -> intArrayOf(0x00151820, 0xFF1B1F27.toInt(), 0xFF2B303A.toInt())
            }, floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(road, p)
        // soft sheen on the asphalt right behind the car
        p.shader = RadialGradient(vx, yc + (h - yc) * 0.3f, cw * 1.1f, intArrayOf(if (day || light) 0x33FFFFFF else 0x22FFFFFF, 0), null, Shader.TileMode.CLAMP)
        cv.save(); cv.scale(1f, 0.35f, vx, yc); cv.drawCircle(vx, yc + (h - yc) * 0.3f, cw * 1.1f, p); cv.restore()
        p.shader = null

        fun strip(off: Float, wd: Float, y0: Float, y1: Float, color: Int) {
            val q = Path().apply {
                moveTo(vx + (off - wd / 2) * lane(y0), y0); lineTo(vx + (off + wd / 2) * lane(y0), y0)
                lineTo(vx + (off + wd / 2) * lane(y1), y1); lineTo(vx + (off - wd / 2) * lane(y1), y1); close()
            }
            p.color = color; cv.drawPath(q, p)
        }
        val lineC = if (day || light) 0xF2FFFFFF.toInt() else 0xB3FFFFFF.toInt()
        for (s in floatArrayOf(-1.5f, 1.5f)) {
            if (!day) strip(s, 0.16f, vy, yb, Ui.alpha(accent, if (light) 0.25f else 0.35f))   // accent halo only at night
            strip(s, 0.045f, vy, yb, lineC)
        }
        for (s in floatArrayOf(-0.5f, 0.5f)) {
            var z = 1f / 0.03f
            while (true) {
                val y0 = vy + (yc - vy) / z
                if (y0 > yb) break
                val y1 = vy + (yc - vy) / (z / 1.18f)
                strip(s, 0.04f, y0, min(y1, yb), lineC)
                z /= 1.45f
                if (z < 0.2f) break
            }
        }
        if (fade) {
            val m = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            m.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0, Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), m)
            if (day) {
                // daylight is a soft oval "window" that melts into the (dark) interface on every side — no box edges
                val cy = (vy + yb) / 2f; val rx = w * 0.56f; val ry = min((yb - vy) * 0.62f + vy * 0.5f, cy * 0.97f)
                m.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, 0x55000000, 0), floatArrayOf(0f, 0.45f, 0.78f, 0.97f), Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(vx, cy) }) }
            } else {
                m.shader = LinearGradient(0f, vy, 0f, yb, intArrayOf(0, Color.BLACK, Color.BLACK, 0),
                    floatArrayOf(0f, 0.4f, (0.8f * (yc - vy) / (yb - vy)).coerceIn(0.45f, 0.9f), 1f), Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), m)
            if (day) { m.shader = LinearGradient(0f, min(yc, yb) * 0.86f, 0f, yb, Color.BLACK, 0, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), m) }
        }
        cv.restoreToCount(layer)
        // horizon glow at the vanishing point, sized so it fades out well inside the view (no visible box edges)
        val gr = w * 0.36f; val sy = (min(vy, h - vy) * 0.9f / gr).coerceIn(0.12f, 0.5f)
        val gc = when { day -> 0x40FFFFFF; light -> 0x99FFFFFF.toInt(); else -> Ui.alpha(accent, 0.38f) }
        p.shader = RadialGradient(vx, vy, gr, intArrayOf(gc, Ui.alpha(if (day || light) Color.WHITE else accent, 0.08f), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.save(); cv.scale(1f, sy, vx, vy); cv.drawCircle(vx, vy, gr, p); cv.restore(); p.shader = null

        if (car == null) return
        // 2) car: ground shadow (crisper under the sun), the car, its plate, tail-light bloom at night
        val top = yc - ch * CONTACT; val l = vx - cw / 2
        if (day) {
            p.shader = RadialGradient(vx, yc, cw * 0.56f, intArrayOf(0x99000000.toInt(), 0x66000000, 0), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
            cv.save(); cv.scale(1f, 0.13f, vx, yc); cv.drawCircle(vx, yc, cw * 0.56f, p); cv.restore()
        } else {
            p.shader = RadialGradient(vx, yc, cw * 0.62f, intArrayOf(if (light) 0x55000000 else 0xAA000000.toInt(), 0), null, Shader.TileMode.CLAMP)
            cv.save(); cv.scale(1f, 0.16f, vx, yc); cv.drawCircle(vx, yc, cw * 0.62f, p); cv.restore()
        }
        p.shader = null
        // v1.7.1: the car is pre-scaled in halving steps to its exact pixel size (a single big bilinear jump from the
        // 637 px asset aliased into "pixels"), the plate is drawn as vector shapes / text right at the final size
        val sc = scaledCar(car, Math.round(cw), Math.round(ch))
        val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (!day && !light) {
            // faint mirror of the car on the wet-looking night asphalt
            val rl = cv.saveLayer(l, yc, l + cw, yc + ch * 0.3f, null)
            cv.save(); cv.scale(1f, -1f, vx, yc); bp.alpha = 46; cv.drawBitmap(sc, null, RectF(l, top, l + cw, top + ch), bp); cv.restore()
            bp.alpha = 255
            val m = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); shader = LinearGradient(0f, yc, 0f, yc + ch * 0.3f, Color.BLACK, 0, Shader.TileMode.CLAMP) }
            cv.drawRect(l, yc, l + cw, yc + ch * 0.3f, m)
            cv.restoreToCount(rl)
        }
        cv.drawBitmap(sc, null, RectF(l, top, l + cw, top + ch), bp)
        drawPlate(cv, plate, l + cw * PLATE_CX, top + ch * PLATE_CY, cw * PLATE_W, ch * PLATE_H, day || light)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
        for (i in 0..1) {
            val gx = l + cw * TAIL[i * 2]; val gy = top + ch * TAIL[i * 2 + 1]; val r = cw * (if (day) 0.12f else 0.2f)
            glow.shader = RadialGradient(gx, gy, r, intArrayOf(when { day -> 0x22FF3B30; light -> 0x40FF3B30; else -> 0x80FF2A1F.toInt() }, 0), null, Shader.TileMode.CLAMP)
            cv.drawCircle(gx, gy, r, glow)
            if (!day && !light) {   // faint red reflection on the asphalt under each light
                glow.shader = RadialGradient(gx, yc + ch * 0.04f, r, intArrayOf(0x33FF2A1F, 0), null, Shader.TileMode.CLAMP)
                cv.save(); cv.scale(0.6f, 0.25f, gx, yc + ch * 0.04f); cv.drawCircle(gx, yc + ch * 0.04f, r, glow); cv.restore()
            }
        }
    }

    // ---------------------------------------------------------------- car scaling (quality)
    private var carKey: String? = null; private var carScaled: Bitmap? = null

    /** High-quality downscale: halve with filtering until < 2× the target, then one last filtered step. Cached per size. */
    fun scaledCar(src: Bitmap, w: Int, h: Int): Bitmap {
        if (w <= 0 || h <= 0 || (w >= src.width && h >= src.height)) return src
        val key = "${System.identityHashCode(src)}|$w|$h"
        carScaled?.let { if (key == carKey && !it.isRecycled) return it }
        var b = src
        while (b.width / 2 >= w && b.height / 2 >= h) {
            val n = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle(); b = n
        }
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (b !== src && b !== out) b.recycle()
        carScaled?.takeIf { it !== src }?.recycle(); carScaled = out; carKey = key
        return out
    }

    // ---------------------------------------------------------------- Kazakhstan plate
    private var plateKey: String? = null; private var plateBmp: Bitmap? = null
    const val PW = 1040; const val PH = 224

    /** v1.7.1: the plate is drawn as vectors directly at its on-screen size (sharp text), with a slight keystone. */
    private fun drawPlate(cv: Canvas, text: String, cx: Float, cy: Float, pw: Float, ph: Float, lit: Boolean) {
        if (text.isBlank()) return
        val src = floatArrayOf(0f, 0f, PW.toFloat(), 0f, PW.toFloat(), PH.toFloat(), 0f, PH.toFloat())
        val k = pw * 0.012f      // camera is above: the top edge is a touch closer → a hair wider
        val dst = floatArrayOf(cx - pw / 2 - k, cy - ph / 2, cx + pw / 2 + k, cy - ph / 2, cx + pw / 2, cy + ph / 2, cx - pw / 2, cy + ph / 2)
        val m = Matrix(); m.setPolyToPoly(src, 0, dst, 0, 4)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // the plate sits in the recess: a thin shadow under it
        p.color = 0x66000000; cv.drawRoundRect(RectF(cx - pw / 2, cy - ph / 2 + ph * 0.14f, cx + pw / 2, cy + ph / 2 + ph * 0.14f), ph * 0.14f, ph * 0.14f, p)
        cv.save(); cv.concat(m)
        plateShapes(cv, text, if (lit) 1f else 0.86f)
        cv.restore()
    }

    /** Raster copy (used by tests / previews). */
    fun plateBitmap(text: String): Bitmap {
        plateBmp?.let { if (plateKey == text && !it.isRecycled) return it }
        val b = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        plateShapes(Canvas(b), text, 1f)
        plateBmp?.recycle(); plateBmp = b; plateKey = text
        return b
    }

    /** KZ plate (520×112 mm, ×2): white field, black frame, sky-blue flag with sun and eagle, «KZ», number | region. */
    private fun plateShapes(cv: Canvas, text: String, bright: Float) {
        val W = PW; val H = PH
        fun dim(c: Int) = if (bright >= 1f) c else Color.rgb((Color.red(c) * bright).toInt(), (Color.green(c) * bright).toInt(), (Color.blue(c) * bright).toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val r = RectF(4f, 4f, W - 4f, H - 4f)
        p.color = dim(Color.WHITE); cv.drawRoundRect(r, 20f, 20f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.color = 0xFF111111.toInt()
        cv.drawRoundRect(RectF(12f, 12f, W - 12f, H - 12f), 16f, 16f, p); p.style = Paint.Style.FILL
        // left field: national flag (sky blue, golden sun with rays, eagle) and "KZ"
        val fx0 = 26f; val fx1 = 178f
        p.color = dim(0xFF00AFCA.toInt()); cv.drawRoundRect(RectF(fx0, 26f, fx1, 126f), 6f, 6f, p)
        val sx = (fx0 + fx1) / 2; val sy = 66f
        p.color = dim(0xFFFEC50C.toInt())
        for (i in 0 until 16) {   // rays
            val a = Math.toRadians(i * 22.5); val r0 = 22f; val r1 = 32f
            cv.drawLine(sx + (r0 * cos(a)).toFloat(), sy + (r0 * sin(a)).toFloat(), sx + (r1 * cos(a)).toFloat(), sy + (r1 * sin(a)).toFloat(), p.apply { strokeWidth = 4f })
        }
        cv.drawCircle(sx, sy, 17f, p)
        val eagle = Path().apply {   // simplified steppe eagle under the sun
            moveTo(sx - 46f, 104f); quadTo(sx - 20f, 92f, sx, 104f); quadTo(sx + 20f, 92f, sx + 46f, 104f)
            quadTo(sx + 20f, 100f, sx, 114f); quadTo(sx - 20f, 100f, sx - 46f, 104f); close()
        }
        cv.drawPath(eagle, p)
        p.color = 0xFF111111.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSize = 72f
        cv.drawText("KZ", sx, 196f, p)
        // characters: "123 ABC" + divider + region "01"
        val (main, region) = Plate.split(text)
        val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.typeface = cond; p.color = 0xFF111111.toInt()
        val regionW = if (region.isNotEmpty()) 190f else 0f
        if (region.isNotEmpty()) {
            p.strokeWidth = 10f; cv.drawLine(W - 12f - regionW, 12f, W - 12f - regionW, H - 12f, p)
            p.textSize = 170f; fitText(p, region, regionW - 30f)
            cv.drawText(region, W - 12f - regionW / 2, H / 2f + p.textSize * 0.36f, p)
        }
        val mx0 = fx1 + 10f; val mx1 = W - 12f - regionW - 10f
        p.textSize = 176f; fitText(p, main, mx1 - mx0 - 20f)
        cv.drawText(main, (mx0 + mx1) / 2, H / 2f + p.textSize * 0.36f, p)
    }

    private fun fitText(p: Paint, s: String, maxW: Float) { val w = p.measureText(s); if (w > maxW) p.textSize *= maxW / w }

    fun release() { plateBmp?.recycle(); plateBmp = null; plateKey = null; carScaled = null; carKey = null }

    fun view(c: Context, accent: Int, light: Boolean, carH: Float = 0.62f, maxCarW: Float = 0.44f, horizon: Float = 0.14f, ground: Float = 0.9f): RoadView =
        RoadView(c) { cv, w, h, day -> paint(c, cv, w, h, accent, light, carH, maxCarW, horizon, ground, day = day) }
}

/** Road scene view: remembers which time of day it painted; [check] repaints only when that changes (≤ once a tick). */
class RoadView(c: Context, painter: (Canvas, Int, Int, Boolean) -> Unit) : CachedArt(c, { cv, w, h -> painter(cv, w, h, RoadArt.isDay()) }) {
    private var shownDay = RoadArt.isDay()
    fun check() { val d = RoadArt.isDay(); if (d != shownDay) { shownDay = d; repaint() } }
}

/** Kazakhstan plate text: loose input → "123 ABC 01". Latin letters + digits only; Cyrillic look-alikes are mapped. */
object Plate {
    private const val CYR = "АВЕКМНОРСТХУ"; private const val LAT = "ABEKMHOPCTXY"
    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch0 in raw.uppercase()) {
            val i = CYR.indexOf(ch0); val ch = if (i >= 0) LAT[i] else ch0
            if (ch in 'A'..'Z' || ch in '0'..'9') sb.append(ch)
        }
        val s = sb.toString().take(9)
        Regex("^(\\d{3})([A-Z]{2,3})(\\d{2})$").find(s)?.let { return it.groupValues.drop(1).joinToString(" ") }
        // anything else: space between digit / letter runs
        val out = StringBuilder()
        for ((n, ch) in s.withIndex()) { if (n > 0 && ch.isDigit() != s[n - 1].isDigit()) out.append(' '); out.append(ch) }
        return out.toString()
    }
    /** (main, region): region = trailing 2-digit group when there is something before it. */
    fun split(text: String): Pair<String, String> {
        val g = text.trim().split(' ').filter { it.isNotEmpty() }
        return if (g.size >= 2 && g.last().length == 2 && g.last().all { it.isDigit() }) g.dropLast(1).joinToString(" ") to g.last()
        else text.trim() to ""
    }
}
