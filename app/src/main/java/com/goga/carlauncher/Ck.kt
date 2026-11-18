package com.goga.carlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * v1.7.1 helpers for the C / D screens. Everything is laid out in DESIGN pixels of a 1280×720 stage (the same numbers as
 * the approved mockup /workspace/car-design); [Stage] scales the whole stage to the real window (TEYES status bar etc.).
 */
object Ck {
    const val W = 1280; const val H = 720

    lateinit var orb: Typeface; lateinit var jura: Typeface; lateinit var juraB: Typeface
    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val sansM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val sansB: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val sansL: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun init(c: Context) {
        if (::orb.isInitialized) return
        fun font(id: Int, fb: Typeface) = try { c.resources.getFont(id) } catch (_: Throwable) { fb }
        orb = font(R.font.ck_orb, sansM); jura = font(R.font.ck_jura, sans); juraB = font(R.font.ck_jura_bold, sansB)
    }

    /** current style flags */
    val isC get() = K.style == "C"
    /** text face: C uses Jura, D the system sans */
    fun face(bold: Boolean = false): Typeface = if (isC) (if (bold) juraB else jura) else (if (bold) sansB else sansM)

    // ---- colours ----
    const val WHITE = Color.WHITE
    val T2 = 0xA6FFFFFF.toInt()       // secondary text
    val T3 = 0x80FFFFFF.toInt()
    const val LIME = 0xFFC8F53A.toInt()

    class Theme(val id: String, val name: String, val b0: Int, val b1: Int, val b2: Int, val mist: Int, val c1: Int, val c2: Int, val c3: Int, val accent: Int, val rim: Int)
    private fun c(s: String) = Color.parseColor(s)
    val THEMES = listOf(
        Theme("violet", "Фиолетовый", c("#1d1a31"), c("#30264a"), c("#15122a"), c("#6a4f92"), c("#8a6cc4"), c("#d98cc0"), c("#ffb08a"), c("#ffb08a"), c("#ffb08a")),
        Theme("green", "Зелёный", c("#0a1a14"), c("#123526"), c("#04100b"), c("#2c7552"), c("#3d9e70"), c("#8fd65a"), c("#e2ff86"), c("#b6f04a"), c("#c8f56a")),
        Theme("navy", "Тёмно-синий", c("#071029"), c("#10224f"), c("#000000"), c("#26498c"), c("#3864c4"), c("#4aa6ff"), c("#a8e8ff"), c("#5ab4ff"), c("#7cc8ff")),
        Theme("graphite", "Графит", c("#141417"), c("#26262c"), c("#08080a"), c("#4a4b54"), c("#6e717b"), c("#bba78c"), c("#ffc27a"), c("#ffc27a"), c("#ffc27a"))
    )
    fun theme(): Theme = THEMES.firstOrNull { it.id == K.theme } ?: THEMES[0]

