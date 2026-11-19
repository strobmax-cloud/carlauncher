package com.goga.carlauncher

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goga.carlauncher.Ck.put
import com.goga.carlauncher.W.lin

/**
 * Popups of the v1.7.1 interface: quick «Фон» panel, gallery, weather menu, source menu, tile editor, mileage reset,
 * Settings (iOS-like sections) and «Память и очистка». All in design px inside [Cockpit.over].
 */
@SuppressLint("SetTextI18n")
class Overlays(private val ck: Cockpit) {
    private val a get() = ck.a
    private val over get() = ck.over
    private val stack = ArrayList<Pair<String, View>>()
    val open get() = stack.isNotEmpty()
    fun isBgPanel() = stack.any { it.first == "bg" }

    private fun t(size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1, g: Int = Gravity.START or Gravity.CENTER_VERTICAL) =
        Ck.text(a, size, color, Ck.face(bold), lines, g)

    private fun push(id: String, v: View) { over.addView(v, FrameLayout.LayoutParams(Ck.W, Ck.H)); stack.add(id to v) }
    fun close(id: String) { stack.filter { it.first == id }.forEach { over.removeView(it.second); stack.remove(it) }; hideKb() }
    fun closeTop() { stack.removeLastOrNull()?.let { over.removeView(it.second) }; hideKb() }
    fun closeAll() { stack.forEach { over.removeView(it.second) }; stack.clear(); hideKb() }
    fun back(): Boolean { if (stack.isEmpty()) return false; closeTop(); return true }
    private fun hideKb() { try { a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(over.windowToken, 0) } catch (_: Throwable) {} }
    /** v1.7.2: back from the system «App info» / uninstall dialog → the list shows what is really installed now */
    fun onResume() {
        if (stack.any { it.first == "clean" }) clean(keepScroll = true)
        // v1.7.5: back from Спец. возможности → show the new service state
        if (stack.any { it.first == "settings" } && section == "sysnav") refreshSettings()
        if (stack.any { it.first == "navhelp" } && NavAccess.enabled(a)) { close("navhelp"); if (K.floatOn) NavAccess.refresh(); ck.build() }
    }
    fun storageChanged() { if (stack.any { it.first == "gallery" }) { close("gallery"); gallery() } }

