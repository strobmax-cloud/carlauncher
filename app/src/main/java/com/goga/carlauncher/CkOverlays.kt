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
        val s = popup("bg", 380, 90, 860, 560, 0x40)
        s.put(W.header(a, "layers", "Фон", null) { close("bg") }, 28, 18, 804, 56)
        val body = W.vbox(a)
        val modes = listOf("theme" to "Тема", "cover" to "Обложка", "scene" to "Природа", "video" to "Видео")
        body.addView(W.seg(a, modes.map { it.second }, modes.indexOfFirst { it.first == K.bg }.coerceAtLeast(0)) { i ->
            val m = modes[i].first
            if (m == "video" && Videos.list.isEmpty()) Videos.scanAsync(a) { if (Videos.list.isEmpty()) ck.showToast("На флешке нет папки ${Videos.DIR} с .mp4") ; ck.applyBackground() }
            K.bg = m; ck.applyBackground(); ck.build(); close("bg"); bgPanel()
        }, lin(W.M, 58))
        if (!Ck.isC) {
            body.addView(cap("Тема (стиль «Стекло»)"), lin(W.M, W.WR).apply { topMargin = 18; bottomMargin = 8 })
            body.addView(themeRow(), lin(W.M, 70))
        }
        val g = group()
        g.rows(
            W.row(a, "Волны", "Медленное движение фона темы", W.Toggle(a, K.waves) { K.waves = it; ck.bg.refresh() }, 76, 42),
            W.row(a, "Размытие фото и видео", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42),
            W.row(a, "Яркость фона", null, W.Slider(a, K.bright, 30, 100) { v, done -> if (done) { K.bright = v; ck.applyBackground() } }, 300, 44),
            W.row(a, "Дождь и снег", "Капли и снежинки на фото по погоде", W.Toggle(a, K.particles) { K.particles = it; ck.applyBackground() }, 76, 42)
        )
        body.addView(g, lin(W.M, W.WR).apply { topMargin = 16 })
        val b = W.hbox(a)
        b.addView(W.button(a, "Фото природы…", 220, 56) { gallery() }, lin(W.WR, 56).apply { rightMargin = 12 })
        b.addView(W.button(a, "Видео с флешки…", 220, 56) { gallery(video = true) }, lin(W.WR, 56))
        body.addView(b, lin(W.M, W.WR).apply { topMargin = 16 })
        s.put(scroll(body), 28, 86, 804, 456)
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
        s.put(W.header(a, if (video) "folder" else "img", if (video) "Видео · флешка" else "Природа и погода", null) { close("gallery") }, 28, 16, 1104, 56)
        val body = W.vbox(a)
        if (!video) {
            val auto = K.scene == "auto"
            body.addView(W.row(a, "Авто по погоде и времени суток", "Сейчас: ${Scenes.current().name}", W.Toggle(a, auto) {
                K.scene = if (it) "auto" else Scenes.current().id; K.bg = "scene"; ck.applyBackground(); ck.build(); close("gallery"); gallery()
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
                Ck.pressable(f) { K.scene = sc.id; K.bg = "scene"; ck.applyBackground(); ck.build(); close("gallery") }
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
            b.addView(W.button(a, "Обновить список", 220, 54) { Videos.scanAsync(a) { close("gallery"); gallery(true) } }, lin(W.WR, 54))
            if (list.isEmpty()) b.addView(W.button(a, "Разрешить доступ к файлам", 300, 54) { a.requestPerms() }, lin(W.WR, 54).apply { leftMargin = 12 })
            body.addView(b, lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 10 })
            if (list.isEmpty()) body.addView(t(22f, Color.WHITE, true, 2).apply { text = "Видео не найдено.\nПроверено: ${Videos.roots(a).joinToString { it.second.path }.take(200)}" }, lin(W.M, W.WR).apply { topMargin = 10 })
            val g = group()
            for ((grp, clips) in list.groupBy { it.group }) {
                g.addView(cap(grp), lin(W.M, W.WR).apply { topMargin = 12; bottomMargin = 4 })
                for (cl in clips) {
                    val on = K.bg == "video" && Videos.chosen()?.path == cl.file.path
                    g.addView(W.row(a, (if (on) "● " else "") + cl.name, cl.volume + " · " + Storage.gb(cl.file.length()), null) {
                        K.video = cl.file.path; K.bg = "video"; ck.applyBackground(); ck.build(); close("gallery")
                    }, lin(W.M, W.WR))
                }
            }
            if (list.isNotEmpty()) body.addView(g, lin(W.M, W.WR))
        }
        s.put(scroll(body), 28, 84, 1104, 540)
    }
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
        b.addView(W.button(a, "Фон «Природа»", 200, 54, primary = K.bg != "scene") { K.bg = "scene"; K.scene = "auto"; ck.applyBackground(); ck.build(); close("wx") }, lin(W.WR, 54))
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
        "radio" -> "Радио магнитолы"; "nav" -> "Навигатор по умолчанию (${Nav.name(a, Nav.defaultNav(a))})"; "cam" -> "Камера"; "settings" -> "Настройки Car Launcher"
        "music" -> "Музыка"; "app" -> Tiles.label(a, t.pkg); else -> t.kind
    }

    // ================================================================== Settings
    private val SECTIONS = listOf(
        "look" to ("pal" to "Внешний вид"), "tiles" to ("grid" to "Главный экран и плитки"), "music" to ("note" to "Музыка и радио"),
        "car" to ("car" to "Машина и пробег"), "vol" to ("spk" to "Громкость"), "mem" to ("trash" to "Память и очистка"),
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
            "look" -> secLook(right); "tiles" -> secTiles(right); "music" -> secMusic(right); "car" -> secCar(right)
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

    private fun secLook(p: LinearLayout) {
        add(p, "Стиль",
            segRow("Оформление", "«Линза» — тёмная приборная панель, «Стекло» — стеклянные карточки на цветном фоне", listOf("C" to "C · Линза", "D" to "D · Стекло"), K.style) { K.style = it; ck.rebuildAll(); refreshSettings() },
            segRow("Размер", "«Крупный» — меньше всего на экране, но крупнее текст и кнопки", listOf("n" to "Обычный", "b" to "Крупный"), if (K.big) "b" else "n") { K.big = it == "b"; ck.rebuildAll(); refreshSettings() })
        val th = W.vbox(a).apply { setPadding(0, 10, 0, 12) }
        th.addView(t(22f).apply { text = "Тема стиля «Стекло»" })
        th.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(themeRowSettings()) }, lin(W.M, 64).apply { topMargin = 10 })
        add(p, "Цвет", th, W.row(a, "Медленные волны", "Фон темы плавно движется (мало нагружает магнитолу)", W.Toggle(a, K.waves) { K.waves = it; ck.bg.refresh() }, 76, 42))
        val modes = listOf("theme" to "Тема", "cover" to "Обложка", "scene" to "Природа", "video" to "Видео")
        add(p, "Фон",
            segRow("Что за интерфейсом", "Тема · цвет обложки трека · фото природы по погоде · видео с флешки (${Videos.DIR})", modes, K.bg) { K.bg = it; ck.applyBackground(); ck.build(); refreshSettings() },
            W.row(a, "Фото природы", Scenes.current().name, null) { gallery() },
            W.row(a, "Видео с флешки", Videos.chosen()?.name ?: "не выбрано · найдено ${Videos.list.size}", null) { gallery(true) },
            W.row(a, "Размытие фото и видео", null, W.Toggle(a, K.blur) { K.blur = it; ck.applyBackground() }, 76, 42),
            W.row(a, "Яркость фона", null, W.Slider(a, K.bright, 30, 100) { v, done -> if (done) { K.bright = v; ck.applyBackground() } }, 300, 44),
            W.row(a, "Дождь и снег на фото", "Лёгкая анимация по погоде", W.Toggle(a, K.particles) { K.particles = it; ck.applyBackground() }, 76, 42))
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
            W.row(a, "Вернуть стандартные", "Радио · Навигатор · Камера · Настройки", null) { CkTiles.reset(); ck.build(); ck.showToast("Плитки как при установке") })
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
            W.row(a, "Радио", Radio.freq ?: "открыть штатное радио", null) { ck.pickSource("radio") },
            W.row(a, "Яндекс Музыка / плеер", Nav.name(a, Nav.musicApp(a, null)), null) { ck.pickSource("music") },
            W.row(a, "Телефон по Bluetooth", CamApps.btMusic(a)?.let { Tiles.label(a, it) } ?: "не найдено", null) { ck.pickSource("phone") })
        add(p, "Радио",
            W.row(a, "Кнопки ⏮ ⏭ и пресеты", "Нажатие — следующая / предыдущая станция из памяти радио, удержание ⏮ ⏭ — поиск. Частоты станций настраиваются в самом радио", null))
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
            W.row(a, "Поездка", Trip.km(K.trip) + " км", W.button(a, "Сбросить", 150, 50, danger = true) { resetPopup("trip") }, W.WR, 50),
            W.row(a, "Всего", Trip.km(K.total) + " км", W.button(a, "Сбросить", 150, 50, danger = true) { resetPopup("total") }, W.WR, 50))
        add(p, "Скорость",
            W.row(a, "Скорость и пробег считаются по GPS", "Нужно разрешение «Местоположение». Шкала до 160 км/ч. Может отличаться от спидометра на 2–5 %", W.button(a, "Разрешение", 170, 50) { a.requestPerms() }, W.WR, 50))
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
            W.row(a, "Подробно: папки и APK", "Старый экран очистки 1.7.0 (папки во внутренней памяти, файлы установки)", null) { classic("clean") },
            W.row(a, "Предупреждение «Мало места»", "Полоска появляется, когда свободно меньше 10 %. Скрытая — вернётся через 3 дня", W.button(a, "Показать снова", 200, 50) { K.lowHideUntil = 0; ck.checkLow() }, W.WR, 50))
    }
    private fun secUpd(p: LinearLayout) {
        add(p, null,
            W.row(a, "Версия", Updater.installedName(a) + " (" + Updater.installedCode(a) + ")", null),
            W.row(a, "Проверить обновления", "Скачивается с GitHub, проверяется подпись и контрольная сумма. После установки файл обновления удаляется", null) { Updater.check(a) },
            W.row(a, "С телефона по Wi‑Fi", "Передать APK с телефона на магнитолу", null) { classic("upload") })
    }
    private fun secAbout(p: LinearLayout) {
        add(p, null,
            W.row(a, "Car Launcher " + Updater.installedName(a), "Главный экран для TEYES / FYT. Не перехватывает кнопки руля и не меняет настройки магнитолы", null),
            W.row(a, "Классический экран 1.7.0", "Прежний интерфейс (на случай, если что-то не работает в новом)", null) { classic("") })
        add(p, "Вернуть штатный экран", *stockRows().toTypedArray())
        val cr = W.vbox(a).apply { setPadding(0, 10, 0, 10) }
        cr.addView(t(17f, 0xB3FFFFFF.toInt(), lines = 60).apply {
            text = Scenes.ALL.joinToString("\n") { "${it.name} — ${it.author} · ${it.licence}" } + "\n\nЛайм-кроссовер: иллюстрация по фото Suzuki S-Cross. Шрифты Orbitron и Jura — SIL Open Font License. Погода: Open-Meteo.com (CC BY 4.0)."
        })
        add(p, "Фото и авторы", cr)
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
    private fun classic(what: String) {
        try { a.startActivity(Intent(a, ClassicActivity::class.java).putExtra(ClassicActivity.EXTRA_OPEN, what)) } catch (_: Throwable) { ck.showToast("Не удалось открыть") }
    }

    // ================================================================== «Память и очистка»
    private var cleanGen = 0
    fun clean() {
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
        btns.addView(W.button(a, "Папки и APK…", 180, 56) { classic("clean") }, lin(W.WR, 56))
        body.addView(btns, lin(W.M, W.WR).apply { topMargin = 14 })
        val status = t(19f, 0xB3FFFFFF.toInt(), lines = 2).apply { text = "Считаю размеры…" }
        body.addView(status, lin(W.M, W.WR).apply { topMargin = 14; bottomMargin = 6 })
        val apps = group()
        body.addView(apps, lin(W.M, W.WR))
        body.addView(t(16f, 0x80FFFFFF.toInt(), lines = 4).apply { text = "Car Launcher сам ничего не удаляет: «Кэш» открывает страницу приложения с кнопкой «Очистить кэш», «Удалить» — системное окно с подтверждением. Отмеченные «не трогать» нужны магнитоле." }, lin(W.M, W.WR).apply { topMargin = 12 })
        s.put(scroll(body), 28, 84, 1144, 560)
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
        r.addView(W.button(a, "Кэш", 110, 50) { AppRepo.startSafe(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg))) }, lin(W.WR, 50).apply { leftMargin = 10 })
        if (s.mark != Cleanup.Mark.KEEP && s.mark != Cleanup.Mark.SELF && (!s.system || s.updated))
            r.addView(W.button(a, if (s.system) "Удалить обновл." else "Удалить", 130, 50, danger = true) {
                AppRepo.startSafe(a, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg)), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg)))
            }, lin(W.WR, 50).apply { leftMargin = 10 })
        return r
    }
}
