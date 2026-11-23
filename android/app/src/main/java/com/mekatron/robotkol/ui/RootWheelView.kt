package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Çevrilebilir kök": direksiyon gibi döndürülen çark. Kök servosunu sürer.
 * Çark açısı -90..+90 (saat yönü = sağa) ↔ servo açısı; bırakınca yerinde kalır.
 * Çift dokunma: ortaya (ileri) döner.
 */
class RootWheelView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var minAngle = 0
    var maxAngle = 180
    /** servo açısı değiştiğinde */
    var onAngleChanged: ((Int) -> Unit)? = null

    /** Servo açısı; 90 = ileri. Çark dönüşü = 90 - açı (saat yönü pozitif). */
    var angle = 90
        private set

    private var dragging = false
    private var lastTouchDeg = 0.0
    private var accum = 0.0          // sürükleme sırasında kesirli birikim
    private var lastTapMs = 0L

    private val rim = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 6f)
    private val spoke = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 5f)
    private val arc = RoverTheme.stroke(ctx, RoverTheme.ORANGE_DIM, 3f)
    private val knob = RoverTheme.fill(RoverTheme.THUMB)
    private val knobActive = RoverTheme.fill(RoverTheme.ACTIVE)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT, 13f, bold = true)
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f)

    private fun d(v: Float) = RoverTheme.dp(context, v)
    private fun cx() = width / 2f
    private fun cy() = d(22f) + (height - d(22f) - d(18f)) / 2f
    private fun radius() = min(width / 2f, (height - d(22f) - d(18f)) / 2f) - d(8f)

    fun setAngle(a: Int, notify: Boolean = false) {
        val v = a.coerceIn(minAngle, maxAngle)
        if (v != angle) { angle = v; invalidate(); if (notify) onAngleChanged?.invoke(v) }
    }

    override fun onDraw(c: Canvas) {
        val cx = cx(); val cy = cy(); val r = radius()
        if (r <= 0) return
        c.drawText("ÇEVRİLEBİLİR KÖK", cx, d(15f), labelPaint)

        // dönüş = saat yönünde derece (ekranda), servo 90 iken 0
        val rot = (90 - angle).toFloat()
        // aralık yayı
        c.drawArc(cx - r - d(10f), cy - r - d(10f), cx + r + d(10f), cy + r + d(10f),
            -90f - (maxAngle - 90), (maxAngle - minAngle).toFloat(), false, arc)

        c.save()
        c.rotate(rot, cx, cy)
        c.drawCircle(cx, cy, r, rim)
        // Y şeklinde 3 kol (çizimdeki gibi)
        for (deg in intArrayOf(-90, 30, 150)) {
            val rad = Math.toRadians(deg.toDouble())
            c.drawLine(cx, cy, cx + (r * cos(rad)).toFloat(), cy + (r * sin(rad)).toFloat(), spoke)
        }
        // tutamak (sağ kenarda)
        c.drawCircle(cx, cy - r, d(13f), if (dragging) knobActive else knob)
        c.restore()

        val turn = 90 - angle
        val dir = when { turn > 0 -> "SAĞ $turn°"; turn < 0 -> "SOL ${-turn}°"; else -> "İLERİ" }
        c.drawText(dir, cx, height - d(4f), valuePaint)
    }

    private fun touchDeg(x: Float, y: Float) = Math.toDegrees(atan2((y - cy()).toDouble(), (x - cx()).toDouble()))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 300) { setAngle(90, notify = true); lastTapMs = 0; return true }
                lastTapMs = now
                // merkezin çok yakınında açı kararsızdır
                if (hypot(e.x - cx(), e.y - cy()) < radius() * 0.2f) return true
                dragging = true
                lastTouchDeg = touchDeg(e.x, e.y)
                accum = angle.toDouble()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (hypot(e.x - cx(), e.y - cy()) < radius() * 0.2f) return true
                val t = touchDeg(e.x, e.y)
                var delta = t - lastTouchDeg
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                lastTouchDeg = t
                // ekranda saat yönü (+delta) = sağa = servo açısı azalır
                accum = (accum - delta).coerceIn(minAngle.toDouble(), maxAngle.toDouble())
                setAngle(accum.roundToInt(), notify = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}
