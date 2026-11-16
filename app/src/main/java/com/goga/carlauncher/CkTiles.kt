package com.goga.carlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * v1.7.1 editable home tiles. A tile = what it opens + one of 16 line icons + a name. Kinds: radio, nav (default
 * navigator), cam (rear camera / Cam REC), settings (launcher settings), app (any installed app). Stored in K.tiles.
 */
object CkTiles {
    data class Tile(val kind: String, val pkg: String = "", val cls: String = "", val icon: String, val name: String)
    val ICONS = listOf("nav", "play", "cam", "radio", "note", "pin", "folder", "phone", "gear", "cloud", "star", "car", "fuel", "blue", "mic", "home")
    val DEFAULT = listOf(
        Tile("radio", icon = "radio", name = "Радио"), Tile("nav", icon = "nav", name = "Навигатор"),
        Tile("cam", icon = "cam", name = "Камера"), Tile("settings", icon = "gear", name = "Настройки")
    )
    private const val SEP = "\u001f"; private const val ROW = "\u001e"

    fun encode(l: List<Tile>) = l.joinToString(ROW) { listOf(it.kind, it.pkg, it.cls, it.icon, it.name.replace(SEP, " ").replace(ROW, " ")).joinToString(SEP) }
    fun decode(s: String): List<Tile> = if (s.isEmpty()) DEFAULT else s.split(ROW).mapNotNull { r ->
        val f = r.split(SEP); if (f.size < 5) null else Tile(f[0], f[1], f[2], f[3].takeIf { it in ICONS } ?: "star", f[4])
    }
    fun load(): List<Tile> = decode(K.tiles)
    fun save(l: List<Tile>) { K.tiles = encode(l) }
    fun reset() { K.tiles = "" }

    /** pages: [cap] tiles per page */
    fun pages(n: Int, cap: Int) = maxOf(1, (n + cap - 1) / cap)

    /** equal-size tiles centred as a group in [width]: returns (tile width, x of the first tile) */
    fun layout(count: Int, width: Float, gap: Float, maxW: Float, slots: Int = count): Pair<Float, Float> {
        val m = maxOf(1, slots)
        val tw = minOf(maxW, (width - (m - 1) * gap) / m)
        val tot = count * tw + (count - 1).coerceAtLeast(0) * gap
        return tw to (width - tot) / 2
    }

    /** choices for the sheet: built-ins + installed apps (SmartTube etc.) */
    class Choice(val tile: Tile, val title: String)
    fun choices(c: Context): List<Choice> {
        val out = ArrayList<Choice>()
        out.add(Choice(Tile("nav", icon = "nav", name = "Навигатор"), "Навигатор по умолчанию"))
        out.add(Choice(Tile("radio", icon = "radio", name = "Радио"), "Радио"))
        out.add(Choice(Tile("cam", icon = "cam", name = "Камера"), "Камера (Cam REC)"))
        out.add(Choice(Tile("settings", icon = "gear", name = "Настройки"), "Настройки лаунчера"))
        val apps = AppRepo.apps ?: try { AppRepo.load(c).also { AppRepo.apps = it } } catch (_: Throwable) { emptyList() }
        val known = mapOf("org.smarttube" to "play", "com.teamsmart.videomanager" to "play", "ru.yandex.music" to "note", "ru.dublgis" to "pin", "ru.yandex.yandexnavi" to "nav",
            "ru.yandex.yandexmaps" to "pin", "com.google.android.youtube" to "play", "com.spotify" to "note", "com.apple.android.music" to "note")
        val sorted = apps.filter { it.pkg != c.packageName }.sortedWith(compareBy({ a -> if (known.keys.any { a.pkg.startsWith(it) }) 0 else 1 }, { it.label.lowercase() }))
        for (a in sorted) {
            val ic = known.entries.firstOrNull { a.pkg.startsWith(it.key) }?.value ?: when {
                a.pkg.contains("music", true) -> "note"; a.pkg.contains("navi", true) || a.pkg.contains("map", true) -> "nav"
                a.pkg.contains("bt", true) || a.pkg.contains("bluetooth", true) -> "blue"; a.pkg.contains("file", true) -> "folder"
                a.pkg.contains("cam", true) || a.pkg.contains("dvr", true) -> "cam"; a.pkg.contains("radio", true) -> "radio"
                a.pkg.contains("setting", true) -> "gear"; a.pkg.contains("weather", true) -> "cloud"; else -> "star"
            }
            out.add(Choice(Tile("app", a.pkg, a.cls, ic, a.label.take(14)), a.label))
        }
        return out
    }
    fun sameTarget(a: Tile, b: Tile) = a.kind == b.kind && (a.kind != "app" || a.pkg == b.pkg)

    /** open what the tile points to; returns false when it is the launcher's own settings (handled by the caller) */
    fun open(a: Activity, t: Tile): Boolean {
        when (t.kind) {
            "settings" -> return false
            "radio" -> {
                val p = MediaHub.RADIO_PKGS.firstOrNull { a.packageManager.getLaunchIntentForPackage(it) != null }
                if (p != null) { AppRepo.launch(a, p) } else CkToast.show(a, "Радио магнитолы не найдено")
            }
            "nav" -> try { Nav.open(a, Nav.defaultNav(a)) } catch (_: Throwable) { CkToast.show(a, "Навигатор не установлен") }
            "cam" -> try { CamApps.openCamera(a) } catch (_: Throwable) { CkToast.show(a, "Камера не найдена") }
            else -> if (!AppRepo.launch(a, t.pkg, t.cls.ifEmpty { null })) CkToast.show(a, "Приложение не найдено")
        }
        return true
    }
}

/** tiny in-stage toast (no system toast over the driver's view for long) */
object CkToast {
    var sink: ((String) -> Unit)? = null
    fun show(c: Context, s: String) { sink?.invoke(s) ?: android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show() }
}

/** v1.7.1 system screens opened from the status icons (never changes a setting itself). */
object SysOpen {
    fun open(c: Context, action: String, what: String) {
        val tries = listOf(Intent(action), Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {}
        }
        CkToast.show(c, "Не удалось открыть настройки $what")
    }
}
