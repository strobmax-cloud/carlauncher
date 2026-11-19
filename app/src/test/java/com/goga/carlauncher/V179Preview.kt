package com.goga.carlauncher

import android.graphics.Bitmap
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** v1.7.9 previews of the real festive mode (Minimal v4b + snowdrift) → /workspace/car-design/png-v179 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V179Preview {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() { NY4Env.fresh(ctx); K.nyMode = "on" }
    @Test fun previews() {
        val out = File("/workspace/car-design/png-v179").apply { mkdirs() }
        if (!out.canWrite()) return
        for ((st, sc) in NY4Env.SCREENS) for (big in listOf(false, true)) {
            if (big && sc != "home") continue
            val (a, root) = NY4Env.open(st, sc, big)
            NY4Env.settle(); NY4Env.relayout(root); NY4Env.settle()
            val f = File(out, "v179-$st-$sc${if (big) "-big" else ""}.png"); NY4Env.shot(root, f)
            if (sc == "home" && !big) {
                val b = android.graphics.BitmapFactory.decodeFile(f.path)
                val crop = if (st == "C") Bitmap.createBitmap(b, 100, 150, 560, 320) else Bitmap.createBitmap(b, 60, 100, 420, 380)
                val z = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                FileOutputStream(File(out, "v179-$st-home-closeup.png")).use { z.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            a.finish()
        }
    }
}
