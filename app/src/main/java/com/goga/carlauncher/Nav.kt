package com.goga.carlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast

/** Navigator / music app choice, «Навигатор + музыка» (split screen) and mini-player auto-start. */
object Nav {
    class App(val pkg: String, val name: String, val short: String)
    val NAVS = listOf(
        App("ru.dublgis.dgismobile", "2ГИС", "2ГИС"),
        App("ru.yandex.yandexnavi", "Яндекс Навигатор", "Я.Навигатор"),
        App("ru.yandex.yandexmaps", "Яндекс Карты", "Я.Карты")
    )
    val MUSIC = listOf(
        App("ru.yandex.music", "Яндекс Музыка", "Я.Музыка"),
        App("com.google.android.youtube", "YouTube", "YouTube"),
        App("org.schabi.newpipe", "NewPipe", "NewPipe"),
        App("com.apple.android.music", "Apple Music", "Apple Music")
    )
    fun isNav(pkg: String?) = pkg != null && (NAVS.any { it.pkg == pkg } || pkg == "com.google.android.apps.maps")

    fun installedNavs(c: Context) = NAVS.filter { Tiles.installed(c, it.pkg) }
    fun installedMusic(c: Context) = MUSIC.filter { Tiles.installed(c, it.pkg) }

    /** Default navigator: the user's choice → tile «Навигатор» → first installed. */
    fun defaultNav(c: Context): String? {
        Prefs.navDefault.takeIf { it.isNotEmpty() && Tiles.installed(c, it) }?.let { return it }
        Tiles.resolve(c, 0).pkg?.let { return it }
        return installedNavs(c).firstOrNull()?.pkg
    }

    fun name(c: Context, pkg: String?): String = pkg?.let { p -> (NAVS + MUSIC).firstOrNull { it.pkg == p }?.name ?: Tiles.label(c, p) } ?: "не установлен"
    fun short(c: Context, pkg: String): String = (NAVS + MUSIC).firstOrNull { it.pkg == pkg }?.short ?: Tiles.label(c, pkg)

    /** Music app for split screen: user's choice → app that is playing now → tile «Я.Музыка» → first installed. */
    fun musicApp(c: Context, playing: String?): String? {
        Prefs.splitMusic.takeIf { it.isNotEmpty() && Tiles.installed(c, it) }?.let { return it }
        if (playing != null && Tiles.installed(c, playing)) return playing
        Tiles.resolve(c, 4).pkg?.let { return it }
        return installedMusic(c).firstOrNull()?.pkg
    }

    /**
     * v1.7.0: the ONE «Навигатор» — the default navigator with the music built in (Prefs.navMode):
     * "mini" — navigator + floating mini player (if «Поверх других окон» is allowed), "split" — next to the music if the
     * unit can split the screen, "only" — just the navigator. Prefs.navMusic: resume the music if nothing plays.
     */
    fun open(a: Activity, pkg: String?) {
        val p = pkg ?: defaultNav(a)
        if (p == null) { Toast.makeText(a, "Навигатор не установлен — выберите в Настройках", Toast.LENGTH_LONG).show(); return }
        if (Prefs.navMode == "split") { split(a, MediaHub.current?.packageName, p); return }
        if (!AppRepo.launch(a, p)) return
        if (Prefs.navMusic) MediaHub.resume(a)
        if (Prefs.navMode == "mini" && Prefs.miniAuto) MiniPlayer.start(a, auto = true)
    }

