package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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

    private val card = RoverTheme.Card(ctx)
    private val ring = RoverTheme.stroke(ctx, RoverTheme.PANEL_2, 7f).apply { strokeCap = Paint.Cap.BUTT }
    private val valueArc = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 7f)
    private val tick = RoverTheme.stroke(ctx, RoverTheme.TEXT_FAINT, 1.5f)
    private val tickMain = RoverTheme.stroke(ctx, RoverTheme.TEXT_DIM, 2f)
    private val rim = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 4f)
    private val spoke = RoverTheme.stroke(ctx, RoverTheme.withAlpha(RoverTheme.ORANGE, 170), 4f)
    private val hub = RoverTheme.fill(RoverTheme.PANEL_2)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.TEXT, 16f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = RoverTheme.text(ctx, RoverTheme.TEXT_FAINT, 10f).apply { textAlign = Paint.Align.LEFT }

    private fun d(v: Float) = RoverTheme.dp(context, v)
    // yazılar solda, çark sağda
    private fun textW() = d(78f)
    private fun cx() = textW() + (width - textW() - d(6f)) / 2f
    private fun cy() = height / 2f
    private fun outer() = min((width - textW() - d(6f)) / 2f, height / 2f) - d(14f)
    private fun radius() = outer() - d(9f)

    fun setAngle(a: Int, notify: Boolean = false) {
        val v = a.coerceIn(minAngle, maxAngle)
        if (v != angle) { angle = v; invalidate(); if (notify) onAngleChanged?.invoke(v) }
    }

    override fun onDraw(c: Canvas) {
        card.draw(c, width, height, highlighted = dragging)
        val cx = cx(); val cy = cy(); val r = radius()
        c.drawText("KÖK", d(18f), d(28f), labelPaint)
        val turn = 90 - angle
        val dir = when { turn > 0 -> "SAĞ $turn°"; turn < 0 -> "SOL ${-turn}°"; else -> "İLERİ" }
        valuePaint.color = if (turn == 0) RoverTheme.TEXT else RoverTheme.ORANGE_LIGHT
        c.drawText(dir, d(18f), d(52f), valuePaint)
        c.drawText("çift dokun:", d(18f), height - d(26f), hintPaint)
        c.drawText("ileri", d(18f), height - d(13f), hintPaint)
        if (r <= d(8f)) return

        // dış halka: izinli aralık + ortadan (ileri) mevcut açıya değer yayı
        val ro = outer()
        val startDeg = -90f - (maxAngle - 90)
        c.drawArc(cx - ro, cy - ro, cx + ro, cy + ro, startDeg, (maxAngle - minAngle).toFloat(), false, ring)
        if (turn != 0) c.drawArc(cx - ro, cy - ro, cx + ro, cy + ro, -90f, turn.toFloat(), false, valueArc)
        // çentikler: her 45°
        for (deg in -90..90 step 45) {
            val a = Math.toRadians((deg - 90).toDouble())
            val r1 = ro + d(6f); val r2 = ro + if (deg == 0) d(12f) else d(9f)
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), if (deg == 0) tickMain else tick)
        }

        c.save()
        c.rotate(turn.toFloat(), cx, cy)
        val rw = r - d(4f)
        c.drawCircle(cx, cy, rw, rim)
        for (deg in intArrayOf(-90, 30, 150)) {
            val rad = Math.toRadians(deg.toDouble())
            c.drawLine(cx, cy, cx + (rw * cos(rad)).toFloat(), cy + (rw * sin(rad)).toFloat(), spoke)
        }
        c.drawCircle(cx, cy, rw * 0.22f, hub)
        RoverTheme.drawThumb(context, c, cx, cy - rw, d(10f), dragging)
        c.restore()
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
