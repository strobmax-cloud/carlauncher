package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Color

/**
 * v1.7.1 «Цвет из обложки»: a small Palette-like picker without AndroidX. The cover is scaled to 32×32, pixels are
 * grouped by hue (24 buckets); the bucket with the most saturated weight wins, its average colour is then made bright
 * enough for the dark interface. Grey / black-and-white covers return null (the theme accent stays).
 */
object CoverColor {
    fun pick(b: Bitmap): Int? {
        val s = Bitmap.createScaledBitmap(b, 32, 32, true)
        val px = IntArray(32 * 32); s.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (s !== b) s.recycle()
        return pick(px)
    }

    fun pick(px: IntArray): Int? {
        val n = 24
        val w = DoubleArray(n); val hs = DoubleArray(n); val ss = DoubleArray(n); val vs = DoubleArray(n)
        val hsv = FloatArray(3)
        var total = 0.0
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val sat = hsv[1]; val v = hsv[2]
            total += 1
            if (sat < 0.28f || v < 0.22f) continue
            val k = ((hsv[0] / 360f) * n).toInt().coerceIn(0, n - 1)
            val weight = (sat * sat * (0.4f + v)).toDouble()
            w[k] += weight; hs[k] += hsv[0] * weight; ss[k] += sat * weight; vs[k] += v * weight
        }
        var best = -1; var bw = 0.0
        for (k in 0 until n) { val nb = w[k] + 0.5 * (w[(k + 1) % n] + w[(k + n - 1) % n]); if (nb > bw) { bw = nb; best = k } }
        if (best < 0 || bw < total * 0.04) return null
        val h = (hs[best] / w[best]).toFloat()
        val sat = (ss[best] / w[best]).toFloat().coerceIn(0.55f, 0.95f)
        val v = (vs[best] / w[best]).toFloat().coerceIn(0.85f, 1f)   // readable on the dark UI
        return Color.HSVToColor(floatArrayOf(h, sat, v))
    }
}
