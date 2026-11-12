package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Wallpaper under the whole content area.
 * Memory: the wallpaper is decoded once at content size in RGB_565 (≈1168×720×2 ≈ 1.7 MB);
 * the frosted-glass source is a 1/4 copy blurred once (≈292×180×4 ≈ 210 KB). Nothing is blurred live.
 * "Cover as wallpaper" uses a tiny blurred + darkened copy of the album art (≈146×90, ~53 KB), upscaled by the GPU.
 */
object Wallpaper {
    class Builtin(val id: String, val title: String, val res: Int)
    val BUILTIN = listOf(
        Builtin("dusk", "Сумерки", R.drawable.wp_dusk),
        Builtin("aurora", "Сияние", R.drawable.wp_aurora),
        Builtin("fluid", "Флюид", R.drawable.wp_fluid),
        Builtin("synth", "Синтвейв", R.drawable.wp_synth),
        Builtin("city", "Город", R.drawable.wp_city),
        Builtin("ocean", "Океан", R.drawable.wp_ocean)
    )

    @Volatile var full: Bitmap? = null; private set
    @Volatile var blur: Bitmap? = null; private set
    private var baseFull: Bitmap? = null
    private var baseBlur: Bitmap? = null
    private var baseKey: String? = null
    private var cover: Bitmap? = null
    private var coverKey: Any? = null
    /** v1.7.1 «Чёткая обложка»: the album art itself at content size (lightly darkened), made only in that mode. */
    private var coverSharp: Bitmap? = null
    private var coverArt: Bitmap? = null
    /** v1.7.1 colour picked from the current cover (null = no cover / grey cover). */
    @Volatile var coverColor: Int? = null; private set
    /** Called on the main thread when [coverColor] changed. */
    var onCoverColor: ((Int?) -> Unit)? = null
    private val listeners = ArrayList<() -> Unit>()
    var width = 0; private set
    var height = 0; private set

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun publish() {
        val c = if (Prefs.coverWallpaper) cover else null
        full = (if (Prefs.bgMode == "sharp" && c != null) coverSharp else null) ?: c ?: baseFull
        blur = c ?: baseBlur
        AppRepo.main.post { listeners.toList().forEach { it() } }
    }

    fun currentId(): String = Prefs.wallpaper.let { if (it == "theme") Themes.current.wallpaper else it }

    /** Loads the configured wallpaper at w×h on a worker thread; no-op if unchanged. */
    fun reload(ctx: Context, w: Int, h: Int, force: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val app = ctx.applicationContext
        AppRepo.exec.execute {
            val id = currentId()
            val key = "$id|$w|$h|" + (Gallery.fileFor(app, id)?.lastModified() ?: 0)
            synchronized(this) {
                if (!force && key == baseKey) return@execute
                val b = try { decode(app, id, w, h) } catch (e: Throwable) { null }
                baseFull = b; baseBlur = b?.let { makeBlur(it, 4, 6) }
                baseKey = key; width = w; height = h
            }
            publish()
        }
    }

    private fun decode(c: Context, id: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val file = Gallery.fileFor(c, id)
        val res = (BUILTIN.firstOrNull { it.id == id } ?: BUILTIN[0]).res
        fun dec(o: BitmapFactory.Options): Bitmap? =
            if (file != null) BitmapFactory.decodeFile(file.path, o) else BitmapFactory.decodeResource(c.resources, res, o)
        dec(bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = dec(BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false }) ?: return null
        return centerCrop(src, w, h, Bitmap.Config.RGB_565).also { if (it !== src) src.recycle() }
    }

