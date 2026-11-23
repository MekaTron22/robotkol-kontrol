package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue

/** Ortak renkler ve yardımcılar (çizimdeki siyah zemin + turuncu hat). */
object RoverTheme {
    val BG = Color.parseColor("#0B0B0E")
    val PANEL = Color.parseColor("#16161B")
    val ORANGE = Color.parseColor("#FF8A2A")
    val ORANGE_DIM = Color.parseColor("#5A3416")
    val THUMB = Color.parseColor("#C8C8C8")
    val TEXT = Color.parseColor("#F2F2F2")
    val TEXT_DIM = Color.parseColor("#9A9AA2")
    val ACTIVE = Color.parseColor("#FFD08A")
    val GREEN = Color.parseColor("#3DDC84")
    val RED = Color.parseColor("#FF4B4B")

    fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun stroke(ctx: Context, color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = dp(ctx, widthDp)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun text(ctx: Context, color: Int, sizeSp: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = bold
    }
}
