package com.mekatron.robotkol.core

/** Kol eklemleri – sıra, firmware'deki sıra ile aynıdır. */
enum class Joint(val label: String, val servoMin: Int, val servoMax: Int, val home: Int) {
    ROOT("Kök", 0, 180, 90),
    SHOULDER("Omuz", 15, 165, 100),
    ELBOW("Dirsek", 0, 180, 50),
    WRIST_PITCH("Bilek", 0, 180, 70),
    WRIST_ROLL("Rotasyon", 0, 180, 90),
    GRIP("Kıskaç", 20, 110, 60);

    companion object { val COUNT = values().size }
}

/** 6 eklemin servo açıları (derece, 0..180). Değişmez. */
class Pose(angles: IntArray) {
    private val a = IntArray(Joint.COUNT) { i -> Joint.values()[i].let { j -> angles[i].coerceIn(j.servoMin, j.servoMax) } }

    init { require(angles.size == Joint.COUNT) { "6 açı gerekli" } }

    operator fun get(j: Joint) = a[j.ordinal]
    fun with(j: Joint, v: Int): Pose = Pose(a.copyOf().also { it[j.ordinal] = v })
    fun toArray(): IntArray = a.copyOf()

    override fun equals(other: Any?) = other is Pose && other.a.contentEquals(a)
    override fun hashCode() = a.contentHashCode()
    override fun toString() = Joint.values().joinToString(", ") { "${it.label}=${this[it]}" }

    companion object {
        val HOME = Pose(IntArray(Joint.COUNT) { Joint.values()[it].home })
    }
}

/**
 * Uygulama tarafındaki açı ile servoya giden açı arasındaki dönüşüm.
 * Servo ters takıldıysa (invert) 180 - açı gönderilir; böylece arayüz hep aynı yönde çalışır.
 */
class JointMapping(private val inverted: Set<Joint> = emptySet()) {
    fun isInverted(j: Joint) = j in inverted
    fun toServo(j: Joint, ui: Int) = if (j in inverted) 180 - ui else ui
    fun fromServo(j: Joint, servo: Int) = if (j in inverted) 180 - servo else servo
    fun uiMin(j: Joint) = if (j in inverted) 180 - j.servoMax else j.servoMin
    fun uiMax(j: Joint) = if (j in inverted) 180 - j.servoMin else j.servoMax
    fun clampUi(j: Joint, v: Int) = v.coerceIn(uiMin(j), uiMax(j))

    /** Arayüz pozunu servo pozuna çevirir. */
    fun toServoPose(ui: IntArray): Pose =
        Pose(IntArray(Joint.COUNT) { i -> val j = Joint.values()[i]; toServo(j, clampUi(j, ui[i])) })

    fun fromServoPose(p: Pose): IntArray =
        IntArray(Joint.COUNT) { i -> val j = Joint.values()[i]; fromServo(j, p[j]) }
}