    fun centerCrop(src: Bitmap, w: Int, h: Int, cfg: Bitmap.Config): Bitmap {
        if (src.width == w && src.height == h && src.config == cfg) return src
        val out = Bitmap.createBitmap(w, h, cfg)
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { setScale(s, s); postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Downscale by [div] and box-blur [passes] times (≈ gaussian). Done once per wallpaper change. */
    fun makeBlur(src: Bitmap, div: Int, radius: Int, passes: Int = 3): Bitmap {
        val w = maxOf(8, src.width / div); val h = maxOf(8, src.height / div)
        val small = centerCrop(src, w, h, Bitmap.Config.ARGB_8888).let { if (it === src) it.copy(Bitmap.Config.ARGB_8888, true) else it }
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) { box(px, tmp, w, h, radius, true); box(tmp, px, w, h, radius, false) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val len = if (horiz) w else h; val lines = if (horiz) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var rs = 0; var gs = 0; var bs = 0
            fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horiz) src[l * w + k] else src[k * w + l] }
            for (i in -r..r) { val c = at(i); rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255 }
            for (i in 0 until len) {
                val o = if (horiz) l * w + i else i * w + l
                dst[o] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val a = at(i - r); val b = at(i + r + 1)
                rs += ((b shr 16) and 255) - ((a shr 16) and 255)
                gs += ((b shr 8) and 255) - ((a shr 8) and 255)
                bs += (b and 255) - (a and 255)
            }
        }
    }

    /** "Cover as wallpaper": heavy blur + darken of the album art, computed once per track. */
    fun setCover(art: Bitmap?) {
        if (art === coverKey) return
        coverKey = art; coverArt = art
        if (art == null) { cover = null; coverSharp = null; setColor(null); publish(); return }
        val w = if (width > 0) width else 1168; val h = if (height > 0) height else 720
        AppRepo.exec.execute {
            val c = try {
                val crop = centerCrop(art, w / 4, h / 4, Bitmap.Config.ARGB_8888)
                makeBlur(crop, 2, 5, 3).also { Canvas(it).drawColor(0x8C000000.toInt()) }
            } catch (e: Throwable) { null }
            val sharp = if (Prefs.bgMode == "sharp") sharpOf(art, w, h) else null
            val col = try { CoverColor.pick(art) } catch (e: Throwable) { null }
            if (coverKey === art) { cover = c; coverSharp = sharp; publish(); AppRepo.main.post { setColor(col) } }
        }
    }

    private fun setColor(c: Int?) {
        if (c == coverColor) return
        coverColor = c
        onCoverColor?.invoke(c)
    }

    /** The cover filling the content area, sharp (RGB_565 ≈ 1.7 MB), darkened a bit so the text stays readable. */
    private fun sharpOf(art: Bitmap, w: Int, h: Int): Bitmap? = try {
        centerCrop(art, w, h, Bitmap.Config.RGB_565).let { if (it === art) it.copy(Bitmap.Config.RGB_565, true) else it }
            .also { Canvas(it).drawColor(0x47000000) }
    } catch (e: Throwable) { null }

    /** Background mode changed in Settings / quick panel. */
    fun refreshCoverMode() {
        val art = coverArt
        if (Prefs.bgMode == "sharp" && coverSharp == null && art != null) {
            val w = if (width > 0) width else 1168; val h = if (height > 0) height else 720
            AppRepo.exec.execute { val s = sharpOf(art, w, h); if (coverArt === art) { coverSharp = s; publish() } }
            return
        }
        if (Prefs.bgMode != "sharp") coverSharp = null
        publish()
    }

    /** onTrimMemory: forget all bitmaps (GC frees them once views drop their references). */
    fun release() {
        synchronized(this) { baseFull = null; baseBlur = null; baseKey = null }
        cover = null; coverKey = null; coverSharp = null; coverArt = null
        full = null; blur = null
        AppRepo.main.post { listeners.toList().forEach { it() } }
    }

    fun thumb(c: Context, id: String): Bitmap? = try {
        val f = Gallery.fileFor(c, id)
        val o = BitmapFactory.Options().apply { inSampleSize = 8; inPreferredConfig = Bitmap.Config.RGB_565 }
        if (f != null) BitmapFactory.decodeFile(f.path, o)
        else if (id.startsWith("g:")) null
        else BitmapFactory.decodeResource(c.resources, (BUILTIN.firstOrNull { it.id == id } ?: BUILTIN[0]).res, o)
    } catch (e: Throwable) { null }
}
