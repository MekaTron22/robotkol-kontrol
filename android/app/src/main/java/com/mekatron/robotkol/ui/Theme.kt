package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Ortak renkler ve çizim yardımcıları.
 * Koyu, hafif mavimsi zemin + yuvarlak köşeli kartlar + turuncu vurgu.
 */
object RoverTheme {
    // zemin ve yüzeyler
    val BG = Color.parseColor("#0C0E13")
    val PANEL = Color.parseColor("#151922")          // kart
    val PANEL_2 = Color.parseColor("#1E2330")        // kart içi iz / yuva
    val STROKE = Color.parseColor("#262C3A")         // kart kenarı
    val GRID = Color.parseColor("#1B202B")

    // vurgu
    val ORANGE = Color.parseColor("#FF8A2A")
    val ORANGE_LIGHT = Color.parseColor("#FFB066")
    val ORANGE_DIM = Color.parseColor("#4A2C14")
    val ACTIVE = Color.parseColor("#FFD08A")
    val BLUE = Color.parseColor("#5AB4FF")           // geri / ters yön

    // metin ve tutamaklar
    val THUMB = Color.parseColor("#EDEFF4")
    val TEXT = Color.parseColor("#EEF1F6")
    val TEXT_DIM = Color.parseColor("#8A93A6")
    val TEXT_FAINT = Color.parseColor("#565E70")

    val GREEN = Color.parseColor("#34D399")
    val RED = Color.parseColor("#F04848")

    fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    fun stroke(ctx: Context, color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = dp(ctx, widthDp)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun text(ctx: Context, color: Int, sizeSp: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (bold) letterSpacing = 0.06f
    }

    /** Görünümün tamamını kaplayan yuvarlak köşeli kart (iç boşluk [insetDp]). */
    class Card(ctx: Context) {
        private val fillP = fill(PANEL)
        private val edgeP = stroke(ctx, STROKE, 1f)
        private val r = dp(ctx, 18f)
        private val inset = dp(ctx, 4f)
        val rect = RectF()

        fun draw(c: Canvas, w: Int, h: Int, highlighted: Boolean = false) {
            rect.set(inset, inset, w - inset, h - inset)
            c.drawRoundRect(rect, r, r, fillP)
            edgeP.color = if (highlighted) withAlpha(ORANGE, 140) else STROKE
            c.drawRoundRect(rect, r, r, edgeP)
        }
    }

    /** Başparmak: gölgeli beyaz daire + vurgulu halka. */
    private val thumbP = fill(THUMB)   // yalnız UI iş parçacığında kullanılır

    fun drawThumb(ctx: Context, c: Canvas, x: Float, y: Float, radius: Float, active: Boolean, ring: Int = ORANGE) {
        thumbP.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(x, y + dp(ctx, 2f), radius + dp(ctx, 1f), thumbP)
        if (active) { thumbP.color = withAlpha(ring, 50); c.drawCircle(x, y, radius + dp(ctx, 8f), thumbP) }
        thumbP.color = if (active) ACTIVE else THUMB
        c.drawCircle(x, y, radius, thumbP)
        thumbP.color = ring
        c.drawCircle(x, y, radius * 0.38f, thumbP)
    }
}
