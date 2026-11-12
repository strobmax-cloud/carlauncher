package com.goga.carlauncher

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Loads album art and downscales it to the card size (keeps memory low: ~460px square ≈ 0.8 MB). */
object ArtLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun scale(src: Bitmap, target: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= target) return src
        val k = target.toFloat() / m
        return Bitmap.createScaledBitmap(src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), true)
    }

    fun fromUri(cr: ContentResolver, s: String, target: Int): Bitmap? {
        val bytes = open(cr, s)?.use { readCapped(it) } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return scale(b, target)
    }

    /** Decodes a (possibly huge) image Uri with inSampleSize, never loading the full-size photo. */
    fun decodeSampled(cr: ContentResolver, uri: Uri, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        val b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        return scale(b, target)
    }

    private fun open(cr: ContentResolver, s: String): InputStream? {
        val uri = Uri.parse(s)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> (URL(s).openConnection() as HttpURLConnection).run {
                connectTimeout = 4000; readTimeout = 6000; instanceFollowRedirects = true
                if (responseCode in 200..299) inputStream else null
            }
            else -> cr.openInputStream(uri)
        }
    }

    private fun readCapped(i: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf); if (n < 0) break
            out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }
}

/** ImageView that is always square, sized by its height (used for the big album cover). */
class SquareImageView(c: Context) : ImageView(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(h, h)
    }
}
