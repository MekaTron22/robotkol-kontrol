package com.mekatron.robotkol.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dikey DC motor sürgüsü (tank sürüşü). Orta = dur, yukarı = ileri.
 * Parmak kalkınca yaylı gibi 0'a döner (güvenlik). Her sürgü kendi parmağını izler,
 * böylece iki başparmakla aynı anda kullanılabilir.
 */
class DriveSliderView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var label = "DC"
    /** -255..255 değeri değiştiğinde çağrılır */
    var onValueChanged: ((Int) -> Unit)? = null

    /** -1..1 arası konum (1 = en üst) */
    private var pos = 0f
    private var pointerId = -1
    private var returnAnim: ValueAnimator? = null
    private var lastSent = 0

    /** Parmak yokken her zaman 0 (geri dönüş animasyonu sırasında bile) */
    val value: Int get() = if (pointerId < 0) 0 else toPwm(pos)

    private val card = RoverTheme.Card(ctx)
    private val trackPaint = RoverTheme.fill(RoverTheme.PANEL_2)
    private val fillPaint = RoverTheme.fill(RoverTheme.ORANGE)
    private val tickPaint = RoverTheme.stroke(ctx, RoverTheme.TEXT_FAINT, 1.5f)
    private val centerPaint = RoverTheme.stroke(ctx, RoverTheme.TEXT_DIM, 2f)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f, bold = true)
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.TEXT, 15f, bold = true)
    private val dirPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 11f)
    private val rect = RectF()

    private fun d(v: Float) = RoverTheme.dp(context, v)

    private fun trackTop() = d(40f)
    private fun trackBottom() = height - d(52f)

    companion object {
        const val DEADZONE = 0.08f
        fun toPwm(p: Float): Int {
            val a = abs(p)
            if (a < DEADZONE) return 0
            // ölü bölge sonrası 60..255: düşük PWM'de motor zaten dönmez
            val scaled = 60f + (a - DEADZONE) / (1f - DEADZONE) * (255f - 60f)
            return (if (p > 0) scaled else -scaled).roundToInt().coerceIn(-255, 255)
        }
    }

    override fun onDraw(c: Canvas) {
        val active = pointerId >= 0
        card.draw(c, width, height, highlighted = active)
        val cx = width / 2f
        val w = minOf(width - d(36f), d(58f))
        val top = trackTop(); val bot = trackBottom()
        val mid = (top + bot) / 2f
        val half = (bot - top) / 2f

        c.drawText(label, cx, d(26f), labelPaint)

        // yuva
        rect.set(cx - w / 2, top, cx + w / 2, bot)
        c.drawRoundRect(rect, w / 2, w / 2, trackPaint)
        // çentikler (%25, %50, %75)
        for (f in floatArrayOf(-0.75f, -0.5f, -0.25f, 0.25f, 0.5f, 0.75f)) {
            val y = mid - f * half
            c.drawLine(cx - w / 2 + d(8f), y, cx - w / 2 + d(14f), y, tickPaint)
            c.drawLine(cx + w / 2 - d(14f), y, cx + w / 2 - d(8f), y, tickPaint)
        }
        // değer dolgusu: ileri turuncu, geri mavi
        val thumbY = mid - pos * half
        val col = if (pos >= 0) RoverTheme.ORANGE else RoverTheme.BLUE
        fillPaint.color = RoverTheme.withAlpha(col, if (active) 150 else 90)
        val inset = d(5f)
        if (abs(thumbY - mid) > 1f) c.drawRoundRect(cx - w / 2 + inset, minOf(mid, thumbY), cx + w / 2 - inset,
            maxOf(mid, thumbY), d(10f), d(10f), fillPaint)
        c.drawLine(cx - w / 2 - d(4f), mid, cx + w / 2 + d(4f), mid, centerPaint)

        RoverTheme.drawThumb(context, c, cx, thumbY, w * 0.42f, active, ring = col)

        val v = value
        val pct = (abs(v) * 100 / 255)
        valuePaint.color = if (v == 0) RoverTheme.TEXT_DIM else col
        c.drawText(if (v == 0) "DUR" else "%$pct", cx, height - d(28f), valuePaint)
        c.drawText(when { v > 0 -> "İLERİ"; v < 0 -> "GERİ"; else -> "bırak = dur" }, cx, height - d(13f), dirPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId < 0) {
                    pointerId = e.getPointerId(e.actionIndex)
                    returnAnim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    setFromY(e.getY(e.actionIndex))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) setFromY(e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == pointerId) release()
            }
        }
        return true
    }

    private fun setFromY(y: Float) {
        val top = trackTop(); val bot = trackBottom()
        val mid = (top + bot) / 2f
        setPos(((mid - y) / ((bot - top) / 2f)).coerceIn(-1f, 1f))
    }

    private fun setPos(p: Float) {
        val wasZero = value == 0
        pos = p
        val v = value
        if (v != lastSent) {
            if (wasZero != (v == 0)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastSent = v
            onValueChanged?.invoke(v)
        }
        invalidate()
    }

    private fun release() {
        pointerId = -1
        // Komut anında sıfırlanır; animasyon yalnız görsel
        lastSent = 0
        onValueChanged?.invoke(0)
        val start = pos
        returnAnim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 160; interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Acil durda dışarıdan sıfırlama */
    fun reset() { returnAnim?.cancel(); pointerId = -1; pos = 0f; lastSent = 0; invalidate() }
}