    /**
     * «Навигатор + музыка»: opens the music app, then the navigator with FLAG_ACTIVITY_LAUNCH_ADJACENT (public API;
     * Android honours it only while split-screen is active). Otherwise the navigator is full screen in front,
     * the music keeps playing behind it and the floating mini player is shown over it as the fallback.
     */
    fun split(a: Activity, playing: String?, navPkg: String? = null) {
        val nav = navPkg ?: defaultNav(a)
        val mus = musicApp(a, playing)
        if (nav == null) { Toast.makeText(a, "Навигатор не установлен", Toast.LENGTH_LONG).show(); return }
        val pm = a.packageManager
        // music first (starts/keeps playing), navigator last → in the fallback case the navigator is the app in front
        val musI = mus?.let { pm.getLaunchIntentForPackage(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val navI = pm.getLaunchIntentForPackage(nav)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        if (navI == null) { Toast.makeText(a, "Не удалось открыть навигатор", Toast.LENGTH_SHORT).show(); return }
        val inMw = Build.VERSION.SDK_INT >= 24 && a.isInMultiWindowMode
        try {
            if (musI != null) a.startActivities(arrayOf(musI, navI)) else a.startActivity(navI)
        } catch (e: Exception) {
            try { a.startActivity(navI) } catch (_: Exception) { Toast.makeText(a, "Не удалось открыть", Toast.LENGTH_SHORT).show(); return }
        }
        val mini = MiniPlayer.start(a, auto = false)
        Toast.makeText(a, when {
            musI == null -> "Музыкальное приложение не найдено — открыт только навигатор"
            inMw -> "Открываю ${short(a, nav)} и ${short(a, mus!!)} рядом"
            mini -> "Если экран не разделился — музыка в мини-плеере поверх навигатора"
            else -> "Если экран не разделился, включите мини-плеер в Настройках (поверх других окон)"
        }, Toast.LENGTH_LONG).show()
    }

    fun canOverlay(c: Context) = Settings.canDrawOverlays(c)
}

/**
 * Static «route» illustration for the navigator card (replaces the live map): stylised streets, a route line
 * with the theme accent, start arrow and destination pin. Drawn once per size, no tiles, no network.
 */
class NavArtView(c: Context, private val accent: Int, private var day: Boolean = false, private val bg: Int = 0) : View(c), LightAware {
    override fun toLight() { if (bg == 0) { day = true; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val k = Ui.s
        if (bg != 0) cv.drawColor(bg)
        else cv.drawColor(if (day) 0xFFE6E7E2.toInt() else 0xF0161A21.toInt())
        // blocks + streets
        val street = if (day) 0xFFFFFFFF.toInt() else 0xFF262B35.toInt()
        val minor = if (day) 0xFFF4F4F0.toInt() else 0xFF1D222B.toInt()
        val park = if (day) 0xFFD3E8CF.toInt() else 0xFF17251F.toInt()
        val water = if (day) 0xFFC5DAF0.toInt() else 0xFF141E2C.toInt()
        p.style = Paint.Style.FILL
        p.color = park; cv.drawRoundRect(w * 0.06f, h * 0.10f, w * 0.30f, h * 0.38f, 18 * k, 18 * k, p)
        p.color = water; path.reset(); path.moveTo(w * 0.70f, h); path.cubicTo(w * 0.78f, h * 0.70f, w * 0.92f, h * 0.78f, w, h * 0.55f); path.lineTo(w, h); path.close(); cv.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.color = minor; p.strokeWidth = 6 * k
        var x = -h * 0.2f; while (x < w) { cv.drawLine(x, 0f, x + h * 0.35f, h, p); x += 90 * k }
        var y = 40 * k; while (y < h) { cv.drawLine(0f, y, w, y - w * 0.08f, p); y += 80 * k }
        p.color = street; p.strokeWidth = 14 * k
        cv.drawLine(0f, h * 0.62f, w, h * 0.50f, p)
        cv.drawLine(w * 0.42f, 0f, w * 0.56f, h, p)
        path.reset(); path.moveTo(0f, h * 0.22f); path.quadTo(w * 0.5f, h * 0.30f, w, h * 0.12f); cv.drawPath(path, p)
        // route
        val sx = w * 0.18f; val sy = h * 0.80f; val ex = w * 0.80f; val ey = h * 0.26f
        path.reset(); path.moveTo(sx, sy); path.lineTo(w * 0.40f, h * 0.64f); path.lineTo(w * 0.50f, h * 0.38f); path.quadTo(w * 0.62f, h * 0.30f, ex, ey)
        p.strokeJoin = Paint.Join.ROUND
        p.shader = null; p.color = Ui.alpha(accent, 0.28f); p.strokeWidth = 22 * k; cv.drawPath(path, p)
        p.shader = LinearGradient(sx, sy, ex, ey, Ui.blend(accent, 0x60FFFFFF), accent, Shader.TileMode.CLAMP)
        p.strokeWidth = 9 * k; cv.drawPath(path, p); p.shader = null
        p.color = Ui.alpha(if (day) Color.BLACK else Color.WHITE, 0.35f); p.strokeWidth = 2 * k; p.pathEffect = DashPathEffect(floatArrayOf(10 * k, 12 * k), 0f)
        cv.drawPath(path, p); p.pathEffect = null
        // start: arrow in a circle
        p.style = Paint.Style.FILL
        p.color = Ui.alpha(accent, 0.25f); cv.drawCircle(sx, sy, 30 * k, p)
        p.color = Color.WHITE; cv.drawCircle(sx, sy, 17 * k, p)
        p.color = accent; path.reset(); path.moveTo(sx, sy - 10 * k); path.lineTo(sx + 8 * k, sy + 8 * k); path.lineTo(sx, sy + 4 * k); path.lineTo(sx - 8 * k, sy + 8 * k); path.close(); cv.drawPath(path, p)
        // destination pin
        val pr = 18 * k
        p.color = 0x33000000; cv.drawOval(ex - pr * 0.8f, ey + pr * 0.1f, ex + pr * 0.8f, ey + pr * 0.6f, p)
        p.color = 0xFFFF3B30.toInt()
        path.reset(); path.moveTo(ex, ey); path.cubicTo(ex - pr * 1.3f, ey - pr * 1.2f, ex - pr, ey - pr * 2.6f, ex, ey - pr * 2.6f)
        path.cubicTo(ex + pr, ey - pr * 2.6f, ex + pr * 1.3f, ey - pr * 1.2f, ex, ey); cv.drawPath(path, p)
        p.color = Color.WHITE; cv.drawCircle(ex, ey - pr * 1.75f, pr * 0.42f, p)
    }
}

/**
 * v1.7.0 navigator card (replaces the drawn «map with a route», which looked like a real but dead map):
 * the chosen navigator's icon, «Куда едем?» and its name; the whole card is one big tap target.
 */
class NavCardView(c: Context, accent: Int, light: Boolean = false, compact: Boolean = false) : android.widget.FrameLayout(c), LightAware {
    private val title: android.widget.TextView
    private val sub: android.widget.TextView
    init {
        val pkg = Nav.defaultNav(c)
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if (light) intArrayOf(0xFFF4F6F9.toInt(), 0xFFE3E8EF.toInt()) else intArrayOf(Ui.blend(0xF0161A21.toInt(), Ui.alpha(accent, 0.22f)), 0xF00D0F13.toInt()))
        val col = Ui.vbox(c).apply { gravity = android.view.Gravity.CENTER }
        val disc = android.widget.FrameLayout(c).apply { background = Ui.oval(if (light) 0xFFFFFFFF.toInt() else 0x26FFFFFF) }
        val iv = android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        if (pkg != null) AppRepo.bindIcon(c, pkg, null, iv, Tiles.brand(pkg).takeIf { it != 0 } ?: R.drawable.tile_nav) else iv.setImageResource(R.drawable.tile_nav)
        val isz = if (compact) 64 else 96
        disc.addView(iv, Ui.frame(Ui.u(isz), Ui.u(isz)))
        col.addView(disc, Ui.lin(Ui.u(isz + 40), Ui.u(isz + 40)))
        title = Ui.text(c, if (compact) 26 else 34, if (light) LightPass.INK else Color.WHITE, Ui.BOLD).apply { text = "Куда едем?"; gravity = android.view.Gravity.CENTER }
        col.addView(title, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.u(if (compact) 12 else 20) })
        val music = when (Prefs.navMode) { "split" -> " · рядом с музыкой"; "mini" -> " · с мини-плеером"; else -> "" }
        sub = Ui.text(c, if (compact) 15 else 18, if (light) LightPass.INK2 else Ui.TEXT2, Ui.MEDIUM, 2).apply {
            text = (if (pkg != null) "Открыть " + Nav.name(c, pkg) + music else "Нажмите, чтобы выбрать навигатор"); gravity = android.view.Gravity.CENTER }
        col.addView(sub, Ui.lin(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.u(6) })
        addView(col, LayoutParams(Ui.MATCH, Ui.MATCH))
        isClickable = true; contentDescription = "Навигатор"
    }
    override fun toLight() {}
}
