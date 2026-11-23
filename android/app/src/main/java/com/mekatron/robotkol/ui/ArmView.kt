package com.mekatron.robotkol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Etkileşimli kol kontrolü – yandan görünüş.
 *
 * Kullanıcı üst kol, ön kol veya el segmentine dokunup sürükler. Yalnızca o segmentin
 * eklemi döner (bir önceki segmente göre); sonraki segmentler ona bağlı olarak birlikte
 * taşınır. Tüm kolu birden hareket ettiren ters kinematik kullanılmaz.
 *
 * Sol üstteki eklem panelinde her eklem için −/+ ince ayar düğmeleri vardır (dokun: 1°,
 * basılı tut: sürekli). Sürükleme sırasında etkin eklemin izinli aralığı yay olarak, dirsekte
 * ayrıca üst kola paralel referans çizgisi gösterilir.
 */
class ArmView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Eklemler (arayüz açısı): omuz, dirsek, bilek */
    var shoulder = 100; private set
    var elbow = 90; private set
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

    // ---- ince ayar düğmeleri ----
    private var nudgeSeg = -1
    private var nudgeDir = 0
    private var nudgeRepeated = false
    private val nudgeRepeat = object : Runnable {
        override fun run() {
            if (nudgeSeg < 0) return
            nudgeRepeated = true
            nudge(nudgeSeg, nudgeDir)
            postDelayed(this, 70)
        }
    }

    // ---- boyalar ----
    private val card = RoverTheme.Card(ctx)
    private val gridPaint = RoverTheme.fill(RoverTheme.GRID)
    private val segPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE, 15f)
    private val segGlow = RoverTheme.stroke(ctx, RoverTheme.withAlpha(RoverTheme.ORANGE, 60), 30f)
    private val segHighlight = RoverTheme.stroke(ctx, RoverTheme.withAlpha(0xFFFFFFFF.toInt(), 70), 3f)
    private val hubOuter = RoverTheme.fill(RoverTheme.PANEL_2)
    private val hubRing = RoverTheme.stroke(ctx, RoverTheme.ORANGE_LIGHT, 3f)
    private val hubDot = RoverTheme.fill(RoverTheme.THUMB)
    private val bodyFill = RoverTheme.fill(RoverTheme.PANEL_2)
    private val bodyEdge = RoverTheme.stroke(ctx, RoverTheme.withAlpha(RoverTheme.ORANGE, 150), 2f)
    private val wheelFill = RoverTheme.fill(RoverTheme.STROKE)
    private val groundPaint = RoverTheme.stroke(ctx, RoverTheme.STROKE, 2f)
    private val ghostPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE_DIM, 1.5f).apply {
        pathEffect = DashPathEffect(floatArrayOf(8f, 10f), 0f)
    }
    private val rangePaint = RoverTheme.stroke(ctx, RoverTheme.withAlpha(RoverTheme.ACTIVE, 45), 22f).apply {
        strokeCap = Paint.Cap.BUTT
    }
    private val refPaint = RoverTheme.stroke(ctx, RoverTheme.withAlpha(RoverTheme.ACTIVE, 150), 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }
    private val clawPaint = RoverTheme.stroke(ctx, RoverTheme.ORANGE_LIGHT, 6f)
    private val rollPaint = RoverTheme.stroke(ctx, RoverTheme.THUMB, 2f)

    private val titlePaint = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 12f, bold = true).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = RoverTheme.text(ctx, RoverTheme.TEXT_FAINT, 10.5f).apply { textAlign = Paint.Align.LEFT }
    private val rowName = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 11f)
    private val rowValue = RoverTheme.text(ctx, RoverTheme.TEXT, 16f, bold = true)
    private val rowSub = RoverTheme.text(ctx, RoverTheme.TEXT_DIM, 9.5f)
    private val rowBg = RoverTheme.fill(RoverTheme.PANEL_2)
    private val btnBg = RoverTheme.fill(RoverTheme.STROKE)
    private val btnText = RoverTheme.text(ctx, RoverTheme.TEXT, 18f, bold = true)
    private val bubbleBg = RoverTheme.fill(RoverTheme.withAlpha(RoverTheme.ORANGE, 38))
    private val bubbleText = RoverTheme.text(ctx, RoverTheme.ACTIVE, 13f, bold = true)
    private val path = Path()

    private val names = arrayOf("OMUZ", "DİRSEK", "BİLEK")

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

    private fun jointValue(i: Int) = when (i) { 0 -> shoulder; 1 -> elbow; else -> wrist }

    // ---- yerleşim ----
    private fun panelW() = d(150f)
    private fun rowTop(i: Int) = d(40f) + i * d(50f)
    private fun rowH() = d(44f)
    private fun btnR() = d(15f)
    private fun minusX() = d(16f) + btnR() + d(4f)
    private fun plusX() = panelW() - btnR() - d(4f)
    private fun rowCy(i: Int) = rowTop(i) + rowH() / 2

    // ---- ekran ↔ dünya dönüşümü (dünya: omuz pivotu (0,0), y yukarı) ----
    private fun pivotY() = height - d(46f)
    private fun pivotX(): Float {
        val l = panelW() + d(12f)
        return l + (width - l) * 0.45f
    }
    private fun scale(): Float {
        val up = (pivotY() - d(36f)) / len.reach   // üstte kıskaç payı
        val side = (min(pivotX() - panelW() - d(12f), width - pivotX() - d(16f)) - d(24f)) / len.reach
        return min(up, side).toFloat().coerceAtLeast(1f)
    }
    private fun sx(p: Point) = pivotX() + (p.x * scale()).toFloat()
    private fun sy(p: Point) = pivotY() - (p.y * scale()).toFloat()
    private fun toWorld(x: Float, y: Float) = Point(((x - pivotX()) / scale()).toDouble(), ((pivotY() - y) / scale()).toDouble())

    override fun onDraw(c: Canvas) {
        card.draw(c, width, height, highlighted = active != null)
        val px = pivotX(); val py = pivotY()

        // noktalı ızgara (sağ çalışma alanı)
        val step = d(24f)
        var gx = panelW() + d(24f)
        while (gx < width - d(10f)) {
            var gy = d(16f)
            while (gy < py + d(20f)) { c.drawCircle(gx, gy, d(1.2f), gridPaint); gy += step }
            gx += step
        }

        drawPanel(c)

        val pts = ArmKinematics.points(shoulder, elbow, wrist, len)
        val abs = ArmKinematics.absoluteAngles(shoulder, elbow, wrist)

        // erişim yayı
        val reach = (len.reach * scale()).toFloat()
        c.drawArc(px - reach, py - reach, px + reach, py + reach, 180f, 180f, false, ghostPaint)

        // zemin + rover gövdesi
        val groundY = py + d(40f)
        c.drawLine(panelW() + d(16f), groundY, width - d(16f), groundY, groundPaint)
        c.drawRoundRect(px - d(64f), py + d(8f), px + d(64f), py + d(28f), d(8f), d(8f), bodyFill)
        c.drawRoundRect(px - d(64f), py + d(8f), px + d(64f), py + d(28f), d(8f), d(8f), bodyEdge)
        for (wx in floatArrayOf(-44f, 44f)) c.drawCircle(px + d(wx), py + d(30f), d(9f), wheelFill)
        c.drawRoundRect(px - d(16f), py - d(6f), px + d(16f), py + d(10f), d(4f), d(4f), bodyFill)
        c.drawRoundRect(px - d(16f), py - d(6f), px + d(16f), py + d(10f), d(4f), d(4f), bodyEdge)

        // sürüklenirken: etkin eklemin izinli aralığı ve referanslar
        active?.let { drawActiveGuides(c, it, pts, abs) }

        // segmentler
        for (i in 0..2) {
            val a = pts[i]; val b = pts[i + 1]
            if (active?.ordinal == i) c.drawLine(sx(a), sy(a), sx(b), sy(b), segGlow)
            segPaint.color = if (active?.ordinal == i) RoverTheme.ACTIVE else RoverTheme.ORANGE
            segPaint.strokeWidth = d(if (i == 2) 11f else 15f)
            c.drawLine(sx(a), sy(a), sx(b), sy(b), segPaint)
            c.drawLine(sx(a), sy(a), sx(b), sy(b), segHighlight)
        }
        drawClaw(c, pts[3], abs[2])

        // eklem göbekleri
        for (i in 0..2) {
            val x = sx(pts[i]); val y = sy(pts[i])
            c.drawCircle(x, y, d(11f), hubOuter)
            hubRing.color = if (active?.ordinal == i) RoverTheme.ACTIVE else RoverTheme.ORANGE_LIGHT
            c.drawCircle(x, y, d(11f), hubRing)
            c.drawCircle(x, y, d(3.5f), hubDot)
        }

        // sürüklenen eklemin değer balonu (sağ üst köşe, kolu örtmez)
        active?.let { seg ->
            val i = seg.ordinal
            val txt = "${names[i]}  ${jointValue(i)}°" +
                if (i == 1) "  ·  üst kola ∠${ArmKinematics.elbowBendDeg(elbow).roundToInt()}°" else ""
            val tw = bubbleText.measureText(txt) + d(20f)
            val bx = width - tw - d(14f); val by = d(14f)
            c.drawRoundRect(bx, by, bx + tw, by + d(26f), d(13f), d(13f), bubbleBg)
            c.drawText(txt, bx + tw / 2, by + d(18f), bubbleText)
        }
    }

    private fun drawPanel(c: Canvas) {
        c.drawText("KOL", d(18f), d(26f), titlePaint)
        for (i in 0..2) {
            val top = rowTop(i); val cy = rowCy(i)
            val isActive = active?.ordinal == i
            rowBg.color = if (isActive) RoverTheme.withAlpha(RoverTheme.ORANGE, 40) else RoverTheme.PANEL_2
            c.drawRoundRect(d(12f), top, panelW() + d(2f), top + rowH(), d(12f), d(12f), rowBg)
            for ((x, sign) in listOf(minusX() to -1, plusX() to 1)) {
                btnBg.color = if (nudgeSeg == i && nudgeDir == sign) RoverTheme.ORANGE else RoverTheme.STROKE
                c.drawCircle(x, cy, btnR(), btnBg)
                c.drawText(if (sign < 0) "−" else "+", x, cy + d(6f), btnText)
            }
            val mid = (minusX() + plusX()) / 2
            c.drawText(names[i], mid, top + d(14f), rowName)
            rowValue.color = if (isActive) RoverTheme.ACTIVE else RoverTheme.TEXT
            c.drawText("${jointValue(i)}°", mid, top + d(32f), rowValue)
            if (i == 1) c.drawText("üst kola ∠${ArmKinematics.elbowBendDeg(elbow).roundToInt()}°", mid, top + d(42f), rowSub)
        }
        val hy = rowTop(3) + d(8f)
        if (hy + d(24f) < height) {
            c.drawText("segmenti sürükle:", d(16f), hy, hintPaint)
            c.drawText("yalnız o eklem döner", d(16f), hy + d(14f), hintPaint)
        }
    }

    /** Etkin eklemin izinli dönüş aralığı (yay) ve dirsekte üst kola paralel referans. */
    private fun drawActiveGuides(c: Canvas, seg: Segment, pts: List<Point>, abs: DoubleArray) {
        val i = seg.ordinal
        val pivot = pts[i]
        val x = sx(pivot); val y = sy(pivot)
        val parent = ArmKinematics.parentAbsolute(seg, shoulder, elbow, wrist)
        // servo s → mutlak açı = parent + (s − ofset); ofset = mutlak − parent − ... : mevcut değerden türet
        val offset = jointValue(i) - (abs[i] - parent)
        val a0 = parent + (minA[i] - offset); val a1 = parent + (maxA[i] - offset)
        val rr = d(if (i == 2) 34f else 46f)
        // ekranda y aşağı: açı −θ
        c.drawArc(x - rr, y - rr, x + rr, y + rr, (-a1).toFloat(), (a1 - a0).toFloat(), false, rangePaint)
        if (seg == Segment.FORE) {
            // üst kolun uzantısı = paralel konum (dirsek ≈ 180)
            val r = Math.toRadians(parent)
            val l = len.fore * scale()
            c.drawLine(x, y, x + (l * cos(r)).toFloat(), y - (l * sin(r)).toFloat(), refPaint)
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
            x - ux * d(10f) + (nx * cos(rr) * d(10f)).toFloat(), y - uy * d(10f) + (ny * cos(rr) * d(10f)).toFloat(), rollPaint)
    }

    // ---- dokunma ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopNudge()
                pickNudge(e.x, e.y)?.let { (seg, dir) ->
                    nudgeSeg = seg; nudgeDir = dir; nudgeRepeated = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    postDelayed(nudgeRepeat, 400)
                    invalidate()
                    return true
                }
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
            MotionEvent.ACTION_UP -> {
                // kısa dokunuş: tek adım
                if (nudgeSeg >= 0 && !nudgeRepeated) nudge(nudgeSeg, nudgeDir)
                stopNudge(); active = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { stopNudge(); active = null; invalidate() }
        }
        return true
    }

    private fun stopNudge() {
        removeCallbacks(nudgeRepeat)
        if (nudgeSeg >= 0) { nudgeSeg = -1; nudgeDir = 0; invalidate() }
    }

    private fun nudge(i: Int, dir: Int) {
        val before = Triple(shoulder, elbow, wrist)
        when (i) {
            0 -> setPose(shoulder + dir, elbow, wrist, notify = true)
            1 -> setPose(shoulder, elbow + dir, wrist, notify = true)
            else -> setPose(shoulder, elbow, wrist + dir, notify = true)
        }
        // sınıra dayandıysa hissettir
        if (before == Triple(shoulder, elbow, wrist)) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun pickNudge(x: Float, y: Float): Pair<Int, Int>? {
        val hit = btnR() + d(6f)
        for (i in 0..2) {
            val cy = rowCy(i)
            if (hypot(x - minusX(), y - cy) <= hit) return i to -1
            if (hypot(x - plusX(), y - cy) <= hit) return i to 1
        }
        return null
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
        if (x < panelW() + d(8f)) return null   // panel bölgesi
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

    override fun onDetachedFromWindow() { stopNudge(); super.onDetachedFromWindow() }
}
