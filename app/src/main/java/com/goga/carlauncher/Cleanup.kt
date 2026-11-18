package com.goga.carlauncher

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goga.carlauncher.Ui.u
import java.io.File

/**
 * v1.7.1 «Очистка памяти»: what takes the unit memory. Apps sorted by size (StorageStatsManager with «Доступ к истории
 * использования», otherwise the APK size), the biggest folders of the internal storage, and buttons that only OPEN the
 * system screens (app info → clear cache, the system uninstall dialog that asks for confirmation). The launcher itself
 * deletes nothing. TEYES/FYT system apps the unit needs are marked «не трогать» (see APPS-RU.md).
 */
object Cleanup {
    enum class Mark(val label: String, val color: Int) {
        KEEP("не трогать", 0xFFFF9F0A.toInt()),
        CAN("можно убрать", 0xFF30D158.toInt()),
        USER("установлено вами", 0xFF64D2FF.toInt()),
        SYSTEM("системное", 0xFF8E8E93.toInt()),
        SELF("Car Launcher", 0xFF8E8E93.toInt())
    }
    class AppSize(val pkg: String, val label: String, val app: Long, val data: Long, val cache: Long, val system: Boolean, val updated: Boolean, val exact: Boolean, val mark: Mark) {
        val total get() = app + data + cache
    }
    class Folder(val name: String, val path: String, val bytes: Long, val hint: String)

    /** Labels from the TEYES app revision (APPS-RU.md). */
    private val KEEP_LABELS = setOf("настройки", "settings", "обучение руля", "руль", "радио", "radio", "эквалайзер", "equalizer", "bluetooth", "bt",
        "cam rec", "camrec", "dvr", "google play", "play маркет", "play store", "яндекс музыка", "2гис", "яндекс навигатор", "smarttube")
    private val CAN_LABELS = setOf("aux", "google", "teyes", "teyes vision", "teyes voice", "time display", "tpms", "chrome", "spotify",
        "teyes radio", "hd видеорегистратор", "hd dvr")
    private val CAN_PKGS = setOf("com.google.android.googlequicksearchbox", "com.android.chrome", "com.spotify.music")
    private val KEEP_PKGS = setOf("com.android.settings", "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.android.bluetooth",
        "com.syu.ms", "com.syu.steer", "com.syu.radio", "com.syu.eq", "com.syu.bt", "com.syu.carradio", "com.syu.av", "com.android.systemui")

    fun mark(c: Context, pkg: String, label: String, system: Boolean): Mark {
        val l = label.trim().lowercase()
        return when {
            pkg == c.packageName -> Mark.SELF
            pkg in CAN_PKGS || l in CAN_LABELS -> Mark.CAN
            pkg in KEEP_PKGS || l in KEEP_LABELS || pkg.startsWith("com.syu.") || pkg.startsWith("com.fyt.") -> Mark.KEEP
            system -> Mark.SYSTEM
            else -> Mark.USER
        }
    }

    fun hasUsageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** Apps worth looking at: everything with an icon + everything installed by the user (incl. updates of system apps). */
    fun apps(c: Context): List<AppSize> {
        val pm = c.packageManager
        val launchable = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet() } catch (_: Throwable) { emptySet() }
        val stats = if (hasUsageAccess(c)) try { c.getSystemService(StorageStatsManager::class.java) } catch (_: Throwable) { null } else null
        val list = ArrayList<AppSize>()
        val infos = try { pm.getInstalledApplications(0) } catch (_: Throwable) { emptyList<ApplicationInfo>() }
        for (ai in infos) {
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (system && !updated && ai.packageName !in launchable) continue
            if (!ai.enabled) continue   // v1.7.2: «Отключено» → leaves the list
            val label = try { ai.loadLabel(pm).toString() } catch (_: Throwable) { ai.packageName }
            var app = 0L; var data = 0L; var cache = 0L; var exact = false
            if (stats != null) try {
                val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                app = s.appBytes; data = s.dataBytes - s.cacheBytes; cache = s.cacheBytes; exact = true
            } catch (_: Throwable) {}
            if (!exact) app = apkSize(ai)
            list.add(AppSize(ai.packageName, label, app, maxOf(0, data), cache, system, updated, exact, mark(c, ai.packageName, label, system)))
        }
        return sort(list)
    }
    fun sort(l: List<AppSize>) = l.sortedByDescending { it.total }

    fun apkSize(ai: ApplicationInfo): Long = try {
        (listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).sumOf { File(it).length() }
    } catch (_: Throwable) { 0L }

    private val HINTS = mapOf("download" to "загрузки: старые APK, файлы из браузера", "movies" to "фильмы", "dcim" to "фото и видео камер",
        "music" to "музыка", "pictures" to "картинки, скриншоты", "android" to "данные приложений (карты 2ГИС/Яндекс, кэш)",
        "cameralock" to "заблокированные ролики регистратора", "dvr" to "записи регистратора", "camrec" to "записи камеры", "podcasts" to "подкасты",
        "telegram" to "файлы Telegram", "yandexmaps" to "карты Яндекса", "2gis" to "карты 2ГИС")
    fun hint(name: String) = HINTS[name.lowercase()] ?: ""

    /** Top-level folders of the internal storage with their size (walk with a time budget; read-only). */
    fun folders(root: File = Environment.getExternalStorageDirectory(), budgetMs: Long = 6000): List<Folder> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val end = SystemClock.elapsedRealtime() + budgetMs
        return dirs.map { d -> Folder(d.name, d.path, du(d, end, 0), hint(d.name)) }.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    }
    fun du(f: File, end: Long, depth: Int): Long {
        if (SystemClock.elapsedRealtime() > end || depth > 12) return 0
        val kids = f.listFiles() ?: return 0
        var s = 0L
        for (k in kids) s += if (k.isDirectory) du(k, end, depth + 1) else k.length()
        return s
    }
    /** Installer files left in Download (SmartTube, CarLauncher… — safe to delete after installing). */
    fun apks(root: File = Environment.getExternalStorageDirectory()): List<File> =
        listOf(File(root, "Download"), File(root, "download"), root).distinct().flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", true) } ?: emptyList() }
            .distinctBy { it.canonicalPath }.sortedByDescending { it.length() }
}

