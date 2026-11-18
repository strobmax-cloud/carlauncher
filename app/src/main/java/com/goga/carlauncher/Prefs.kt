package com.goga.carlauncher

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("cl", Context.MODE_PRIVATE) }

    var showSpeed: Boolean
        get() = sp.getBoolean("speed", true)
        set(v) = sp.edit().putBoolean("speed", v).apply()
    var use24: Boolean
        get() = sp.getBoolean("h24", true)
        set(v) = sp.edit().putBoolean("h24", v).apply()
    var askedPerms: Boolean
        get() = sp.getBoolean("asked", false)
        set(v) = sp.edit().putBoolean("asked", v).apply()

    var theme: String
        get() = sp.getString("theme", "glass") ?: "glass"
        set(v) = sp.edit().putString("theme", v).apply()
    /** "theme" = wallpaper of the current theme; or a built-in id; or "custom". */
    var wallpaper: String
        get() = sp.getString("wp", "theme") ?: "theme"
        set(v) = sp.edit().putString("wp", v).apply()
    /** Home arrangement id, see Layouts. */
    var layout: String
        get() {
            val v = sp.getString("layout", Layouts.SCREENS.id) ?: Layouts.SCREENS.id
            Layouts.RETIRED[v]?.let { sp.edit().putString("layout", it).remove("nordicLight").apply(); return if (classic) it else Layouts.SCREENS.id }   // v1.6.1: «Скандинавский» removed
            // v1.7.1: the new swipeable screens are the default; the 14 old layouts only with «Классические раскладки»
            return if (v == Layouts.SCREENS.id || !classic) Layouts.SCREENS.id else v
        }
        set(v) = sp.edit().putString("layout", v).apply { if (v != Layouts.SCREENS.id) putBoolean("classic", true) }.apply()
    /** v1.7.1 «Классические раскладки» (off → the new home screens; 1.7.0 users land on the screens too). */
    var classic: Boolean
        get() = sp.getBoolean("classic", false)
        set(v) = sp.edit().putBoolean("classic", v).apply()
    /** v1.7.1 home screens: last page shown (0 Главная, 1 Музыка, 2 Камера) and whether «Камера» is there. */
    var homePage: Int
        get() = sp.getInt("homepage", 0)
        set(v) = sp.edit().putInt("homepage", v).apply()
    var camPage: Boolean
        get() = sp.getBoolean("campage", true)
        set(v) = sp.edit().putBoolean("campage", v).apply()
    var lastLat: Float
        get() = sp.getFloat("lat", Float.NaN)
        set(v) = sp.edit().putFloat("lat", v).apply()
    var lastLon: Float
        get() = sp.getFloat("lon", Float.NaN)
        set(v) = sp.edit().putFloat("lon", v).apply()
    /** Update manifest URL ("" = Updater.UPDATE_URL). */
    var updateUrl: String
        get() = sp.getString("updurl", "") ?: ""
        set(v) = sp.edit().putString("updurl", v.trim()).apply()
    /** «Сменить телефон»: first ask Android to close ZLink / Autokit (best effort, no root). Off by default. */
    var closeWireless: Boolean
        get() = sp.getBoolean("closewl", false)
        set(v) = sp.edit().putBoolean("closewl", v).apply()
    /** v1.6: soft edge glow following the music loudness (Visualizer, needs RECORD_AUDIO). Off by default. */
    var musicGlow: Boolean
        get() = musicFx == "glow"
        set(v) { musicFx = if (v) "glow" else "off" }
    /** v1.6.3: music-reactive effect: "off" / "glow" (soft edge glow) / "bars" (spectrum bars in the cover). Off by default. */
    var musicFx: String
        get() = sp.getString("musicfx", null)?.takeIf { it in setOf("off", "glow", "bars") } ?: if (sp.getBoolean("musicglow", false)) "glow" else "off"
        set(v) = sp.edit().putString("musicfx", v).apply()
    /** v1.6 dashboards: "auto" (system night mode / 07–19 h), "day", "night". */
    var dayMode: String
        get() = (sp.getString("daymode", "night") ?: "night").let { if (it == "day") "day" else "night" }
        set(v) = sp.edit().putString("daymode", v).apply()
    /** v1.6 dashboards accent: "" theme / blue / gold / lime / red. */
    var dashAccent: String
        get() = sp.getString("dashaccent", "") ?: ""
        set(v) = sp.edit().putString("dashaccent", v).apply()
    /** v1.6.3: road scene time of day, independent from the UI: "auto" (sunrise–sunset, Astana) / "day" / "night". */
    var roadMode: String
        get() = (sp.getString("roadmode", "auto") ?: "auto").let { if (it in setOf("auto", "day", "night")) it else "auto" }
        set(v) = sp.edit().putString("roadmode", v).apply()
    /** v1.6.3: Kazakhstan plate shown on the car, normalized "123 ABC 01"; "" = no plate. */
    var plate: String
        get() = sp.getString("plate", "") ?: ""
        set(v) = sp.edit().putString("plate", Plate.normalize(v)).apply()
    /** v1.6.3: right / main media card: "music" (what plays) or "youtube" (YouTube card with «Рядом» / «На весь экран»). */
    /** v1.7.0: YouTube can't play inside a home card → the option is gone, the card always shows the music. */
    var rightCard: String
        get() = "music"
        set(@Suppress("UNUSED_PARAMETER") v) { sp.edit().remove("rightcard").apply() }
    /** Background while music plays — "cover" (blurred album art), "sharp" (v1.7.1: the cover itself, not blurred) or "wall" (my wallpaper). */
    var bgMode: String
        get() = (sp.getString("bgmode", null) ?: if (sp.getBoolean("coverwp", true)) "cover" else "wall").let { if (it in setOf("cover", "sharp", "wall")) it else "cover" }
        set(v) = sp.edit().putString("bgmode", v).apply()
    val coverWallpaper: Boolean get() = bgMode != "wall"

    /** v1.5: built-in OpenStreetMap preview; off by default (2GIS / Yandex do the real navigation). */
    var showOsmMap: Boolean
        get() = sp.getBoolean("osm", false)
        set(v) = sp.edit().putBoolean("osm", v).apply()
    /** Default navigator package, "" = automatic (tile 1 / first installed). */
    var navDefault: String
        get() = sp.getString("navdef", "") ?: ""
        set(v) = sp.edit().putString("navdef", v).apply()
    /** Music app for «Навигатор + музыка», "" = automatic (what is playing / tile «Я.Музыка»). */
    var splitMusic: String
        get() = sp.getString("splitmusic", "") ?: ""
        set(v) = sp.edit().putString("splitmusic", v).apply()
    /** Mini player over the navigator: auto-show when a navigator is opened from the launcher. */
    var miniAuto: Boolean
        get() = sp.getBoolean("miniauto", true)
        set(v) = sp.edit().putBoolean("miniauto", v).apply()
    var miniX: Int
        get() = sp.getInt("minix", -1)
        set(v) = sp.edit().putInt("minix", v).apply()
    var miniY: Int
        get() = sp.getInt("miniy", -1)
        set(v) = sp.edit().putInt("miniy", v).apply()
    var miniCollapsed: Boolean
        get() = sp.getBoolean("minicol", false)
        set(v) = sp.edit().putBoolean("minicol", v).apply()
    /** Wallpaper rotation: off / launch / hour / day. */
    var rotate: String
        get() = sp.getString("rotate", "off") ?: "off"
        set(v) = sp.edit().putString("rotate", v).apply()
    var rotatedAt: Long
        get() = sp.getLong("rotatedAt", 0L)
        set(v) = sp.edit().putLong("rotatedAt", v).apply()
    var stoppedAt: Long
        get() = sp.getLong("stoppedAt", 0L)
        set(v) = sp.edit().putLong("stoppedAt", v).apply()

    /** Media screen: "" auto (VLC if installed), "ask", or a package. */
    var videoPlayer: String
        get() = sp.getString("vplayer", "") ?: ""
        set(v) = sp.edit().putString("vplayer", v).apply()
    var musicPlayer: String
        get() = sp.getString("mplayer", "") ?: ""
        set(v) = sp.edit().putString("mplayer", v).apply()
    var lastVideo: String
        get() = sp.getString("lastvideo", "") ?: ""
        set(v) = sp.edit().putString("lastvideo", v).apply()
    var lastMusic: String
        get() = sp.getString("lastmusic", "") ?: ""
        set(v) = sp.edit().putString("lastmusic", v).apply()
    var mediaKind: String
        get() = sp.getString("mediakind", "video") ?: "video"
        set(v) = sp.edit().putString("mediakind", v).apply()

    /** Tile 9 («Флешка») is new in 1.5 — hidden by default so the existing layouts keep their 8 tiles. */
    fun tileVisible(i: Int) = sp.getBoolean("tv$i", i < 8)
    fun setTileVisible(i: Int, v: Boolean) = sp.edit().putBoolean("tv$i", v).apply()
    fun tileCustom(i: Int): String? = sp.getString("tc$i", null)
    fun setTileCustom(i: Int, pkg: String?) = sp.edit().apply { if (pkg == null) remove("tc$i") else putString("tc$i", pkg) }.apply()

    // ---------------- v1.7.0 ----------------
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    /** Side menu items (ids, see Rail), comma separated. */
    var railItems: String
        get() = str("railitems", "home,back,recents,apps,media,dvr,settings,layout,phone,wifi")
        set(v) = put("railitems", v)
    /** Side menu: "left" (default), "right" or "bottom" (dock). */
    var railSide: String
        get() = str("railside", "left").let { if (it in setOf("left", "right", "bottom")) it else "left" }
        set(v) = put("railside", v)
    /** Volume buttons: "both" (default: driver + passenger), "left", "right". */
    var volSide: String
        get() = str("volside", "both").let { if (it in setOf("both", "left", "right")) it else "both" }
        set(v) = put("volside", v)
    /** Volume method: "auto" or one of [Volume.METHODS] (v1.7.1: TEYES/FYT vendor service first). */
    var volMethod: String
        get() = str("volmethod", "auto").let { if (it in Volume.IDS) it else "auto" }
        set(v) = put("volmethod", v)
    /** Global accent colour id ("" = as in the theme), see Accents. */
    var accent: String
        get() = str("accent", "")
        set(v) = put("accent", v)
    /** Navigator: "mini" (navigator + floating mini player), "split" (next to the music, if the unit can), "only". */
    var navMode: String
        get() = str("navmode", "mini").let { if (it in setOf("mini", "split", "only")) it else "mini" }
        set(v) = put("navmode", v)
    /** Opening the navigator also resumes the music if nothing plays. */
    var navMusic: Boolean
        get() = sp.getBoolean("navmusic", true)
        set(v) = sp.edit().putBoolean("navmusic", v).apply()
    /** Tapping the music card when nothing plays opens the last player and presses Play. */
    var autoPlay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) = sp.edit().putBoolean("autoplay", v).apply()
    /** Package that played last (survives restarts). */
    var lastPlayer: String
        get() = str("lastplayer", "")
        set(v) = put("lastplayer", v)
    /** YouTube app: "" automatic (SmartTube if installed, else YouTube) or a package. */
    var ytApp: String
        get() = str("ytapp", "")
        set(v) = put("ytapp", v)
    /** Rear camera / DVR apps ("" = automatic by name: Cam REC / DVR / HD видеорегистратор). */
    var camApp: String
        get() = str("camapp", "")
        set(v) = put("camapp", v)
    var dvrApp: String
        get() = str("dvrapp", "")
        set(v) = put("dvrapp", v)
    /** «Камера» tile in the car layouts (opens the camera app). */
    var camTile: Boolean
        get() = sp.getBoolean("camtile", true)
        set(v) = sp.edit().putBoolean("camtile", v).apply()
    /** Experimental live camera picture in the tile (Camera2; off by default). */
    var camLive: Boolean
        get() = sp.getBoolean("camlive", false)
        set(v) = sp.edit().putBoolean("camlive", v).apply()
    var camId: String
        get() = str("camid", "")
        set(v) = put("camid", v)
    /** Extra folder for «Записи» (chosen by hand), "" = none. */
    var dvrFolder: String
        get() = str("dvrfolder", "")
        set(v) = put("dvrfolder", v)
    /** Apps hidden from «Приложения». */
    var hiddenApps: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v.toSet()).apply()
    /** How many times the «Доступ к музыке» hint was shown. */
    var notifHints: Int
        get() = sp.getInt("notifhints", 0)
        set(v) = sp.edit().putInt("notifhints", v).apply()

    // ---------------- v1.7.1 ----------------
    /** Accent colour taken from the album cover while music plays (off by default). */
    var coverAccent: Boolean
        get() = sp.getBoolean("coveraccent", false)
        set(v) = sp.edit().putBoolean("coveraccent", v).apply()
    /** Floating volume buttons: vertical position "center" (default), "bottom", "top". */
    var volPos: String
        get() = str("volpos", "center").let { if (it in setOf("bottom", "center", "top")) it else "center" }
        set(v) = put("volpos", v)
    /** Floating volume buttons size (design px), 64…96. */
    var volSize: Int
        get() = sp.getInt("volsize", 76).coerceIn(60, 100)
        set(v) = sp.edit().putInt("volsize", v).apply()
    /** Floating «Назад / Домой» bubble over other apps (overlay permission). Off by default. */
    var floatNav: Boolean
        get() = sp.getBoolean("floatnav", false)
        set(v) = sp.edit().putBoolean("floatnav", v).apply()
    var floatX: Int
        get() = sp.getInt("floatx", -1)
        set(v) = sp.edit().putInt("floatx", v).apply()
    var floatY: Int
        get() = sp.getInt("floaty", -1)
        set(v) = sp.edit().putInt("floaty", v).apply()
    /** One-time migrations done (bit set). */
    var migrated: Int
        get() = sp.getInt("migrated", 0)
        set(v) = sp.edit().putInt("migrated", v).apply()
    /** v1.7.1 size of the side menu / dock and its buttons: "normal" (1.7.0), "large" (+25 %, default), "xlarge" (+45 %). */
    var railSize: String
        get() = str("railsize", "large").let { if (it in setOf("normal", "large", "xlarge")) it else "large" }
        set(v) = put("railsize", v)
    val railScale: Float get() = when (railSize) { "normal" -> 1f; "xlarge" -> 1.45f; else -> 1.25f }
}
