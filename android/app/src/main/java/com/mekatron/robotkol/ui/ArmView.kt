package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.mekatron.robotkol.core.ArmKinematics
import com.mekatron.robotkol.core.ArmKinematics.Point
import com.mekatron.robotkol.core.ArmKinematics.Segment
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Etkileşimli kol kontrolü – yandan görünüş.
 *
 * Kullanıcı üst kol, ön kol veya el segmentine dokunup sürükler. Yalnızca o segmentin
 * eklemi döner (bir önceki segmente göre); sonraki segmentler ona bağlı olarak birlikte
 * taşınır. Tüm kolu birden hareket ettiren ters kinematik kullanılmaz.
 */
class ArmView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Eklemler (arayüz açısı): omuz, dirsek, bilek */
    var shoulder = 100; private set
    var elbow = 50; private set
    var wrist = 70; private set
    /** Görsel: kıskaç açıklığı 0..1 ve bilek rotasyonu (derece, 90 = düz) */
    var gripOpen = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var wristRoll = 90
        set(v) { field = v; invalidate() }

    private val minA = intArrayOf(15, 0, 0)
    private val maxA = intArrayOf(165, 180, 180)

    /** (omuz, dirsek, bilek) değişince */
    var onArmChanged: ((Int, Int, Int) -> Unit)? = null

    private val len = ArmKinematics.Lengths()
    private var active: Segment? = null
    private var grabOffset = 0.0

    private val segPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 14f)
    private val segActive = RoverTheme.stroke(ctx, RoverTheme.ACTIVE, 16f)
    private val segInner = RoverTheme.stroke(ctx, RoverTheme.BG, 6f)
    private val jointPaint = RoverTheme.fill(RoverTheme.THUMB)
    private val basePaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 5f)
    private val groundPaint = RoverTheme.stroke(ctx, RoverTheme.TEXT_DIM, 1.5f)
    private val ghostPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE_DIM, 2f).apply {
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val labelPaint = RoverTheme.text(ctx, RoverTheme.TEXT, 13f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val anglePaint = RoverTheme.text(ctx, RoverTheme.ACTIVE, 12f).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 11f).apply { textAlign = Paint.Align.LEFT }
    private val clawPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 6f)
    private val path = Path()

    private fun d(v: Float) = RoverTheme.dp(context, v)

    fun setLimits(segment: Segment, min: Int, max: Int) {
        minA[segment.ordinal] = min; maxA[segment.ordinal] = max
        setPose(shoulder, elbow, wrist, notify = false)
    }

    fun setPose(s: Int, e: Int, w: Int, notify: Boolean = false) {
        val ns = s.coerceIn(minA[0], maxA[0]); val ne = e.coerceIn(minA[1], maxA[1]); val nw = w.coerceIn(minA[2], maxA[2])
        if (ns == shoulder && ne == elbow && nw == wrist) return
        shoulder = ns; elbow = ne; wrist = nw
        invalidate()
        if (notify) onArmChanged?.invoke(ns, ne, nw)
    }

    val isDragging get() = active != null

    // ---- ekran ↔ dünya dönüşümü (dünya: omuz pivotu (0,0), y yukarı) ----
    private fun pivotX() = width * 0.44f
    private fun pivotY() = height - d(30f)
    private fun scale() = min((pivotY() - d(10f)) / len.reach, (width * 0.44f - d(12f)) / len.reach).toFloat()
    private fun sx(p: Point) = pivotX() + (p.x * scale()).toFloat()
    private fun sy(p: Point) = pivotY() - (p.y * scale()).toFloat()
    private fun toWorld(x: Float, y: Float) = Point(((x - pivotX()) / scale()).toDouble(), ((pivotY() - y) / scale()).toDouble())

    override fun onDraw(c: Canvas) {
        c.drawText("ETKİLEŞİMLİ KOL KONTROLÜ", d(8f), d(16f), labelPaint)
        c.drawText("bir segmenti sürükleyin –", d(8f), d(32f), hintPaint)
        c.drawText("yalnız o eklem döner", d(8f), d(46f), hintPaint)

        val pts = ArmKinematics.points(shoulder, elbow, wrist, len)
        val px = pivotX(); val py = pivotY()

        // erişim yayı (yarım daire, rehber)
        val reach = (len.reach * scale()).toFloat()
        c.drawArc(px - reach, py - reach, px + reach, py + reach, 180f, 180f, false, ghostPaint)

        // zemin ve gövde (rover tablası + omuz kulesi)
        c.drawLine(d(8f), py + d(26f), width - d(8f), py + d(26f), groundPaint)
        c.drawRoundRect(px - d(60f), py + d(8f), px + d(60f), py + d(24f), d(6f), d(6f), basePaint)
        c.drawRect(px - d(14f), py - d(4f), px + d(14f), py + d(8f), basePaint)

        // segmentler
        for (i in 0..2) {
            val a = pts[i]; val b = pts[i + 1]
            val p = if (active?.ordinal == i) segActive else segPaint
            c.drawLine(sx(a), sy(a), sx(b), sy(b), p)
            c.drawLine(sx(a), sy(a), sx(b), sy(b), segInner)
        }
        drawClaw(c, pts[3], ArmKinematics.absoluteAngles(shoulder, elbow, wrist)[2])

        // eklemler
        for (i in 0..2) c.drawCircle(sx(pts[i]), sy(pts[i]), d(9f), jointPaint)

        // açı etiketleri
        val names = arrayOf("Omuz", "Dirsek", "Bilek")
        val vals = intArrayOf(shoulder, elbow, wrist)
        val abs = ArmKinematics.absoluteAngles(shoulder, elbow, wrist)
        for (i in 0..2) {
            anglePaint.color = if (active?.ordinal == i) RoverTheme.ACTIVE else RoverTheme.TEXT_DIM
            // etiketi segmentin ortasına, segmente dik yönde dışarı kaydır
            val mx = (sx(pts[i]) + sx(pts[i + 1])) / 2f; val my = (sy(pts[i]) + sy(pts[i + 1])) / 2f
            val r = Math.toRadians(abs[i])
            var nx = -sin(r).toFloat(); var ny = -cos(r).toFloat()   // ekranda segment normali
            if (nx < 0) { nx = -nx; ny = -ny }                        // hep sağa doğru
            val txt = "${names[i]} ${vals[i]}°"
            c.drawText(txt, mx + nx * d(16f), my + ny * d(16f) + d(4f), anglePaint)
        }
    }

    private fun drawClaw(c: Canvas, tip: Point, absDeg: Double) {
        val r = Math.toRadians(absDeg)
        val ux = cos(r).toFloat(); val uy = -sin(r).toFloat()   // ekran yönü
        val nx = -uy; val ny = ux
        val x = sx(tip); val y = sy(tip)
        val spread = d(6f) + gripOpen * d(14f)
        val l = d(22f)
        for (sgn in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(x + nx * d(6f) * sgn, y + ny * d(6f) * sgn)
            path.lineTo(x + nx * spread * sgn + ux * l * 0.5f, y + ny * spread * sgn + uy * l * 0.5f)
            path.lineTo(x + nx * (spread - d(5f)) * sgn + ux * l, y + ny * (spread - d(5f)) * sgn + uy * l)
            c.drawPath(path, clawPaint)
        }
        // bilek rotasyonu göstergesi: küçük ibre
        val rr = Math.toRadians((wristRoll - 90).toDouble())
        c.drawLine(x - ux * d(10f), y - uy * d(10f),
            x - ux * d(10f) + (nx * cos(rr) * d(10f)).toFloat(), y - uy * d(10f) + (ny * cos(rr) * d(10f)).toFloat(), groundPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val seg = pickSegment(e.x, e.y) ?: return true
                active = seg
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val pts = ArmKinematics.points(shoulder, elbow, wrist, len)
                val pivot = pts[seg.ordinal]
                val touchAbs = ArmKinematics.angleDeg(pivot, toWorld(e.x, e.y))
                val segAbs = ArmKinematics.absoluteAngles(shoulder, elbow, wrist)[seg.ordinal]
                grabOffset = touchAbs - segAbs   // segmentin tam ucundan tutmasanız da zıplamaz
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> active?.let { seg -> drag(seg, e.x, e.y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = null; invalidate() }
        }
        return true
    }

    private fun drag(seg: Segment, x: Float, y: Float) {
        val pts = ArmKinematics.points(shoulder, elbow, wrist, len)
        val pivot = pts[seg.ordinal]
        val p = toWorld(x, y)
        // pivota çok yakın dokunuşlarda açı kararsız: yok say
        if (hypot(p.x - pivot.x, p.y - pivot.y) * scale() < d(18f)) return
        val desired = ArmKinematics.angleDeg(pivot, p) - grabOffset
        val parent = ArmKinematics.parentAbsolute(seg, shoulder, elbow, wrist)
        val servo = ArmKinematics.servoForAbsolute(seg, desired, parent, minA[seg.ordinal], maxA[seg.ordinal])
        when (seg) {
            Segment.UPPER -> setPose(servo, elbow, wrist, notify = true)
            Segment.FORE -> setPose(shoulder, servo, wrist, notify = true)
            Segment.HAND -> setPose(shoulder, elbow, servo, notify = true)
        }
    }

    private fun pickSegment(x: Float, y: Float): Segment? {
        val pts = ArmKinematics.points(shoulder, elbow, wrist, len)
        val p = toWorld(x, y)
        // el kısa olduğu için uç bölgesine (kıskaç dahil) biraz pay
        val tip = pts[3]
        val handExtra = Point(tip.x + (tip.x - pts[2].x) * 0.5, tip.y + (tip.y - pts[2].y) * 0.5)
        val dists = doubleArrayOf(
            ArmKinematics.distanceToSegment(p, pts[0], pts[1]),
            ArmKinematics.distanceToSegment(p, pts[1], pts[2]),
            ArmKinematics.distanceToSegment(p, pts[2], handExtra),
        )
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in 0..2) if (dists[i] < bestD) { bestD = dists[i]; best = i }
        return if (bestD * scale() <= d(40f)) Segment.values()[best] else null
    }
}
