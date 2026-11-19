package com.goga.carlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/** v1.7.1 bundled photo scenes (Wikimedia Commons, see «О программе» → авторы фото). */
object Scenes {
    class Scene(val id: String, val name: String, val group: String, val tod: String, val wx: Set<String>, val author: String, val licence: String, val page: String, val res: Int)
    val ALL = listOf(
        Scene("forest_day", "Лесная дорога", "road", "day", setOf("clear"), "Lukas Beck", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Brandstra%C3%9Fe_Saxon_Switzerland.jpg", R.drawable.ck_sc_forest_day),
        Scene("pine_road", "Дорога в сосновом лесу", "road", "day", setOf("clear"), "Sridhar Rao", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Road_through_forest.jpg", R.drawable.ck_sc_pine_road),
        Scene("desert_hw", "Шоссе в пустыне", "road", "day", setOf("clear"), "John King johnking", "CC0", "https://commons.wikimedia.org/wiki/File:Desert_Empty_Highway_(Unsplash).jpg", R.drawable.ck_sc_desert_hw),
        Scene("alps_pass", "Альпийский перевал", "road", "day", setOf("clear", "cloudy"), "Dimitry Anikin", "CC0", "https://commons.wikimedia.org/wiki/File:Gro%C3%9Fglockner-Hochalpenstra%C3%9Fe_3.jpg", R.drawable.ck_sc_alps_pass),
        Scene("autumn_valley", "Осенняя долина", "road", "day", setOf("clear", "cloudy"), "Ximonic (Simo Räsänen)", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:National_road_970_and_river_Teno_near_Gistugurra,_Utsjoki,_Lapland,_Finland,_2021_September.jpg", R.drawable.ck_sc_autumn_valley),
        Scene("autumn_forest", "Осенний лес", "road", "day", setOf("clear"), "ForestWander", "CC BY-SA 3.0 us", "https://commons.wikimedia.org/wiki/File:Autumn-forest-foliage-country-road_-_West_Virginia_-_ForestWander.jpg", R.drawable.ck_sc_autumn_forest),
        Scene("overcast_rd", "Пасмурная дорога", "road", "day", setOf("cloudy"), "B137", "CC0", "https://commons.wikimedia.org/wiki/File:View_down_country_road_in_May.jpg", R.drawable.ck_sc_overcast_rd),
        Scene("fog_bridge", "Туман на трассе", "road", "day", setOf("cloudy"), "DanTD", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:NB_FL_SR_19;_Fog_over_Juniper_Creek_Bridge.jpg", R.drawable.ck_sc_fog_bridge),
        Scene("rain_forest", "Дождь в лесу", "road", "day", setOf("rain"), "Sam Beebe", "CC BY 2.0", "https://commons.wikimedia.org/wiki/File:Old_Growth_Forest_along_US_101_in_Washington.jpg", R.drawable.ck_sc_rain_forest),
        Scene("rain_teal", "Мокрая дорога", "road", "day", setOf("rain", "cloudy"), "Noah Silliman noahsilliman", "CC0", "https://commons.wikimedia.org/wiki/File:Rainy_road_(Unsplash).jpg", R.drawable.ck_sc_rain_teal),
        Scene("snow_forest", "Заснеженная лесная дорога", "road", "day", setOf("snow"), "George Chernilevsky", "Public domain", "https://commons.wikimedia.org/wiki/File:Snowy_road_Sosonka_2013_G1.jpg", R.drawable.ck_sc_snow_forest),
        Scene("snow_road2", "Зимняя просека", "road", "day", setOf("snow"), "George Chernilevsky", "Public domain", "https://commons.wikimedia.org/wiki/File:Forest_road_Slavne_2017_G8.jpg", R.drawable.ck_sc_snow_road2),
        Scene("night_lamps", "Ночная улица с фонарями", "road", "night", setOf("clear"), "PattayaPatrol", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:DZ6_1685_Quiet_city_street_at_night_lined_with_glowing_streetlights_and_empty_lanes_stretching_toward_distant_buildings.jpg", R.drawable.ck_sc_night_lamps),
        Scene("night_rain", "Ночная трасса · мокро", "road", "night", setOf("rain"), "PattayaPatrol", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:DSCF1047_Empty_highway_at_night_with_streaking_taillights_and_illuminated_roadside_signs_stretching_into_the_distance.jpg", R.drawable.ck_sc_night_rain),
        Scene("night_fog", "Туманная ночь", "road", "night", setOf("cloudy", "rain"), "Famartin", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2026-01-07_03_49_12_A_foggy_night_view_westward_along_Burlington_County_Route_630_(Woodlane_Road)_in_Westampton_Township,_Burlington_County,_New_Jersey.jpg", R.drawable.ck_sc_night_fog),
        Scene("night_snow_forest", "Ночная зимняя дорога в лесу", "road", "night", setOf("snow"), "Tony Webster from Minneapolis, Minnesota", "CC BY-SA 2.0", "https://commons.wikimedia.org/wiki/File:High_Trestle_Trail_in_Winter_-_Night_Headlamp_-_Madrid,_Iowa_(24196836569).jpg", R.drawable.ck_sc_night_snow_forest),
        Scene("night_snow_lamps", "Снег под фонарями", "road", "night", setOf("snow"), "Famartin", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2026-01-18_18_23_56_View_east_along_Forest_Lane_at_its_western_intersection_with_Lochatong_Road_during_a_moderate_snowfall_at_night_in_the_Mountainview_section_of_Ewing_Township,_Mercer_County,_New_Jersey.jpg", R.drawable.ck_sc_night_snow_lamps),
        Scene("lake", "Бирюзовое озеро", "nature", "day", setOf("clear"), "Tobias Alt, Tobi 87", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Peyto_Lake-Banff_NP-Canada.jpg", R.drawable.ck_sc_lake),
        Scene("fog_forest", "Туманный лес", "nature", "day", setOf("cloudy", "rain"), "Mar Mkrtchyan", "CC0", "https://commons.wikimedia.org/wiki/File:Foggy_forest_hillside.jpg", R.drawable.ck_sc_fog_forest),
        Scene("snow_pines", "Зимний лес", "nature", "day", setOf("snow"), "Aleksandr Gorlov", "Public domain", "https://commons.wikimedia.org/wiki/File:Snow-covered_pine_forest_(52600179620).jpg", R.drawable.ck_sc_snow_pines),
        Scene("sunset_mtn", "Закат в горах", "nature", "day", setOf("clear", "cloudy"), "Michal Klajban", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Sunset_in_The_Trossachs,_Scotland.jpg", R.drawable.ck_sc_sunset_mtn),
        Scene("aurora", "Северное сияние", "nature", "night", setOf("clear", "snow"), "United States Air Force photo by Senior Airman Joshua Strang", "Public domain", "https://commons.wikimedia.org/wiki/File:Aurora_borealis_over_Eielson_Air_Force_Base,_Alaska.jpg", R.drawable.ck_sc_aurora),
        Scene("fjord_night", "Фьорд при луне", "nature", "night", setOf("clear", "snow"), "Ximonic (Simo Räsänen)", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:Aurora_borealis_above_Storfjorden_and_the_Lyngen_Alps_in_moonlight,_2012_March.jpg", R.drawable.ck_sc_fjord_night)
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /** «Авто по погоде»: the first road scene for the time of day + weather (then nature, then anything of that time). */
    fun auto(tod: String, kind: String): Scene {
        val k = if (kind == "cloudy" && ALL.none { it.tod == tod && "cloudy" in it.wx }) "clear" else kind
        return ALL.firstOrNull { it.group == "road" && it.tod == tod && k in it.wx }
            ?: ALL.firstOrNull { it.tod == tod && k in it.wx }
            ?: ALL.firstOrNull { it.tod == tod } ?: ALL[0]
    }
    /** scene that is shown now */
    fun current(): Scene = if (K.scene == "auto") auto(Wx.tod(), Wx.kind()) else byId(K.scene) ?: auto(Wx.tod(), Wx.kind())
}

/**
 * v1.7.1 weather: Open-Meteo (free, no key), position = last GPS / network fix, otherwise Astana. Cached in prefs so the
 * last known weather (and its time) is shown offline. Refresh at most every 30 min.
 */
object Wx {
    const val ASTANA_LAT = 51.17; const val ASTANA_LON = 71.45
    @Volatile var temp: Double? = null; @Volatile var code: Int = -1; @Volatile var isDayApi: Boolean? = null
    @Volatile var at = 0L
    @Volatile private var loading = false
    var lat = ASTANA_LAT; var lon = ASTANA_LON
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init() {
        if (code >= 0) return
        try { parse(K.wxJson); at = K.wxAt } catch (_: Throwable) {}
    }
    fun parse(s: String) {
        if (s.isEmpty()) return
        val o = JSONObject(s).getJSONObject("current")
        temp = o.optDouble("temperature_2m").takeIf { !it.isNaN() }; code = o.optInt("weather_code", -1)
        isDayApi = if (o.has("is_day")) o.optInt("is_day", 1) == 1 else null
    }
    /** WMO code → clear / cloudy / rain / snow */
    fun kindOf(c: Int): String = when (c) {
        0, 1 -> "clear"
        2, 3, 45, 48 -> "cloudy"
        in 51..67, in 80..82, in 95..99 -> "rain"
        in 71..77, 85, 86 -> "snow"
        else -> "clear"
    }
    fun forecastKind() = if (code >= 0) kindOf(code) else "clear"
    fun sunDay(now: Calendar = Calendar.getInstance()): Boolean {
        val t = RoadArt.sunTimes(now, lat, lon) ?: return now.get(Calendar.MONTH) in 3..8
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return m >= t.first && m < t.second
    }
    /** effective time of day / weather for the background */
    fun tod(): String = if (!K.wxAuto && K.wxTod != "auto") K.wxTod else if (sunDay()) "day" else "night"
    fun kind(): String = if (!K.wxAuto && K.wxKind != "auto") K.wxKind else forecastKind()

    fun icon(kind: String = forecastKind(), day: Boolean = sunDay()): String = when (kind) {
        "snow" -> "snow"; "rain" -> "rain"; "cloudy" -> "cloud2"; else -> if (day) "sun" else "moon"
    }
    fun kindName(k: String) = when (k) { "snow" -> "снег"; "rain" -> "дождь"; "cloudy" -> "облачно"; else -> "ясно" }
    fun tempText(): String = temp?.let { val t = Math.round(it).toInt(); (if (t > 0) "+" else if (t < 0) "−" else "") + Math.abs(t) + "°" } ?: "—°"
    /** «Астана · снег» (+ «· 2 ч назад» when old) */
    fun place(): String {
        val base = K.wxCity + " · " + kindName(forecastKind())
        if (at == 0L) return K.wxCity
        val age = (System.currentTimeMillis() - at) / 60000
        return if (age > 120) "$base · ${age / 60} ч назад" else base
    }

    fun refresh(c: Context, force: Boolean = false) {
        init()
        if (loading || (!force && System.currentTimeMillis() - at < 30 * 60_000L && code >= 0)) return
        loading = true
        val app = c.applicationContext
        AppRepo.exec.execute {
            try {
                val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon)
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.connectTimeout = 12_000; cn.readTimeout = 15_000; cn.setRequestProperty("User-Agent", "CarLauncher")
                val txt = cn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                parse(txt); at = System.currentTimeMillis()
                K.wxJson = txt; K.wxAt = at
                city(app)
            } catch (_: Throwable) {
            } finally { loading = false }
            AppRepo.main.post { listeners.forEach { try { it() } catch (_: Throwable) {} } }
        }
    }
    private fun city(c: Context) {
        val d = FloatArray(1); Location.distanceBetween(lat, lon, ASTANA_LAT, ASTANA_LON, d)
        if (d[0] < 40_000) { K.wxCity = "Астана"; return }
        try {
            @Suppress("DEPRECATION")
            val a = android.location.Geocoder(c, Locale("ru")).getFromLocation(lat, lon, 1)?.firstOrNull()
            val n = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            if (!n.isNullOrBlank()) K.wxCity = n
        } catch (_: Throwable) {}
    }
}

/**
 * v1.7.1 speed (GPS, 0–160 on the gauge) and mileage: «За день» (resets itself at 4:00), «Поездка», «Всего»; trip and
 * total are reset only by the user (long press → confirm). Distance from GPS fixes with accuracy ≤ 40 m and a speed
 * above 3 km/h, so standing still does not add metres.
 */
object Trip {
    @Volatile var speed: Int? = null; private set
    @Volatile var fixAt = 0L
    private var last: Location? = null
    private var pending = 0f
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var lm: LocationManager? = null

    /** "yyyy-MM-dd" of the 4:00…4:00 day containing [now] */
    fun dayKey(now: Calendar = Calendar.getInstance()): String {
        val c = now.clone() as Calendar; c.add(Calendar.HOUR_OF_DAY, -4)
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    fun checkDay(now: Calendar = Calendar.getInstance()) {
        val k = dayKey(now)
        if (K.dayKey != k) { K.dayKey = k; K.day = 0f }
    }
    fun add(km: Float, now: Long = System.currentTimeMillis()) {
        if (km <= 0f) return
        checkDay(); checkTrip(now)
        if (K.tripNewPending) { K.tripNewPending = false; K.trip = 0f; K.tripStartAt = 0L }
        if (K.tripStartAt == 0L) K.tripStartAt = now
        K.tripMovedAt = now
        K.day = K.day + km; K.trip = K.trip + km; K.total = K.total + km
    }
    /**
     * v1.7.3 new trip by itself: the car stood (no distance added) longer than K.tripGapH hours — ignition off for the
     * night, a long parking — → «Поездка» = 0 and the next metre starts a new trip. True when it was reset.
     */
    fun checkTrip(now: Long = System.currentTimeMillis()): Boolean {
        val moved = K.tripMovedAt
        if (!K.tripAuto || moved <= 0L || now - moved <= K.tripGapMin * 60_000L) return false
        K.trip = 0f; K.tripMovedAt = 0L; K.tripStartAt = 0L; K.tripNewPending = false
        return true
    }
    /** ignition off → on (the unit woke up / booted): the trip shown stays until the car moves, then starts from 0 */
    fun ignitionCycle() { if (K.tripAuto && K.trip > 0f) K.tripNewPending = true }
    /** a new boot of the unit since we last looked = the ignition was off */
    fun checkBoot(nowWall: Long = System.currentTimeMillis(), sinceBoot: Long = SystemClock.elapsedRealtime()) {
        val boot = nowWall - sinceBoot
        val prev = K.tripBootAt
        if (prev == 0L || Math.abs(boot - prev) > 120_000L) { if (prev != 0L) ignitionCycle(); K.tripBootAt = boot }
    }
    private var screenOffAt = 0L
    /** screen off ≥ 2 min and back on = ACC off → on on the head unit (it sleeps instead of powering down) */
    fun screen(on: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        if (!on) { screenOffAt = now; return }
        if (screenOffAt > 0 && now - screenOffAt >= 120_000L) ignitionCycle()
        screenOffAt = 0L
    }
    fun reset(which: String) { when (which) { "trip" -> { K.trip = 0f; K.tripMovedAt = 0L; K.tripStartAt = 0L; K.tripNewPending = false }; "total" -> K.total = 0f; "day" -> K.day = 0f } }
    fun km(v: Float): String = if (v >= 1000f) String.format(Locale("ru"), "%,d", Math.round(v)).replace('\u00a0', ' ') else String.format(Locale("ru"), "%.1f", v)

    fun onFix(l: Location) {
        fixAt = SystemClock.elapsedRealtime()
        Wx.lat = l.latitude; Wx.lon = l.longitude
        try { Prefs.lastLat = l.latitude.toFloat(); Prefs.lastLon = l.longitude.toFloat() } catch (_: Throwable) {}
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else -1f
        speed = if (kmh >= 0) Math.round(kmh) else speed
        val p = last
        if (l.hasAccuracy() && l.accuracy > 40f) { emit(); return }
        if (p != null) {
            val d = p.distanceTo(l)
            val dt = (l.time - p.time) / 1000f
            // plausible: moving, not a jump (≤ 70 m/s)
            if ((kmh < 0 || kmh > 3f) && d > 2f && (dt <= 0 || d / dt < 70f)) {
                pending += d
                if (pending > 20f) { add(pending / 1000f); pending = 0f }
            }
        }
        last = l
        emit()
    }
    private fun emit() = listeners.forEach { try { it() } catch (_: Throwable) {} }

    private val ll = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { speed = null; emit() }
    }
    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        val pl = Prefs.lastLat; val po = Prefs.lastLon
        if (pl != 0f || po != 0f) { Wx.lat = pl.toDouble(); Wx.lon = po.toDouble() }
        if (lm != null) return
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        lm = c.getSystemService(LocationManager::class.java)
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper()) } catch (_: Throwable) {}
        try { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { Wx.lat = it.latitude; Wx.lon = it.longitude } } catch (_: Throwable) {}
    }
    fun stop() { try { lm?.removeUpdates(ll) } catch (_: Throwable) {}; lm = null }
    /** no fix for 10 s → speed unknown */
    fun stale() = SystemClock.elapsedRealtime() - fixAt > 10_000
}

/**
 * v1.7.1 video backgrounds from a USB stick: folder CarLauncher/Backgrounds (*.mp4) at the root of any mounted
 * storage, fallback /sdcard/CarLauncher/Backgrounds. Nothing is copied to the unit.
 */
object Videos {
    const val DIR = "CarLauncher/Backgrounds"
    class Clip(val file: File, val name: String, val group: String, val volume: String)
    @Volatile var list: List<Clip> = emptyList(); private set
    @Volatile var scannedAt = 0L

    fun roots(c: Context): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        try { Media.volumes(c).forEach { out.add(it.name to it.dir) } } catch (_: Throwable) {}
        listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/udisk", "/udisk", "/mnt/usb", "/mnt/usbhost", "/mnt/usbdisk", "/storage/usb").forEach { p ->
            File(p).listFiles()?.forEach { d -> if (d.isDirectory && d.name != "self" && d.name != "emulated") out.add("Флешка" to d) }
        }
        out.add("Память" to Environment.getExternalStorageDirectory())
        return out.distinctBy { try { it.second.canonicalPath } catch (_: Throwable) { it.second.path } }
    }
    fun nice(fileName: String): Pair<String, String> {
        var n = fileName.substringBeforeLast('.')
        val g = when { n.startsWith("road-", true) || n.startsWith("road_", true) -> "Дороги"; n.startsWith("nature-", true) || n.startsWith("nature_", true) -> "Природа"; else -> "Видео" }
        n = n.replace(Regex("^(road|nature)[-_]", RegexOption.IGNORE_CASE), "").replace('_', ' ').replace('-', ' ').trim()
        return (n.replaceFirstChar { it.uppercase() }.ifEmpty { fileName }) to g
    }
    val EXT = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")
    /** v1.7.8: files smaller than this are never real clips (macOS «._name» AppleDouble files are ~4 KB) */
    const val MIN_BYTES = 100L * 1024
    /** v1.7.8: no hidden / macOS «._» companions, no empty or tiny files */
    fun isVideo(f: File) = !f.name.startsWith(".") && f.name.substringAfterLast('.').lowercase() in EXT && f.isFile && f.length() >= MIN_BYTES
    /** a child folder by name, any letter case (CarLauncher/Backgrounds = carlauncher/backgrounds) */
    fun child(dir: File, name: String): File? = try { dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, true) } } catch (_: Throwable) { null }
    /** folders looked at on one stick / memory: CarLauncher/Backgrounds, Backgrounds, CarLauncher (any case) */
    fun dirsOn(root: File): List<File> = listOfNotNull(child(root, "CarLauncher")?.let { child(it, "Backgrounds") }, child(root, "Backgrounds"), child(root, "CarLauncher"))
    fun videosIn(dir: File, sub: Boolean = true): List<File> {
        val all = try { dir.listFiles() } catch (_: Throwable) { null } ?: return emptyList()
        val here = all.filter { isVideo(it) }
        return if (!sub) here else here + all.filter { it.isDirectory && !it.name.startsWith(".") }.flatMap { d -> videosIn(d, false) }
    }
    /** v1.7.4: folders that are never shown in the picker (system / hidden / not media) */
    private val SYS = setOf("android", "lost.dir", "system volume information", "\$recycle.bin", "recycler", "obb", "data", "found.000", "msdownld.tmp", "thumbnails", ".thumbnails", "fseventsd", "spotlight-v100", "trashes", "temp", "tmp", "cache", "logs", "log")
    fun hiddenName(n: String) = n.startsWith(".") || n.startsWith("$") || n.lowercase() in SYS
    private val hasMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun clearMemo() = hasMemo.clear()
    /** a folder with at least one video inside (≤ 4 levels down), system folders skipped */
    fun hasVideos(dir: File, depth: Int = 4): Boolean = hasMemo.getOrPut(dir.path + "|" + depth) {
        val all = try { dir.listFiles() } catch (_: Throwable) { null } ?: return@getOrPut false
        all.any { isVideo(it) && !hiddenName(it.name) } || (depth > 0 && all.any { it.isDirectory && !hiddenName(it.name) && hasVideos(it, depth - 1) })
    }
    /** sub-folders worth showing: only those with videos somewhere inside */
    fun videoFolders(dir: File): List<File> {
        val col = java.text.Collator.getInstance(Locale("ru"))
        return (try { dir.listFiles() } catch (_: Throwable) { null } ?: return emptyList())
            .filter { it.isDirectory && !hiddenName(it.name) && hasVideos(it, 3) }.sortedWith { a, b -> col.compare(a.name, b.name) }
    }
    fun videoFiles(dir: File): List<File> = (try { dir.listFiles() } catch (_: Throwable) { null } ?: emptyArray()).filter { isVideo(it) && !hiddenName(it.name) }.sortedBy { it.name.lowercase() }

    fun scan(c: Context): List<Clip> {
        val res = ArrayList<Clip>()
        // v1.7.3: the folder chosen by hand first, then the usual places on every mounted stick
        K.videoDir.takeIf { it.isNotEmpty() }?.let { File(it) }?.let { d -> videosIn(d).sortedBy { it.name.lowercase() }.forEach { f -> val (n, g) = nice(f.name); res.add(Clip(f, n, g, d.name)) } }
        for ((vol, root) in roots(c)) for (dir in dirsOn(root)) {
            videosIn(dir).sortedBy { it.name.lowercase() }.forEach { f -> val (n, g) = nice(f.name); res.add(Clip(f, n, g, vol)) }
        }
        list = res.distinctBy { it.file.name + "/" + it.file.length() }
        scannedAt = System.currentTimeMillis()
        return list
    }
    fun scanAsync(c: Context, done: () -> Unit) {
        val app = c.applicationContext
        AppRepo.exec.execute { scan(app); AppRepo.main.post(done) }
    }
    /** the chosen clip, if its file is there now */
    fun chosen(): File? = K.video.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { isVideo(it) } ?: list.firstOrNull { it.file.name == File(K.video).name }?.file ?: list.firstOrNull()?.file
}


/**
 * v1.7.3: ◀ ▶ on the screens: the next / previous background of the CURRENT kind (theme colour, nature photo,
 * video clip). A kind with only one choice (cover, the black «Линза» theme, one video) → the next kind.
 */
object BgCycle {
    private fun <T> next(l: List<T>, cur: Int, dir: Int) = l[Math.floorMod(cur + dir, l.size)]
    fun kinds(): List<String> = listOf("theme", "cover", "scene") + (if (Videos.list.isNotEmpty()) listOf("video") else emptyList())
    fun kindName(k: String) = when (k) { "cover" -> "Обложка"; "scene" -> "Фото"; "video" -> "Видео"; else -> "Тема" }
    /** returns what is shown now, for the toast */
    fun step(dir: Int, isC: Boolean, fon: Boolean): String {
        val m = if (fon) (if (K.fonKind == "video" && Videos.list.isNotEmpty()) "video" else "scene") else K.bg
        when {
            m == "scene" -> {
                val ids = listOf("auto") + Scenes.ALL.map { it.id }
                val id = next(ids, ids.indexOf(K.scene).coerceAtLeast(0), dir); K.scene = id
                return "Фото · " + if (id == "auto") "авто по погоде" else Scenes.current().name
            }
            m == "theme" && !isC -> {
                val t = next(Ck.THEMES, Ck.THEMES.indexOfFirst { it.id == K.theme }.coerceAtLeast(0), dir); K.theme = t.id
                return "Тема · " + t.name
            }
            m == "video" && Videos.list.size > 1 -> {
                val l = Videos.list; val cur = l.indexOfFirst { it.file.path == Videos.chosen()?.path }.coerceAtLeast(0)
                val c = next(l, cur, dir); K.video = c.file.path
                return "Видео · " + c.name
            }
        }
        if (fon) {   // «Фон»: only photo / video
            K.fonKind = if (m == "video") "scene" else if (Videos.list.isNotEmpty()) "video" else "scene"
            return kindName(K.fonKind) + if (K.fonKind == "video") Videos.chosen()?.let { " · " + Videos.nice(it.name).first } ?: "" else " · " + Scenes.current().name
        }
        val ks = kinds(); val k = next(ks, ks.indexOf(m).let { if (it < 0) 0 else it }, dir); K.bg = k
        return "Фон: " + kindName(k) + when (k) { "scene" -> " · " + Scenes.current().name; "video" -> Videos.chosen()?.let { " · " + Videos.nice(it.name).first } ?: ""; "theme" -> if (isC) "" else " · " + Ck.theme().name; else -> "" }
    }
}


/** v1.7.4: video previews — one frame, small, cached, made off the main thread */
object VThumb {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    fun cached(path: String): android.graphics.Bitmap? = cache.get(path)
    fun make(path: String): android.graphics.Bitmap? {
        cache.get(path)?.let { return it }
        @Suppress("DEPRECATION")
        var b: android.graphics.Bitmap? = try { android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND) } catch (_: Throwable) { null }
        if (b == null) {
            val r = android.media.MediaMetadataRetriever()
            b = try { r.setDataSource(path); r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
        }
        b ?: return null
        val sc = 320f / b.width
        val small = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(b, 320, Math.max(1, (b.height * sc).toInt()), true).also { if (it !== b) b.recycle() } else b
        cache.put(path, small)
        return small
    }
    fun bind(path: String, iv: android.widget.ImageView) {
        iv.tag = path
        cache.get(path)?.let { iv.setImageBitmap(it); return }
        if (!busy.add(path + "#" + System.identityHashCode(iv))) return
        AppRepo.exec.execute {
            val b = make(path)
            AppRepo.main.post { busy.remove(path + "#" + System.identityHashCode(iv)); if (iv.tag == path && b != null) iv.setImageBitmap(b) }
        }
    }
}