    /** veil + sheet at x,y,w,h; returns the sheet */
    private fun popup(id: String, x: Int, y: Int, w: Int, h: Int, veilAlpha: Int = 0x8C): FrameLayout {
        close(id)
        val v = W.veil(a, veilAlpha) { close(id) }
        val s = W.sheet(a)
        v.put(s, x, y, w, h)
        push(id, v)
        return s
    }
    private fun scroll(content: View): ScrollView = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(content) }
    private fun cap(s: String) = t(17f, 0x8CFFFFFF.toInt(), true).apply { text = s.uppercase(); letterSpacing = 0.08f }
    private fun group(): LinearLayout = W.vbox(a).apply { setPadding(22, 6, 22, 6); background = Panel(0x14FFFFFF, 0x1FFFFFFF, 1f, floatArrayOf(20f, 20f, 20f, 20f), false) }
    private fun LinearLayout.rows(vararg v: View) { v.forEachIndexed { i, x -> if (i > 0 || childCount > 0) addView(W.divider(a), lin(W.M, 1)); addView(x, lin(W.M, W.WR)) } }

    // ================================================================== quick «Фон»
    fun bgPanel() {
        val s = popup("bg", 360, 80, 880, 590, 0x40)
        s.put(W.header(a, "layers", "Фон", null) { close("bg") }, 28, 16, 824, 56)
        val body = W.vbox(a)
        val modes = listOf("theme" to "Тема", "cover" to "Обложка", "scene" to "Фото", "video" to "Видео")
        val cur = K.bg
        // 1) the mode: four tabs, one of them is on
        body.addView(cap("Что за интерфейсом — выберите одно"), lin(W.M, W.WR).apply { bottomMargin = 8 })
        body.addView(W.seg(a, modes.map { it.second }, modes.indexOfFirst { it.first == cur }.coerceAtLeast(0), 60, 21f) { i ->
            val m = modes[i].first
            K.bg = m; ck.applyBackground(); ck.build(); close("bg"); bgPanel()
            if (m == "video" && Videos.list.isEmpty()) Videos.scanAsync(a) { if (Videos.list.isEmpty()) { ck.showToast("Видео не найдено — выберите папку"); folderPicker(null) } else { ck.applyBackground(); ck.build() } }
        }, lin(W.M, 60))
        body.addView(t(19f, Ck.accent(), true, 2).apply { text = "Сейчас: " + modeText(cur) }, lin(W.M, W.WR).apply { topMargin = 10 })
        // 2) the settings of THIS mode only
        val g = group()
        when (cur) {
            "theme" -> {
                if (!Ck.isC) { val th = W.vbox(a).apply { setPadding(0, 10, 0, 10) }; th.addView(t(21f).apply { text = "Цвет темы" }); th.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(themeRow()) }, lin(W.M, 64).apply { topMargin = 8 }); g.rows(th) }
                else g.rows(W.row(a, "Тема «Линзы»", "Чёрная панель. Цветные темы — у стиля «Стекло» (Настройки → Внешний вид)", null))
                if (!Ck.isC) g.rows(W.row(a, "Волны", "Медленное движение фона", W.Toggle(a, K.waves) { K.waves = it; ck.bg.refresh() }, 76, 42))
            }
            "cover" -> {
                g.rows(W.row(a, "Обложка трека на весь экран", "Меняется с каждой песней. Размытие и яркость — ниже. Нет обложки — цвет темы", null))
                g.rows(W.row(a, "Размытие обложки", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42))
                if (!Ck.isC) g.rows(W.row(a, "Волны", "Медленное движение фона", W.Toggle(a, K.waves) { K.waves = it; ck.bg.refresh() }, 76, 42))
            }
            "scene" -> g.rows(
                W.row(a, "Фото", (if (K.scene == "auto") "Авто по погоде: " else "") + Scenes.current().name, W.button(a, "Выбрать…", 160, 50) { gallery() }, W.WR, 50),
                W.row(a, "Размытие", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42))
            "video" -> g.rows(
                W.row(a, "Видео", Videos.chosen()?.name ?: "не найдено — папка ${Videos.DIR} на флешке", W.button(a, "Выбрать…", 160, 50) { gallery(video = true) }, W.WR, 50),
                W.row(a, "Размытие", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42))
        }
        // 3) brightness — works for every mode
        g.rows(W.row(a, "Яркость фона", if (cur == "scene") "Для фото" else "Темнее / светлее", W.Slider(a, K.bright, 30, 100) { v, done ->
            K.bright = v; ck.refreshFx(); if (done && cur == "scene") ck.applyBackground()
        }, 300, 44))
        // v1.7.3: glow from the track cover — on every background
        g.rows(glowRow())
        body.addView(g, lin(W.M, W.WR).apply { topMargin = 12 })
        // 4) rain / snow
        body.addView(cap("Дождь и снег"), lin(W.M, W.WR).apply { topMargin = 16; bottomMargin = 8 })
        val f = group(); fxRows(f) { close("bg"); bgPanel() }
        body.addView(f, lin(W.M, W.WR))
        s.put(scroll(body), 28, 84, 824, 490)
    }
    private fun glowRow(): View = W.row(a, "Сила подсветки от трека", "Цветной свет от обложки вокруг неё" + if (K.bg == "cover") " и на фоне" else "", W.Slider(a, K.glow, 0, 100) { v, done ->
        K.glow = v; ck.refreshGlowLive(); if (done) ck.refreshGlow()
    }, 300, 44)
    private fun modeText(m: String) = when (m) {
        "cover" -> "Обложка трека на весь экран"; "scene" -> "Фото " + if (K.scene == "auto") "по погоде" else "«${Scenes.current().name}»"
        "video" -> "Видео с флешки" + (Videos.chosen()?.let { " — ${Videos.nice(it.name).first}" } ?: " — не найдено"); else -> if (Ck.isC) "Тема — чёрная «Линза»" else "Тема — " + Ck.theme().name
    }
    /** rain / snow rows (quick panel and settings): on/off, «По погоде», or always rain / snow */
    private fun fxRows(g: LinearLayout, reopen: () -> Unit) {
        g.rows(W.row(a, "Показывать дождь и снег", "Лёгкая анимация поверх фона", W.Toggle(a, K.particles) { K.particles = it; ck.refreshFx(); reopen() }, 76, 42))
        if (!K.particles) return
        g.rows(W.row(a, "По погоде", if (K.fxAuto) "Только когда за окном дождь или снег (на фото и видео)" else "Выключено — показываю то, что выбрано ниже, всегда", W.Toggle(a, K.fxAuto) { K.fxAuto = it; ck.refreshFx(); reopen() }, 76, 42))
        if (!K.fxAuto) {
            val opts = listOf("rain" to "Дождь", "snow" to "Снег")
            val c = W.vbox(a).apply { setPadding(0, 8, 0, 12) }
            c.addView(W.seg(a, opts.map { it.second }, opts.indexOfFirst { it.first == K.fxKind }.coerceAtLeast(0)) { K.fxKind = opts[it].first; ck.refreshFx() }, lin(W.M, 54))
            g.rows(c)
        }
    }
    private fun themeRow(): LinearLayout {
        val r = W.hbox(a)
        for (th in Ck.THEMES) {
            val on = th.id == K.theme
            val f = W.hbox(a).apply { gravity = Gravity.CENTER; setPadding(14, 0, 18, 0) }
            f.background = Panel(0x14FFFFFF, if (on) th.accent else 0x33FFFFFF, if (on) 2.5f else 1f, floatArrayOf(30f, 30f, 30f, 30f), false).apply { if (on) glow = th.accent }
            f.addView(View(a).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(th.c1, th.c2)).apply { shape = android.graphics.drawable.GradientDrawable.OVAL } }, lin(34, 34))
            f.addView(t(19f, Color.WHITE, on).apply { text = th.name }, lin(W.WR, W.WR).apply { leftMargin = 10 })
            Ck.pressable(f) { K.theme = th.id; if (K.bg != "theme" && K.bg != "cover") K.bg = "theme"; ck.rebuildAll(); close("bg"); bgPanel() }
            r.addView(f, lin(W.WR, 60).apply { rightMargin = 10 })
        }
        return r
    }

    // ================================================================== gallery (photos + flash videos)
    fun gallery(video: Boolean = false) {
        val s = popup("gallery", 60, 40, 1160, 640)
        s.put(W.header(a, if (video) "folder" else "img", if (video) "Видео · флешка" else "Фото", null) { close("gallery") }, 28, 16, 1104, 56)
        val body = W.vbox(a)
        if (!video) {
            val auto = K.scene == "auto"
            body.addView(W.row(a, "Авто по погоде и времени суток", "Сейчас: ${Scenes.current().name}", W.Toggle(a, auto) {
                K.scene = if (it) "auto" else Scenes.current().id; useKind("scene"); ck.applyBackground(); ck.build(); close("gallery"); gallery()
            }, 76, 42), lin(W.M, W.WR))
            val grid = GridBox(4, 16)
            for (sc in Scenes.ALL) {
                val f = FrameLayout(a)
                val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(16f) }
                f.addView(iv, FrameLayout.LayoutParams(-1, -1))
                thumb(sc.res, iv)
                val on = K.scene == sc.id || (K.scene == "auto" && Scenes.current().id == sc.id && K.bg == "scene")
                f.foreground = Panel(0, if (on) Ck.accent() else 0x33FFFFFF, if (on) 3f else 1f, floatArrayOf(16f, 16f, 16f, 16f), false)
                f.addView(t(17f, Color.WHITE, true).apply { text = sc.name; setPadding(10, 4, 10, 6); setBackgroundColor(0x8C000000.toInt()) }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                f.contentDescription = sc.name
                Ck.pressable(f) { K.scene = sc.id; useKind("scene"); ck.applyBackground(); ck.build(); close("gallery") }
                grid.add(f, 148)
            }
            body.addView(grid.view, lin(W.M, W.WR).apply { topMargin = 10 })
            body.addView(t(16f, 0x80FFFFFF.toInt(), lines = 3).apply { text = "Фото: Unsplash / Pexels / Wikimedia Commons — авторы и лицензии в Настройки → О программе." }, lin(W.M, W.WR).apply { topMargin = 12 })
        } else {
            val list = Videos.list
            body.addView(t(18f, 0xB3FFFFFF.toInt(), lines = 4).apply {
                text = "Положите .mp4 в папку ${Videos.DIR} на флешке (или во внутренней памяти) и вставьте её в магнитолу. " +
                    "Видео крутится по кругу без звука. Лучше 1280×720, до 1–2 минут. Имена road-… и nature-… сортируются по группам."
            }, lin(W.M, W.WR))
            val b = W.hbox(a)
            // v1.7.3: choose ANY folder — built-in browser over all mounted sticks, or Android's own folder picker
            b.addView(W.button(a, "Выбрать папку…", 230, 54, primary = true) { folderPicker(null) }.apply { contentDescription = "Выбрать папку" }, lin(W.WR, 54))
            b.addView(W.button(a, "Через Android…", 220, 54) { a.pickVideoTree() }, lin(W.WR, 54).apply { leftMargin = 12 })
            b.addView(W.button(a, "Обновить", 160, 54) { Videos.scanAsync(a) { close("gallery"); gallery(true) } }, lin(W.WR, 54).apply { leftMargin = 12 })
            if (!a.hasFiles()) b.addView(W.button(a, "Разрешить доступ к файлам", 300, 54) { a.askFiles() }, lin(W.WR, 54).apply { leftMargin = 12 })
            body.addView(b, lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 10 })
            if (K.videoDir.isNotEmpty()) body.addView(W.row(a, "Своя папка", K.videoDir, W.button(a, "Сбросить", 150, 48) { K.videoDir = ""; Videos.scanAsync(a) { close("gallery"); gallery(true) } }, W.WR, 48), lin(W.M, W.WR))
            if (list.isEmpty()) body.addView(t(22f, Color.WHITE, true, 2).apply { text = "Видео не найдено — нажмите «Выбрать папку…»" }, lin(W.M, W.WR).apply { topMargin = 10 })
            // v1.7.4: previews in a grid, tap = background
            for ((grp, clips) in list.groupBy { it.group }) {
                body.addView(cap(grp), lin(W.M, W.WR).apply { topMargin = 12; bottomMargin = 6 })
                val grid = GridBox(4, 14)
                for (cl in clips) grid.add(videoTile(cl.file, cl.name, Storage.gb(cl.file.length())) { pickVideo(cl.file.path) }, 150)
                grid.finish(); body.addView(grid.view, lin(W.M, W.WR))
            }
        }
        s.put(scroll(body), 28, 84, 1104, 540)
    }
    /** v1.7.4: on the «Фон» screen a pick changes ITS background (photo / video), elsewhere the screens' background */
    private fun useKind(k: String) { if (ck.fon) K.fonKind = k else K.bg = k }
    /** a video preview tile: frame + name; tap = this video as the background */
    private fun videoTile(f: java.io.File, name: String, sub: String?, onPick: () -> Unit): View {
        val box = FrameLayout(a)
        val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = RoundOutline(14f); setBackgroundColor(0x33000000) }
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        VThumb.bind(f.path, iv)
        val on = Videos.chosen()?.path == f.path && (if (ck.fon) K.fonKind else K.bg) == "video"
        box.foreground = Panel(0, if (on) Ck.accent() else 0x33FFFFFF, if (on) 3f else 1f, floatArrayOf(14f, 14f, 14f, 14f), false)
        box.addView(Ck.icon(a, "play", 0xCCFFFFFF.toInt()), FrameLayout.LayoutParams(34, 34, Gravity.CENTER))
        box.addView(t(16f, Color.WHITE, true, 1).apply { text = name + (sub?.let { " · $it" } ?: ""); setPadding(10, 4, 10, 6); setBackgroundColor(0x99000000.toInt()); ellipsize = android.text.TextUtils.TruncateAt.END }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        box.contentDescription = name
        Ck.pressable(box) { onPick() }
        return box
    }
    private fun pickVideo(path: String) { K.video = path; useKind("video"); ck.applyBackground(); ck.build(); close("gallery"); close("folders"); ck.showToast("Фон: видео «${Videos.nice(java.io.File(path).name).first}»") }
    private class GridBox(val cols: Int, val gap: Int) {
        lateinit var view: LinearLayout
        private var row: LinearLayout? = null
        private var n = 0
        fun add(v: View, h: Int) {
            if (!::view.isInitialized) view = LinearLayout(v.context).apply { orientation = LinearLayout.VERTICAL }
            if (n % cols == 0) { row = LinearLayout(v.context).apply { orientation = LinearLayout.HORIZONTAL }; view.addView(row, LinearLayout.LayoutParams(-1, h).apply { if (n > 0) topMargin = gap }) }
            row!!.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (n % cols > 0) leftMargin = gap })
            n++
        }
        /** pad the last row so its tiles keep the same width */
        fun finish() { while (n % cols != 0) { row!!.addView(View(row!!.context), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap }); n++ } }
    }
    private fun thumb(res: Int, iv: ImageView) {
        AppRepo.exec.execute {
            val b: Bitmap? = try { BitmapFactory.decodeResource(a.resources, res, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Throwable) { null }
            AppRepo.main.post { if (b != null) iv.setImageBitmap(b) }
        }
    }

    // ================================================================== weather menu
    fun weatherMenu() {
        val s = popup("wx", 30, 100, 640, 470, 0x40)
        s.put(W.header(a, Wx.icon(), "Погода ${Wx.tempText()}", Wx.place()) { close("wx") }, 26, 16, 588, 56)
        val body = W.vbox(a)
        body.addView(W.row(a, "Авто: фон по прогнозу", "Open-Meteo, обновляется раз в 30 минут; без интернета — последний прогноз", W.Toggle(a, K.wxAuto) { K.wxAuto = it; ck.applyBackground(); close("wx"); weatherMenu() }, 76, 42), lin(W.M, W.WR))
        if (!K.wxAuto) {
            body.addView(cap("Время суток"), lin(W.M, W.WR).apply { topMargin = 12; bottomMargin = 6 })
            val tods = listOf("day" to "День", "night" to "Ночь")
            body.addView(W.seg(a, tods.map { it.second }, tods.indexOfFirst { it.first == Wx.tod() }.coerceAtLeast(0)) { K.wxTod = tods[it].first; ck.applyBackground() }, lin(W.M, 54))
            body.addView(cap("Погода"), lin(W.M, W.WR).apply { topMargin = 12; bottomMargin = 6 })
            val ks = listOf("clear" to "Ясно", "cloudy" to "Облачно", "rain" to "Дождь", "snow" to "Снег")
            body.addView(W.seg(a, ks.map { it.second }, ks.indexOfFirst { it.first == Wx.kind() }.coerceAtLeast(0)) { K.wxKind = ks[it].first; ck.applyBackground() }, lin(W.M, 54))
        }
        val b = W.hbox(a)
        b.addView(W.button(a, "Обновить", 160, 54) { Wx.refresh(a, true); ck.showToast("Обновляю погоду…") }, lin(W.WR, 54).apply { rightMargin = 12 })
        b.addView(W.button(a, "Фон «Фото»", 200, 54, primary = K.bg != "scene") { K.bg = "scene"; K.scene = "auto"; ck.applyBackground(); ck.build(); close("wx") }, lin(W.WR, 54))
        body.addView(b, lin(W.M, W.WR).apply { topMargin = 18 })
        s.put(scroll(body), 26, 84, 588, 370)
    }

    // ================================================================== source menu
    fun sourceMenu() {
        val s = popup("src", 340, 120, 600, 480, 0x40)
        s.put(W.header(a, "note", "Источник", null) { close("src") }, 26, 16, 548, 56)
        val body = W.vbox(a)
        val g = group()
        g.rows(
            W.row(a, "Радио", Radio.freq ?: "Штатное радио магнитолы", Ck.icon(a, "radio"), 30, 30) { close("src"); ck.pickSource("radio") },
            W.row(a, "Яндекс Музыка / плеер", "Последний музыкальный плеер", Ck.icon(a, "ya"), 30, 30) { close("src"); ck.pickSource("music") },
            W.row(a, "Телефон", "Музыка с телефона по Bluetooth", Ck.icon(a, "bt"), 30, 30) { close("src"); ck.pickSource("phone") }
        )
        body.addView(g, lin(W.M, W.WR))
        val sess = MediaHub.all
        if (sess.isNotEmpty()) {
            body.addView(cap("Сейчас открыты"), lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 6 })
            val g2 = group()
            for (mc in sess) g2.rows(W.row(a, Tiles.label(a, mc.packageName), MediaHub.title(mc) ?: "", null) { MediaHub.pin(mc.packageName); close("src"); ck.updMedia(true) })
            body.addView(g2, lin(W.M, W.WR))
        }
        s.put(scroll(body), 26, 84, 548, 380)
    }

    // ================================================================== mileage reset
    fun resetPopup(which: String) {
        val name = if (which == "trip") "Поездка" else if (which == "total") "Всего" else "За день"
        val s = popup("reset", 390, 220, 500, 250)
        s.put(t(26f, Color.WHITE, true, 1, Gravity.CENTER).apply { text = "Сбросить «$name»?" }, 20, 30, 460, 40)
        s.put(t(19f, 0xA6FFFFFF.toInt(), false, 2, Gravity.CENTER).apply { text = "Сейчас ${Trip.km(if (which == "trip") K.trip else if (which == "total") K.total else K.day)} км. Счётчик начнётся с нуля." }, 30, 80, 440, 56)
        s.put(W.button(a, "Отмена", 200, 60) { close("reset") }, 40, 160, 200, 60)
        s.put(W.button(a, "Сбросить", 200, 60, danger = true) { Trip.reset(which); close("reset"); ck.updMil(); ck.showToast("«$name» сброшено") }, 260, 160, 200, 60)
    }

    // ================================================================== tile editor
    // ================================================================== radio chooser (v1.7.4)
    fun radioChooser(fromTile: Boolean = false) {
        val s = popup("radiochoice", 340, 150, 600, 420)
        s.put(W.header(a, "radio", "Радио", null) { close("radiochoice") }, 28, 14, 544, 56)
        var remember = false
        val body = W.vbox(a)
        fun go(c: String) { if (remember) K.radioChoice = c; close("radiochoice"); if (c == "fm") { ck.pickSource("fm"); if (fromTile) ck.show(1) } else ck.radioNet(fromTile) }
        body.addView(W.button(a, "FM — радио магнитолы", 544, 72, primary = true) { go("fm") }.apply { contentDescription = "FM" }, lin(W.M, 72))
        body.addView(t(16f, 0x99FFFFFF.toInt()).apply { text = "Работает без интернета" }, lin(W.M, W.WR).apply { topMargin = 4; leftMargin = 8 })
        body.addView(W.button(a, "Интернет-радио — RadioDroid", 544, 72) { go("net") }.apply { contentDescription = "Интернет-радио" }, lin(W.M, 72).apply { topMargin = 14 })
        body.addView(t(16f, 0x99FFFFFF.toInt()).apply { text = "Нужен интернет" }, lin(W.M, W.WR).apply { topMargin = 4; leftMargin = 8 })
        body.addView(W.row(a, "Запомнить выбор", "Снова спросить — удержание кнопки «Радио»", W.Toggle(a, false) { remember = it }, 76, 42), lin(W.M, W.WR).apply { topMargin = 12 })
        s.put(body, 28, 80, 544, 330)
    }

    // ================================================================== video folder browser (v1.7.3)
    /** [path] null = the list of sticks / memory; else one folder: its sub-folders + «Выбрать эту папку» */
    fun folderPicker(path: String?) {
        val s = popup("folders", 60, 24, 1160, 672)
        val dir = path?.let { java.io.File(it) }
        s.put(W.header(a, "folder", if (dir == null) "Где видео — флешки и память" else dir.name.ifEmpty { dir.path }, null) { close("folders") }, 28, 14, 1104, 64)
        val body = W.vbox(a)
        if (!a.hasFiles()) {
            val g = group(); g.rows(W.row(a, "Нет доступа к файлам", "Без разрешения флешка выглядит пустой", W.button(a, "Разрешить", 170, 48, primary = true) { a.askFiles() }, W.WR, 48))
            body.addView(g, lin(W.M, W.WR).apply { bottomMargin = 12 })
        }
        val status = t(19f, 0xB3FFFFFF.toInt(), lines = 2).apply { text = "Ищу папки с видео…" }
        body.addView(status, lin(W.M, W.WR))
        val content = W.vbox(a); body.addView(content, lin(W.M, W.WR))
        s.put(scroll(body), 28, 92, 1104, if (dir == null) 560 else 490)
        if (dir == null) Videos.clearMemo()
        // v1.7.4: only folders that contain videos (system / hidden folders never shown), counted off the main thread
        AppRepo.exec.execute {
            val roots = if (dir == null) (try { Videos.roots(a) } catch (_: Throwable) { emptyList() }).filter { Videos.hasVideos(it.second) } else emptyList()
            val subs = if (dir != null) Videos.videoFolders(dir) else emptyList()
            val files = if (dir != null) Videos.videoFiles(dir) else emptyList()
            val readable = dir == null || dir.listFiles() != null
            AppRepo.main.post {
                if (!stack.any { it.first == "folders" }) return@post
                val g = group()
                if (dir == null) {
                    status.text = if (roots.isEmpty()) "Видео не найдено ни на одной флешке. Проверьте флешку и разрешение на файлы." else "Накопители с видео:"
                    for ((name, root) in roots) g.rows(W.row(a, name, root.path, Ck.icon(a, "folder", 0xB3FFFFFF.toInt()), 26, 26) { folderPicker(root.path) })
                } else {
                    val isRoot = Videos.roots(a).any { it.second.path == dir.path }
                    val up = dir.parentFile
                    status.text = dir.path
                    g.rows(W.row(a, "⬆ Назад", if (!isRoot && up != null) up.name else "к списку накопителей", null) { folderPicker(if (!isRoot && up != null) up.path else null) })
                    if (!readable) g.rows(W.row(a, "Нет доступа к этой папке", "Разрешите доступ к файлам или выберите папку «Через Android…»", null))
                    for (d in subs) g.rows(W.row(a, d.name, null, Ck.icon(a, "folder", 0xB3FFFFFF.toInt()), 26, 26) { folderPicker(d.path) })
                }
                content.addView(g, lin(W.M, W.WR))
                if (files.isNotEmpty()) {
                    content.addView(cap("Видео в этой папке — нажмите, чтобы поставить фоном"), lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 6 })
                    val grid = GridBox(4, 14)
                    for (f in files) grid.add(videoTile(f, Videos.nice(f.name).first, null) { K.videoDir = dir!!.path; Videos.scanAsync(a) {}; pickVideo(f.path) }, 150)
                    grid.finish(); content.addView(grid.view, lin(W.M, W.WR))
                }
            }
        }
        if (dir != null) {
            val bar = W.hbox(a).apply { gravity = Gravity.CENTER_VERTICAL }
            bar.addView(t(18f, 0x99FFFFFF.toInt(), lines = 2).apply { text = "«Выбрать эту папку» — все её видео попадут в список фонов и в стрелки ◀ ▶" }, lin(0, W.WR, 1f))
            bar.addView(W.button(a, "Выбрать эту папку", 280, 58) { useVideoDir(dir.path) }, lin(W.WR, 58).apply { leftMargin = 16 })
            s.put(bar, 28, 598, 1104, 62)
        }
    }
    /** a folder chosen by hand → its videos become the background list */
    fun useVideoDir(path: String) {
        K.videoDir = path
        Videos.scanAsync(a) {
            val first = Videos.videosIn(java.io.File(path)).minByOrNull { it.name.lowercase() }
            if (first == null) { ck.showToast("В папке нет видео: $path"); return@scanAsync }
            K.video = first.path; useKind("video"); close("folders"); close("gallery"); ck.applyBackground(); ck.build()
            ck.showToast("Видео из папки «${java.io.File(path).name}»: ${Videos.list.size}")
        }
    }

    // ================================================================== Все приложения (v1.7.2)
    private var appsShowHidden = false
    private fun hiddenSet() = K.hiddenApps.split(',').filter { it.isNotEmpty() }.toMutableSet()
    /** full app grid: search, A–Я, tap = open, hold = add as a home tile / hide */
    fun allApps(query: String = "") {
        val s = popup("apps", 30, 20, 1220, 680)
        s.put(W.header(a, "grid", "Все приложения", null) { close("apps") }, 28, 14, 1164, 56)
        val search = EditText(a).apply {
            hint = "Поиск приложения"; setHintTextColor(0x80FFFFFF.toInt()); setText(query); setSelection(query.length)
            setTextColor(Color.WHITE); textSize = 22f; typeface = Ck.face(); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE; setPadding(20, 0, 20, 0); background = Panel(0x24FFFFFF, 0x40FFFFFF, 1.2f, floatArrayOf(16f, 16f, 16f, 16f), false)
            contentDescription = "Поиск приложения"
        }
        s.put(search, 28, 84, 860, 60)
        val hidden = hiddenSet()
        val hb = W.button(a, if (appsShowHidden) "Скрыть скрытые" else "Скрытые: ${hidden.size}", 260, 60) { appsShowHidden = !appsShowHidden; allApps(search.text.toString()) }
        s.put(hb, 904, 84, 288, 60)
        val gridBox = W.vbox(a)
        val sv = scroll(gridBox)
        s.put(sv, 28, 158, 1164, 508)
        val status = t(20f, 0xB3FFFFFF.toInt(), lines = 2, g = Gravity.CENTER)
        fun fill(all: List<AppRepo.App>) {
            gridBox.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val hs = hiddenSet()
            val col = java.text.Collator.getInstance(java.util.Locale("ru"))
            val list = all.filter { it.pkg != a.packageName && (appsShowHidden || it.pkg !in hs) && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)) }
                .sortedWith { x, y -> col.compare(x.label, y.label) }
            if (list.isEmpty()) { status.text = if (q.isEmpty()) "Приложений нет" else "Ничего не найдено по «$q»"; gridBox.addView(status, lin(W.M, 120)); return }
            val cols = 6; val cw = 1164 / cols
            list.chunked(cols).forEach { rowApps ->
                val row = W.hbox(a)
                for (app in rowApps) {
                    val c = W.vbox(a).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(8, 14, 8, 10) }
                    val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; if (app.pkg in hs) alpha = .4f }
                    AppRepo.bindIcon(a, app.pkg, app.cls, iv)
                    c.addView(iv, lin(76, 76))
                    c.addView(t(18f, if (app.pkg in hs) 0x80FFFFFF.toInt() else Color.WHITE, lines = 2, g = Gravity.CENTER).apply { text = app.label }, lin(W.M, W.WR).apply { topMargin = 8 })
                    c.contentDescription = app.label
                    Ck.pressable(c, { appMenu(app) { allApps(search.text.toString()) } }) { if (AppRepo.launch(a, app.pkg, app.cls)) close("apps") }
                    row.addView(c, lin(cw, 162))
                }
                gridBox.addView(row, lin(W.M, 162))
            }
        }
        status.text = "Загружаю список…"; gridBox.addView(status, lin(W.M, 120))
        var loaded: List<AppRepo.App> = emptyList()
        AppRepo.loadAsync(a) { l -> loaded = l; fill(l) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { fill(loaded); sv.scrollTo(0, 0) }
        })
    }
    private fun appMenu(app: AppRepo.App, reopen: () -> Unit) {
        val s = popup("appmenu", 390, 170, 500, 380)
        s.put(W.header(a, "grid", app.label, null) { close("appmenu") }, 28, 14, 444, 56)
        val body = W.vbox(a)
        val tiles = CkTiles.load()
        val onHome = tiles.any { it.kind == "app" && it.pkg == app.pkg }
        body.addView(W.button(a, if (onHome) "Уже на главном экране" else "На главный экран", 444, 62, primary = !onHome) {
            if (!onHome) { CkTiles.save(tiles + CkTiles.Tile("app", app.pkg, app.cls, CkTiles.iconFor(app.pkg), app.label.take(14))); ck.build(); ck.showToast("«${app.label}» — плитка на главном экране") }
            close("appmenu")
        }, lin(W.M, 62))
        val hs = hiddenSet(); val hidden = app.pkg in hs
        body.addView(W.button(a, if (hidden) "Показать в списке" else "Скрыть из списка", 444, 62) {
            if (hidden) hs.remove(app.pkg) else hs.add(app.pkg); K.hiddenApps = hs.joinToString(",")
            close("appmenu"); reopen()
            if (!hidden) ck.showToast("Скрыто. Вернуть — кнопка «Скрытые» вверху")
        }, lin(W.M, 62).apply { topMargin = 12 })
        body.addView(W.button(a, "О приложении", 444, 62) {
            close("appmenu"); AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
        }, lin(W.M, 62).apply { topMargin = 12 })
        s.put(body, 28, 84, 444, 270)
    }

    fun tileSheet(index: Int) {
        val all = CkTiles.load().toMutableList()
        val cur = all.getOrNull(index)
        var tile = cur ?: CkTiles.Tile("app", icon = "star", name = "Плитка")
        val s = popup("tile", 140, 40, 1000, 640)
        s.put(W.header(a, "grid", if (cur == null) "Новая плитка" else "Плитка «${cur.name}»", null) { close("tile") }, 28, 16, 944, 56)
        val body = W.vbox(a)
        val name = EditText(a).apply {
            setText(tile.name); setTextColor(Color.WHITE); textSize = 22f; typeface = Ck.face(); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE; setPadding(18, 0, 18, 0); background = Panel(0x24FFFFFF, 0x40FFFFFF, 1.2f, floatArrayOf(14f, 14f, 14f, 14f), false)
        }
        body.addView(cap("Название"), lin(W.M, W.WR).apply { bottomMargin = 6 })
        body.addView(name, lin(W.M, 60))
        body.addView(cap("Значок"), lin(W.M, W.WR).apply { topMargin = 16; bottomMargin = 6 })
        val icons = W.hbox(a)
        val iconViews = ArrayList<Pair<String, View>>()
        fun markIcon() { for ((n, v) in iconViews) v.background = Panel(if (n == tile.icon) Ck.alpha(Ck.accent(), .25f) else 0x14FFFFFF, if (n == tile.icon) Ck.accent() else 0x26FFFFFF, if (n == tile.icon) 2f else 1f, floatArrayOf(14f, 14f, 14f, 14f), false) }
        for (n in CkTiles.ICONS) {
            val f = FrameLayout(a); f.addView(Ck.icon(a, n), FrameLayout.LayoutParams(28, 28, Gravity.CENTER)); f.contentDescription = n
            Ck.pressable(f) { tile = tile.copy(icon = n); markIcon() }
            iconViews.add(n to f); icons.addView(f, lin(52, 52).apply { rightMargin = 6 })
        }
        markIcon()
        body.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(icons) }, lin(W.M, 56))
        body.addView(cap("Что открывать"), lin(W.M, W.WR).apply { topMargin = 16; bottomMargin = 6 })
        val target = t(20f, Ck.accent(), true).apply { text = "Сейчас: " + describe(tile) }
        body.addView(target, lin(W.M, W.WR).apply { bottomMargin = 6 })
        val g = group()
        val choices = try { CkTiles.choices(a) } catch (_: Throwable) { emptyList() }
        for (ch in choices.take(120)) {
            val r = W.row(a, ch.title, if (ch.tile.kind == "app") ch.tile.pkg else null, Ck.icon(a, ch.tile.icon, 0xB3FFFFFF.toInt()), 26, 26) {
                val keepName = name.text.toString().trim().let { it.isNotEmpty() && it != tile.name }
                tile = ch.tile.copy(name = if (keepName) name.text.toString().trim() else ch.tile.name, icon = if (cur != null && tile.icon != cur.icon) tile.icon else ch.tile.icon)
                name.setText(tile.name); markIcon(); target.text = "Сейчас: " + describe(tile)
            }
            g.rows(r)
        }
        body.addView(g, lin(W.M, W.WR))
        s.put(scroll(body), 28, 84, 944, 466)
        val bar = W.hbox(a)
        if (cur != null) bar.addView(W.button(a, "Убрать плитку", 220, 58, danger = true) { all.removeAt(index); CkTiles.save(all); close("tile"); ck.build() }, lin(W.WR, 58))
        // v1.7.2: move without dragging (works across pages)
        if (cur != null) {
            bar.addView(W.button(a, "◀ Влево", 150, 58) { if (index > 0) { close("tile"); tileSheet(ck.moveTile(index, -1)) } }.apply { contentDescription = "Переместить влево"; alpha = if (index > 0) 1f else .35f }, lin(W.WR, 58).apply { leftMargin = 12 })
            bar.addView(W.button(a, "Вправо ▶", 150, 58) { if (index < all.size - 1) { close("tile"); tileSheet(ck.moveTile(index, 1)) } }.apply { contentDescription = "Переместить вправо"; alpha = if (index < all.size - 1) 1f else .35f }, lin(W.WR, 58).apply { leftMargin = 12 })
        }
        bar.addView(View(a), lin(0, 1, 1f))
        bar.addView(W.button(a, "Отмена", 160, 58) { close("tile") }, lin(W.WR, 58).apply { rightMargin = 12 })
        bar.addView(W.button(a, "Сохранить", 200, 58, primary = true) {
            val n = name.text.toString().trim().ifEmpty { tile.name }.take(24)
            val t2 = tile.copy(name = n)
            if (cur == null) all.add(t2) else all[index] = t2
            CkTiles.save(all); close("tile"); ck.build()
        }, lin(W.WR, 58))
        s.put(bar, 28, 564, 944, 58)
    }
    private fun describe(t: CkTiles.Tile) = when (t.kind) {
        "radio" -> "Радио магнитолы"; "nav" -> "Навигатор по умолчанию (${Nav.name(a, Nav.defaultNav(a))})"; "cam" -> "Камера"; "settings" -> "Настройки Car Launcher"; "apps" -> "Все приложения (список с поиском)"
        "music" -> "Музыка"; "app" -> Tiles.label(a, t.pkg); else -> t.kind
    }

    // ================================================================== Settings
    private val SECTIONS = listOf(
        "look" to ("pal" to "Внешний вид"), "tiles" to ("grid" to "Главный экран и плитки"), "music" to ("note" to "Музыка и радио"),
        "car" to ("car" to "Машина и пробег"), "sysnav" to ("sys_home" to "Экран и кнопки ◁ ○ □"), "vol" to ("spk" to "Громкость"), "mem" to ("trash" to "Память и очистка"),
        "upd" to ("cloud2" to "Обновления"), "about" to ("star" to "О программе")
    )
    private var section = "look"
    fun settings(sec: String = section) {
        section = sec
        close("settings")
        val v = FrameLayout(a).apply { isClickable = true }
        // always a blurred photo behind the settings (readable over anything)
        val bgv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        v.addView(bgv, FrameLayout.LayoutParams(-1, -1))
        v.addView(View(a).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        AppRepo.exec.execute {
            val b = try { BgView.sceneBitmap(a, Scenes.current(), true, 60, 640, 360) } catch (_: Throwable) { null }
            AppRepo.main.post { if (b != null) bgv.setImageBitmap(b) }
        }
        val left = W.vbox(a).apply { setPadding(14, 14, 14, 14); background = Panel(0x1AFFFFFF, 0x26FFFFFF, 1f, floatArrayOf(24f, 24f, 24f, 24f), false) }
        left.addView(W.header(a, "gear", "Настройки", null, null), lin(W.M, 54).apply { leftMargin = 8; bottomMargin = 8 })
        for ((id, p) in SECTIONS) {
            val on = id == section
            val r = W.hbox(a).apply { setPadding(14, 0, 10, 0); background = if (on) Panel(Ck.alpha(Ck.accent(), .25f), Ck.accent(), 1.5f, floatArrayOf(16f, 16f, 16f, 16f), false) else null }
            r.addView(Ck.icon(a, p.first, if (on) Color.WHITE else 0xB3FFFFFF.toInt()), lin(26, 26))
            r.addView(t(20f, if (on) Color.WHITE else 0xCCFFFFFF.toInt(), on).apply { text = p.second }, lin(0, W.WR, 1f).apply { leftMargin = 12 })
            r.contentDescription = p.second
            Ck.pressable(r) { settings(id) }
            left.addView(r, lin(W.M, 60).apply { topMargin = 2 })
        }
        v.put(left, 24, 24, 330, 672)
        val title = SECTIONS.first { it.first == section }.second.second
        val right = W.vbox(a)
        right.addView(t(30f, Color.WHITE, true).apply { text = title }, lin(W.M, W.WR).apply { bottomMargin = 14 })
        when (section) {
            "look" -> secLook(right); "tiles" -> secTiles(right); "music" -> secMusic(right); "car" -> secCar(right); "sysnav" -> secSysNav(right)
            "vol" -> secVol(right); "mem" -> secMem(right); "upd" -> secUpd(right); else -> secAbout(right)
        }
        v.put(scroll(right.apply { setPadding(0, 6, 0, 30) }), 384, 30, 780, 666)
        val x = W.round(a, "x", 60, 26) { close("settings") }
        x.background = Panel(0x33FFFFFF, 0x4DFFFFFF, 1.2f, floatArrayOf(30f, 30f, 30f, 30f), false); x.contentDescription = "Закрыть настройки"
        v.put(x, 1190, 24, 60, 60)
        val home = W.round(a, "home", 60, 26) { ck.home() }
        home.background = Panel(0x33FFFFFF, 0x4DFFFFFF, 1.2f, floatArrayOf(30f, 30f, 30f, 30f), false); home.contentDescription = "Главная"
        v.put(home, 1190, 96, 60, 60)
        push("settings", v)
    }
    private fun refreshSettings() { settings(section) }
    private fun add(p: LinearLayout, capText: String?, vararg rows: View) {
        if (capText != null) p.addView(cap(capText), lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 8; leftMargin = 6 })
        val g = group(); g.rows(*rows); p.addView(g, lin(W.M, W.WR))
    }
    private fun segRow(title: String, sub: String?, opts: List<Pair<String, String>>, cur: String, w: Int = 360, pick: (String) -> Unit): View {
        val c = W.vbox(a).apply { setPadding(0, 10, 0, 12) }
        c.addView(t(22f).apply { text = title })
        if (sub != null) c.addView(t(18f, 0x99FFFFFF.toInt(), lines = 2).apply { text = sub }, lin(W.M, W.WR).apply { topMargin = 2 })
        c.addView(W.seg(a, opts.map { it.second }, opts.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { pick(opts[it].first) }, lin(W.M, 54).apply { topMargin = 10 })
        return c
    }

    /** v1.7.8 «Праздничный режим» (approved design v3) */
    private fun festiveRows(p: LinearLayout) {
        fun changed() { ck.ny.apply(); ck.refreshFx(); refreshSettings() }
        val tw = W.vbox(a).apply { setPadding(0, 12, 0, 12) }
        tw.addView(t(22f).apply { text = "Мерцание гирлянды" })
        val sr = W.hbox(a)
        sr.addView(t(18f, 0x99FFFFFF.toInt()).apply { text = "Спокойно" }, lin(W.WR, W.WR))
        sr.addView(W.Slider(a, K.nyTwinkle, 0, 100) { v, _ -> K.nyTwinkle = v; ck.ny.lights.update(); ck.ny.lights.invalidate() }, lin(0, 44, 1f).apply { leftMargin = 16; rightMargin = 16 })
        sr.addView(t(18f, 0x99FFFFFF.toInt()).apply { text = "Ярко" }, lin(W.WR, W.WR))
        tw.addView(sr, lin(W.M, 44).apply { topMargin = 10 })
        add(p, "Праздничный режим  ✦",
            segRow("Праздничный режим", "Провисающие гирлянды, снег на панелях, сугроб и еловые ветки. «Авто» — с 20 декабря по 14 января",
                listOf("off" to "Выкл", "on" to "Вкл", "auto" to "Авто · 20 дек – 14 янв"), K.nyMode) { K.nyMode = it; changed() },
            tw,
            segRow("Огоньки", null, listOf("warm" to "Тёплые", "multi" to "Разноцветные"), K.nyLights) { K.nyLights = it; ck.ny.apply() ; refreshSettings() },
            W.row(a, "Снег и сугроб", "Снежинки, снег на верхних кромках панелей и сугроб внизу", W.Toggle(a, K.nySnow) { K.nySnow = it; changed() }, 76, 42),
            W.row(a, "Еловые ветки", "Веточки по углам — никогда не закрывают текст и кнопки", W.Toggle(a, K.nyFir) { K.nyFir = it; changed() }, 76, 42),
            W.row(a, "Иней по краям", "Морозные узоры у краёв экрана, как на стекле — за интерфейсом, текст не закрывают", W.Toggle(a, K.nyFrost) { K.nyFrost = it; changed() }, 76, 42),
            W.row(a, "Сейчас", Festive.status(), null))
    }

    private fun secLook(p: LinearLayout) {
        festiveRows(p)
        add(p, "Стиль",
            segRow("Оформление", "«Линза» — тёмная приборная панель, «Стекло» — стеклянные карточки на цветном фоне", listOf("C" to "C · Линза", "D" to "D · Стекло"), K.style) { K.style = it; ck.rebuildAll(); refreshSettings() },
            segRow("Размер", "«Крупный» — меньше всего на экране, но крупнее текст и кнопки", listOf("n" to "Обычный", "b" to "Крупный"), if (K.big) "b" else "n") { K.big = it == "b"; ck.rebuildAll(); refreshSettings() })
        val th = W.vbox(a).apply { setPadding(0, 10, 0, 12) }
        th.addView(t(22f).apply { text = "Тема стиля «Стекло»" })
        th.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(themeRowSettings()) }, lin(W.M, 64).apply { topMargin = 10 })
        add(p, "Цвет", th, W.row(a, "Медленные волны", "Фон темы плавно движется (мало нагружает магнитолу)", W.Toggle(a, K.waves) { K.waves = it; ck.bg.refresh() }, 76, 42))
        val modes = listOf("theme" to "Тема", "cover" to "Обложка", "scene" to "Фото", "video" to "Видео")
        add(p, "Фон",
            segRow("Что за интерфейсом", "Тема · обложка трека · фото · видео с флешки (${Videos.DIR})", modes, K.bg) { K.bg = it; ck.applyBackground(); ck.build(); refreshSettings() },
            W.row(a, "Фото природы", Scenes.current().name, null) { gallery() },
            W.row(a, "Видео с флешки", Videos.chosen()?.name ?: "не выбрано · найдено ${Videos.list.size}", null) { gallery(true) },
            W.row(a, "Размытие фото и видео", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42),
            W.row(a, "Яркость фона", "Для всех фонов: темы, обложки, фото и видео", W.Slider(a, K.bright, 30, 100) { v, done -> K.bright = v; ck.refreshFx(); if (done && K.bg == "scene") ck.applyBackground() }, 300, 44),
            glowRow())
        val fx = group(); fxRows(fx) { refreshSettings() }
        p.addView(cap("Дождь и снег"), lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 8; leftMargin = 6 }); p.addView(fx, lin(W.M, W.WR))
    }
    private fun themeRowSettings(): LinearLayout {
        val r = W.hbox(a)
        for (th in Ck.THEMES) {
            val on = th.id == K.theme
            val f = W.hbox(a).apply { gravity = Gravity.CENTER; setPadding(14, 0, 18, 0) }
            f.background = Panel(0x14FFFFFF, if (on) th.accent else 0x33FFFFFF, if (on) 2.5f else 1f, floatArrayOf(30f, 30f, 30f, 30f), false)
            f.addView(View(a).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(th.c1, th.c2)).apply { shape = android.graphics.drawable.GradientDrawable.OVAL } }, lin(34, 34))
            f.addView(t(19f, Color.WHITE, on).apply { text = th.name }, lin(W.WR, W.WR).apply { leftMargin = 10 })
            Ck.pressable(f) { K.theme = th.id; ck.rebuildAll(); refreshSettings() }
            r.addView(f, lin(W.WR, 60).apply { rightMargin = 10 })
        }
        return r
    }
    private fun secTiles(p: LinearLayout) {
        add(p, null,
            W.row(a, "Изменить плитки", "Добавить, убрать, переставить, переименовать, выбрать значок и приложение. Или удерживайте любую плитку", null) { close("settings"); ck.home(); ck.editing = true; ck.build() },
            W.row(a, "Добавить плитку", null, Ck.icon(a, "plus"), 26, 26) { tileSheet(-1) },
            W.row(a, "Вернуть стандартные", "Радио · Навигатор · Камера · Все приложения · Настройки", null) { CkTiles.reset(); ck.build(); ck.showToast("Плитки как при установке") })
        add(p, "Запуск",
            segRow("Какой экран открывать", null, listOf("home" to "Главная", "music" to "Музыка", "last" to "Последний"), K.startScreen) { K.startScreen = it },
            W.row(a, "Навигатор по умолчанию", Nav.name(a, Nav.defaultNav(a)), null) { pickNav() })
    }
    private fun pickNav() {
        val navs = Nav.installedNavs(a)
        if (navs.isEmpty()) { ck.showToast("Навигаторы не найдены"); return }
        val s = popup("nav", 340, 140, 600, 440)
        s.put(W.header(a, "nav", "Навигатор", null) { close("nav") }, 26, 16, 548, 56)
        val g = group()
        for (n in navs) g.rows(W.row(a, (if (n.pkg == Nav.defaultNav(a)) "● " else "") + n.name, n.pkg, null) { Prefs.navDefault = n.pkg; close("nav"); refreshSettings() })
        s.put(scroll(g), 26, 84, 548, 340)
    }
    private fun secMusic(p: LinearLayout) {
        add(p, "Источники",
            W.row(a, "Радио", Radio.freq ?: "включить радио", null) { ck.pickSource("radio") },
            W.row(a, "Яндекс Музыка / плеер", Nav.name(a, Nav.musicApp(a, null)), null) { ck.pickSource("music") },
            W.row(a, "Телефон по Bluetooth", CamApps.btMusic(a)?.let { Tiles.label(a, it) } ?: "не найдено", null) { ck.pickSource("phone") })
        add(p, "Радио",
            W.row(a, "Управление в лаунчере", "«Радио» в источниках включает радио прямо здесь, без экрана приложения. ⏮ ⏭ — поиск станции, удержание — шаг 0.1 МГц, ⏯ — звук выкл/вкл", null),
            W.row(a, "Станции внизу", "Нажатие — включить станцию. Удержание — сохранить в эту кнопку текущую частоту. Сейчас: " + Presets.list().joinToString(" · ") { Presets.mhz(it.khz) }, null),
            W.row(a, "Станции по умолчанию", "Астана: Europa Plus, Русское Радио, Радио Астана, Love Radio, Хит FM, Шалқар", null) { K.radioPresets = ""; ck.build(); refreshSettings(); ck.showToast("Станции — как при установке") },
            W.row(a, "Открыть приложение радио", "Полный экран штатного радио", null) { Radio.open(a) },
            segRow("Кнопка «Радио»", "FM работает без интернета; интернет-радио — RadioDroid" + if (Tiles.installed(a, MediaHub.RADIODROID)) "" else " (не установлено)", listOf("ask" to "Спрашивать", "fm" to "FM", "net" to "Интернет"), K.radioChoice) { K.radioChoice = it; refreshSettings() })
        val pal = listOf("ref" to "Цвета обложки", "vu" to "Зелёный–красный")
        add(p, "Эквалайзер на экране",
            segRow("Цвет полос", "Полосы двигаются под музыку (без записи звука — по такту, если нет разрешения)", pal, K.eqPal) { K.eqPal = it; ck.build() },
            W.row(a, "Доступ к уведомлениям", if (MediaHub.access) "есть — видно названия треков" else "нужен, чтобы видеть трек и управлять плеером", null) {
                AppRepo.startSafe(a, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
            })
    }
    private fun secCar(p: LinearLayout) {
        Trip.checkDay()
        add(p, "Пробег (по GPS)",
            W.row(a, "За день", Trip.km(K.day) + " км · сам обнуляется в 4:00", null),
            W.row(a, "Поездка", Trip.km(K.trip) + " км" + (K.tripStartAt.takeIf { it > 0 }?.let { " · с " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("ru")).format(java.util.Date(it)) } ?: ""), W.button(a, "Сбросить", 150, 50, danger = true) { resetPopup("trip") }, W.WR, 50),
            W.row(a, "Новая поездка сама", "После выключения зажигания или стоянки дольше ${K.tripGapMin} мин «Поездка» начинается с нуля, как только машина тронется. Сбросить вручную — удержание «Поездки»", W.Toggle(a, K.tripAuto) { K.tripAuto = it; refreshSettings() }, 76, 42),
            segRow("Стоянка для новой поездки", null, listOf("30" to "30 мин", "60" to "1 ч", "120" to "2 ч", "240" to "4 ч"), K.tripGapMin.toString()) { K.tripGapMin = it.toInt(); refreshSettings() },
            W.row(a, "Всего", Trip.km(K.total) + " км", W.button(a, "Сбросить", 150, 50, danger = true) { resetPopup("total") }, W.WR, 50))
        add(p, "Скорость",
            W.row(a, "Скорость и пробег считаются по GPS", "Нужно разрешение «Местоположение» — лучше «Разрешить всегда», чтобы пробег считался и под навигатором. Может отличаться от спидометра на 2–5 %", W.button(a, "Разрешение", 170, 50) { a.requestPerms() }, W.WR, 50))
        add(p, "Камера",
            W.row(a, "Камера / регистратор", CamApps.dvr(a)?.let { Tiles.label(a, it) } ?: "приложение камеры магнитолы", null) { CamApps.openCamera(a) })
    }
    private var volInfo: TextView? = null
    private fun secVol(p: LinearLayout) {
        val cur = Prefs.volMethod
        add(p, "Кнопки −  + на экране",
            segRow("Шаг одного нажатия", null, listOf("1" to "1", "2" to "2", "3" to "3"), K.volStep.toString()) { K.volStep = it.toInt() },
            W.row(a, "Показывать шкалу громкости", "Своя шкала сверху экрана после нажатия", W.Toggle(a, K.volOverlay) { K.volOverlay = it }, 76, 42),
            W.row(a, "Способ", if (cur == "auto") "Авто (сначала сервис магнитолы, потом Android)" else Volume.method(cur)?.title ?: cur, null))
        val info = t(19f, 0xCCFFFFFF.toInt(), lines = 3).apply { text = Volume.readout(a) }; volInfo = info
        p.addView(cap("Проверка громкости"), lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 8; leftMargin = 6 })
        p.addView(t(18f, 0x99FFFFFF.toInt(), lines = 4).apply {
            text = "Включите музыку или радио. Нажимайте − и + у каждого способа и слушайте, меняется ли звук. Тот, что работает, — «Выбрать». Руль и штатные кнопки не затрагиваются."
        }, lin(W.M, W.WR).apply { leftMargin = 6; bottomMargin = 8 })
        p.addView(info, lin(W.M, W.WR).apply { leftMargin = 6; bottomMargin = 8 })
        val g = group()
        val methods = listOf(Volume.Method("auto", 0, "Авто", "Сначала сервис магнитолы TEYES/FYT, если он не отвечает — Android")) + Volume.METHODS
        for (m in methods) {
            val ctl = W.hbox(a)
            ctl.addView(W.button(a, "−", 64, 54) { if (m.id == "auto") Volume.step(a, false, "auto") else Volume.run(a, m.id, false); a.window.decorView.postDelayed({ info.text = Volume.readout(a) }, 250) }, lin(W.WR, 54))
            ctl.addView(W.button(a, "+", 64, 54) { if (m.id == "auto") Volume.step(a, true, "auto") else Volume.run(a, m.id, true); a.window.decorView.postDelayed({ info.text = Volume.readout(a) }, 250) }, lin(W.WR, 54).apply { leftMargin = 8 })
            val on = m.id == cur
            ctl.addView(W.button(a, if (on) "✓ Выбран" else "Выбрать", 150, 54, primary = on) { Prefs.volMethod = m.id; ck.showToast("Громкость: «${m.title}»"); refreshSettings() }, lin(W.WR, 54).apply { leftMargin = 12 })
            g.rows(W.row(a, (if (m.n > 0) "${m.n}. " else "") + m.title, m.desc, ctl))
        }
        p.addView(g, lin(W.M, W.WR))
    }
    private fun secMem(p: LinearLayout) {
        add(p, null, W.row(a, "Открыть «Память и очистка»", "Свободное место, самые большие приложения, очистка кэша через системные окна", null) { clean() },
            W.row(a, "Папки и файлы установки", "Самые большие папки во внутренней памяти и забытые .apk", null) { folders() },
            W.row(a, "Предупреждение «Мало места»", "Полоска появляется, когда свободно меньше 10 %. Скрытая — вернётся через 3 дня", W.button(a, "Показать снова", 200, 50) { K.lowHideUntil = 0; ck.checkLow() }, W.WR, 50))
    }
    private fun secUpd(p: LinearLayout) {
        add(p, null,
            W.row(a, "Версия", Updater.installedName(a) + " (" + Updater.installedCode(a) + ")", null),
            W.row(a, "Проверить обновления", "Скачивается с GitHub, проверяется подпись и контрольная сумма. После установки файл обновления удаляется", null) { Updater.check(a) },
            W.row(a, "С телефона по Wi‑Fi", "Передать APK с телефона на магнитолу (QR-код, та же сеть Wi‑Fi)", null) { try { UploadDialog(a) {}.show() } catch (_: Throwable) { ck.showToast("Не удалось открыть") } })
    }
    /** v1.7.5: the system bar of the unit, ◁ ○ □ in the launcher and floating over other apps */
    private fun secSysNav(p: LinearLayout) {
        val ma = a as? MainActivity
        add(p, "Системная панель сверху",
            W.row(a, "Скрывать системную панель в лаунчере", "Только в Car Launcher. Показать на время — провести от верхнего края вниз. В других приложениях панель остаётся",
                W.Toggle(a, K.hideBar) { K.hideBar = it; ma?.applyBars(); ck.root.requestApplyInsets(); ck.root.postDelayed({ refreshSettings() }, 600) }, 76, 42),
            W.row(a, "Сейчас", ma?.screenInfo() ?: "", null))
        if (!K.hideBar) add(p, null, segRow("Место под панель сверху", "«Авто» — как сообщает магнитола. Если интерфейс заходит под панель или над ним пустая полоса — выберите вручную",
            listOf("-1" to "Авто", "0" to "0", "40" to "40", "60" to "60", "80" to "80"), K.topPad.toString()) { K.topPad = it.toInt(); ck.root.requestApplyInsets(); ck.root.requestLayout(); ck.root.postDelayed({ refreshSettings() }, 300) })
        add(p, "Кнопки ◁ ○ □ в лаунчере",
            segRow("Где показывать", "Внизу между громкостью и вкладками. Слева — ближе к водителю. □ «Недавние» появится, когда включена служба (ниже)",
                listOf("left" to "Слева", "right" to "Справа", "off" to "Не показывать"), K.navPos) { K.navPos = it; ck.build(); refreshSettings() })
        val on = NavAccess.enabled(a)
        add(p, "Поверх других приложений",
            W.row(a, "Служба «Car Launcher — кнопки навигации»", if (on) "Включена. Нужна для □ «Недавние», «Назад» в других приложениях и плавающих кнопок" else "Выключена. Нажмите, чтобы включить (Спец. возможности Android)", null) { navHelp() },
            W.row(a, "Плавающие кнопки ◁ ○ □", "Поверх навигатора, музыки и др. Перетаскиваются за полоску слева, место запоминается",
                W.Toggle(a, K.floatOn) { K.floatOn = it; if (it && !NavAccess.enabled(a)) navHelp(); NavAccess.refresh() }, 76, 42),
            segRow("Размер плавающих кнопок", null, listOf("0" to "Маленькие", "1" to "Средние", "2" to "Крупные"), K.floatSize.toString()) { K.floatSize = it.toInt(); NavAccess.refresh() },
            W.row(a, "Кнопки перемотки ±${K.seekStep} с", if (MediaHub.listenerEnabled(a)) "«−${K.seekStep}» и «+${K.seekStep}» на плавающих кнопках, пока в открытом приложении играет видео или музыка (SmartTube, YouTube, плееры)"
                else "Для точной перемотки нужен «Доступ к музыке» — нажмите. Без него кнопки работают только в видеоприложениях и шагом самого плеера",
                W.Toggle(a, K.seekOn) { K.seekOn = it; NavAccess.refresh() }, 76, 42) { if (!MediaHub.listenerEnabled(a)) AppRepo.startSafe(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS)) },
            segRow("Шаг перемотки", null, listOf("10" to "10 с", "15" to "15 с", "30" to "30 с"), K.seekStep.toString()) { K.seekStep = it.toInt(); NavAccess.refresh(); refreshSettings() },
            W.row(a, "Приглушать через 4 секунды", "Кнопки становятся почти прозрачными, касание — снова яркие", W.Toggle(a, K.floatDim) { K.floatDim = it; NavAccess.refresh() }, 76, 42),
            W.row(a, "Вернуть кнопки в правый нижний угол", null, null) { K.floatX = -1; K.floatY = -1; NavAccess.refresh(); ck.showToast("Кнопки — в правом нижнем углу") })
    }
    /** v1.7.5: how to switch on the accessibility service (RU, step by step) */
    fun navHelp() {
        val s = popup("navhelp", 300, 60, 680, 600)
        s.put(W.header(a, "sys_home", "Кнопки навигации", null) { close("navhelp") }, 28, 14, 624, 56)
        s.put(t(19f, 0xE6FFFFFF.toInt(), lines = 20).apply { text = NavKeys.HOW }, 28, 80, 624, 420)
        s.put(W.button(a, "Открыть настройки", 300, 64, primary = true) { NavKeys.openSettings(a) }, 28, 514, 300, 64)
        s.put(W.button(a, "Позже", 280, 64) { close("navhelp") }, 352, 514, 300, 64)
    }

    private fun secAbout(p: LinearLayout) {
        add(p, null,
            W.row(a, "Car Launcher " + Updater.installedName(a), "Главный экран для TEYES / FYT. Не перехватывает кнопки руля и не меняет настройки магнитолы", null),)
        add(p, "Вернуть штатный экран", *stockRows().toTypedArray())
        val log = CrashLog.text(a)
        add(p, "Журнал сбоев", W.row(a, "Журнал сбоев", if (log.isEmpty()) "Сбоев не было" else "Записей: ${log.split("=== ").size - 1}. Копия: Android/data/${a.packageName}/files/crash.log", null) { crashLog() })
        val cr = W.vbox(a).apply { setPadding(0, 10, 0, 10) }
        cr.addView(t(17f, 0xB3FFFFFF.toInt(), lines = 60).apply {
            text = Scenes.ALL.joinToString("\n") { "${it.name} — ${it.author} · ${it.licence}" } + "\n\nЛайм-кроссовер: иллюстрация по фото Suzuki S-Cross. Шрифты Orbitron и Jura — SIL Open Font License. Погода: Open-Meteo.com (CC BY 4.0)."
        })
        add(p, "Фото и авторы", cr)
    }
    fun crashLog() {
        val s = popup("crash", 90, 40, 1100, 640)
        s.put(W.header(a, "x", "Журнал сбоев", null) { close("crash") }, 28, 16, 1044, 56)
        val log = CrashLog.text(a)
        val tv = Ck.text(a, 15f, 0xCCFFFFFF.toInt(), android.graphics.Typeface.MONOSPACE, 4000).apply {
            text = if (log.isEmpty()) "Сбоев не было." else log.takeLast(20000); setTextIsSelectable(false)
        }
        s.put(scroll(tv), 28, 84, 1044, 470)
        s.put(W.button(a, "Очистить журнал", 220, 56, danger = true) { CrashLog.clear(a); close("crash"); ck.showToast("Журнал очищен") }, 28, 568, 220, 56)
    }
    private fun stockRows(): List<View> {
        val out = ArrayList<View>()
        out.add(W.row(a, "Выбрать главный экран", "Системный выбор «Главный экран» — выберите штатный лаунчер", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        })
        val homes = try { a.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.activityInfo.packageName != a.packageName && it.activityInfo.name != "com.android.settings.FallbackHome" } } catch (_: Throwable) { emptyList() }
        for (ri in homes) {
            val label = ri.loadLabel(a.packageManager).toString()
            out.add(W.row(a, "Открыть «$label»", ri.activityInfo.packageName, null) {
                AppRepo.startSafe(a, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(ri.activityInfo.packageName, ri.activityInfo.name))
            })
        }
        out.add(W.row(a, "Сбросить «по умолчанию»", "Страница Car Launcher → «Открывать по умолчанию» → «Очистить»", null) {
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)), Intent(Settings.ACTION_SETTINGS))
        })
        return out
    }
    /** v1.7.7 (replaces the old 1.7.0 cleanup screen): biggest folders of the internal memory + forgotten .apk files */
    fun folders() {
        val s = popup("folders", 120, 40, 1040, 640)
        s.put(W.header(a, "trash", "Папки и файлы установки", null) { close("folders") }, 28, 16, 984, 56)
        val body = W.vbox(a)
        val status = t(18f, 0x99FFFFFF.toInt(), lines = 2).apply { text = "Считаю размеры папок…" }
        body.addView(status, lin(W.M, W.WR))
        val g = group(); body.addView(g, lin(W.M, W.WR).apply { topMargin = 12 })
        val apkBox = W.vbox(a); body.addView(apkBox, lin(W.M, W.WR).apply { topMargin = 12 })
        body.addView(t(16f, 0x80FFFFFF.toInt(), lines = 3).apply { text = "Car Launcher сам файлы не удаляет — откройте файловый менеджер и удалите ненужное там." }, lin(W.M, W.WR).apply { topMargin = 12 })
        s.put(scroll(body), 28, 84, 984, 540)
        val app = a.applicationContext
        AppRepo.exec.execute {
            val list = try { Cleanup.folders() } catch (_: Throwable) { emptyList() }
            val apks = try { Cleanup.apks() } catch (_: Throwable) { emptyList() }
            AppRepo.main.post {
                status.text = if (list.isEmpty()) "Нет доступа к файлам — Настройки Android → Приложения → Car Launcher → Разрешения → Файлы" else "Самые большие папки во внутренней памяти"
                for (f in list.take(12)) g.addView(W.row(a, f.name, (if (f.hint.isNotEmpty()) f.hint + " · " else "") + Storage.gb(f.bytes), null), lin(W.M, W.WR))
                if (apks.isNotEmpty()) apkBox.addView(t(18f, 0xFF30D158.toInt(), lines = 4).apply {
                    text = "Файлы установки (.apk): ${apks.size} шт., ${Storage.gb(apks.sumOf { it.length() })} — " + apks.take(4).joinToString { it.name } + ". После установки их можно удалить"
                }, lin(W.M, W.WR))
                val names = setOf("файлы", "files", "файловый менеджер", "file manager", "total commander", "mixplorer", "проводник")
                AppRepo.apps?.firstOrNull { it.label.trim().lowercase() in names }?.pkg?.let { fm ->
                    apkBox.addView(W.button(a, "Открыть «${Tiles.label(app, fm)}»", 360, 56, primary = true) { AppRepo.launch(a, fm) }, lin(W.WR, 56).apply { topMargin = 12 })
                }
            }
        }
    }

    // ================================================================== «Память и очистка»
    private var cleanGen = 0
    private var cleanScroll: ScrollView? = null
    fun clean(keepScroll: Boolean = false) {
        val y = if (keepScroll) cleanScroll?.scrollY ?: 0 else 0
        val s = popup("clean", 40, 30, 1200, 660)
        s.put(W.header(a, "trash", "Память и очистка", null) { close("clean") }, 28, 16, 1144, 56)
        val body = W.vbox(a)
        val st = W.vbox(a)
        add(body, null, st)
        val btns = W.hbox(a)
        btns.addView(W.button(a, "Системная очистка", 240, 56, primary = true) {
            AppRepo.startSafe(a, Intent(android.os.storage.StorageManager.ACTION_MANAGE_STORAGE), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }, lin(W.WR, 56).apply { rightMargin = 12 })
        btns.addView(W.button(a, "Хранилище", 180, 56) { AppRepo.startSafe(a, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }, lin(W.WR, 56).apply { rightMargin = 12 })
        btns.addView(W.button(a, "Папки и APK…", 180, 56) { folders() }, lin(W.WR, 56))
        body.addView(btns, lin(W.M, W.WR).apply { topMargin = 14 })
        val how = group()
        how.rows(W.row(a, "Как очистить кэш", "Android разрешает чистить кэш только на странице приложения. Нажмите «Очистить кэш» у приложения → откроется его страница → «Хранилище» (или «Память») → «Очистить кэш». Вернитесь назад — список обновится сам", null))
        body.addView(how, lin(W.M, W.WR).apply { topMargin = 14 })
        val status = t(19f, 0xB3FFFFFF.toInt(), lines = 2).apply { text = "Считаю размеры…" }
        body.addView(status, lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 6 })
        val apps = group()
        body.addView(apps, lin(W.M, W.WR))
        body.addView(t(16f, 0x80FFFFFF.toInt(), lines = 4).apply { text = "Car Launcher сам ничего не удаляет: «Очистить кэш» открывает страницу приложения, «Удалить» — системное окно с подтверждением, встроенные приложения можно только «Отключить…» на их странице. Отмеченные «не трогать» нужны магнитоле." }, lin(W.M, W.WR).apply { topMargin = 12 })
        val sv = scroll(body); cleanScroll = sv
        s.put(sv, 28, 84, 1144, 560)
        if (y > 0) sv.post { sv.scrollTo(0, y) }
        val g = ++cleanGen
        val app = a.applicationContext
        AppRepo.exec.execute {
            val vols = try { Storage.all(app) } catch (_: Throwable) { emptyList() }
            AppRepo.main.post { if (g == cleanGen) for (v in vols) st.addView(storageRow(v), lin(W.M, W.WR)) }
            val list = try { Cleanup.apps(app) } catch (_: Throwable) { emptyList() }
            AppRepo.main.post {
                if (g != cleanGen) return@post
                status.text = "${list.size} приложений · ${Storage.gb(list.sumOf { it.total })}" + if (list.any { it.exact }) " · кэш ${Storage.gb(list.sumOf { it.cache })}" else " · только размер установки"
                if (!Cleanup.hasUsageAccess(a)) apps.rows(W.row(a, "Показать кэш и данные", "Разрешите «Доступ к истории использования» — так Android даёт видеть размеры приложений", W.button(a, "Разрешить", 160, 50) {
                    AppRepo.startSafe(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + a.packageName)), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }, W.WR, 50))
                for (x in list.take(30)) apps.rows(appRow(x))
            }
        }
    }
    private fun storageRow(v: Storage.Info): View {
        val c = W.vbox(a).apply { setPadding(0, 10, 0, 12) }
        val r = W.hbox(a)
        r.addView(t(21f, Color.WHITE, true).apply { text = v.name }, lin(0, W.WR, 1f))
        r.addView(t(19f, if (v.low) 0xFFFF6B5E.toInt() else 0xB3FFFFFF.toInt()).apply { text = "свободно ${Storage.gb(v.free)} из ${Storage.gb(v.total)}" })
        c.addView(r)
        val bar = FrameLayout(a).apply { background = Panel(0x26FFFFFF, 0, 0f, floatArrayOf(6f, 6f, 6f, 6f), false) }
        val used = 1f - v.freeFraction
        bar.addView(View(a).apply { background = Panel(if (v.low) 0xFFFF453A.toInt() else Ck.accent(), 0, 0f, floatArrayOf(6f, 6f, 6f, 6f), false) }, FrameLayout.LayoutParams(Math.round(1000 * used), -1))
        c.addView(bar, lin(1000, 12).apply { topMargin = 8 })
        return c
    }
    private fun appRow(s: Cleanup.AppSize): View {
        val iv = ImageView(a); AppRepo.bindIcon(a, s.pkg, null, iv)
        val r = W.hbox(a).apply { setPadding(0, 8, 0, 8) }
        r.addView(iv, lin(48, 48).apply { rightMargin = 14 })
        val col = W.vbox(a)
        val tl = W.hbox(a)
        tl.addView(t(21f).apply { text = s.label })
        tl.addView(t(15f, s.mark.color, true).apply { text = s.mark.label; setPadding(10, 2, 10, 2); background = Panel(Ck.alpha(s.mark.color, .16f), 0, 0f, floatArrayOf(12f, 12f, 12f, 12f), false) }, lin(W.WR, W.WR).apply { leftMargin = 10 })
        col.addView(tl)
        col.addView(t(16f, 0x99FFFFFF.toInt()).apply { text = Storage.gb(s.total) + if (s.exact) " · данные ${Storage.gb(s.data)} · кэш ${Storage.gb(s.cache)}" else " · размер установки" })
        r.addView(col, lin(0, W.WR, 1f))
        r.addView(W.button(a, "Очистить кэш", 170, 50) {
            ck.showToast("«${s.label}»: «Хранилище» → «Очистить кэш», затем назад")
            AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
        }, lin(W.WR, 50).apply { leftMargin = 10 })
        if (s.mark != Cleanup.Mark.KEEP && s.mark != Cleanup.Mark.SELF) {
            if (!s.system) r.addView(W.button(a, "Удалить", 130, 50, danger = true) {
                AppRepo.startSafe(a, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg)), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
            }, lin(W.WR, 50).apply { leftMargin = 10 })
            // v1.7.2: built into the unit → cannot be removed (uninstalling only rolls back its updates, it stays in the list) → «Отключить»
            else r.addView(W.button(a, "Отключить…", 150, 50, danger = true) {
                ck.showToast("«${s.label}» встроено в магнитолу: удалить нельзя — на его странице нажмите «Отключить»")
                AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
            }, lin(W.WR, 50).apply { leftMargin = 10 })
        }
        return r
    }
}
