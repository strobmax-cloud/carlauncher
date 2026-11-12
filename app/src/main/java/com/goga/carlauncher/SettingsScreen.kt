package com.goga.carlauncher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goga.carlauncher.Ui.u

class SettingsScreen(a: MainActivity) : Screen(a) {
    companion object {
        /** v1.7.0: scroll position survives rebuilds after a change (the page no longer jumps to the top). */
        var savedScroll = 0
    }
    private lateinit var body: LinearLayout
    private val sv: ScrollView = ScrollView(a).apply {
        overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        body = Ui.vbox(a).apply { setPadding(u(20), u(20), u(20), u(28)) }
        addView(body)
        setOnScrollChangeListener { _, _, y, _, _ -> if (!restoring) savedScroll = y }
    }
    private var restoring = false
    override val view: View = sv

    private fun section(title: String): LinearLayout {
        body.addView(Ui.text(a, 17, Ui.alpha(Color.WHITE, 0.75f), Ui.BOLD).apply { text = title.uppercase(); letterSpacing = 0.08f },
            Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = u(if (body.childCount == 0) 4 else 28); bottomMargin = u(12); leftMargin = u(8) })
        val card = Ui.vbox(a).apply { setPadding(u(8), u(8), u(8), u(8)) }
        Ui.card(card, blur = false)
        body.addView(card, Ui.lin(Ui.MATCH, Ui.WRAP))
        return card
    }

    private fun row(card: LinearLayout, icon: Int?, title: String, sub: String?, right: View?, onClick: (() -> Unit)?): LinearLayout {
        val r = Ui.hbox(a).apply {
            setPadding(u(20), u(12), u(16), u(12)); minimumHeight = u(96)
            if (onClick != null) { background = Ui.pressable(0, u(20), 0x1FFFFFFF); isClickable = true; setOnClickListener { onClick() } }
        }
        if (icon != null) {
            val c = FrameLayout(a).apply { background = Ui.oval(Ui.SURFACE2) }
            c.addView(Ui.icon(a, icon, Ui.accent), Ui.frame(u(30), u(30)))
            r.addView(c, Ui.lin(u(60), u(60)).apply { rightMargin = u(20) })
        }
        val col = Ui.vbox(a)
        col.addView(Ui.text(a, 25, Ui.TEXT, Ui.MEDIUM).apply { text = title })
        if (sub != null) col.addView(Ui.text(a, 18, Ui.TEXT2, Ui.SANS, 2).apply { text = sub; setPadding(0, u(6), 0, 0) })
        r.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        if (right != null) r.addView(right)
        card.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP))
        return r
    }

    /** iOS-style flat toggle (instant, no animation). */
    private fun switch(on: Boolean, cb: (Boolean) -> Unit): View {
        // v1.7.1: bigger for a finger in the car (whole 132×88 area reacts)
        val wrap = FrameLayout(a).apply { layoutParams = LinearLayout.LayoutParams(u(132), u(88)); isClickable = true }
        val track = FrameLayout(a).apply { setPadding(u(5), u(5), u(5), u(5)) }
        val thumb = View(a).apply { background = Ui.oval(Color.WHITE) }
        track.addView(thumb, FrameLayout.LayoutParams(u(52), u(52)))
        wrap.addView(track, Ui.frame(u(104), u(62)))
        var state = on
        fun paint() {
            track.background = Ui.round(if (state) Ui.accent else Ui.PRESSED, u(31))
            (thumb.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)
            thumb.requestLayout()
        }
        paint()
        wrap.setOnClickListener { state = !state; paint(); cb(state) }
        return wrap
    }

    /** Segmented choice under a row (instant, no animation). */
    private fun choiceRow(card: LinearLayout, icon: Int, title: String, sub: String?, options: List<Pair<String, String>>, cur: String, cb: (String) -> Unit) {
        row(card, icon, title, sub, null, null)
        val seg = Ui.hbox(a).apply { setPadding(u(100), 0, u(16), u(14)) }
        for ((id, label) in options) {
            val sel = id == cur
            // v1.7.1: ≥ 72 px tall, ≥ 120 px wide, 14 px apart — big enough for a thumb
            seg.addView(Ui.text(a, 21, if (sel) Color.WHITE else Ui.TEXT2, Ui.MEDIUM).apply {
                text = label; gravity = Gravity.CENTER; setPadding(u(28), 0, u(28), 0); minWidth = u(120); contentDescription = "$title: $label"
                background = Ui.pressable(if (sel) Ui.accent else Ui.SURFACE2, u(30), Ui.PRESSED); isClickable = true
                setOnClickListener { if (!sel) cb(id) }
            }, Ui.lin(Ui.WRAP, u(72)).apply { rightMargin = u(14) })
        }
        card.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(seg) })
    }

    private fun pickFrom(title: String, apps: List<Nav.App>, cur: String, cb: (String) -> Unit) {
        val ids = listOf("") + apps.map { it.pkg }
        val names = (listOf("Автоматически") + apps.map { it.name }).toTypedArray()
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(title)
            .setSingleChoiceItems(names, ids.indexOf(cur).coerceAtLeast(0)) { dlg, w -> cb(ids[w]); dlg.dismiss() }
            .setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }

    /** Explains, then opens the system «Поверх других окон» page (nothing is changed by the launcher itself). */
    private fun askOverlay() {
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("Мини-плеер поверх навигатора")
            .setMessage("Чтобы показывать обложку и кнопки музыки поверх 2ГИС или Яндекс Навигатора, Android просит разрешение «Поверх других окон».\n\n" +
                "В открывшемся списке найдите Car Launcher и включите переключатель. Больше это разрешение ничего не даёт, выключить можно там же.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                AppRepo.startSafe(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.packageName)),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.packageName)))
            }
            .setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }

    private fun thumbCard(thumb: View, title: String, sub: String?, sel: Boolean, accent: Int, onClick: () -> Unit): View {
        val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener { onClick() } }
        val frame = FrameLayout(a).apply {
            background = GradientDrawable().apply { setColor(0); cornerRadius = u(18).toFloat(); if (sel) setStroke(u(4), accent) }
            setPadding(u(6), u(6), u(6), u(6))
        }
        frame.addView(thumb, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        col.addView(frame, Ui.lin(u(220), u(136)))
        col.addView(Ui.text(a, 19, if (sel) Ui.TEXT else Ui.alpha(Color.WHITE, 0.75f), Ui.MEDIUM).apply { text = title; gravity = Gravity.CENTER },
            Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
        if (sub != null) col.addView(Ui.text(a, 14, Ui.TEXT2, Ui.SANS, 2).apply { text = sub; gravity = Gravity.CENTER },
            Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        return col
    }

    /** Other apps that can be the home screen (stock FYT launcher, Launcher3, …). */
    private fun otherHomes(): List<android.content.pm.ResolveInfo> {
        val pm = a.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(i, 0).filter {
            val p = it.activityInfo.packageName
            p != a.packageName && it.activityInfo.name != "com.android.settings.FallbackHome"
        }
    }

    private fun build() {
        val keep = savedScroll
        restoring = true
        try { build0() } finally {
            sv.scrollTo(0, keep)
            sv.post { sv.scrollTo(0, keep); restoring = false; savedScroll = keep }
        }
    }

    private fun appChoice(title: String, cur: String, auto: String, pkgs: List<Pair<String, String>>, cb: (String) -> Unit) {
        val ids = listOf("") + pkgs.map { it.first }
        val names = (listOf("Автоматически ($auto)") + pkgs.map { it.second + "  ·  " + it.first }).toTypedArray()
        val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(title)
            .setSingleChoiceItems(names, ids.indexOf(cur).coerceAtLeast(0)) { dlg, w -> cb(ids[w]); dlg.dismiss() }
            .setNeutralButton("Другое приложение…") { _, _ -> pickAnyApp(title) { cb(it) } }
            .setNegativeButton("Отмена", null).create()
        d.window?.setWindowAnimations(0); d.show()
    }

    private fun pickAnyApp(title: String, cb: (String) -> Unit) {
        AppRepo.loadAsync(a) { apps ->
            val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(title)
                .setItems(apps.map { it.label + "  ·  " + it.pkg }.toTypedArray()) { _, w -> cb(apps[w].pkg) }
                .setNegativeButton("Отмена", null).create()
            d.window?.setWindowAnimations(0); d.show()
        }
    }

    /** v1.7.0 side menu editor: order (▲▼), remove (✕), add built-in items or apps. */
    private fun railEditor(card: LinearLayout) {
        val items = Rail.items()
        items.forEachIndexed { n, id ->
            val b = Rail.builtin(id)
            val r = Ui.hbox(a).apply { setPadding(u(20), u(6), u(12), u(6)); minimumHeight = u(72) }
            val c = FrameLayout(a).apply { background = Ui.oval(Ui.SURFACE2) }
            if (b != null) c.addView(Ui.icon(a, b.icon, Ui.accent), Ui.frame(u(26), u(26)))
            else c.addView(ImageView(a).also { AppRepo.bindIcon(a, id.removePrefix(Rail.APP), null, it) }, Ui.frame(u(36), u(36)))
            r.addView(c, Ui.lin(u(52), u(52)).apply { rightMargin = u(18) })
            val col = Ui.vbox(a)
            col.addView(Ui.text(a, 22, Ui.TEXT, Ui.MEDIUM).apply { text = "${n + 1}. " + Rail.label(a, id).replace('\n', ' ') })
            col.addView(Ui.text(a, 15, Ui.TEXT2).apply { text = b?.desc ?: id.removePrefix(Rail.APP) })
            r.addView(col, Ui.lin(0, Ui.WRAP, 1f))
            fun btn(t: String, desc: String, f: () -> Unit) = Ui.text(a, 22, Ui.TEXT, Ui.MEDIUM).apply {
                text = t; gravity = Gravity.CENTER; contentDescription = desc; isClickable = true
                background = Ui.pressable(Ui.SURFACE2, u(24), Ui.PRESSED); setOnClickListener { f(); a.rebuild() }
            }
            r.addView(btn("▲", "Выше") { Rail.move(id, -1) }, Ui.lin(u(64), u(52)).apply { leftMargin = u(8) })
            r.addView(btn("▼", "Ниже") { Rail.move(id, 1) }, Ui.lin(u(64), u(52)).apply { leftMargin = u(8) })
            r.addView(btn("✕", "Убрать") {
                if (id != "settings" && items.size > 1) Rail.remove(id)
                else android.widget.Toast.makeText(a, "«Настройки» остаются в меню — иначе в них не попасть", android.widget.Toast.LENGTH_LONG).show() },
                Ui.lin(u(64), u(52)).apply { leftMargin = u(8) })
            card.addView(r, Ui.lin(Ui.MATCH, Ui.WRAP))
        }
        row(card, R.drawable.ic_add, "Добавить пункт", "Действие (Wi-Fi, Bluetooth, Камера, Навигатор, Музыка…) или любое приложение", null) {
            val missing = Rail.BUILTIN.filter { it.id !in items }
            val names = (missing.map { it.label.replace('\n', ' ') + " — " + it.desc } + "Приложение…").toTypedArray()
            val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("Добавить в меню")
                .setItems(names) { _, w -> if (w < missing.size) { Rail.add(missing[w].id); a.rebuild() } else pickAnyApp("Приложение в меню") { Rail.add(Rail.APP + it); a.rebuild() } }
                .setNegativeButton("Отмена", null).create()
            d.window?.setWindowAnimations(0); d.show()
        }
        row(card, R.drawable.ic_refresh, "Меню по умолчанию", "Главная, Приложения, Флешка, Записи, Настройки, ‹☀›, Сменить телефон, Wi-Fi", null) {
            Prefs.railItems = Rail.DEFAULT; a.rebuild()
        }
    }

    private fun build0() {
        body.removeAllViews()
        // Layout: completely different home arrangements
        val hs = section("Главный экран")
        choiceRow(hs, R.drawable.ic_home, "Вид главного экрана", "Экраны: Главная · Музыка · Камера — листаются пальцем, большой переключатель сверху. Классические — 14 старых раскладок",
            listOf("screens" to "Экраны", "classic" to "Классические"), if (Prefs.classic) "classic" else "screens") {
            if (it == "classic") { Prefs.classic = true; if (Prefs.layout == Layouts.SCREENS.id) Prefs.layout = Layouts.ALL[0].id }
            else { Prefs.classic = false; Prefs.layout = Layouts.SCREENS.id }
            a.rebuild()
        }
        if (!Prefs.classic) row(hs, R.drawable.ic_videocam, "Экран «Камера»", "Третий экран: камера заднего вида и скорость",
            switch(Prefs.camPage) { Prefs.camPage = it; a.rebuild() }, null)
        if (Prefs.classic) {
        val ls = section("Раскладка главного экрана")
        fun layoutStrip(card: LinearLayout, brand: Boolean) {
            val lstrip = Ui.hbox(a).apply { setPadding(u(12), u(14), u(12), u(10)); gravity = Gravity.TOP }
            for (l in Layouts.ALL.filter { it.brand == brand }) {
                val sel = l.id == Layouts.byId(Prefs.layout).id
                lstrip.addView(thumbCard(LayoutThumb(a, l.id, Themes.current), l.name, l.desc, sel, Themes.current.accent) {
                    if (!sel) { Prefs.layout = l.id; a.rebuild() }
                }, Ui.lin(u(220), Ui.WRAP).apply { rightMargin = u(12) })
            }
            card.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(lstrip) })
        }
        layoutStrip(ls, false)
        val bs = section("Раскладки по мотивам автомобилей")
        layoutStrip(bs, true)
        }

        // v1.7.0: side menu — position, volume buttons, items
        val mn = section("Боковое меню и громкость")
        choiceRow(mn, R.drawable.ic_apps, "Где меню", "Слева или справа — колонка, снизу — док как на Mac (листается вбок). Пункты листаются, если их много",
            listOf("left" to "Слева", "right" to "Справа", "bottom" to "Снизу"), Prefs.railSide) { Prefs.railSide = it; a.rebuild() }
        choiceRow(mn, R.drawable.ic_tune, "Размер меню и кнопок", "Ширина меню, значки, подписи и кнопки ‹ ☀ › 🎨. Крупный — на четверть больше прежнего",
            listOf("normal" to "Обычный", "large" to "Крупный", "xlarge" to "Очень крупный"), Prefs.railSize) { Prefs.railSize = it; a.rebuild() }
        choiceRow(mn, R.drawable.ic_vol_up, "Кнопки громкости", "Полупрозрачные, поверх экрана, место под них не занимается. Обе стороны — водителю и пассажиру",
            listOf("both" to "Обе стороны", "left" to "Только слева", "right" to "Только справа"), Prefs.volSide) { Prefs.volSide = it; a.rebuild() }
        choiceRow(mn, R.drawable.ic_vol_up, "Где кнопки громкости по высоте", null,
            listOf("center" to "По центру", "bottom" to "Внизу", "top" to "Вверху"), Prefs.volPos) { Prefs.volPos = it; a.rebuild() }
        choiceRow(mn, R.drawable.ic_vol_up, "Размер кнопок громкости", null,
            listOf("64" to "Обычные", "76" to "Крупные", "92" to "Очень крупные"), Prefs.volSize.toString()) { Prefs.volSize = it.toInt(); a.rebuild() }
        row(mn, R.drawable.ic_vol_down, "Тест громкости", "Способ сейчас: " + (Volume.method(Prefs.volMethod)?.let { "${it.n} — ${it.title}" } ?: "Авто") +
            ". Магнитола (com.syu.ms): " + (if (Syu.connected) "подключена ✓" else if (Syu.missing) "сервис не найден" else "подключаюсь") +
            ". Нажмите — 8 способов по большой кнопке, выберите тот, что работает", null) { a.show(MainActivity.VOLTEST) }
        val acc = NavAccess.enabled(a)
        row(mn, R.drawable.ic_back, "Кнопки «Назад» и «Недавние»", if (acc) "Служба включена ✓ — кнопки работают в меню и на плавающей кнопке"
            else "Нужна служба «Car Launcher — кнопки навигации» (Спец. возможности). Нажмите — объясню и открою. Кнопки руля она не трогает", null) {
            if (acc) NavKeys.openSettings(a) else NavKeys.explain(a)
        }
        row(mn, R.drawable.ic_home, "Плавающие кнопки Назад / Домой / Недавние", "Поверх других приложений (навигатор, YouTube), если системные кнопки под держателем телефона. " +
            "Перетаскивается за полоску слева. " + (if (Nav.canOverlay(a)) "" else "Нужно разрешение «Поверх других окон»"),
            switch(Prefs.floatNav) { on ->
                Prefs.floatNav = on
                if (on && !Nav.canOverlay(a)) askOverlay() else FloatNav.sync(a)
            }, null)
        railEditor(mn)

        // v1.6.3: the SX4 on the road — road time of day (only the road; the interface stays as you set it) and the plate
        val car = section("Машина на экране")
        val st = RoadArt.sunTimes(java.util.Calendar.getInstance())
        fun hm(m: Double) = "%d:%02d".format((m / 60).toInt(), (m % 60).toInt())
        choiceRow(car, R.drawable.ic_sun, "Дорога", "Только картинка дороги с машиной. Интерфейс остаётся тёмным или светлым, как вы выбрали. Авто: день " +
            (st?.let { "с ${hm(it.first)} до ${hm(it.second)} (восход–закат, Астана)" } ?: "с 7:00 до 18:00"),
            listOf("auto" to "Авто", "day" to "День", "night" to "Ночь"), Prefs.roadMode) { Prefs.roadMode = it; a.rebuild(); build() }
        row(car, R.drawable.ic_tune, "Госномер", Prefs.plate.ifEmpty { "Не задан — нажмите, чтобы вписать (например, 123 ABC 01)" }, null) {
            val e = android.widget.EditText(a).apply {
                setText(Prefs.plate); hint = "123 ABC 01"; setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            }
            val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("Госномер (казахстанский)")
                .setMessage("Цифры и латинские буквы, например 123 ABC 01 (01 — Астана). Пробелы расставлю сам. Пусто — без номера.")
                .setView(e)
                .setPositiveButton("Сохранить") { _, _ -> Prefs.plate = e.text.toString(); a.rebuild(); build() }
                .setNegativeButton("Отмена", null).create()
            d.window?.setWindowAnimations(0); d.show()
        }
        row(car, R.drawable.ic_videocam, "Плитка «Камера» в раскладках с машиной", "Американский минимал, Витрина, Приборка: открывает камеру заднего вида (" +
            (CamApps.camera(a)?.let { Tiles.label(a, it) } ?: "приложение не найдено") + ")", switch(Prefs.camTile) { Prefs.camTile = it; a.rebuild() }, null)

        // Themes (colours + wallpaper), previewed in the current layout
        val th = section("Тема: цвета и обои")
        val tstrip = Ui.hbox(a).apply { setPadding(u(12), u(14), u(12), u(10)); gravity = Gravity.TOP }
        for (t in Themes.ALL) {
            val sel = t.id == Themes.current.id
            tstrip.addView(thumbCard(LayoutThumb(a, Prefs.layout, t), t.name, null, sel, t.accent) {
                if (!sel) { Prefs.theme = t.id; a.rebuild() }
            }, Ui.lin(u(220), Ui.WRAP).apply { rightMargin = u(12) })
        }
        th.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tstrip) })
        // v1.7.0 accent colour for the whole interface
        row(th, R.drawable.ic_tune, "Цвет акцента", "Кнопки, подсветка, переключатели и меню во всех раскладках. «Как в теме» — цвет выбранной темы", null, null)
        val sw = Ui.hbox(a).apply { setPadding(u(24), 0, u(16), u(16)); gravity = Gravity.TOP }
        for ((id, label) in Accents.ALL) {
            val sel = id == Prefs.accent
            val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; contentDescription = "Акцент $label"
                setOnClickListener { if (!sel) { Prefs.accent = id; a.rebuild() } } }
            val dot = View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                setColor(Accents.color(id) ?: Themes.byId(Prefs.theme).accent); if (sel) setStroke(u(5), Color.WHITE) } }
            col.addView(dot, Ui.lin(u(76), u(76)))
            col.addView(Ui.text(a, 16, if (sel) Ui.TEXT else Ui.TEXT2, Ui.MEDIUM).apply { text = label; gravity = Gravity.CENTER }, Ui.lin(u(110), Ui.WRAP).apply { topMargin = u(6) })
            sw.addView(col, Ui.lin(u(110), Ui.WRAP).apply { rightMargin = u(6) })
        }
        th.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(sw) })

        // Wallpaper: built-ins + my gallery (USB / phone) + background mode + rotation
        val wp = section("Обои")
        val wstrip = Ui.hbox(a).apply { setPadding(u(12), u(14), u(12), u(10)); gravity = Gravity.TOP }
        fun option(id: String?, title: String, thumb: Bitmap?, iconRes: Int?, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
            val sel = id != null && Prefs.wallpaper == id
            val col = Ui.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener { onClick() }
                if (onLong != null) { isLongClickable = true; setOnLongClickListener { onLong(); true } } }
            val frame = FrameLayout(a).apply {
                background = GradientDrawable().apply { setColor(Ui.alpha(Color.WHITE, 0.08f)); cornerRadius = u(16).toFloat(); if (sel) setStroke(u(4), Ui.accent) }
                setPadding(u(5), u(5), u(5), u(5))
            }
            val iv = ImageView(a).apply {
                scaleType = if (thumb != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
                if (thumb != null) setImageBitmap(thumb) else if (iconRes != null) { setImageResource(iconRes); imageTintList = ColorStateList.valueOf(Ui.TEXT); setPadding(u(52), u(26), u(52), u(26)) }
                Ui.clipRound(this, u(12))
            }
            frame.addView(iv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            col.addView(frame, Ui.lin(u(168), u(100)))
            col.addView(Ui.text(a, 17, if (sel) Ui.TEXT else Ui.alpha(Color.WHITE, 0.7f), Ui.MEDIUM).apply { text = title; gravity = Gravity.CENTER }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(8) })
            wstrip.addView(col, Ui.lin(u(168), Ui.WRAP).apply { rightMargin = u(12) })
        }
        fun choose(id: String) { Prefs.wallpaper = id; a.rebuild() }
        option(null, "С телефона", null, R.drawable.ic_phone_up) { UploadDialog(a) { a.rebuild() }.show() }
        option(null, "С флешки", null, R.drawable.ic_add) { a.pickWallpaper() }
        val gallery = Gallery.ids(a).reversed()
        gallery.forEachIndexed { n, id ->
            option(id, "Моё фото ${gallery.size - n}", Wallpaper.thumb(a, id), null, onLong = {
                val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("Удалить эту картинку из галереи?")
                    .setPositiveButton("Удалить") { _, _ -> Gallery.delete(a, id); a.wallpaperChanged(); build() }
                    .setNegativeButton("Отмена", null).create()
                d.window?.setWindowAnimations(0); d.show()
            }) { choose(id) }
        }
        option("theme", "Как в теме", Wallpaper.thumb(a, Themes.current.wallpaper), null) { choose("theme") }
        for (b in Wallpaper.BUILTIN) option(b.id, b.title, Wallpaper.thumb(a, b.id), null) { choose(b.id) }
        wp.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(wstrip) })
        row(wp, R.drawable.ic_phone_up, "Загрузить обои с телефона", "Телефон и магнитола в одной Wi-Fi сети (раздача с телефона подходит) — покажу QR-код", null) {
            UploadDialog(a) { a.rebuild() }.show()
        }
        row(wp, R.drawable.ic_folder, "Добавить с флешки", "Можно выбрать несколько. Картинки копируются в память магнитолы — флешку можно вынуть", null) { a.pickWallpaper() }
        if (gallery.isNotEmpty()) row(wp, R.drawable.ic_trash, "Моя галерея: ${gallery.size} фото", "Долгое нажатие на картинку выше — удалить", null, null)
        choiceRow(wp, R.drawable.ic_music, "Фон при музыке", "Чёткая — сама обложка на весь экран (слегка затемнена). Быстро: кнопка 🎨 в меню на главном",
            listOf("wall" to "Мои обои", "cover" to "Размытая обложка", "sharp" to "Чёткая обложка"), Prefs.bgMode) { Prefs.bgMode = it; Wallpaper.refreshCoverMode(); build() }
        row(wp, R.drawable.ic_palette, "Цвет из обложки", "Кнопки, подсветка и меню берут цвет обложки играющего трека. Без музыки — «Цвет акцента». Быстро: 🎨 в меню",
            switch(Prefs.coverAccent) { Prefs.coverAccent = it; a.rebuild() }, null)
        choiceRow(wp, R.drawable.ic_refresh, "Смена обоев", if (gallery.size >= 2) "По кругу из вашей галереи (${gallery.size} фото)" else "Пока в галерее меньше 2 фото — по кругу вместе со встроенными",
            Gallery.ROTATE, Prefs.rotate) { Prefs.rotate = it; Prefs.rotatedAt = System.currentTimeMillis(); build() }

        // Navigation + music
        val nv = section("Навигация и музыка")
        row(nv, R.drawable.ic_bt_switch, "Сменить телефон", "Открывает список сопряжённых телефонов (Bluetooth магнитолы). Та же кнопка — в боковом меню", null) { PhoneSwitch.open(a) }
        val wl = PhoneSwitch.installedWireless(a)
        row(nv, R.drawable.ic_bt, "Сначала закрыть беспроводной CarPlay / AA", (if (wl.isEmpty()) "ZLink / Autokit не найдены. " else "Найдено: " + wl.joinToString { Tiles.label(a, it) } + ". ") +
            "Без root Android закрывает их не всегда — если телефон не отпускается, отключите его в самом адаптере",
            switch(Prefs.closeWireless) { Prefs.closeWireless = it }, null)
        row(nv, R.drawable.ic_nav, "Навигатор по умолчанию", Nav.name(a, Nav.defaultNav(a)) + if (Prefs.navDefault.isEmpty()) " (автоматически)" else "", null) {
            pickFrom("Навигатор по умолчанию", Nav.installedNavs(a), Prefs.navDefault) { Prefs.navDefault = it; a.rebuild() }
        }
        choiceRow(nv, R.drawable.ic_split, "Навигатор открывать", "Одна кнопка «Навигатор» везде. С мини-плеером — музыка поверх навигатора (нужно «Поверх других окон»). " +
            "Рядом — разделить экран с музыкой, если прошивка умеет (TEYES часто не умеет — тогда мини-плеер)",
            listOf("mini" to "С мини-плеером", "split" to "Рядом с музыкой", "only" to "Только навигатор"), Prefs.navMode) { Prefs.navMode = it; a.rebuild() }
        row(nv, R.drawable.ic_play, "Включать музыку с навигатором", "Если ничего не играет — продолжить последний плеер", switch(Prefs.navMusic) { Prefs.navMusic = it }, null)
        row(nv, R.drawable.ic_music, "Музыка для режима «Рядом»", (Nav.musicApp(a, null)?.let { Nav.name(a, it) } ?: "не установлено") +
            if (Prefs.splitMusic.isEmpty()) " (автоматически: что играет сейчас)" else "", null) {
            pickFrom("Музыка рядом с навигатором", Nav.installedMusic(a), Prefs.splitMusic) { Prefs.splitMusic = it; build() }
        }
        row(nv, R.drawable.ic_play, "Плеер с карточки — сразу играть", "Нажатие на карточку музыки, когда ничего не играет: открыть последний плеер и нажать ▶. Долгое нажатие на карточку — выбрать источник",
            switch(Prefs.autoPlay) { Prefs.autoPlay = it }, null)
        row(nv, R.drawable.ic_youtube, "Приложение YouTube", (YouTube.pkg(a)?.let { Tiles.label(a, it) } ?: "не установлено — откроется m.youtube.com") +
            (if (Prefs.ytApp.isEmpty()) " (автоматически: SmartTube → ReVanced → YouTube → LibreTube/NewPipe). SmartTube сделан для пульта ТВ — часть экранов пальцем неудобна" else ""), null) {
            appChoice("Приложение YouTube", Prefs.ytApp, "SmartTube → ReVanced → YouTube → LibreTube", YouTube.choices(a)) { Prefs.ytApp = it; a.rebuild() }
        }
        row(nv, R.drawable.ic_music, "Мини-плеер поверх навигатора", "Появляется, когда открываете навигатор из Car Launcher. Перетаскивается; нажатие на обложку — свернуть в кружок",
            switch(Prefs.miniAuto) { Prefs.miniAuto = it; if (!it) MiniPlayer.stop(a) }, null)
        val ovOk = Nav.canOverlay(a)
        row(nv, R.drawable.ic_apps, "Разрешение «Поверх других окон»", if (ovOk) "Разрешено ✓" else "Не разрешено — нажмите, объясню и открою нужный экран", null) { askOverlay() }
        row(nv, R.drawable.ic_play, "Показать мини-плеер сейчас", "Для проверки: появится, когда вы уйдёте с этого экрана и что-то играет", null) {
            if (!MiniPlayer.start(a, auto = false)) {
                if (!ovOk) askOverlay()
                else android.widget.Toast.makeText(a, "Нужен «Доступ к музыке» (уведомления) — см. раздел «Система»", android.widget.Toast.LENGTH_LONG).show()
            } else android.widget.Toast.makeText(a, "Мини-плеер включён — закрыть: ✕ на нём", android.widget.Toast.LENGTH_SHORT).show()
        }

        // v1.7.0 camera / DVR apps
        val cm = section("Камера и регистратор")
        val camNow = CamApps.camera(a); val dvrNow = CamApps.dvr(a)
        row(cm, R.drawable.ic_videocam, "Приложение камеры заднего вида", (camNow?.let { Tiles.label(a, it) + "  ·  " + it } ?: "не найдено") + if (Prefs.camApp.isEmpty()) " (автоматически)" else "", null) {
            val cands = listOfNotNull(CamApps.find(a, CamApps.CAM_PKGS, CamApps.CAM_LABELS), CamApps.find(a, emptyList(), listOf("teyes vision")), CamApps.dvr(a)).distinct().map { it to Tiles.label(a, it) }
            appChoice("Камера заднего вида", Prefs.camApp, "Cam REC / камера", cands) { Prefs.camApp = it; a.rebuild() }
        }
        row(cm, R.drawable.ic_videocam, "Приложение регистратора", (dvrNow?.let { Tiles.label(a, it) + "  ·  " + it } ?: "не найдено") + if (Prefs.dvrApp.isEmpty()) " (автоматически)" else "", null) {
            val cands = listOfNotNull(CamApps.find(a, CamApps.DVR_PKGS, listOf("dvr")), CamApps.find(a, emptyList(), listOf("hd видеорегистратор")), CamApps.find(a, emptyList(), listOf("cam rec"))).distinct().map { it to Tiles.label(a, it) }
            appChoice("Регистратор", Prefs.dvrApp, "DVR / HD видеорегистратор", cands) { Prefs.dvrApp = it; build() }
        }
        val cams = CamApps.cameras(a)
        row(cm, R.drawable.ic_videocam, "Живая картинка в плитке (эксперимент)", "Камеры, видимые приложениям: " + (if (cams.isEmpty()) "нет — на TEYES/FYT задняя камера обычно идёт мимо Android, тогда плитка просто открывает приложение камеры"
            else cams.joinToString { "№${it.first} ${it.second}" }) + ". ВНИМАНИЕ: может прервать запись Cam REC — выключите, если запись пропадает",
            switch(Prefs.camLive) { on ->
                Prefs.camLive = on
                if (on && !a.has(android.Manifest.permission.CAMERA)) a.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 3)
                a.rebuild()
            }, null)
        if (cams.size > 1) choiceRow(cm, R.drawable.ic_videocam, "Какая камера", null, listOf("" to "Первая") + cams.map { it.first to "№${it.first} ${it.second}" }, Prefs.camId) { Prefs.camId = it; a.rebuild() }

        // USB media
        val md = section("Флешка / Медиа")
        md.addView(Storage.view(a).apply { setPadding(u(20), u(8), u(20), u(8)) }, Ui.lin(Ui.MATCH, Ui.WRAP))
        for ((k, title) in listOf("video" to "Плеер видео", "music" to "Плеер музыки")) {
            val pref = if (k == "video") Prefs.videoPlayer else Prefs.musicPlayer
            val now = Media.playerFor(a, k)
            val sub = when {
                pref == "ask" -> "Спрашивать каждый раз"
                now != null -> Tiles.label(a, now) + (if (pref.isEmpty()) " (автоматически)" else "") +
                        (if (now == Media.VLC) "  ·  продолжение с места остановки ✓" else if (now == Media.MX || now == Media.MX_PRO) "  ·  продолжение ✓" else "  ·  продолжение — если плеер сообщает позицию")
                else -> "Системный плеер" + (if (k == "video") " — установите VLC: он запоминает, где вы остановились" else "")
            }
            row(md, if (k == "video") R.drawable.ic_videocam else R.drawable.ic_music, title, sub, null) {
                val ps = Media.players(a, k)
                val ids = listOf("", "ask") + ps.map { it.first }
                val names = (listOf(if (k == "video") "Автоматически (VLC, если установлен)" else "Автоматически (системный)", "Спрашивать каждый раз") + ps.map { it.second }).toTypedArray()
                val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle(title)
                    .setSingleChoiceItems(names, ids.indexOf(pref).coerceAtLeast(0)) { dlg, w ->
                        if (k == "video") Prefs.videoPlayer = ids[w] else Prefs.musicPlayer = ids[w]; dlg.dismiss(); build()
                    }.setNegativeButton("Отмена", null).create()
                d.window?.setWindowAnimations(0); d.show()
            }
        }
        row(md, R.drawable.ic_usb, "Открыть «Флешка / Медиа»", "Тоже есть в боковом меню. Плитку «Флешка» можно включить ниже", null) { a.show(MainActivity.MEDIA) }

        // Tiles
        val tiles = section("Плитки на главном экране")
        Tiles.ALL.forEachIndexed { i, def ->
            val r = Tiles.resolve(a, i)
            val sub = when {
                r.pkg == null -> "не установлено — нажмите, чтобы выбрать приложение"
                r.pkg == Tiles.INTERNAL_MEDIA -> "встроенный экран «Флешка / Медиа»  ·  до 8 плиток (в широких раскладках 9)"
                else -> Tiles.label(a, r.pkg) + "  ·  " + r.pkg + if (r.custom) "  (выбрано вами)" else ""
            }
            row(tiles, def.icon, def.title, sub, switch(Prefs.tileVisible(i)) { Prefs.setTileVisible(i, it); a.rebuild() }) {
                Pickers.pickApp(a, i) { a.rebuild() }
            }
        }
        // Widgets
        val w = section("Виджеты и вид")
        row(w, R.drawable.ic_speed, "Спидометр (GPS)", "Скорость по спутникам на главном экране",
            switch(Prefs.showSpeed) { Prefs.showSpeed = it; a.rebuild() }, null)
        row(w, R.drawable.ic_home, "24-часовой формат", "Иначе 12-часовой", switch(Prefs.use24) { Prefs.use24 = it }, null)
        choiceRow(w, R.drawable.ic_tune, "День / ночь", "Для всех раскладок, переключается только вручную. Быстро: кнопка ☀/☾ в боковом меню на главном экране",
            listOf("day" to "День", "night" to "Ночь"), Prefs.dayMode) { Prefs.dayMode = it; a.rebuild() }
        choiceRow(w, R.drawable.ic_tune, "Подсветка приборов", "Цвет стрелок и дуг в «приборных» раскладках",
            Dash.ACCENTS, Prefs.dashAccent) { Prefs.dashAccent = it; a.rebuild() }
        choiceRow(w, R.drawable.ic_music, "Подсветка под музыку", "Мягкое свечение — еле заметно по краям экрана. Столбики — спектр вдоль левого и правого края экрана (на обложку не заходят), до 30 кадров/с, только пока играет. " +
            (if (a.has(android.Manifest.permission.RECORD_AUDIO)) "Доступ к звуку есть ✓" else "Нужен доступ «Микрофон/звук» — без него просто спокойно «дышит»") +
            ". Если радио или BT-звонки начнут глючить — выключите",
            listOf("off" to "Выкл", "glow" to "Мягкое свечение", "bars" to "Столбики"), Prefs.musicFx) {
            Prefs.musicFx = it; if (it != "off") a.requestAudioPerm(); a.glow?.settingsChanged(); a.rebuild() }
        row(w, R.drawable.ic_nav, "Показывать карту OSM", "Маленькая карта-превью вместо карточки навигатора. Тратит интернет и память — обычно не нужна",
            switch(Prefs.showOsmMap) { Prefs.showOsmMap = it; a.rebuild() }, null)
        // Updates: from the phone (local page) or from the internet manifest; the system installer always asks
        val up = section("Обновление · сейчас ${Updater.installedName(a)}")
        row(up, R.drawable.ic_phone_up, "Обновить с телефона", "Покажет QR-код: откройте страницу на телефоне и выберите файл CarLauncher-….apk. " +
            "Ставится только ваша сборка (проверка подписи), данные и настройки сохраняются", null) {
            if (Updater.ensureAllowed(a)) UploadDialog(a) { a.rebuild() }.show()
        }
        row(up, R.drawable.ic_refresh, "Проверить обновления",
            (Prefs.updateUrl.ifEmpty { Updater.UPDATE_URL }).ifEmpty { "Адрес ещё не задан — нажмите «Адрес обновлений»" }, null) { Updater.check(a) }
        row(up, R.drawable.ic_tune, "GitHub / адрес обновлений", "Репозиторий GitHub «владелец/имя» (берётся последний Release с .apk) или ссылка на update.json. Сейчас: " +
            Prefs.updateUrl.ifEmpty { "по умолчанию: github.com/strobmax-cloud/carlauncher" }, null) {
            val e = android.widget.EditText(a).apply { setText(Prefs.updateUrl.ifEmpty { Updater.UPDATE_URL }); hint = "goga/carlauncher  или  https://…/update.json"; setSingleLine() }
            val d = android.app.AlertDialog.Builder(a, R.style.Dialog).setTitle("GitHub-репозиторий или адрес").setView(e)
                .setPositiveButton("Сохранить") { _, _ -> Prefs.updateUrl = e.text.toString(); build() }
                .setNegativeButton("Отмена", null).create()
            d.window?.setWindowAnimations(0); d.show()
        }
        // Rollback: always a way back to the stock home screen (only launches system screens, changes nothing itself)
        val rb = section("Вернуть штатный экран")
        row(rb, R.drawable.ic_home, "Выбрать главный экран", "Откроется системный выбор «Главный экран» — выберите штатный лаунчер", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        for (ri in otherHomes()) {
            val label = ri.loadLabel(a.packageManager).toString()
            row(rb, R.drawable.ic_apps, "Открыть «$label»", ri.activityInfo.packageName + " — штатный/другой главный экран", null) {
                AppRepo.startSafe(a, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name))
            }
        }
        row(rb, R.drawable.ic_refresh, "Сбросить «по умолчанию»", "Страница Car Launcher → «Открывать по умолчанию» → «Очистить». После этого кнопка «Домой» снова спросит, какой экран открыть", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.packageName)), Intent(Settings.ACTION_SETTINGS))
        }

        // v1.7.1 storage cleanup
        val cl = section("Память магнитолы")
        cl.addView(Storage.view(a).apply { setPadding(u(20), u(8), u(20), u(8)) }, Ui.lin(Ui.MATCH, Ui.WRAP))
        row(cl, R.drawable.ic_storage, "Очистка памяти", "Приложения и папки по размеру, что можно убрать и что не трогать. Удаление — только через системное окно", null) { a.show(MainActivity.CLEAN) }
        // System
        val s = section("Система")
        row(s, R.drawable.ic_home, "Сделать главным экраном", "Выберите Car Launcher как приложение «Главный экран»", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        row(s, R.drawable.ic_music, "Доступ к музыке", (if (MediaHub.listenerEnabled(a)) "Разрешено ✓ — " else "НЕ разрешено — нажмите. ") + "Нужен, чтобы видеть и переключать любую музыку: Я.Музыка, Apple Music, радио, телефон по Bluetooth", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        row(s, R.drawable.ic_speed, "Разрешения", "GPS (спидометр) и файлы (записи регистратора)", null) {
            a.requestPerms()
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.packageName)))
        }
        row(s, R.drawable.ic_tune, "Настройки Android", null, null) { AppRepo.startSafe(a, Intent(Settings.ACTION_SETTINGS)) }
        row(s, R.drawable.ic_refresh, "Ускорить анимации", "Параметры разработчика → «Анимация окон/переходов/длительность» = 0,5x или Откл.", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
        val hid = Prefs.hiddenApps
        row(s, R.drawable.ic_apps, "Скрытые приложения", if (hid.isEmpty()) "Нет. Скрыть: долгое нажатие на значок в «Приложениях»" else "${hid.size}: " + hid.joinToString { Tiles.label(a, it) } + " — нажмите, чтобы вернуть все", null) {
            if (hid.isNotEmpty()) { Prefs.hiddenApps = emptySet(); build() }
        }
        row(s, R.drawable.ic_apps, "Список пакетов (FYT)", "Найти точные имена штатных приложений, нажмите — скопировать", null) { Pickers.packages(a) }

        body.addView(Ui.text(a, 17, Ui.TEXT3).apply {
            text = "Car Launcher ${Updater.installedName(a)} · Suzuki SX4 S-Cross · долгое нажатие на плитку — сменить приложение"; gravity = Gravity.CENTER
        }, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(28) })
    }

    override fun onShow() = build()
    override fun onPackagesChanged() { if (a.current == 3) build() }

    @Suppress("unused") private fun unused(v: ImageView, t: TextView) {}
}
