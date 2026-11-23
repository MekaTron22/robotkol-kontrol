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

    private val track = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 4f)
    private val fillPaint = RoverTheme.fill(RoverTheme.ORANGE_DIM)
    private val center = RoverTheme.stroke(ctx, RoverTheme.TEXT_DIM, 1.5f)
    private val thumb = RoverTheme.fill(RoverTheme.THUMB)
    private val thumbActive = RoverTheme.fill(RoverTheme.ACTIVE)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT, 15f, bold = true)
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f)
    private val rect = RectF()

    private fun d(v: Float) = RoverTheme.dp(context, v)

    private fun trackTop() = d(34f) + d(22f)
    private fun trackBottom() = height - d(22f) - d(20f)

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
        val cx = width / 2f
        val w = minOf(width * 0.42f, d(56f))
        val top = trackTop(); val bot = trackBottom()
        val mid = (top + bot) / 2f

        c.drawText(label, cx, d(26f), labelPaint)

        rect.set(cx - w / 2, top, cx + w / 2, bot)
        val thumbY = mid - pos * (bot - top) / 2f
        // değer dolgusu (ortadan başparmağa)
        c.drawRoundRect(cx - w / 2 + d(4f), minOf(mid, thumbY), cx + w / 2 - d(4f), maxOf(mid, thumbY), d(6f), d(6f), fillPaint)
        c.drawRoundRect(rect, w / 2, w / 2, track)
        c.drawLine(cx - w / 2 - d(6f), mid, cx + w / 2 + d(6f), mid, center)

        c.drawCircle(cx, thumbY, w * 0.48f, if (pointerId >= 0) thumbActive else thumb)
        c.drawText(if (value == 0) "DUR" else "$value", cx, height - d(10f), valuePaint)
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
