package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar

/**
 * The user's own wallpapers (from USB/SD via the picker or uploaded from the phone).
 * Stored as ≤1280×720 JPEGs in app storage: filesDir/gallery/w_<time>.jpg. Wallpaper id = "g:<file name>".
 */
object Gallery {
    const val MAX_W = 1280
    const val MAX_H = 720
    const val MAX_FILES = 60
    private val NAME = Regex("^w_[0-9]+\\.jpg$")

    fun dir(c: Context) = File(c.filesDir, "gallery").apply { mkdirs() }
    fun list(c: Context): List<File> = dir(c).listFiles { f -> NAME.matches(f.name) }?.sortedBy { it.name } ?: emptyList()
    fun ids(c: Context): List<String> = list(c).map { "g:" + it.name }
    fun fileFor(c: Context, id: String): File? {
        if (!id.startsWith("g:")) return null
        val n = id.substring(2)
        return if (NAME.matches(n)) File(dir(c), n).takeIf { it.exists() } else null
    }

    /** v1.4 had a single "custom" wallpaper.jpg: move it into the gallery once. */
    fun migrate(c: Context) {
        val old = File(c.filesDir, "wallpaper.jpg")
        if (!old.exists()) return
        val dst = File(dir(c), "w_${old.lastModified().coerceAtLeast(1)}.jpg")
        if (!old.renameTo(dst)) { old.copyTo(dst, true); old.delete() }
        if (Prefs.wallpaper == "custom") Prefs.wallpaper = "g:" + dst.name
    }

    @Synchronized private fun newFile(c: Context): File {
        var t = System.currentTimeMillis()
        while (File(dir(c), "w_$t.jpg").exists()) t++
        return File(dir(c), "w_$t.jpg")
    }

    /** Sample size so the decoded image still covers MAX_W×MAX_H (never decode the full 12 MP photo). */
    fun sampleFor(w: Int, h: Int): Int {
        val lw = maxOf(w, h); val lh = minOf(w, h)
        var s = 1
        while (lw / (s * 2) >= MAX_W && lh / (s * 2) >= MAX_H) s *= 2
        return s
    }

    /** Centre-crops to 16:9 and scales down to at most MAX_W×MAX_H (no upscaling). */
    fun fit(src: Bitmap): Bitmap {
        val k = minOf(1f, maxOf(MAX_W.toFloat() / src.width, MAX_H.toFloat() / src.height))
        val outW: Int; val outH: Int
        val aspect = MAX_W.toFloat() / MAX_H
        if (src.width.toFloat() / src.height > aspect) { outH = minOf(MAX_H, Math.round(src.height * k)); outW = Math.round(outH * aspect) }
        else { outW = minOf(MAX_W, Math.round(src.width * k)); outH = Math.round(outW / aspect) }
        return Wallpaper.centerCrop(src, maxOf(1, outW), maxOf(1, outH), Bitmap.Config.RGB_565)
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val deg = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg == 0f) return b
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        if (out !== b) b.recycle()
        return out
    }

    private fun orientation(open: () -> InputStream?): Int = try {
        open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: 0
    } catch (e: Throwable) { 0 }

    private fun decode(open: () -> InputStream?): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val b = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight); inPreferredConfig = Bitmap.Config.RGB_565 }) } ?: return null
        return rotate(b, orientation(open))
    }

    private fun save(c: Context, b: Bitmap): String {
        val out = fit(b)
        val f = newFile(c)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
        if (out !== b) out.recycle()
        b.recycle()
        // keep storage bounded: drop the oldest beyond MAX_FILES (never the current one)
        val all = list(c)
        if (all.size > MAX_FILES) all.take(all.size - MAX_FILES).forEach { if ("g:" + it.name != Prefs.wallpaper) it.delete() }
        return "g:" + f.name
    }

    fun addBytes(c: Context, bytes: ByteArray): String? = decode { ByteArrayInputStream(bytes) }?.let { save(c, it) }
    fun addUri(c: Context, uri: Uri): String? = decode { c.contentResolver.openInputStream(uri) }?.let { save(c, it) }

    fun delete(c: Context, id: String): Boolean {
        val f = fileFor(c, id) ?: return false
        val ok = f.delete()
        if (ok && Prefs.wallpaper == id) Prefs.wallpaper = "theme"
        return ok
    }

    // ---------------- automatic rotation ----------------
    val ROTATE = listOf("off" to "Выкл.", "launch" to "При включении", "hour" to "Каждый час", "day" to "Раз в день")
    /** Launch = the launcher process starts or the unit wakes after ≥ 20 min off (FYT units sleep instead of rebooting). */
    const val WAKE_GAP_MS = 20 * 60_000L

    fun isDue(mode: String, now: Long, last: Long, launch: Boolean): Boolean = when (mode) {
        "launch" -> launch
        "hour" -> last == 0L || now - last >= 3_600_000L || now < last
        "day" -> {
            if (last == 0L) true else {
                val a = Calendar.getInstance().apply { timeInMillis = last }; val b = Calendar.getInstance().apply { timeInMillis = now }
                a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
            }
        }
        else -> false
    }

    /** Pool: the user's gallery; with fewer than 2 own pictures the built-in wallpapers join in. */
    fun pool(c: Context): List<String> {
        val g = ids(c)
        return if (g.size >= 2) g else Wallpaper.BUILTIN.map { it.id } + g
    }

    fun next(pool: List<String>, current: String): String? {
        if (pool.isEmpty()) return null
        val i = pool.indexOf(current)
        return pool[(i + 1).mod(pool.size)].takeIf { it != current || pool.size == 1 }
    }

    /** Cheap check (called on launch / resume / once a minute on the home screen). Returns true if the wallpaper changed. */
    fun maybeRotate(c: Context, launch: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (!isDue(Prefs.rotate, now, Prefs.rotatedAt, launch)) return false
        Prefs.rotatedAt = now
        val cur = Wallpaper.currentId()
        val n = next(pool(c), cur) ?: return false
        if (n == cur) return false
        Prefs.wallpaper = n
        return true
    }

    @Suppress("unused") private val keep = Paint::class.java to Canvas::class.java
}
