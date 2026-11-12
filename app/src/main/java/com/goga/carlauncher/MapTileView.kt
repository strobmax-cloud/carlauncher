package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import android.view.View
import com.goga.carlauncher.Ui.u
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Tiny raster map for the "Карта" layout — not a navigator, a live "where am I" picture.
 * - Standard OpenStreetMap raster tiles (tile.openstreetmap.org, zoom 16) recoloured to a night look once per tile (see night()). Fetched only while the view is on screen, with an identifying User-Agent,
 *   cached on disk and refreshed after 30 days — within the OSM tile usage policy for a single personal device.
 * - Disk cache in cacheDir/tiles (≈10–25 KB per tile, trimmed to 1500 files ≈ 20 MB); memory LRU ≈ 4 MB of RGB_565 tiles.
 * - Redraws only on GPS updates or when a tile arrives. No gestures; a tap opens the real navigator.
 * - Offline and never cached → dark grid placeholder with a hint.
 */
class MapTileView(ctx: Context, private val accent: Int, private val day: Boolean = false) : View(ctx) {
    companion object {
        const val Z = 16
        const val DEFAULT_LAT = 43.2380   // Алматы, если GPS ещё не было
        const val DEFAULT_LON = 76.9286
        private const val URL_FMT = "https://tile.openstreetmap.org/%d/%d/%d.png"
        private const val MAX_AGE = 30L * 24 * 3600 * 1000
        /**
         * Night recolouring of a standard OSM tile, once per tile on a worker thread (~2 ms per 256² tile).
         * Mirrors tools/make_map_preview.py: dark blue-grey ground, light labels, dark green parks, navy water,
         * muted amber main roads, grey streets.
         */
        fun night(src: Bitmap): Bitmap {
            val w = src.width; val h = src.height
            val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
                val mx = maxOf(r, g, b); val sat = mx - minOf(r, g, b)
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                var nr: Float; var ng: Float; var nb: Float
                val v = 0.075f + 0.3f * (0.95f - l).coerceIn(0f, 0.2f)
                nr = v * 0.92f; ng = v * 0.96f; nb = v * 1.12f
                if (l < 0.55f) { val t = 0.9f * (1 - l); nr = t; ng = t; nb = t }
                else if (g > r + 0.04f && g > b) { nr = 0.07f; ng = 0.13f; nb = 0.10f }
                else if (b > r + 0.08f && b >= g) { nr = 0.05f; ng = 0.09f; nb = 0.16f }
                else if (r > 0.85f && sat > 0.12f && b < g) {
                    if (g > 0.62f) { nr = 0.52f; ng = 0.41f; nb = 0.27f } else { nr = 0.20f; ng = 0.22f; nb = 0.26f }
                }
                if (sat < 0.05f && l > 0.975f) { nr = 0.36f; ng = 0.39f; nb = 0.45f }
                px[i] = (0xFF shl 24) or ((nr.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((ng.coerceIn(0f, 1f) * 255).toInt() shl 8) or (nb.coerceIn(0f, 1f) * 255).toInt()
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
        }
        /** Calm day style (Scandinavian light layout): half-desaturated, lifted, slightly cool. Mirrors tools/make_map_preview.py day(). */
        fun day(src: Bitmap): Bitmap {
            val w = src.width; val h = src.height
            val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val l = (299 * r + 587 * g + 114 * b) / 1000
                val nr = (r + l) / 2 * 86 / 100 + 30; val ng = (g + l) / 2 * 86 / 100 + 31; val nb = (b + l) / 2 * 86 / 100 + 34
                px[i] = (0xFF shl 24) or (nr.coerceAtMost(255) shl 16) or (ng.coerceAtMost(255) shl 8) or nb.coerceAtMost(255)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
        }
        private val mem = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val pending = HashSet<String>()
        private val failed = HashMap<String, Long>()
        @Volatile var lastLat = Double.NaN; @Volatile var lastLon = Double.NaN
        fun trim() { mem.evictAll() }
        fun memBytes() = mem.size()
        fun tileDir(c: Context) = File(c.cacheDir, "tiles")
    }

    private var lat = if (lastLat.isNaN()) DEFAULT_LAT else lastLat
    private var lon = if (lastLon.isNaN()) DEFAULT_LON else lastLon
    private var bearing: Float? = null
    private var hasFix = false
    private var anyTile = false
    private val bg = Paint().apply { color = if (day) 0xFFE4E4E0.toInt() else 0xFF16181D.toInt() }
    private val grid = Paint().apply { color = if (day) 0x14000000 else 0x14FFFFFF; strokeWidth = 1f }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val puck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val puckRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(accent, 0.22f) }
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(accent, 0.35f) }
    private val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (day) 0x99000000.toInt() else 0x80FFFFFF.toInt(); textAlign = Paint.Align.RIGHT }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (day) 0x99000000.toInt() else 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val path = Path()

    fun setLocation(la: Double, lo: Double, b: Float?) {
        lat = la; lon = lo; bearing = b; hasFix = true
        lastLat = la; lastLon = lo
        invalidate()
    }

    private fun worldX(lo: Double) = (lo + 180.0) / 360.0 * 256.0 * 2.0.pow(Z)
    private fun worldY(la: Double): Double {
        val r = Math.toRadians(la)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 256.0 * 2.0.pow(Z)
    }

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val ts = u(256).toFloat().coerceAtLeast(64f)       // tile size on screen (design scale)
        val k = ts / 256.0
        val cxw = worldX(lon) * k; val cyw = worldY(lat) * k
        val left = cxw - w / 2.0; val top = cyw - h * 0.55
        val x0 = floor(left / ts).toInt(); val y0 = floor(top / ts).toInt()
        val x1 = floor((left + w) / ts).toInt(); val y1 = floor((top + h) / ts).toInt()
        val n = 1 shl Z
        anyTile = false
        for (ty in y0..y1) for (tx in x0..x1) {
            if (ty < 0 || ty >= n) continue
            val sx = (tx * ts - left).toFloat(); val sy = (ty * ts - top).toFloat()
            val b = tile(((tx % n) + n) % n, ty)
            if (b != null) {
                anyTile = true
                c.save(); c.translate(sx, sy); c.scale(ts / b.width, ts / b.width); c.drawBitmap(b, 0f, 0f, tilePaint); c.restore()
            } else {
                c.drawLine(sx, sy, sx + ts, sy, grid); c.drawLine(sx, sy, sx, sy + ts, grid)
            }
        }
        // position puck
        val px = w / 2f; val py = (h * 0.55f)
        val b = bearing
        if (b != null) {
            c.save(); c.rotate(b, px, py)
            path.reset(); path.moveTo(px, py); path.lineTo(px - u(34), py - u(80)); path.lineTo(px + u(34), py - u(80)); path.close()
            c.drawPath(path, cone); c.restore()
        }
        c.drawCircle(px, py, u(30).toFloat(), halo)
        c.drawCircle(px, py, u(13).toFloat(), puckRing)
        c.drawCircle(px, py, u(9).toFloat(), puck)
        attr.textSize = u(12).toFloat()
        c.drawText("© участники OpenStreetMap", w - u(12).toFloat(), h - u(10).toFloat(), attr)
        if (!anyTile) {
            hint.textSize = u(18).toFloat()
            c.drawText("Карта появится при интернете · нажмите — откроется навигатор", w / 2f, py + u(70), hint)
        } else if (!hasFix) {
            hint.textSize = u(15).toFloat()
            c.drawText("последнее известное место", px, py + u(46), hint)
        }
    }

    private fun tile(x: Int, y: Int): Bitmap? {
        val key = (if (day) "d" else "n") + "$Z/$x/$y"
        mem.get(key)?.let { return it }
        if (!isAttachedToWindow) return null
        synchronized(pending) {
            if (key in pending) return null
            val f = failed[key]; if (f != null && System.currentTimeMillis() - f < 60_000) return null
            pending.add(key)
        }
        val dir = tileDir(context)
        AppRepo.exec.execute {
            var bmp: Bitmap? = null
            try {
                val file = File(dir, "$Z-$x-$y.png")
                if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE) try { download(String.format(java.util.Locale.US, URL_FMT, Z, x, y), file, dir) } catch (_: Exception) {}
                if (file.exists()) bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.let { if (day) day(it) else night(it) }
            } catch (_: Throwable) {}
            synchronized(pending) { pending.remove(key); if (bmp == null) failed[key] = System.currentTimeMillis() }
            if (bmp != null) { mem.put(key, bmp); AppRepo.main.post { invalidate() } }
        }
        return null
    }

    private fun download(url: String, file: File, dir: File) {
        dir.mkdirs()
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 6000; con.readTimeout = 8000
        con.setRequestProperty("User-Agent", "CarLauncher/1.4 (Android head unit; personal use)")
        try {
            if (con.responseCode != 200) return
            val tmp = File(dir, file.name + ".tmp")
            con.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            tmp.renameTo(file)
        } finally { con.disconnect() }
        val list = dir.listFiles() ?: return
        if (list.size > 1500) list.sortedBy { it.lastModified() }.take(list.size - 1200).forEach { it.delete() }
    }

}
