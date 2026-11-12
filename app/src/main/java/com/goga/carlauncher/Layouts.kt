package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Home-screen arrangements. Independent from the colour theme (Theme.kt). */
object Layouts {
    class L(val id: String, val name: String, val desc: String, val brand: Boolean = false)
    val ALL = listOf(
        L("cockpit", "Кокпит", "Стрелочный спидометр, док снизу"),
        L("map", "Навигатор", "Большая карточка навигатора, плеер-капсула"),
        L("media", "Медиа", "Огромная обложка, карусель иконок"),
        L("arc", "Дуга", "Иконки по дуге, минимализм"),
        L("widgets", "Виджеты", "Доска виджетов: компас, навигатор"),
        L("tiles", "Плитки", "Классика 1.2: плитки и часы"),
        // v1.4: original interpretations of real car head units (no brand marks)
        L("minimal", "Американский минимал", "Навигатор на весь экран, панель авто слева, тонкий док", true),
        L("smart", "Китайский смарт", "Яркие карточки, глянцевый силуэт авто", true),
        L("premium", "Европейский премиум", "Хромированный прибор, контурная подсветка", true),
        L("grand", "Премиум Гранд", "Тонкие крупные часы, хромированный прибор, круглая обложка, строгая типографика", true),
        L("sport", "Спорт-кокпит", "Два сегментных прибора, карбон, острые грани", true),
        // v1.6: «приборные» — originals inspired by AGAMA / CarWebGuru templates; day / night, own accent
        L("cluster", "Приборка", "Хромированный щиток, ваш SX4 на дороге, крупная скорость, обложка справа", true),
        L("chrono", "Хронограф", "Стрелочный спидометр и часы с датой, как дорогой хронограф", true),
        L("radial", "Радиал", "Круглая обложка в дуге скорости, кнопки по бокам, кожа, строка статуса", true),
        L("showcase", "Витрина", "Ваш SX4 сзади на дороге, часы и скорость, плеер и навигатор справа", true)
    )
    /** v1.7.1 default: «Главная / Музыка / Камера» screens, swiped horizontally (not part of the classic list). */
    val SCREENS = L("screens", "Экраны", "Главная · Музыка · Камера — листаются пальцем, переключатель сверху")
    /** Layouts removed in later versions → what a saved preference becomes. */
    val RETIRED = mapOf("nordic" to "cockpit")
    fun byId(id: String) = if (id == SCREENS.id) SCREENS else ALL.firstOrNull { it.id == (RETIRED[id] ?: id) } ?: ALL[0]
}

