package com.goga.carlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdaterTest {
    private fun res(n: String): File {
        val f = File.createTempFile("upd", ".apk"); javaClass.classLoader!!.getResourceAsStream(n).use { i -> f.outputStream().use { i.copyTo(it) } }; return f
    }

    @Test fun signingBlockCertMatchesOurKeystore() {
        assertTrue(Updater.CERT_SHA256 in Updater.v2Certs(res("signed.apk")))
        val foreign = Updater.v2Certs(res("foreign.apk"))
        assertTrue("foreign apk has a cert", foreign.isNotEmpty())
        assertFalse(Updater.CERT_SHA256 in foreign)
        assertTrue(Updater.v2Certs(File.createTempFile("bad", ".apk").apply { writeBytes(ByteArray(5000) { it.toByte() }) }).isEmpty())
    }

    @Test fun verifyRejectsBadFiles() {
        val c = RuntimeEnvironment.getApplication()
        assertFalse(Updater.verify(c, File.createTempFile("bad", ".apk").apply { writeBytes(ByteArray(10)) }).ok)
        assertFalse(Updater.verify(c, File.createTempFile("bad", ".apk").apply { writeBytes(ByteArray(5000)) }).ok)
        val signed = res("signed.apk")
        val bad = Updater.verify(c, signed, "00".repeat(32))
        assertFalse(bad.ok); assertTrue(bad.message.contains("Контрольная"))
        val fr = Updater.verify(c, res("foreign.apk"))
        assertFalse(fr.ok)
        println("verify(signed) on Robolectric: " + Updater.verify(c, signed, Updater.sha256(signed)).message)
    }

    @Test fun manifestParses() {
        val m = Updater.parse("""{"versionCode": 8, "versionName": "1.7", "apk": "https://github.com/u/r/releases/download/v1.7/CarLauncher.apk", "sha256": "ab", "notes": "Новое"}""")
        assertEquals(8L, m.versionCode); assertEquals("1.7", m.versionName); assertTrue(m.apk.endsWith(".apk")); assertEquals("Новое", m.notes)
        assertEquals("", Updater.parse("""{"versionCode":1,"apk":"x","sha256":"y"}""").notes)
    }
}