    /** accent: C = cool cyan/orange like the mockup (cover colour when «Обложка»), D = theme accent (or cover colour) */
    var coverColor: Int = 0
    /** v1.7.4: the current track cover (full-screen «Обложка» background) */
    @Volatile var coverArt: android.graphics.Bitmap? = null
    fun accent(): Int {
        if (K.bg == "cover" && coverColor != 0) return lift(coverColor)
        return if (isC) 0xFFFF6A2B.toInt() else theme().accent
    }
    /** makes a dark cover colour usable as an accent */
    fun lift(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f); hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsv)
    }
    fun alpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    // ---- views ----
    fun lp(x: Number, y: Number, w: Number, h: Number) = FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = x.toInt(); topMargin = y.toInt(); gravity = Gravity.TOP or Gravity.START }
    fun ViewGroup.put(v: View, x: Number, y: Number, w: Number, h: Number): View { addView(v, lp(x, y, w, h)); return v }

    fun text(c: Context, size: Float, color: Int = WHITE, face: Typeface = face(), lines: Int = 1, gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL): TextView =
        TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size); setTextColor(color); typeface = face; includeFontPadding = false
            this.gravity = gravity
            if (lines == 1) { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END } else { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        }

    private val iconCache = HashMap<String, Int>()
    @SuppressLint("DiscouragedApi")
    fun iconRes(c: Context, name: String): Int = iconCache.getOrPut(name) {
        c.resources.getIdentifier("ck_" + name.lowercase(), "drawable", c.packageName).takeIf { it != 0 } ?: R.drawable.ck_star
    }
    fun icon(c: Context, name: String, color: Int = WHITE): ImageView = ImageView(c).apply {
        setImageResource(iconRes(c, name)); imageTintList = ColorStateList.valueOf(color); scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** a press feedback that does not need any drawable: dims the view a little while pressed */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
        v.isClickable = true; v.isFocusable = false
        v.setOnClickListener { onClick() }
        if (onLong != null) v.setOnLongClickListener { onLong(); true }
        v.setOnTouchListener { vv, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { vv.animate().cancel(); vv.alpha = 0.72f; vv.scaleX = 0.97f; vv.scaleY = 0.97f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start() }
            }
            false
        }
        return v
    }
}

/**
 * Glass / line panel background. D: frosted glass (white 7–9 % + light rim + top highlight). C: thin silver line,
 * dark fill on photo backgrounds. Radii per corner (design px).
 */
class Panel(
    var fill: Int, var stroke: Int, var strokeW: Float = 1.5f, val radii: FloatArray = floatArrayOf(22f, 22f, 22f, 22f),
    var highlight: Boolean = true, var glow: Int = 0
) : Drawable() {
    constructor(fill: Int, stroke: Int, r: Float, strokeW: Float = 1.5f, highlight: Boolean = true) : this(fill, stroke, strokeW, floatArrayOf(r, r, r, r), highlight)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    override fun draw(cv: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val r = RectF(b); r.inset(strokeW / 2, strokeW / 2)
        path.reset()
        val rr = floatArrayOf(radii[0], radii[0], radii[1], radii[1], radii[3], radii[3], radii[2], radii[2])
        path.addRoundRect(r, rr, android.graphics.Path.Direction.CW)
        if (glow != 0) { p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.color = Ck.alpha(glow, 0.25f); p.shader = null; cv.drawPath(path, p) }
        p.style = Paint.Style.FILL; p.shader = null; p.color = fill; cv.drawPath(path, p)
        if (highlight) {
            p.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height(), 90f), 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            cv.drawPath(path, p); p.shader = null
        }
        if (stroke != 0 && strokeW > 0) { p.style = Paint.Style.STROKE; p.strokeWidth = strokeW; p.color = stroke; cv.drawPath(path, p) }
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** standard card of the current style; [photo] = a photo / video is behind (C gets the dark glass of the mockup) */
        fun card(r: Float = 22f, photo: Boolean = false): Panel = if (Ck.isC)
            Panel(if (photo) 0x94060810.toInt() else 0x08FFFFFF, 0x42DCE2EB, 1.3f, floatArrayOf(r, r, r, r), true)
        else Panel(0x1FFFFFFF, 0x33FFFFFF, 1.3f, floatArrayOf(r, r, r, r), true)
        fun pill(fill: Int, stroke: Int, h: Float) = Panel(fill, stroke, 1.3f, floatArrayOf(h / 2, h / 2, h / 2, h / 2), false)
    }
}

/**
 * The 1280×720 design stage, scaled (uniformly) and centred into the real window. Touches are mapped by the framework
 * (view transforms), so every child works in design pixels.
 *
 * v1.7.5: the TEYES / FYT system bar on top (◁ ○ □, Wi-Fi, weather, voltage — com.android.systemui) takes ~40 px of
 * a 1280×720 screen. Either the window is shorter (1280×680 → the 16:9 stage was letterboxed with black side strips)
 * or the bar is drawn over the window. Now: the window always spans the whole screen (LAYOUT_FULLSCREEN), the stage is
 * fitted into the part not covered by the bar ([topInset], 0 when the launcher hides the bar), and the background
 * layers ([bleed]) reach past the stage to every edge, so a narrower area has no black strips.
 */
