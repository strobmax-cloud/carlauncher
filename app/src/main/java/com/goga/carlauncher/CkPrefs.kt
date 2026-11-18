package com.goga.carlauncher

import android.content.Context
import android.content.SharedPreferences

/**
 * v1.7.1 settings of the new C «Линза» / D «Стекло» screens (own file "ck", the 1.7.0 prefs "cl" stay untouched so the
 * classic screens keep working).
 */
object K {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("ck", Context.MODE_PRIVATE) }
    /** tests */
    fun use(p: SharedPreferences) { sp = p }
    val ready get() = ::sp.isInitialized

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun ps(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun pi(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun pf(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    private fun pl(k: String, v: Long) = sp.edit().putLong(k, v).apply()

    /** "C" Линза | "D" Стекло */
    var style: String get() = s("style", "D"); set(v) = ps("style", v)
    /** D colour theme: violet / green / navy / graphite */
    var theme: String get() = s("theme", "violet"); set(v) = ps("theme", v)
    /** slow procedural waves of D (and the idle shimmer of C) */
    var waves: Boolean get() = b("waves", true); set(v) = pb("waves", v)
    /** background: theme | cover | scene | video */
    var bg: String get() = s("bg", "theme"); set(v) = ps("bg", v)
    /** photo scene id or "auto" (by weather + time of day) */
    var scene: String get() = s("scene", "auto"); set(v) = ps("scene", v)
    /** weather for the background: auto (forecast) or manual */
    var wxAuto: Boolean get() = b("wxAuto", true); set(v) = pb("wxAuto", v)
    var wxTod: String get() = s("wxTod", "auto"); set(v) = ps("wxTod", v)          // auto / day / night
    var wxKind: String get() = s("wxKind", "auto"); set(v) = ps("wxKind", v)       // auto / clear / cloudy / rain / snow
    var particles: Boolean get() = b("particles", true); set(v) = pb("particles", v)
    var blur: Boolean get() = b("blur", false); set(v) = pb("blur", v)
    /** v1.7.3: strength of the glow from the track cover, 0…100 */
    var glow: Int get() = i("glow", 70); set(v) = pi("glow", v.coerceIn(0, 100))
    var bright: Int get() = i("bright", 80); set(v) = pi("bright", v.coerceIn(30, 100))
    var big: Boolean get() = b("big", false); set(v) = pb("big", v)
    /** C equalizer colours: ref (rainbow like the reference) / vu (green → red by level) */
    var eqPal: String get() = s("eqPal", "ref"); set(v) = ps("eqPal", v)
    /** absolute path of the chosen video background */
    /** v1.7.3: a video folder chosen by hand (built-in folder browser or Android's folder picker) */
    var videoDir: String get() = s("videoDir", ""); set(v) = ps("videoDir", v)
    var video: String get() = s("video", ""); set(v) = ps("video", v)
    var tiles: String get() = s("tiles", ""); set(v) = ps("tiles", v)
    /** v1.7.2: «Все приложения» was added once to an existing custom tile list (removing it later is respected) */
    var appsTileAdded: Boolean get() = b("appsTileAdded", false); set(v) = pb("appsTileAdded", v)
    /** v1.7.2: packages hidden in «Все приложения» (comma separated) */
    var hiddenApps: String get() = s("hiddenApps", ""); set(v) = ps("hiddenApps", v)
    var startScreen: String get() = s("startScreen", "home"); set(v) = ps("startScreen", v)
    var lastScreen: Int get() = i("lastScreen", 0); set(v) = pi("lastScreen", v)
    var tilePage: Int get() = i("tilePage", 0); set(v) = pi("tilePage", v)

    // ---- mileage (km, GPS) ----
    var day: Float get() = f("day", 0f); set(v) = pf("day", v)
    /** "yyyy-MM-dd" of the current 4:00…4:00 day */
    var dayKey: String get() = s("dayKey", ""); set(v) = ps("dayKey", v)
    var trip: Float get() = f("trip", 0f); set(v) = pf("trip", v)
    /** v1.7.3: «Поездка» starts again by itself: after the ignition was off (unit asleep / rebooted) or a stop > [tripGapMin] */
    var tripAuto: Boolean get() = b("tripAuto", true); set(v) = pb("tripAuto", v)
    var tripGapMin: Int get() = i("tripGapMin", 30); set(v) = pi("tripGapMin", v.coerceIn(5, 720))
    /** the ignition was off since the last movement → the next metre starts a new trip */
    var tripNewPending: Boolean get() = b("tripNewPending", false); set(v) = pb("tripNewPending", v)
    /** wall-clock time of the unit's boot that was last seen (a new boot = the ignition was off) */
    var tripBootAt: Long get() = l("tripBootAt", 0L); set(v) = pl("tripBootAt", v)
    /** wall-clock ms: last time distance was added / when the current trip started */
    var tripMovedAt: Long get() = l("tripMovedAt", 0L); set(v) = pl("tripMovedAt", v)
    var tripStartAt: Long get() = l("tripStartAt", 0L); set(v) = pl("tripStartAt", v)
    var total: Float get() = f("total", 0f); set(v) = pf("total", v)

    // ---- volume ----
    var volStep: Int get() = i("volStep", 1); set(v) = pi("volStep", v)
    var volOverlay: Boolean get() = b("volOverlay", true); set(v) = pb("volOverlay", v)
    /** volume level we show when the unit does not report one (0..40) */
    var volShown: Int get() = i("volShown", 14); set(v) = pi("volShown", v.coerceIn(0, 40))

    // ---- misc ----
    var lowHideUntil: Long get() = l("lowHideUntil", 0L); set(v) = pl("lowHideUntil", v)
    var wxJson: String get() = s("wxJson", ""); set(v) = ps("wxJson", v)
    var wxAt: Long get() = l("wxAt", 0L); set(v) = pl("wxAt", v)
    var wxCity: String get() = s("wxCity", "Астана"); set(v) = ps("wxCity", v)
    var cleanedApks: Int get() = i("cleanedApks", 0); set(v) = pi("cleanedApks", v)

    // v1.7.2
    var radioPresets: String get() = s("radioPresets", ""); set(v) = ps("radioPresets", v)
    /** rain / snow: true = by the weather, false = [fxKind] always */
    var fxAuto: Boolean get() = b("fxAuto", true); set(v) = pb("fxAuto", v)
    var fxKind: String get() = s("fxKind", "snow"); set(v) = ps("fxKind", v)     // none | rain | snow
    var safeAt: Long get() = l("safeAt", 0L); set(v) = pl("safeAt", v)
    fun clearAll() = sp.edit().clear().commit()
}
