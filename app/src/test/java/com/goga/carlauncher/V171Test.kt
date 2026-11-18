package com.goga.carlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** v1.7.1: TEYES volume (com.syu.ms), volume test screen, floating volume, big buttons / menu size, cover colour + sharp cover, cleanup, nav buttons, big crisp SX4. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w1280dp-h720dp-land-mdpi")
class V171Test {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun fresh() {
        val sp = ctx.getSharedPreferences("cl", android.content.Context.MODE_PRIVATE); sp.edit().clear().commit()
        Prefs::class.java.getDeclaredField("sp").apply { isAccessible = true }.set(Prefs, sp)
        Prefs.dayMode = "night"; Prefs.roadMode = "night"; Prefs.plate = "777 ABC 01"; Prefs.notifHints = 99
        BtMeta.at = 0; BtMeta.playing = false; BtMeta.playAt = 0
        Radio.on = false; Radio.at = 0; Radio.freq = null; Radio.pkg = null
    }
    private fun settle(n: Int = 4) { repeat(n) { Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() } }
    private fun relayout(root: View, w: Int = root.width, h: Int = root.height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun shot(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        val out = File(System.getProperty("user.dir"), "../preview/robo-v171-$name.png"); out.parentFile!!.mkdirs()
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    private fun save(b: Bitmap, name: String) = FileOutputStream(File(System.getProperty("user.dir"), "../preview/robo-v171-$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun fakeApp(pkg: String, label: String) {
        val spm = shadowOf(ctx.packageManager)
        val ai = android.content.pm.ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label; sourceDir = "/nonexistent/$pkg.apk" }
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val cn = android.content.ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }
    private fun loc(v: View) = IntArray(2).also { v.getLocationInWindow(it) }

    // ---------------- volume ----------------
    /** Fake com.syu.ms SOUND module: records what we send (wire format of IRemoteModule.cmd). */
    class FakeModule : Binder() {
        val sent = ArrayList<Pair<Int, IntArray?>>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) { data.enforceInterface("com.syu.ipc.IRemoteModule"); val c = data.readInt(); sent.add(c to data.createIntArray()); reply?.writeNoException(); return true }
            return super.onTransact(code, data, reply, flags)
        }
    }

    @Test fun teyesVolumeWireFormat() {
        assertEquals((1..8).toList(), Volume.METHODS.map { it.n })
        assertEquals(9, Volume.IDS.size)
        Prefs.volMethod = "syu"; assertEquals("syu", Prefs.volMethod)
        Prefs.volMethod = "nonsense"; assertEquals("auto", Prefs.volMethod)
        // not connected → auto falls back to Android without crashing
        assertTrue(Volume.step(ctx, true, "auto") in setOf("stream", "suggested", "key"))
        val m = FakeModule()
        Syu::class.java.getDeclaredField("sound").apply { isAccessible = true }.set(Syu, m)
        Syu::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(Syu, true)
        assertEquals("syu", Volume.step(ctx, true, "auto"))
        assertEquals("syu", Volume.step(ctx, false, "syu"))
        Syu.level = 17
        Volume.step(ctx, true, "syuset")
        assertEquals(listOf(0, 0, 0), m.sent.map { it.first })
        assertEquals(listOf(-1, -2, 18), m.sent.map { it.second!!.single() })
        assertTrue(Volume.readout(ctx).contains("Магнитола (com.syu.ms): 17"))
        // every Android method runs without throwing
        for (id in listOf("stream", "suggested", "adjust", "set", "key", "bcast")) Volume.run(ctx, id, true)
        Syu::class.java.getDeclaredField("sound").apply { isAccessible = true }.set(Syu, null)
        Syu::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(Syu, false)
        Syu.level = -1
    }

}
