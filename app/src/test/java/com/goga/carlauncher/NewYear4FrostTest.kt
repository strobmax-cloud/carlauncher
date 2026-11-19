package com.goga.carlauncher

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** design v4b: «Иней по краям» — a festive setting, on by default, stored in prefs */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NewYear4FrostTest {
    @Test fun frostToggle() {
        val k = RuntimeEnvironment.getApplication().getSharedPreferences("ck", android.content.Context.MODE_PRIVATE); k.edit().clear().commit(); K.use(k)
        assertTrue("on by default", K.nyFrost)
        K.nyFrost = false; assertTrue(!K.nyFrost); K.nyFrost = true
        val src = File("src/main/java/com/goga/carlauncher/CkOverlays.kt").readText()
        assertTrue("row in festive settings", src.contains("\"Иней по краям\"") && src.contains("K.nyFrost = it"))
    }
}