class Stage(c: Context) : FrameLayout(c) {
    val stage = StageBox(c).apply { clipChildren = false }
    var scale = 1f; private set
    /** system bar height over our window (px, real), from the window insets */
    var topInset = 0; private set
    var bottomInset = 0; private set
    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        addView(stage, LayoutParams(Ck.W, Ck.H))
    }
    private var lastTop = 0; private var lastBottom = 0
    /** v1.7.6: the bar is hidden in the launcher right now (our immersive flags are in effect) */
    @Suppress("DEPRECATION")
    fun barHidden(): Boolean = K.ready && K.hideBar &&
        ((rootView.systemUiVisibility or windowSystemUiVisibility) and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        lastTop = insets.systemWindowInsetTop; lastBottom = insets.systemWindowInsetBottom
        refit()
        return insets
    }
    /**
     * Recompute the free area. v1.7.6 fix: with the bar hidden the stage uses the whole window (1280×720 → scale 1, the
     * top row hugs the real top edge). Before, LAYOUT_STABLE made the insets keep the bar's height even while it was
     * hidden, so the whole stage stayed shrunk and pushed down by ~60–80 px.
     */
    fun refit() {
        val hidden = barHidden()
        // manual override (Настройки → Экран и кнопки), for a firmware whose bar does not report its height
        val t = when { hidden -> 0; K.ready && K.topPad >= 0 -> K.topPad; else -> lastTop }
        val b = if (hidden) 0 else lastBottom
        if (t != topInset || b != bottomInset) { topInset = t; bottomInset = b; requestLayout() }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        val ah = h - topInset - bottomInset
        if (w > 0 && ah > 0) {
            scale = minOf(w / Ck.W.toFloat(), ah / Ck.H.toFloat())
            val tx = (w - Ck.W * scale) / 2f; val ty = topInset + (ah - Ck.H * scale) / 2f
            // background layers bleed to the window edges (in design px)
            stage.setBleed(Math.ceil((tx / scale).toDouble()).toInt(), Math.ceil((ty / scale).toDouble()).toInt(),
                Math.ceil(((w - tx - Ck.W * scale) / scale).toDouble()).toInt(), Math.ceil(((h - ty - Ck.H * scale) / scale).toDouble()).toInt())
            stage.pivotX = 0f; stage.pivotY = 0f; stage.scaleX = scale; stage.scaleY = scale
            stage.translationX = tx; stage.translationY = ty
        }
        stage.measure(MeasureSpec.makeMeasureSpec(Ck.W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ck.H, MeasureSpec.EXACTLY))
        stage.layout(0, 0, Ck.W, Ck.H)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        stage.measure(MeasureSpec.makeMeasureSpec(Ck.W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ck.H, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
}

/** the design-space stage; children in [bleed] (background, video, veil, rain) are laid out past its edges by bl/bt/br/bb */
class StageBox(c: Context) : FrameLayout(c) {
    val bleed: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    var bl = 0; private set
    var bt = 0; private set
    var br = 0; private set
    var bb = 0; private set
    fun setBleed(l: Int, t: Int, r: Int, b: Int) {
        if (l == bl && t == bt && r == br && b == bb) return
        bl = l; bt = t; br = r; bb = b
        forceLayout(); for (i in 0 until childCount) getChildAt(i).let { if (it in bleed) it.forceLayout() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) { val v = getChildAt(i); if (v in bleed) v.measure(MeasureSpec.makeMeasureSpec(Ck.W + bl + br, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ck.H + bt + bb, MeasureSpec.EXACTLY)) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        for (i in 0 until childCount) { val v = getChildAt(i); if (v in bleed) v.layout(-bl, -bt, Ck.W + br, Ck.H + bb) }
    }
}
