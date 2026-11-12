package com.goga.carlauncher

import android.content.Context

class TileDef(val title: String, val icon: Int, val pkgs: List<String>, val labels: List<String> = emptyList())

object Tiles {
    /** Not a package: opens the built-in «Флешка / Медиа» screen. */
    const val INTERNAL_MEDIA = "internal:media"
    val ALL = listOf(
        TileDef("Навигатор", R.drawable.ic_nav, listOf("ru.yandex.yandexnavi", "ru.dublgis.dgismobile", "com.google.android.apps.maps", "ru.yandex.yandexmaps")),
        TileDef("Радио", R.drawable.ic_radio, listOf("com.syu.radio", "com.syu.ms.radio", "com.android.fmradio"), listOf("радио", "radio", "fm")),
        TileDef("Телефон", R.drawable.ic_phone, listOf("com.syu.bt", "com.syu.btphone", "com.android.bluetooth", "com.android.dialer"), listOf("bluetooth", "телефон")),
        TileDef("Камера", R.drawable.ic_videocam, listOf("com.syu.dvr", "com.syu.camera360", "com.syu.camera", "com.android.camera2")),
        TileDef("Я.Музыка", R.drawable.ic_music, listOf("ru.yandex.music", "com.syu.music", "com.android.music"), listOf("яндекс музыка", "музыка", "music")),
        TileDef("YouTube", R.drawable.ic_youtube, listOf("org.schabi.newpipe", "org.polymorphicshade.newpipe", "com.google.android.youtube")),
        TileDef("Apple Music", R.drawable.ic_headphones, listOf("com.apple.android.music", "com.spotify.music")),
        TileDef("Файлы", R.drawable.ic_folder, listOf("com.ghisler.android.TotalCommander", "com.mixplorer", "com.android.documentsui", "com.syu.filemanager"), listOf("файлы", "files", "проводник")),
        TileDef("Флешка", R.drawable.ic_usb, listOf(INTERNAL_MEDIA))
    )

    /** Illustrated icon + block colour for system functions (shown when the app is a head-unit/system app). */
    fun art(def: TileDef): Int = when (def.icon) {
        R.drawable.ic_nav -> R.drawable.tile_nav; R.drawable.ic_radio -> R.drawable.tile_radio
        R.drawable.ic_phone -> R.drawable.tile_phone; R.drawable.ic_videocam -> R.drawable.tile_camera
        R.drawable.ic_music -> R.drawable.tile_music; R.drawable.ic_youtube -> R.drawable.tile_video
        R.drawable.ic_headphones -> R.drawable.tile_headphones; R.drawable.ic_usb -> R.drawable.tile_usb; else -> R.drawable.tile_files
    }
    fun blockColor(def: TileDef): Int = when (art(def)) {
        R.drawable.tile_nav -> 0xFF0A6CFF.toInt(); R.drawable.tile_radio -> 0xFFFF7A00.toInt()
        R.drawable.tile_phone -> 0xFF1DB954.toInt(); R.drawable.tile_camera -> 0xFF2C2C2E.toInt()
        R.drawable.tile_music -> 0xFFFF2D55.toInt(); R.drawable.tile_video -> 0xFFE0162B.toInt()
        R.drawable.tile_headphones -> 0xFFB02BE8.toInt(); R.drawable.tile_usb -> 0xFFFF9F0A.toInt(); else -> 0xFF0A84FF.toInt()
    }
    /** Bundled brand-style icon for well-known apps (shown when the real icon is missing or the generic Android robot). 0 = none. */
    fun brand(pkg: String?): Int = when {
        pkg == null -> 0
        pkg == "ru.yandex.music" -> R.drawable.brand_ymusic
        pkg == "ru.dublgis.dgismobile" -> R.drawable.brand_2gis
        pkg == "ru.yandex.yandexnavi" -> R.drawable.brand_ynavi
        pkg == "ru.yandex.yandexmaps" -> R.drawable.brand_ymaps
        pkg == "com.apple.android.music" -> R.drawable.brand_applemusic
        pkg == "com.google.android.youtube" || pkg.contains("newpipe") || pkg.contains("youtube") || pkg.contains("vanced") || pkg.contains("revanced") -> R.drawable.brand_youtube
        pkg == "com.google.android.apps.maps" -> R.drawable.tile_nav
        pkg == "com.spotify.music" -> R.drawable.tile_headphones
        pkg == "com.ghisler.android.TotalCommander" || pkg == "com.mixplorer" || pkg == "com.android.documentsui" || pkg.contains("filemanager") || pkg.contains("fileexplorer") -> R.drawable.brand_files
        else -> 0
    }
    /** What a tile shows when the app has no usable icon: brand-style art, else the tile's illustrated icon. */
    fun fallbackArt(i: Int, pkg: String?): Int {
        val def = ALL[i]
        brand(pkg ?: def.pkgs.first()).let { if (it != 0) return it }
        return if (def.icon == R.drawable.ic_folder) R.drawable.brand_files else art(def)
    }
    /** System/head-unit packages get the illustrated tile; third-party apps show their real icon. */
    fun useRealIcon(pkg: String?): Boolean = pkg != null && pkg != INTERNAL_MEDIA && !pkg.startsWith("com.syu") && !pkg.startsWith("com.android.") &&
        !pkg.startsWith("com.fyt")

    class Resolved(val pkg: String?, val custom: Boolean)

    fun installed(c: Context, pkg: String) = pkg == INTERNAL_MEDIA || c.packageManager.getLaunchIntentForPackage(pkg) != null

    fun resolve(c: Context, i: Int): Resolved {
        Prefs.tileCustom(i)?.let { if (installed(c, it)) return Resolved(it, true) }
        if (i == 3) CamApps.camera(c)?.let { return Resolved(it, false) }           // v1.7.0: TEYES Cam REC / camera by name
        if (i == 5) YouTube.pkg(c)?.let { return Resolved(it, false) }             // v1.7.0: SmartTube / YouTube choice
        for (p in ALL[i].pkgs) if (installed(c, p)) return Resolved(p, false)
        if (ALL[i].labels.isNotEmpty()) CamApps.find(c, emptyList(), ALL[i].labels)?.let { return Resolved(it, false) }
        return Resolved(null, false)
    }

    fun label(c: Context, pkg: String): String = if (pkg == INTERNAL_MEDIA) "Флешка / Медиа" else try {
        val pm = c.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) { pkg }
}
