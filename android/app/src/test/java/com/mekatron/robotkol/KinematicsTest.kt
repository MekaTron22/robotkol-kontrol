package com.mekatron.robotkol

import com.mekatron.robotkol.core.ArmKinematics
import com.mekatron.robotkol.core.ArmKinematics.Segment
import com.mekatron.robotkol.core.Joint
import com.mekatron.robotkol.core.JointMapping
import com.mekatron.robotkol.core.NamedPose
import com.mekatron.robotkol.core.PoseLibrary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KinematicsTest {
    private val eps = 1e-9

    @Test fun elbowMountedPerpendicularToTheRightAt90() {
        // Omuz dik, dirsek 90: dirsek pivotu sağa çıkıntının ucunda, ön kol sağa yatay
        val len = ArmKinematics.Lengths()
        val pts = ArmKinematics.points(90, 90, 90, len)
        assertEquals(len.elbowOffset, pts[1].x, eps); assertEquals(len.upper, pts[1].y, eps)
        assertEquals(len.elbowOffset + len.fore + len.hand, pts[3].x, eps)   // sağa
        assertEquals(len.upper, pts[3].y, eps)                                // yatay
        assertEquals(90.0, ArmKinematics.elbowBendDeg(90), eps)
    }

    @Test fun elbowFoldsParallelBesideUpperArmWithoutCollision() {
        // Dirsek 0: ön kol aşağı katlanır, üst kola paralel; çıkıntı kadar sağında kalır
        val len = ArmKinematics.Lengths()
        for (shoulder in intArrayOf(45, 90, 120)) {
            val pts = ArmKinematics.points(shoulder, Joint.ELBOW.servoMin, 90, len)
            val abs = ArmKinematics.absoluteAngles(shoulder, Joint.ELBOW.servoMin, 90)
            assertEquals(abs[0] - 180.0, abs[1], eps)                         // paralel (ters yön)
            val corner = ArmKinematics.elbowCorner(shoulder, len)
            // ön kolun iki ucu da üst kol doğrusuna çıkıntı kadar uzak → çarpışma yok
            for (p in listOf(pts[1], pts[2]))
                assertEquals(len.elbowOffset, ArmKinematics.distanceToSegment(p, pts[0], corner), 1e-6)
        }
        assertEquals(180.0, ArmKinematics.elbowBendDeg(0), eps)
    }

    @Test fun elbowCanGoStraightUp() {
        // Dirsek 180: ön kol üst kolla düz devam (çıkıntı kadar sağda, dik)
        val len = ArmKinematics.Lengths()
        val pts = ArmKinematics.points(90, Joint.ELBOW.servoMax, 90, len)
        assertEquals(len.elbowOffset, pts.last().x, 1e-9)
        assertEquals(len.upper + len.fore + len.hand, pts.last().y, eps)
        // sürükleyerek de ulaşılabilmeli (sınıra kırpılmadan)
        val parent = ArmKinematics.parentAbsolute(Segment.FORE, 100, 90, 90)
        assertEquals(180, ArmKinematics.servoForAbsolute(Segment.FORE, 100.0, parent, 0, 180))
        assertEquals(0, ArmKinematics.servoForAbsolute(Segment.FORE, -80.0, parent, 0, 180))
    }

    @Test fun childFollowsParentRotation() {
        // Dirsek ve bilek sabitken omuz dönerse, ön kolun omza göre açısı değişmez
        val a = ArmKinematics.absoluteAngles(60, 40, 120)
        val b = ArmKinematics.absoluteAngles(100, 40, 120)
        assertEquals(a[1] - a[0], b[1] - b[0], eps)
        assertEquals(a[2] - a[1], b[2] - b[1], eps)
        assertEquals(40.0, b[1] - b[0] + ArmKinematics.ELBOW_MOUNT_DEG + 90, eps)
    }

    @Test fun draggingOnlyChangesThatJoint() {
        // Ön kolu mutlak 0°'ye (yatay ileri) getir: omuz 120 iken dirsek = 180 + (0 - 120) = 60
        val parent = ArmKinematics.parentAbsolute(Segment.FORE, 120, 90, 90)
        assertEquals(60, ArmKinematics.servoForAbsolute(Segment.FORE, 0.0, parent, 0, 180))
        // omuz 60 iken aynı istek: 180 + (0 - 60) = 120
        val p2 = ArmKinematics.parentAbsolute(Segment.FORE, 60, 90, 90)
        assertEquals(120, ArmKinematics.servoForAbsolute(Segment.FORE, 0.0, p2, 0, 180))
        // ön kolu üst kolun 30° ötesine istemek: 180 + 30 = 210 → üst sınır 180'e (paralel) kırpılır
        val p3 = ArmKinematics.parentAbsolute(Segment.FORE, 60, 90, 90)
        assertEquals(180, ArmKinematics.servoForAbsolute(Segment.FORE, 90.0, p3, 0, 180))
    }

    @Test fun shoulderWrapsToNearestLimit() {
        // -170° (geride, yere yakın) isteği 165'e kırpılmalı, 15'e "atlamamalı"
        assertEquals(165, ArmKinematics.servoForAbsolute(Segment.UPPER, -170.0, 0.0, 15, 165))
        assertEquals(15, ArmKinematics.servoForAbsolute(Segment.UPPER, -10.0, 0.0, 15, 165))
        assertEquals(45, ArmKinematics.servoForAbsolute(Segment.UPPER, 45.0, 0.0, 15, 165))
    }

    @Test fun inversionRoundTrip() {
        val m = JointMapping(setOf(Joint.GRIP, Joint.ROOT))
        assertEquals(70, m.uiMin(Joint.GRIP))
        assertEquals(160, m.uiMax(Joint.GRIP))
        val ui = intArrayOf(30, 100, 50, 70, 90, 120)
        val servo = m.toServoPose(ui)
        assertEquals(150, servo[Joint.ROOT])
        assertEquals(60, servo[Joint.GRIP])
        assertArrayEquals(ui, m.fromServoPose(servo))
    }

    @Test fun builtInPosesWithinLimits() {
        for (p in PoseLibrary.BUILT_IN) for (j in Joint.values()) {
            val v = p.angles[j.ordinal]
            assertTrue("${p.name} ${j.label}=$v", v in j.servoMin..j.servoMax)
        }
        assertArrayEquals(Joint.values().map { it.home }.toIntArray(), PoseLibrary.BUILT_IN[0].angles)
    }

    @Test fun reachingPosesStayAboveGroundLine() {
        // Zemin çizgisi omuz pivotunun ~0.35 kol boyu altında; hazır duruşlar oraya girmemeli
        for (p in PoseLibrary.BUILT_IN) {
            val pts = ArmKinematics.points(p.angles[1], p.angles[2], p.angles[3])
            assertTrue("${p.name} uç çok aşağıda: ${pts.last()}", pts.all { it.y > -0.35 })
        }
    }

    @Test fun userPoseEncoding() {
        val list = listOf(NamedPose("Deneme|1", intArrayOf(1, 20, 3, 4, 5, 30)))
        val back = PoseLibrary.decodeAll(PoseLibrary.encodeAll(list))
        assertEquals("Deneme/1", back.single().name)
        assertArrayEquals(intArrayOf(1, 20, 3, 4, 5, 30), back.single().angles)
    }
}
