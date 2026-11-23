package com.mekatron.robotkol.core

/**
 * Referans duruşlar (servo açısı olarak; sıra: kök, omuz, dirsek, bilek, rotasyon, kıskaç).
 * Sahada test edilip gerekirse düzeltilecek başlangıç değerleridir.
 */
data class NamedPose(val name: String, val angles: IntArray, val builtIn: Boolean = false) {
    init { require(angles.size == Joint.COUNT) }
    override fun equals(other: Any?) = other is NamedPose && other.name == name && other.angles.contentEquals(angles)
    override fun hashCode() = name.hashCode() * 31 + angles.contentHashCode()

    fun encode() = name.replace("|", "/").replace(";", ",") + "|" + angles.joinToString(",")

    companion object {
        fun decode(s: String): NamedPose? {
            val parts = s.split('|')
            if (parts.size != 2) return null
            val a = parts[1].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (a.size != Joint.COUNT || a.any { it !in 0..180 }) return null
            return NamedPose(parts[0], a.toIntArray())
        }
    }
}

object PoseLibrary {
    val BUILT_IN = listOf(
        NamedPose("Ev / Park",            intArrayOf(90, 100, 50, 70, 90, 60), true),
        NamedPose("Kalibrasyon (hepsi 90°)", intArrayOf(90, 90, 90, 90, 90, 90), true),
        NamedPose("Öne uzan",             intArrayOf(90, 30, 90, 90, 90, 30), true),
        NamedPose("Yerden al",            intArrayOf(90, 45, 20, 50, 90, 30), true),
        NamedPose("Kavra",                intArrayOf(90, 45, 20, 50, 90, 105), true),
        NamedPose("Taşıma",               intArrayOf(90, 120, 30, 60, 90, 105), true),
        NamedPose("Sola bak",             intArrayOf(150, 100, 60, 80, 90, 60), true),
        NamedPose("Sağa bak",             intArrayOf(30, 100, 60, 80, 90, 60), true),
    )

    fun encodeAll(list: List<NamedPose>) = list.filter { !it.builtIn }.joinToString(";") { it.encode() }
    fun decodeAll(s: String?): List<NamedPose> =
        s.orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull { NamedPose.decode(it) }
}
