package com.goga.carlauncher

import android.app.Activity
import android.view.KeyEvent

/**
 * v1.7.3: «Телефон» = let the phone's Bluetooth audio play through the unit WITHOUT opening the Bluetooth app.
 * 1) the unit's audio source → Bluetooth music: MAIN module C_APP_ID = APP_ID_BTAV (3) — the command the stock BT-music
 *    app sends when it comes to the front (same vendor service as the volume / radio; no settings, no MCU writes);
 * 2) Play to the phone: the Bluetooth media session (AVRCP controller) if there is one, else a media Play key;
 * 3) only if the unit's service is missing AND there is no Bluetooth session → open the BT-music app (last resort).
 */
object BtAudio {
    enum class Result { OK, OPENED, NONE }
    fun select(a: Activity): Result {
        val switched = try { FmCtl.sourceBt() } catch (_: Throwable) { false }
        val s = MediaHub.all.firstOrNull { MediaHub.isPhone(a, it.packageName) }
        if (s != null) {
            try { s.transportControls.play() } catch (_: Throwable) {}
            MediaHub.pinPhone(s.packageName); return Result.OK
        }
        if (switched) {
            MediaHub.key(a, KeyEvent.KEYCODE_MEDIA_PLAY)
            MediaHub.pinPhone(null); return Result.OK
        }
        val p = CamApps.btMusic(a) ?: return Result.NONE
        AppRepo.launch(a, p); MediaHub.pinPhone(null)
        return Result.OPENED
    }
}
