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

class CleanScreen(a: MainActivity) : Screen(a) {
    private lateinit var body: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var foldersBox: LinearLayout
    private lateinit var storageBox: LinearLayout
    private lateinit var statusTv: TextView
    private var gen = 0
    private var showAll = false

    override val view: View by lazy { build() }

    private fun btn(t: String, bg: Int = Ui.SURFACE2, f: () -> Unit) = Ui.pill(a, t, bg) { f() }.apply {
        minHeight = u(64); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(19).toFloat()); setPadding(u(22), 0, u(22), 0)
    }

    private fun build(): View {
        val sv = ScrollView(a).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        body = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(28)) }
        sv.addView(body)
        val top = Ui.hbox(a)
        val tcol = Ui.vbox(a)
        tcol.addView(Ui.text(a, 34, Ui.TEXT, Ui.BOLD).apply { text = "Очистка памяти" })
        statusTv = Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 2).apply { text = "Считаю…" }
        tcol.addView(statusTv, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(6) })
        top.addView(tcol, Ui.lin(0, Ui.WRAP, 1f))
        top.addView(btn("Системная очистка", Ui.accent) {
            AppRepo.startSafe(a, Intent(StorageManager.ACTION_MANAGE_STORAGE), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }, Ui.lin(Ui.WRAP, u(64)).apply { rightMargin = u(12) })
        top.addView(Ui.iconButton(a, R.drawable.ic_refresh, 72, 32, Ui.SURFACE, Ui.TEXT) { refresh() })
        body.addView(top)
        storageBox = Ui.vbox(a).apply { setPadding(u(18), u(12), u(18), u(12)) }
        Ui.card(storageBox, blur = false, radius = 18)
        body.addView(storageBox, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(12) })
        body.addView(Ui.text(a, 17, Ui.alpha(android.graphics.Color.WHITE, 0.75f), Ui.BOLD).apply { text = "ПРИЛОЖЕНИЯ — САМЫЕ БОЛЬШИЕ СВЕРХУ"; letterSpacing = 0.08f },
            Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(22); bottomMargin = u(10); leftMargin = u(8) })
        appsBox = Ui.vbox(a).apply { setPadding(u(8), u(8), u(8), u(8)) }
        Ui.card(appsBox, blur = false)
        body.addView(appsBox, Ui.lin(Ui.MATCH, Ui.WRAP))
        body.addView(Ui.text(a, 17, Ui.alpha(android.graphics.Color.WHITE, 0.75f), Ui.BOLD).apply { text = "ПАПКИ ВО ВНУТРЕННЕЙ ПАМЯТИ"; letterSpacing = 0.08f },
            Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(22); bottomMargin = u(10); leftMargin = u(8) })
        foldersBox = Ui.vbox(a).apply { setPadding(u(20), u(12), u(20), u(12)) }
        Ui.card(foldersBox, blur = false)
        body.addView(foldersBox, Ui.lin(Ui.MATCH, Ui.WRAP))
        body.addView(Ui.text(a, 16, Ui.TEXT3, Ui.SANS, 6).apply {
            text = "Car Launcher сам ничего не удаляет: «Кэш / данные» открывает страницу приложения (кнопки «Очистить кэш», «Остановить», «Отключить»), " +
                "«Удалить» — системное окно с подтверждением. Отмеченные «не трогать» нужны магнитоле (руль, радио, звук, Bluetooth, камера, регистратор, Play)."
        }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(18); leftMargin = u(8); rightMargin = u(8) })
        return sv
    }

    override fun onShow() { refresh() }
    override fun onPackagesChanged() { if (a.current == MainActivity.CLEAN) refresh() }

    fun refresh() {
        val g = ++gen
        val app = a.applicationContext
        statusTv.text = "Считаю размеры…"
        storageBox.removeAllViews(); storageBox.addView(Storage.view(a), Ui.lin(Ui.MATCH, Ui.WRAP))
        AppRepo.exec.execute {
            val apps = try { Cleanup.apps(app) } catch (e: Throwable) { emptyList() }
            AppRepo.main.post { if (g == gen) showApps(apps) }
            val folders = try { Cleanup.folders() } catch (e: Throwable) { emptyList() }
            val apks = try { Cleanup.apks() } catch (e: Throwable) { emptyList() }
            AppRepo.main.post { if (g == gen) showFolders(folders, apks) }
        }
    }

    fun showApps(list: List<Cleanup.AppSize>) {
        appsBox.removeAllViews()
        val exact = list.any { it.exact }
        val sum = list.sumOf { it.total }; val cache = list.sumOf { it.cache }
        statusTv.text = "${list.size} приложений · " + Storage.gb(sum) + (if (exact) " (кэш ${Storage.gb(cache)})" else " (только размер установки)")
        if (!Cleanup.hasUsageAccess(a)) {
            val r = Ui.hbox(a).apply { setPadding(u(16), u(12), u(12), u(12)); minimumHeight = u(96) }
            r.addView(Ui.text(a, 18, Ui.TEXT, Ui.SANS, 4).apply {
                text = "Сейчас видно только размер установки. Чтобы показать данные и кэш каждого приложения, разрешите Car Launcher " +
                    "«Доступ к истории использования» (Android так называет доступ к размерам приложений; больше он ничего не даёт)."
            }, Ui.lin(0, Ui.WRAP, 1f))
            r.addView(btn("Разрешить", Ui.accent) {
                AppRepo.startSafe(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + a.packageName)), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            }, Ui.lin(Ui.WRAP, u(64)).apply { leftMargin = u(12) })
            appsBox.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP))
        }
        val shown = if (showAll) list else list.take(25)
        for (s in shown) appsBox.addView(row(s), Ui.lin(Ui.MATCH, Ui.WRAP))
        if (list.size > shown.size) appsBox.addView(btn("Показать все (${list.size})") { showAll = true; showApps(list) }, Ui.lin(Ui.MATCH, u(64)).apply { topMargin = u(8) })
    }

    private fun row(s: Cleanup.AppSize): View {
        val r = Ui.hbox(a).apply { setPadding(u(14), u(10), u(10), u(10)); minimumHeight = u(96) }
        val iv = ImageView(a); AppRepo.bindIcon(a, s.pkg, null, iv)
        r.addView(iv, Ui.lin(u(56), u(56)).apply { rightMargin = u(16) })
        val col = Ui.vbox(a)
        val t = Ui.hbox(a)
        t.addView(Ui.text(a, 22, Ui.TEXT, Ui.MEDIUM).apply { text = s.label }, Ui.lin(Ui.WRAP, Ui.WRAP))
        t.addView(Ui.text(a, 15, s.mark.color, Ui.MEDIUM).apply {
            text = s.mark.label; setPadding(u(12), u(4), u(12), u(4)); background = Ui.round(Ui.alpha(s.mark.color, 0.16f), u(14))
        }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { leftMargin = u(12) })
        col.addView(t)
        col.addView(Ui.text(a, 16, Ui.TEXT2, Ui.SANS, 1).apply {
            text = Storage.gb(s.total) + (if (s.exact) "  ·  программа ${Storage.gb(s.app)} · данные ${Storage.gb(s.data)} · кэш ${Storage.gb(s.cache)}" else "  ·  размер установки") + "  ·  " + s.pkg
        }, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(4) })
        r.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        r.addView(btn("Кэш / данные") {
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
        }, Ui.lin(Ui.WRAP, u(64)).apply { leftMargin = u(10) })
        if (s.mark != Cleanup.Mark.KEEP && s.mark != Cleanup.Mark.SELF) {
            val canUninstall = !s.system || s.updated
            r.addView(btn(if (canUninstall) (if (s.system) "Удалить обновл." else "Удалить") else "Отключить…") {
                if (canUninstall) AppRepo.startSafe(a, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg)), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
                else AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
            }, Ui.lin(Ui.WRAP, u(64)).apply { leftMargin = u(10) })
        }
        return r
    }

    fun showFolders(list: List<Cleanup.Folder>, apks: List<File>) {
        foldersBox.removeAllViews()
        if (list.isEmpty()) foldersBox.addView(Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 2).apply { text = "Нет доступа к файлам — Настр. → «Разрешения» → Файлы" })
        val max = list.firstOrNull()?.bytes ?: 1L
        for (f in list.take(10)) {
            val r = Ui.vbox(a).apply { setPadding(0, u(8), 0, u(8)) }
            val t = Ui.hbox(a)
            t.addView(Ui.text(a, 20, Ui.TEXT, Ui.MEDIUM).apply { text = f.name + if (f.hint.isNotEmpty()) "  —  " + f.hint else "" }, Ui.lin(0, Ui.WRAP, 1f))
            t.addView(Ui.text(a, 18, Ui.TEXT2, Ui.MEDIUM).apply { text = Storage.gb(f.bytes) })
            r.addView(t)
            r.addView(BarView(a, Ui.accent, Ui.alpha(android.graphics.Color.WHITE, 0.12f)).apply { fraction = f.bytes.toFloat() / max }, Ui.lin(Ui.MATCH, u(6)).apply { topMargin = u(6) })
            foldersBox.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP))
        }
        if (apks.isNotEmpty()) foldersBox.addView(Ui.text(a, 18, 0xFF30D158.toInt(), Ui.MEDIUM, 3).apply {
            text = "Установочные файлы (.apk): ${apks.size} шт., ${Storage.gb(apks.sumOf { it.length() })} — " + apks.take(4).joinToString { it.name } +
                ". После установки их можно удалить в «Файлах»"
        }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(10) })
        val fm = filesApp()
        if (fm != null) foldersBox.addView(btn("Открыть «${Tiles.label(a, fm)}» — удалить файлы") { AppRepo.launch(a, fm) }, Ui.lin(Ui.WRAP, u(64)).apply { topMargin = u(12) })
    }

    private fun filesApp(): String? {
        val names = setOf("файлы", "files", "файловый менеджер", "file manager", "total commander", "mixplorer", "проводник")
        return AppRepo.apps?.firstOrNull { it.label.trim().lowercase() in names }?.pkg
    }
}
