package com.mekatron.robotkol.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kolun yandan görünüşü için İLERİ kinematik.
 *
 * Her segment, kendinden önceki segmente göre döner:
 *   omuz mutlak açısı   φ1 = omuz servo                              (0 = ileri yatay, 90 = dik)
 *   dirsek mutlak açısı φ2 = φ1 − ELBOW_MOUNT_DEG + (dirsek − 90)
 *   bilek mutlak açısı  φ3 = φ2 + (bilek − 90)                       (bilek 90 = düz devam)
 *
 * Dirsek çıkıntısı: üst kolun ucunda, kola dik ve SAĞA (ileri) doğru kısa bir çıkıntı
 * (Lengths.elbowOffset) vardır; dirsek pivotu bu çıkıntının ucundadır. Böylece ön kol aşağı
 * katlanıp üst kola paralel geldiğinde üst kolun YANINDAN geçer, ona çarpmaz.
 *
 * Dirsek montajı: dirsek servosu ortadayken (90) ön kol üst kola ELBOW_MOUNT_DEG (≈90°)
 * açıyla, sağa/ileri bakar. Dirsek 0 → ön kol aşağı katlanmış, üst kola paralel (çıkıntı
 * sayesinde yanında); dirsek 180 → ön kol yukarı, üst kolla düz devam.
 * Ters kinematik yoktur: bir eklemi sürüklemek yalnızca o eklemi değiştirir,
 * ondan sonraki segmentler ona yapışık olarak birlikte taşınır.
 * Koordinatlar: x ileri, y yukarı; omuz pivotu (0,0).
 */
object ArmKinematics {
    /**
     * Dirsek servosu 90° iken ön kolun üst kola göre montaj açısı (derece, sağa/ileri doğru).
     * Şimdilik ≈90 kabul edildi; gerçek ölçülen değer gelince yalnız burası değişir.
     */
    const val ELBOW_MOUNT_DEG = 90.0

    /**
     * Segment boyları (görsel oran, üst kol = 1).
     * elbowOffset: dirsek noktasındaki sağa çıkıntı (üst kola dik). Gerçek ölçü gelince güncellenir.
     */
    data class Lengths(
        val upper: Double = 1.0, val fore: Double = 0.9, val hand: Double = 0.45,
        val elbowOffset: Double = 0.28,
    ) {
        val reach get() = sqrt(upper * upper + elbowOffset * elbowOffset) + fore + hand
    }

    data class Point(val x: Double, val y: Double)

    /** 0: omuz, 1: dirsek, 2: bilek (sürüklenebilir segmentler) */
    enum class Segment(val joint: Joint) { UPPER(Joint.SHOULDER), FORE(Joint.ELBOW), HAND(Joint.WRIST_PITCH) }

    fun absoluteAngles(shoulder: Int, elbow: Int, wrist: Int): DoubleArray {
        val a1 = shoulder.toDouble()
        val a2 = a1 - ELBOW_MOUNT_DEG + (elbow - 90)
        val a3 = a2 + (wrist - 90)
        return doubleArrayOf(a1, a2, a3)
    }

    /** Üst kolun ucu (çıkıntının başladığı köşe). */
    fun elbowCorner(shoulder: Int, len: Lengths = Lengths()): Point {
        val r = Math.toRadians(shoulder.toDouble())
        return Point(len.upper * cos(r), len.upper * sin(r))
    }

    /** [omuz, dirsek pivotu (çıkıntı ucu), bilek, uç] noktaları */
    fun points(shoulder: Int, elbow: Int, wrist: Int, len: Lengths = Lengths()): List<Point> {
        val abs = absoluteAngles(shoulder, elbow, wrist)
        val pts = ArrayList<Point>(4)
        pts.add(Point(0.0, 0.0))
        // üst kol + sağa (saat yönünde 90°) çıkıntı
        val c = elbowCorner(shoulder, len)
        val ro = Math.toRadians(abs[0] - 90.0)
        var x = c.x + len.elbowOffset * cos(ro); var y = c.y + len.elbowOffset * sin(ro)
        pts.add(Point(x, y))
        val l = doubleArrayOf(len.fore, len.hand)
        for (i in 1..2) {
            val r = Math.toRadians(abs[i])
            x += l[i - 1] * cos(r); y += l[i - 1] * sin(r)
            pts.add(Point(x, y))
        }
        return pts
    }

    /** Bir segmentin ebeveyninin mutlak açısı (omuz için 0 = yatay zemin). */
    fun parentAbsolute(seg: Segment, shoulder: Int, elbow: Int, wrist: Int): Double {
        val abs = absoluteAngles(shoulder, elbow, wrist)
        return when (seg) { Segment.UPPER -> 0.0; Segment.FORE -> abs[0]; Segment.HAND -> abs[1] }
    }

    /** Servo açısı = ofset + göreli açı. Omuz 0, dirsek 90 + montaj açısı, bilek 90. */
    private fun offset(seg: Segment) = when (seg) {
        Segment.UPPER -> 0.0
        Segment.FORE -> 90.0 + ELBOW_MOUNT_DEG
        Segment.HAND -> 90.0
    }

    /** Ön kolun üst kola göre açısı (0 = düz devam, 90 = dik/sağa, 180 = aşağı katlanmış paralel). */
    fun elbowBendDeg(elbow: Int) = ELBOW_MOUNT_DEG - (elbow - 90)

    /**
     * Segmentin istenen mutlak açısından (derece) servo açısını hesaplar.
     * Sonuç, eklem aralığının ortasına göre ±180 penceresine katlanır ve [min,max]'a kırpılır;
     * böylece -170° gibi bir istek yanlış uca "atlamaz".
     */
    fun servoForAbsolute(seg: Segment, desiredAbs: Double, parentAbs: Double, min: Int, max: Int): Int {
        val mid = (min + max) / 2.0
        var servo = offset(seg) + (desiredAbs - parentAbs)
        while (servo < mid - 180) servo += 360
        while (servo >= mid + 180) servo -= 360
        return Math.round(servo).toInt().coerceIn(min, max)
    }

    fun angleDeg(from: Point, to: Point) = Math.toDegrees(atan2(to.y - from.y, to.x - from.x))

    fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = a.x + t * dx - p.x; val cy = a.y + t * dy - p.y
        return sqrt(cx * cx + cy * cy)
    }
}
