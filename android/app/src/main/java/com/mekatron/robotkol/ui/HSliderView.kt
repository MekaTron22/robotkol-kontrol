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

    private val track = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 4f)
    private val thumb = RoverTheme.fill(RoverTheme.THUMB)
    private val thumbActive = RoverTheme.fill(RoverTheme.ACTIVE)
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT, 13f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val valuePaint = RoverTheme.text(ctx, RoverTheme.ACTIVE, 13f, bold = true).apply { textAlign = Paint.Align.RIGHT }
    private val endPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 11f)

    private fun d(v: Float) = RoverTheme.dp(context, v)
    private fun left() = d(22f)
    private fun right() = width - d(22f)
    private fun trackY() = (d(22f) + height - d(16f)) / 2f

    fun setValue(v: Int, notify: Boolean = false) {
        val nv = v.coerceIn(min, max)
        if (nv != value) { value = nv; invalidate(); if (notify) onValueChanged?.invoke(nv) }
    }

    private fun fraction(): Float {
        val f = (value - min).toFloat() / (max - min).coerceAtLeast(1)
        return if (reversed) 1f - f else f
    }

    override fun onDraw(c: Canvas) {
        val l = left(); val r = right(); val y = trackY()
        val h = d(18f)
        c.drawText(label, l - d(8f), d(16f), labelPaint)
        c.drawText("$value°", r + d(8f), d(16f), valuePaint)
        c.drawRoundRect(l - h / 2, y - h / 2, r + h / 2, y + h / 2, h / 2, h / 2, track)
        val x = l + fraction() * (r - l)
        c.drawCircle(x, y, h * 0.8f, if (dragging) thumbActive else thumb)
        if (leftText.isNotEmpty()) c.drawText(leftText, l + d(10f), height - d(3f), endPaint)
        if (rightText.isNotEmpty()) c.drawText(rightText, r - d(10f), height - d(3f), endPaint)
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
