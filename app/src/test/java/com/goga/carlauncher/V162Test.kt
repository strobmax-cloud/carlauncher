package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** v1.6.2: SX4 from behind on the road in every car layout, covers drawn rounded / circular by CoverView. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V162Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp); Prefs.dayMode = "night"
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.width, root.height)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v162-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun limePixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
            val c = b.getPixel(x, y); val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            if (r > 120 && g > 120 && bl < 90 && kotlin.math.abs(r - g) < 50) n++
        }
        return n
    }

    @Test fun coverCornersAreSoftNotSquare() {
        // the corner pixel of a rounded cover must stay transparent (no hard square cut-out)
        val v = CoverView(ctx, false).apply { radiusPx = 24f; shadow = false }
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF8800.toInt()) }
        v.setCover(art)
        v.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 200, 200)
        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888); v.draw(Canvas(out))
        assertTrue("corner transparent", (out.getPixel(1, 1) ushr 24) < 40)
        assertTrue("centre opaque", (out.getPixel(100, 100) ushr 24) == 255)
        v.circle = true; out.eraseColor(0); v.draw(Canvas(out))
        assertTrue("circle corner transparent", (out.getPixel(20, 20) ushr 24) < 40)
    }
}
