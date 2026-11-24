package com.mekatron.robotkol

import com.mekatron.robotkol.core.Joint
import com.mekatron.robotkol.core.Pose
import com.mekatron.robotkol.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun frameHasXorChecksum() {
        // 'H' = 0x48
        assertEquals("<H*48>", Protocol.heartbeat())
        val body = "D,120,-80"
        val x = body.fold(0) { a, c -> a xor c.code }
        assertEquals("<$body*%02X>".format(x), Protocol.drive(120, -80))
    }

    @Test fun driveIsClamped() {
        assertTrue(Protocol.drive(999, -999).startsWith("<D,255,-255*"))
    }

    @Test fun poseFrameOrderAndLimits() {
        val p = Pose(intArrayOf(90, 5, 50, 70, 90, 200))   // omuz min 15, kıskaç max 65
        assertTrue(Protocol.pose(p).startsWith("<J,90,15,50,70,90,65*"))
    }

    @Test fun parsesTelemetryAcrossChunks() {
        val parser = Protocol.Parser()
        val f = Protocol.frame("T,1,90,100,50,70,90,60")
        assertTrue(parser.feed("junk" + f.substring(0, 7)).isEmpty())   // çerçeve öncesi çöp yok sayılır
        val msgs = parser.feed(f.substring(7) + "\n")
        assertEquals(1, msgs.size)
        val t = msgs[0] as Protocol.Message.Telemetry
        assertTrue(t.slaveOk)
        assertEquals(100, t.pose[Joint.SHOULDER])
        assertEquals(60, t.pose[Joint.GRIP])
    }

    @Test fun rejectsCorruptedFrame() {
        val parser = Protocol.Parser()
        val good = Protocol.frame("T,1,90,100,50,70,90,60")
        val bad = good.replace("100", "190")
        assertTrue(parser.feed(bad).isEmpty())
        assertEquals(1, parser.badFrames)
        assertEquals(1, parser.feed(good).size)
    }

    @Test fun parsesHello() {
        val m = Protocol.Parser().feed(Protocol.frame("OK,ROVER,1")).single()
        assertEquals(Protocol.Message.Hello("ROVER", 1), m)
    }
}