/** Miniature of layout × theme: theme wallpaper + a schematic of the layout in theme colours. */
class LayoutThumb(c: Context, private val layout: String, private val t: Theme) : View(c) {
    private val wall: Bitmap? = Wallpaper.thumb(c, t.wallpaper)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val src = Rect(); private val dst = RectF()
    init { Ui.clipRound(this, Ui.u(12)) }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        wall?.let { b ->
            val s = maxOf(w / b.width, h / b.height)
            val sw = (w / s).toInt(); val sh = (h / s).toInt()
            src.set((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2); dst.set(0f, 0f, w, h)
            cv.drawBitmap(b, src, dst, p)
        }
        val k = w / 1168f
        val rad = t.radius * k
        val glass = if (t.glass) Ui.blend(t.tint, 0x14FFFFFF) or (0xA0 shl 24) else t.tint
        p.style = Paint.Style.FILL
        fun card(l: Float, tp: Float, rr: Float, b: Float, color: Int = glass, radius: Float = rad) {
            p.color = color; r.set(l * k, tp * k, rr * k, b * k); cv.drawRoundRect(r, radius, radius, p)
        }
        fun circle(x: Float, y: Float, rr: Float, color: Int) { p.color = color; cv.drawCircle(x * k, y * k, rr * k, p) }
        fun ring(x: Float, y: Float, rr: Float, sw: Float, color: Int) {
            p.style = Paint.Style.STROKE; p.strokeWidth = sw * k; p.color = color; cv.drawCircle(x * k, y * k, rr * k, p); p.style = Paint.Style.FILL
        }
        fun text(s: String, x: Float, y: Float, size: Float) { p.color = t.clockFg; p.textSize = size * k; p.typeface = t.clockTypeface; cv.drawText(s, x * k, y * k, p) }
        val icons = intArrayOf(0xFF0A6CFF.toInt(), 0xFFFF7A00.toInt(), 0xFF1DB954.toInt(), 0xFF3A3A3C.toInt(), 0xFFFFCC00.toInt(), 0xFFE0162B.toInt(), 0xFFB02BE8.toInt(), 0xFF0A84FF.toInt())
        when (layout) {
            "cockpit" -> {
                circle(292f, 292f, 272f, 0xB0000000.toInt()); ring(292f, 292f, 246f, 14f, t.accent)
                text("21:47", 588f, 110f, 96f)
                card(588f, 160f, 1148f, 380f); card(604f, 176f, 790f, 364f, t.accent, 14 * k)
                for (i in 0..2) card(588f + i * 192f, 400f, 764f + i * 192f, 544f)
                card(20f, 560f, 1148f, 700f, glass, 30 * k)
                for (i in 0..7) card(80f + i * 136f, 584f, 150f + i * 136f, 654f, icons[i], 16 * k)
            }
            "map" -> {
                p.color = 0xFF1C1F26.toInt(); r.set(16 * k, 16 * k, w - 16 * k, h - 16 * k); cv.drawRoundRect(r, rad, rad, p)
                p.color = 0xFF2E333D.toInt(); p.strokeWidth = 10 * k
                for (i in 0..6) cv.drawLine((60 + i * 170f) * k, 16 * k, (i * 170f - 140) * k, h - 16 * k, p)
                for (i in 0..3) cv.drawLine(16 * k, (90 + i * 170f) * k, w - 16 * k, (40 + i * 170f) * k, p)
                circle(560f, 396f, 30f, Ui.alpha(t.accent, 0.3f)); circle(560f, 396f, 14f, t.accent)
                card(32f, 32f, 330f, 150f); text("21:47", 52f, 116f, 70f)
                circle(108f, 250f, 80f, 0xB0000000.toInt()); ring(108f, 250f, 66f, 10f, t.accent)
                card(32f, 584f, 640f, 688f, glass, 52 * k); circle(84f, 636f, 40f, t.accent)
                card(1040f, 40f, 1136f, 680f, glass, 30 * k)
                for (i in 0..7) card(1060f, 64f + i * 76f, 1116f, 120f + i * 76f, icons[i], 14 * k)
            }
            "media" -> {
                card(20f, 20f, 534f, 534f, t.accent, rad * 1.2f)
                text("21:47", 566f, 70f, 40f)
                card(566f, 200f, 1040f, 250f, 0xE6FFFFFF.toInt(), 6 * k); card(566f, 270f, 880f, 300f, 0x99FFFFFF.toInt(), 6 * k)
                circle(626f, 470f, 48f, 0x40FFFFFF); circle(756f, 470f, 62f, t.accent); circle(886f, 470f, 48f, 0x40FFFFFF)
                for (i in 0..7) circle(88f + i * 136f, 628f, if (i in 2..5) 50f else 42f, icons[i])
            }
            "arc" -> {
                text("21:47", 24f, 160f, 150f)
                card(860f, 60f, 1140f, 130f, 0xE6FFFFFF.toInt(), 8 * k)
                for (i in 0..7) {
                    val f = i / 7f; val d = (f - 0.5f) * 2f
                    circle(84f + f * 1000f, 300f + 80f * d * d, 52f, icons[i])
                }
                card(24f, 584f, 1144f, 696f, glass, 56 * k); circle(80f, 640f, 44f, t.accent)
            }
            "widgets" -> {
                val cw = 270f; val ch = 216f; val g = 16f; val x0 = 20f; val y0 = 20f
                fun cell(c: Int, rr: Int, cs: Int, rs: Int, color: Int = glass) =
                    card(x0 + c * (cw + g), y0 + rr * (ch + g), x0 + c * (cw + g) + cs * cw + (cs - 1) * g, y0 + rr * (ch + g) + rs * ch + (rs - 1) * g, color)
                cell(0, 0, 2, 2, if (t.speedBg != 0) t.speedBg else glass); ring(298f, 252f, 170f, 22f, t.accent)
                cell(2, 0, 2, 1); card(588f, 36f, 772f, 220f, t.accent, 14 * k)
                cell(2, 1, 1, 1, if (t.clockBg != 0) t.clockBg else glass); cell(3, 1, 1, 1); ring(1009f, 340f, 80f, 6f, 0x80FFFFFF.toInt())
                cell(0, 2, 1, 1); cell(1, 2, 1, 1); ring(441f, 572f, 80f, 6f, 0x80FFFFFF.toInt())
                cell(2, 2, 2, 1)
                for (i in 0..7) card(610f + (i % 4) * 134f, 482f + (i / 4) * 96f, 674f + (i % 4) * 134f, 546f + (i / 4) * 96f, icons[i], 14 * k)
            }
            "minimal" -> {
                p.color = 0xFF1C1F26.toInt(); r.set(360 * k, 0f, w, 640 * k); cv.drawRect(r, p)
                p.color = 0xFF2E333D.toInt(); p.strokeWidth = 10 * k
                for (i in 0..4) cv.drawLine((420 + i * 170f) * k, 0f, (300 + i * 170f) * k, 640 * k, p)
                for (i in 0..2) cv.drawLine(360 * k, (120 + i * 200f) * k, w, (60 + i * 200f) * k, p)
                card(0f, 0f, 360f, 640f, 0xFF121316.toInt(), 0f)
                text("62", 110f, 200f, 120f)
                card(130f, 260f, 230f, 520f, 0xFF40444C.toInt(), 40 * k); card(146f, 320f, 214f, 380f, 0xFF0C0D10.toInt(), 10 * k)
                card(380f, 20f, 710f, 76f, 0xF0F2F2F4.toInt(), 14 * k)
                card(380f, 516f, 880f, 620f, 0xF0141518.toInt(), 14 * k); card(392f, 528f, 472f, 608f, t.accent, 8 * k)
                card(0f, 640f, w / k, 720f, 0xFF08080A.toInt(), 0f)
                for (i in 0..7) card(250f + i * 94f, 655f, 300f + i * 94f, 705f, icons[i], 12 * k)
            }
            "smart" -> {
                card(20f, 20f, 702f, 700f, Ui.blend(0xFF141A3A.toInt(), Ui.alpha(t.accent, 0.55f)), 32 * k)
                text("21:47", 52f, 150f, 84f)
                card(130f, 380f, 600f, 560f, 0xFFD5DAE3.toInt(), 80 * k); card(260f, 400f, 470f, 450f, 0xFF1A2333.toInt(), 30 * k)
                circle(220f, 560f, 40f, 0xFF0B0C0F.toInt()); circle(520f, 560f, 40f, 0xFF0B0C0F.toInt())
                ring(610f, 100f, 60f, 10f, t.accent)
                card(718f, 20f, 1148f, 316f, 0xFFB02BE8.toInt(), 32 * k)
                for (i in 0..7) card(718f + (i % 4) * 110f, 332f + (i / 4) * 190f, 816f + (i % 4) * 110f, 510f + (i / 4) * 190f, icons[i], 24 * k)
            }
            "grand" -> {
                card(0f, 0f, w / k, 720f, 0xF0080A0E.toInt(), 0f)
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * k; p.color = t.accent
                cv.drawLine(0f, 520 * k, w, 470 * k, p); p.style = Paint.Style.FILL
                text("21:47", 40f, 150f, 120f)
                for (i in 0..2) card(40f, 230f + i * 66f, 360f, 280f + i * 66f, 0x22FFFFFF, 25 * k)
                circle(590f, 290f, 200f, 0xFF0B0D12.toInt()); ring(590f, 290f, 195f, 9f, 0xFFB8BEC8.toInt()); ring(590f, 290f, 170f, 5f, t.accent)
                ring(980f, 210f, 96f, 3f, t.accent); circle(980f, 210f, 88f, 0xFF3A3F4A.toInt())
                card(880f, 330f, 1080f, 350f, 0x55FFFFFF, 8 * k)
                card(30f, 588f, 1138f, 590f, 0x33FFFFFF, 0f)
                for (i in 0..8) card(70f + i * 120f, 610f, 130f + i * 120f, 670f, icons[i % 8], 16 * k)
            }
            "premium" -> {
                card(0f, 0f, w / k, 720f, 0xF0050608.toInt(), 0f)
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * k; p.color = t.accent
                cv.drawLine(0f, 600 * k, w, 560 * k, p); p.style = Paint.Style.FILL
                circle(239f, 360f, 210f, 0xFF0B0D12.toInt()); ring(239f, 360f, 205f, 9f, 0xFFB8BEC8.toInt()); ring(239f, 360f, 180f, 5f, t.accent)
                text("21:47", 500f, 130f, 80f)
                ring(645f, 320f, 92f, 3f, t.accent); circle(645f, 320f, 84f, 0xFF3A3F4A.toInt())
                for (i in 0..8) card(894f, 60f + i * 68f, 1144f, 118f + i * 68f, 0x30FFFFFF, 29 * k)
            }
            "sport" -> {
                card(0f, 0f, w / k, 720f, 0xFF141518.toInt(), 0f)
                circle(220f, 216f, 196f, 0xFF0A0A0B.toInt()); ring(220f, 216f, 176f, 22f, t.accent)
                circle(948f, 216f, 196f, 0xFF0A0A0B.toInt()); ring(948f, 216f, 176f, 22f, 0xFF3A3B40.toInt())
                card(434f, 16f, 734f, 416f, 0xFF101114.toInt(), 0f); card(509f, 60f, 659f, 210f, t.accent, 0f)
                card(20f, 430f, 1148f, 486f, 0xFF101114.toInt(), 0f)
                for (i in 0..8) card(20f + i * 126f, 500f, 136f + i * 126f, 700f, 0xFF101114.toInt(), 0f)
            }
            "cluster", "chrono", "radial", "showcase" -> {
                val light = Dash.light(context); val ac = Dash.accent()
                val bg = if (light) 0xFFE4E7EB.toInt() else 0xFF07080B.toInt(); val fg = Dash.fg(light)
                card(0f, 0f, w / k, 720f, bg, 0f)
                fun roadAt(l: Float, tp: Float, rr: Float, bt: Float, carH: Float, horizon: Float) {   // v1.6.2: SX4 from behind on the road
                    cv.save(); cv.translate(l * k, tp * k)
                    RoadArt.paint(context, cv, ((rr - l) * k).toInt(), ((bt - tp) * k).toInt(), ac, light, carH = carH, maxCarW = 0.5f, horizon = horizon, ground = 0.92f)
                    cv.restore()
                }
                fun ringC(x: Float, y: Float, rr: Float) { ring(x, y, rr, 10f, 0xFFC9CDD3.toInt()) }
                p.typeface = Ui.BOLD
                when (layout) {
                    "cluster" -> {
                        card(16f, 16f, 1152f, 478f, if (light) 0xFFF7F8FA.toInt() else 0xFF14171E.toInt(), 150 * k)
                        p.style = Paint.Style.STROKE; p.strokeWidth = 8 * k; p.color = 0xFFC9CDD3.toInt(); r.set(16 * k, 16 * k, 1152 * k, 478 * k); cv.drawRoundRect(r, 150 * k, 150 * k, p); p.style = Paint.Style.FILL
                        roadAt(380f, 130f, 800f, 470f, 0.6f, 0.2f)
                        p.color = fg; p.textSize = 110 * k; p.textAlign = Paint.Align.CENTER; cv.drawText("62", 584 * k, 160 * k, p); p.textAlign = Paint.Align.LEFT
                        card(880f, 90f, 1080f, 290f, ac, 20 * k)
                        for (i in 0..7) circle(60f + i * 90f, 620f, 26f, icons[i])
                        circle(1040f, 610f, 36f, ac)
                    }
                    "chrono" -> {
                        circle(240f, 250f, 210f, if (light) 0xFFF4EFE4.toInt() else 0xFF121419.toInt()); ringC(240f, 250f, 210f)
                        circle(928f, 250f, 210f, if (light) 0xFFF4EFE4.toInt() else 0xFF121419.toInt()); ringC(928f, 250f, 210f)
                        p.color = ac; p.strokeWidth = 8 * k; cv.drawLine(240 * k, 250 * k, 120 * k, 160 * k, p)
                        p.color = fg; cv.drawLine(928 * k, 250 * k, 928 * k, 110 * k, p); cv.drawLine(928 * k, 250 * k, 1010 * k, 290 * k, p)
                        circle(584f, 220f, 84f, ac)
                        for (i in 0..7) circle(80f + i * 120f, 640f, 26f, icons[i])
                    }
                    "radial" -> {
                        card(0f, 0f, w / k, 60f, if (light) 0x30FFFFFF else 0x66000000, 0f)
                        circle(584f, 300f, 210f, Ui.alpha(ac, 0.35f)); circle(584f, 300f, 176f, bg); ring(584f, 300f, 165f, 14f, ac); circle(584f, 300f, 130f, 0xFF3A3A44.toInt())
                        for (i in 0..2) { card(30f, 140f + i * 100f, 330f, 216f + i * 100f, if (light) 0xFFFFFFFF.toInt() else 0xFF1E2027.toInt(), 38 * k)
                            card(838f, 140f + i * 100f, 1138f, 216f + i * 100f, if (light) 0xFFFFFFFF.toInt() else 0xFF1E2027.toInt(), 38 * k) }
                        circle(584f, 610f, 36f, ac)
                    }
                    else -> {
                        roadAt(40f, 120f, 800f, 580f, 0.78f, 0.04f)
                        p.color = fg; p.textSize = 90 * k; cv.drawText("21:47", 40 * k, 110 * k, p)
                        card(830f, 20f, 1150f, 420f, if (light) 0xFFFFFFFF.toInt() else 0xFF15181E.toInt(), 28 * k); card(900f, 50f, 1080f, 230f, ac, 18 * k)
                        for (i in 0..7) circle(60f + i * 95f, 650f, 24f, icons[i])
                    }
                }
            }
            else -> {
                card(20f, 20f, 340f, 484f, if (t.clockBg != 0) t.clockBg else glass)
                card(20f, 500f, 340f, 700f, if (t.speedBg != 0) t.speedBg else glass)
                card(356f, 20f, 1148f, 444f); circle(840f, 300f, 30f, t.accent)
                val tw = (1148f - 356f - 48f) / 4f
                for (row in 0..1) for (i in 0..3) {
                    val x = 356f + i * (tw + 16f); val y = 460f + row * 128f
                    card(x, y, x + tw, y + 112f, if (t.tileBlocks) icons[row * 4 + i] else glass)
                }
                text("21:47", 44f, 130f, 96f)
            }
        }
    }
}
