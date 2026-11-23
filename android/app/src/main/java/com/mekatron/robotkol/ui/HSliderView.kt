package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Yatay sürgü (kıskaç ve bilek rotasyonu için). Bırakınca yerinde kalır.
 * Çift dokunma: [centerValue]'ya döner.
 */
class HSliderView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var label = ""
    var leftText = ""
    var rightText = ""
    var min = 0
    var max = 180
    var centerValue = 90
    /** true ise sağ uç = min (ör. servo ters yönde ise) */
    var reversed = false
    var onValueChanged: ((Int) -> Unit)? = null

    var value = 90
        private set

    private var dragging = false
    private var lastTapMs = 0L

    /** Dolgu nereden başlasın: true = [centerValue]'dan (rotasyon), false = sol uçtan (kıskaç) */
    var fillFromCenter = false

    private val card = RoverTheme.Card(ctx)
    private val trackPaint = RoverTheme.fill(RoverTheme.PANEL_2)
    private val fillPaint = RoverTheme.fill(RoverTheme.withAlpha(RoverTheme.ORANGE, 170))
    private val centerTick = RoverTheme.stroke(ctx, RoverTheme.TEXT_FAINT, 2f)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.TEXT, 15f, bold = true).apply { textAlign = Paint.Align.RIGHT }
    private val endPaint = RoverTheme.text(ctx, RoverTheme.TEXT_FAINT, 11f)

    private fun d(v: Float) = RoverTheme.dp(context, v)
    private fun left() = d(34f)
    private fun right() = width - d(34f)
    private fun trackY() = (d(34f) + height - d(22f)) / 2f

    fun setValue(v: Int, notify: Boolean = false) {
        val nv = v.coerceIn(min, max)
        if (nv != value) { value = nv; invalidate(); if (notify) onValueChanged?.invoke(nv) }
    }

    private fun fractionOf(v: Int): Float {
        val f = (v - min).toFloat() / (max - min).coerceAtLeast(1)
        return if (reversed) 1f - f else f
    }

    override fun onDraw(c: Canvas) {
        card.draw(c, width, height, highlighted = dragging)
        val l = left(); val r = right(); val y = trackY()
        val h = d(12f)
        c.drawText(label, d(18f), d(26f), labelPaint)
        valuePaint.color = if (dragging) RoverTheme.ACTIVE else RoverTheme.TEXT
        c.drawText("$value°", width - d(18f), d(26f), valuePaint)

        c.drawRoundRect(l - h, y - h / 2, r + h, y + h / 2, h / 2, h / 2, trackPaint)
        val x = l + fractionOf(value) * (r - l)
        val from = if (fillFromCenter) l + fractionOf(centerValue) * (r - l) else l - h
        if (fillFromCenter) c.drawLine(from, y - h, from, y + h, centerTick)
        c.drawRoundRect(minOf(from, x), y - h / 2, maxOf(from, x), y + h / 2, h / 2, h / 2, fillPaint)
        RoverTheme.drawThumb(context, c, x, y, d(13f), dragging)

        if (leftText.isNotEmpty()) c.drawText(leftText, l, height - d(12f), endPaint)
        if (rightText.isNotEmpty()) c.drawText(rightText, r, height - d(12f), endPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 300) { setValue(centerValue, notify = true); lastTapMs = 0; return true }
                lastTapMs = now
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                setFromX(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) setFromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }

    private fun setFromX(x: Float) {
        var f = ((x - left()) / (right() - left())).coerceIn(0f, 1f)
        if (reversed) f = 1f - f
        setValue((min + f * (max - min)).roundToInt(), notify = true)
        invalidate()
    }
}
