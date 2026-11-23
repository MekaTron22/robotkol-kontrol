package com.mekatron.robotkol.core

/**
 * Telefon ↔ master Uno ASCII protokolü.
 * Çerçeve: "<GÖVDE*CS>"  CS = GÖVDE karakterlerinin XOR'u (2 haneli büyük harf hex).
 */
object Protocol {
    fun checksum(body: String): Int = body.fold(0) { acc, c -> acc xor (c.code and 0xFF) } and 0xFF

    fun frame(body: String): String = "<" + body + "*" + "%02X".format(checksum(body)) + ">"

    fun drive(left: Int, right: Int) = frame("D,${left.coerceIn(-255, 255)},${right.coerceIn(-255, 255)}")
    fun pose(p: Pose) = frame("J," + p.toArray().joinToString(","))
    fun speed(degPerSec: Int) = frame("V,${degPerSec.coerceIn(10, 250)}")
    fun light(on: Boolean) = frame("L,${if (on) 1 else 0}")
    fun home() = frame("Z")
    fun emergencyStop() = frame("X")
    fun heartbeat() = frame("H")
    fun hello() = frame("?")

    sealed class Message {
        data class Telemetry(val slaveOk: Boolean, val pose: Pose) : Message()
        data class Hello(val name: String, val version: Int) : Message()
    }

    /** Akış ayrıştırıcı: baytları parça parça besleyin, tamamlanan mesajları döndürür. */
    class Parser {
        private val buf = StringBuilder()
        private var inFrame = false
        var badFrames = 0; private set

        fun feed(data: ByteArray, len: Int = data.size): List<Message> {
            val out = ArrayList<Message>()
            for (i in 0 until len) {
                val c = (data[i].toInt() and 0xFF).toChar()
                when {
                    c == '<' -> { inFrame = true; buf.setLength(0) }
                    c == '>' -> {
                        if (inFrame) parseFrame(buf.toString())?.let(out::add)
                        inFrame = false
                    }
                    inFrame -> if (buf.length < 64) buf.append(c) else inFrame = false
                }
            }
            return out
        }

        fun feed(s: String) = s.toByteArray(Charsets.ISO_8859_1).let { feed(it) }

        private fun parseFrame(f: String): Message? {
            val star = f.lastIndexOf('*')
            if (star < 0) { badFrames++; return null }
            val body = f.substring(0, star)
            val cs = f.substring(star + 1).toIntOrNull(16)
            if (cs != checksum(body)) { badFrames++; return null }
            val parts = body.split(',')
            return try {
                when (parts[0]) {
                    "T" -> if (parts.size == 2 + Joint.COUNT) Message.Telemetry(
                        parts[1] == "1",
                        Pose(IntArray(Joint.COUNT) { parts[2 + it].trim().toInt() })
                    ) else null
                    "OK" -> Message.Hello(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "0" }.toInt())
                    else -> null
                }
            } catch (e: NumberFormatException) { badFrames++; null }
        }
    }
}
