package com.goga.carlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Soft edge strip: accent gradient fading away from the screen edge; only its alpha changes. */
class GlowEdge(c: Context, private val bottom: Boolean) : View(c) {
    private val p = Paint()
    private var shH = -1; private var col = 0
    var level = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (abs255(n) != abs255(field)) { field = n; invalidate() } else field = n }
    private fun abs255(f: Float) = (f * 255).toInt()
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(cv: Canvas) {
        val a = (level * 150).toInt()          // never brighter than ~60 % — gentle, low contrast
        if (a <= 2) return
        val accent = Dash.accent()
        if (shH != height || col != accent) {
            shH = height; col = accent
            val c0 = (accent and 0x00FFFFFF) or (0xFF shl 24); val c1 = accent and 0x00FFFFFF
            p.shader = if (bottom) LinearGradient(0f, height.toFloat(), 0f, 0f, c0, c1, Shader.TileMode.CLAMP)
                       else LinearGradient(0f, 0f, 0f, height.toFloat(), c0, c1, Shader.TileMode.CLAMP)
        }
        p.alpha = a
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** v1.6.3 spectrum shared by every CoverView that shows «Столбики»: shown levels 0..1, eased; views register while attached. */
object MusicFx {
    const val BARS = 28
    val shown = FloatArray(BARS)
    val live = LinkedHashSet<View>()
    var on = false        // true while the bars should be drawn (fx == bars and something has been pushed)
    fun push() { live.forEach { it.invalidate() } }
}

/**
 * «Подсветка под музыку» (off by default): "glow" = soft edge glow following loudness (peak/RMS, 10 Hz);
 * "bars" = spectrum bars drawn inside the album cover (FFT of the global output, session 0, ≤ 30 fps).
 * Runs only while the home screen is visible AND music plays. If the Visualizer is unavailable (no RECORD_AUDIO,
 * firmware refuses session 0) it falls back to a calm idle animation (glow: breathing at 5 fps, bars: slow waves at 20 fps).
 * Paused / screen off → the bars ease down to zero and the loop stops (no CPU while idle).
 */
class AmbientGlow(private val ctx: Context, private val edges: List<GlowEdge>) {
    private val h = Handler(Looper.getMainLooper())
    private var vis: Visualizer? = null
    private var active = false; private var playing = false
    private var level = 0f
    var mode = "off"; private set          // off | music | breath | bars | barsIdle (for tests / settings)
    private var fails = 0
    private var fft = ByteArray(0)
    private val target = FloatArray(MusicFx.BARS)

    fun setActive(v: Boolean) { active = v; update() }
    fun setPlaying(v: Boolean) { if (playing != v) { playing = v; update() } }
    fun settingsChanged() { stop(); release(); update() }

    private fun fx() = Prefs.musicFx
    private fun want() = fx() != "off" && active && playing

    private fun update() {
        if (!want()) { stop(); return }
        if (mode != "off" && (mode.startsWith("bars") == (fx() == "bars"))) return
        stop()
        mode = if (fx() == "bars") { if (open(true)) "bars" else "barsIdle" } else { if (open(false)) "music" else "breath" }
        MusicFx.on = mode.startsWith("bars")
        h.removeCallbacks(loop); h.removeCallbacks(decay); h.post(loop)
    }

    private fun open(spectrum: Boolean): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (fails >= 2) return false
        return try {
            val v = vis ?: Visualizer(0).also { vis = it }
            v.enabled = false
            val r = Visualizer.getCaptureSizeRange()
            v.captureSize = if (spectrum) 512.coerceIn(r[0], r[1]) else r[0]
            if (!spectrum) v.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            v.enabled = true
            fft = ByteArray(v.captureSize)
            true
        } catch (e: Throwable) { fails++; release(); false }
    }

    /** FFT → 28 log-spaced bands (60 Hz … 12 kHz), dB-scaled to 0..1 with a gentle treble lift. */
    private fun bands(v: Visualizer): Boolean {
        if (v.getFft(fft) != Visualizer.SUCCESS) return false
        val n = fft.size / 2; val sr = v.samplingRate / 1000f        // Hz
        val binHz = sr / fft.size
        for (b in 0 until MusicFx.BARS) {
            val f0 = 60f * Math.pow(200.0, b / MusicFx.BARS.toDouble()).toFloat(); val f1 = 60f * Math.pow(200.0, (b + 1) / MusicFx.BARS.toDouble()).toFloat()
            val k0 = (f0 / binHz).toInt().coerceIn(1, n - 1); val k1 = (f1 / binHz).toInt().coerceIn(k0, n - 1)
            var mx = 0f
            for (k in k0..k1) { val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat(); val m = re * re + im * im; if (m > mx) mx = m }
            val db = 10f * kotlin.math.log10(mx + 1f)                      // 0 … ~45
            target[b] = ((db - 8f + b * 0.25f) / 34f).coerceIn(0f, 1f)
        }
        return true
    }

    private val m = Visualizer.MeasurementPeakRms()
    private val loop = object : Runnable {
        override fun run() {
            when (mode) {
                "off" -> return
                "bars", "barsIdle" -> {
                    if (mode == "bars") {
                        val v = vis
                        val ok = try { v != null && bands(v) } catch (e: Throwable) { false }
                        if (!ok) { fails++; release(); mode = "barsIdle" }
                    }
                    if (mode == "barsIdle") {
                        val t = SystemClock.uptimeMillis() / 1000.0
                        for (b in 0 until MusicFx.BARS) target[b] = (0.22 + 0.16 * sin(t * 1.1 + b * 0.42) * sin(t * 0.37 + b * 0.17) + 0.06 * sin(t * 2.3 + b)).toFloat().coerceIn(0.05f, 0.5f)
                    }
                    ease()
                    h.postDelayed(this, if (mode == "bars") 33L else 50L)
                }
                else -> {
                    val tgt = if (mode == "music") {
                        val v = vis
                        val ok = try { v != null && v.getMeasurementPeakRms(m) == Visualizer.SUCCESS } catch (e: Throwable) { false }
                        if (!ok) { fails++; release(); mode = "breath"; 0.3f }
                        else ((m.mRms + 5400) / 4200f).coerceIn(0f, 1f)          // mB: ~-54 dB → 0, ~-12 dB → 1
                    } else 0.32f + 0.18f * sin(SystemClock.uptimeMillis() * 2 * PI / 7000).toFloat()
                    level += (tgt - level) * (if (tgt > level) 0.16f else 0.07f)
                    val shown = 0.18f + level * 0.82f
                    edges.forEach { it.level = shown }
                    h.postDelayed(this, if (mode == "music") 100L else 200L)
                }
            }
        }
    }

    /** Smooth attack, slow decay, light neighbour smoothing → no jitter. */
    private fun ease() {
        val s = MusicFx.shown
        for (b in 0 until MusicFx.BARS) {
            val l = target[maxOf(0, b - 1)]; val r = target[minOf(MusicFx.BARS - 1, b + 1)]
            val t = target[b] * 0.6f + (l + r) * 0.2f
            s[b] += (t - s[b]) * (if (t > s[b]) 0.45f else 0.12f)
        }
        MusicFx.push()
    }

    /** After stop: let the bars fall to zero (≈1 s at 30 fps), then stop drawing them. */
    private val decay = object : Runnable {
        override fun run() {
            var any = false
            for (b in 0 until MusicFx.BARS) { MusicFx.shown[b] *= 0.82f; if (MusicFx.shown[b] > 0.01f) any = true else MusicFx.shown[b] = 0f }
            if (!any) MusicFx.on = false
            MusicFx.push()
            if (any) h.postDelayed(this, 33L)
        }
    }

    private fun stop() {
        if (mode == "off") return
        val wasBars = mode.startsWith("bars")
        mode = "off"; h.removeCallbacks(loop)
        try { vis?.enabled = false } catch (_: Throwable) {}
        edges.forEach { it.level = 0f }
        release()
        if (wasBars) { h.removeCallbacks(decay); h.post(decay) }
    }
    private fun release() { try { vis?.release() } catch (_: Throwable) {}; vis = null; if (mode == "music" || mode == "bars") mode = "off" }
}
