package com.goga.carlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent

/**
 * v1.7.0 side menu («боковое меню»): the items are chosen and ordered by the user (built-in actions + any app),
 * it scrolls when there are many, the volume buttons are pinned outside the scroll area, and the menu can sit on the
 * left, on the right or at the bottom (dock). Every action only opens screens / apps — nothing is changed in the system.
 */
object Rail {
    class Item(val id: String, val label: String, val icon: Int, val desc: String)
    val BUILTIN = listOf(
        Item("home", "Главная", R.drawable.ic_home, "Главный экран Car Launcher (кнопка «Домой»)"),
        Item("back", "Назад", R.drawable.ic_back, "Системная «Назад» (служба «Кнопки навигации»)"),
        Item("recents", "Недавние", R.drawable.ic_recents, "Открытые приложения (служба «Кнопки навигации»)"),
        Item("apps", "Прилож.", R.drawable.ic_apps, "Все приложения"),
        Item("media", "Флешка", R.drawable.ic_usb, "Видео и музыка с флешки"),
        Item("dvr", "Записи", R.drawable.ic_videocam, "Записи регистратора"),
        Item("settings", "Настр.", R.drawable.ic_tune, "Настройки Car Launcher"),
        Item("layout", "Вид", R.drawable.ic_sun, "‹ ☀ › — раскладки и день/ночь (на главном экране)"),
        Item("phone", "Сменить\nтелефон", R.drawable.ic_bt_switch, "Список телефонов Bluetooth"),
        Item("wifi", "Wi-Fi", R.drawable.ic_wifi, "Сети Wi-Fi"),
        Item("bt", "Bluetooth", R.drawable.ic_bt, "Bluetooth-приложение магнитолы (звонки, музыка с телефона)"),
        Item("nav", "Навигатор", R.drawable.ic_nav, "Навигатор по умолчанию (с музыкой)"),
        Item("music", "Музыка", R.drawable.ic_music, "Плеер, который играл последним"),
        Item("camera", "Камера", R.drawable.ic_videocam, "Камера заднего вида / Cam REC"),
        Item("dvrapp", "DVR", R.drawable.ic_videocam, "Приложение регистратора"),
        Item("youtube", "YouTube", R.drawable.ic_youtube, "YouTube / SmartTube"),
        Item("playpause", "Пауза", R.drawable.ic_play, "Пауза / играть"),
        Item("clean", "Память", R.drawable.ic_storage, "Очистка памяти: что занимает место"),
        Item("voltest", "Тест\nгромк.", R.drawable.ic_vol_up, "Тест способов громкости"),
        Item("stock", "Штатный", R.drawable.ic_home, "Вернуть штатный экран (выбор главного экрана)")
    )
    const val DEFAULT = "home,back,recents,apps,media,dvr,settings,layout,phone,wifi"
    const val APP = "app:"

    fun parse(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .filter { it.startsWith(APP) || BUILTIN.any { b -> b.id == it } }.distinct()
    fun join(l: List<String>) = l.distinct().joinToString(",")
    fun builtin(id: String) = BUILTIN.firstOrNull { it.id == id }

    fun items(): List<String> = parse(Prefs.railItems).ifEmpty { parse(DEFAULT) }
    fun move(id: String, d: Int) {
        val l = items().toMutableList(); val i = l.indexOf(id); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        l[i] = l[j].also { l[j] = l[i] }; Prefs.railItems = join(l)
    }
    fun remove(id: String) { Prefs.railItems = join(items() - id) }
    fun add(id: String) { Prefs.railItems = join(items() + id) }

    fun label(c: Context, id: String): String = if (id.startsWith(APP)) Tiles.label(c, id.removePrefix(APP)) else builtin(id)?.label ?: id

    /** Runs an item. Screen items go through [ClassicActivity.show]. */
    fun run(a: ClassicActivity, id: String) {
        when (id) {
            "home" -> { if (a.current == ClassicActivity.HOME) (a.homeScreen())?.goPage(0); a.show(ClassicActivity.HOME) }
            "back" -> NavKeys.back(a)
            "recents" -> NavKeys.recents(a)
            "clean" -> a.show(ClassicActivity.CLEAN)
            "voltest" -> a.show(ClassicActivity.VOLTEST)
            "apps" -> a.show(ClassicActivity.APPS)
            "media" -> a.show(ClassicActivity.MEDIA)
            "dvr" -> a.show(ClassicActivity.DVR)
            "settings" -> a.openSettings()
            "layout" -> a.stepLayout(1)
            "phone" -> PhoneSwitch.open(a)
            "wifi" -> wifi(a)
            "bt" -> CamApps.bt(a)?.let { AppRepo.launch(a, it) } ?: AppRepo.startSafe(a, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "nav" -> Nav.open(a, null)
            "music" -> (Prefs.lastPlayer.takeIf { it.isNotEmpty() && Tiles.installed(a, it) } ?: Tiles.resolve(a, 4).pkg)?.let { AppRepo.launch(a, it) }
            "camera" -> CamApps.openCamera(a)
            "dvrapp" -> CamApps.openDvr(a)
            "youtube" -> YouTube.open(a, false)
            "playpause" -> MediaHub.command(a, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "stock" -> AppRepo.startSafe(a, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            else -> if (id.startsWith(APP)) AppRepo.launch(a, id.removePrefix(APP))
        }
    }

    /** Wi-Fi: Android 10 quick panel (if the firmware has it) → Wi-Fi settings → settings. */
    fun wifi(a: Activity) {
        if (Build.VERSION.SDK_INT >= 29) try { a.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        AppRepo.startSafe(a, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }
}

/** v1.7.0 accent colours, applied to the whole interface (theme accent override). */
object Accents {
    val ALL = listOf("" to "Как в теме", "blue" to "Синий", "cyan" to "Бирюза", "green" to "Зелёный", "lime" to "Лайм",
        "yellow" to "Жёлтый", "orange" to "Оранжевый", "red" to "Красный", "pink" to "Розовый", "purple" to "Фиолет.", "gold" to "Золото")
    fun color(id: String): Int? = when (id) {
        "blue" -> 0xFF0A84FF.toInt(); "cyan" -> 0xFF32D2E6.toInt(); "green" -> 0xFF30D158.toInt(); "lime" -> 0xFFB8E01F.toInt()
        "yellow" -> 0xFFFFD60A.toInt(); "orange" -> 0xFFFF9F0A.toInt(); "red" -> 0xFFFF453A.toInt(); "pink" -> 0xFFFF375F.toInt()
        "purple" -> 0xFFBF5AF2.toInt(); "gold" -> 0xFFE0B252.toInt(); else -> null
    }
}
