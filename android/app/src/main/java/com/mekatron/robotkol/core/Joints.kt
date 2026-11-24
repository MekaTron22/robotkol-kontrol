package com.mekatron.robotkol.core

/**
 * Kol eklemleri – sıra, firmware'deki sıra ile aynıdır.
 * servoMin/servoMax: MUTLAK (donanım) sınırlar – slave'deki JOINT_MIN/MAX ile aynı olmalı.
 * Kullanıcı bunların içinde kendi çalışma aralığını [JointLimits] ile ayarlar.
 */
enum class Joint(val label: String, val servoMin: Int, val servoMax: Int, val home: Int) {
    ROOT("Kök", 0, 180, 90),
    SHOULDER("Omuz", 15, 165, 100),
    ELBOW("Dirsek", 0, 180, 90),
    WRIST_PITCH("Bilek", 0, 180, 70),
    WRIST_ROLL("Rotasyon", 0, 180, 90),
    GRIP("Kıskaç", 0, 65, 30);

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
 * Kullanıcının ayarladığı çalışma aralığı (servo derecesi). Her eklemde
 * mutlak sınırların içinde kalır ve min < max olur. Telefonda saklanır.
 */
class JointLimits(min: IntArray = IntArray(Joint.COUNT) { Joint.values()[it].servoMin },
                  max: IntArray = IntArray(Joint.COUNT) { Joint.values()[it].servoMax }) {
    private val lo = IntArray(Joint.COUNT)
    private val hi = IntArray(Joint.COUNT)

    init {
        require(min.size == Joint.COUNT && max.size == Joint.COUNT)
        for (j in Joint.values()) {
            val i = j.ordinal
            var a = min[i].coerceIn(j.servoMin, j.servoMax)
            var b = max[i].coerceIn(j.servoMin, j.servoMax)
            if (a > b) { val t = a; a = b; b = t }
            if (a == b) { if (b < j.servoMax) b++ else a-- }   // en az 1° aralık
            lo[i] = a; hi[i] = b
        }
    }

    fun min(j: Joint) = lo[j.ordinal]
    fun max(j: Joint) = hi[j.ordinal]
    fun clamp(j: Joint, v: Int) = v.coerceIn(lo[j.ordinal], hi[j.ordinal])
    fun isDefault(j: Joint) = lo[j.ordinal] == j.servoMin && hi[j.ordinal] == j.servoMax

    /** Yeni sınırlarla kopya (diğer eklemler aynı kalır). */
    fun with(j: Joint, min: Int, max: Int) = JointLimits(
        lo.copyOf().also { it[j.ordinal] = min }, hi.copyOf().also { it[j.ordinal] = max })

    /** Saklama biçimi: "min-max,min-max,..." (eklem sırasıyla) */
    fun encode() = Joint.values().joinToString(",") { "${min(it)}-${max(it)}" }

    override fun equals(other: Any?) = other is JointLimits && other.lo.contentEquals(lo) && other.hi.contentEquals(hi)
    override fun hashCode() = lo.contentHashCode() * 31 + hi.contentHashCode()
    override fun toString() = Joint.values().joinToString(", ") { "${it.label}=${min(it)}..${max(it)}" }

    companion object {
        val DEFAULT = JointLimits()

        /** Bozuk/eksik veride varsayılana döner; her değer yine mutlak sınırlara kırpılır. */
        fun decode(s: String?): JointLimits {
            val parts = s.orEmpty().split(',')
            if (parts.size != Joint.COUNT) return DEFAULT
            val mn = IntArray(Joint.COUNT); val mx = IntArray(Joint.COUNT)
            for (i in parts.indices) {
                val ab = parts[i].split('-')
                if (ab.size != 2) return DEFAULT
                mn[i] = ab[0].trim().toIntOrNull() ?: return DEFAULT
                mx[i] = ab[1].trim().toIntOrNull() ?: return DEFAULT
            }
            return JointLimits(mn, mx)
        }
    }
}

/**
 * Uygulama tarafındaki açı ile servoya giden açı arasındaki dönüşüm.
 * Servo ters takıldıysa (invert) 180 - açı gönderilir; böylece arayüz hep aynı yönde çalışır.
 */
class JointMapping(private val inverted: Set<Joint> = emptySet(), val limits: JointLimits = JointLimits.DEFAULT) {
    fun isInverted(j: Joint) = j in inverted
    fun toServo(j: Joint, ui: Int) = if (j in inverted) 180 - ui else ui
    fun fromServo(j: Joint, servo: Int) = if (j in inverted) 180 - servo else servo
    fun uiMin(j: Joint) = if (j in inverted) 180 - limits.max(j) else limits.min(j)
    fun uiMax(j: Joint) = if (j in inverted) 180 - limits.min(j) else limits.max(j)
    fun clampUi(j: Joint, v: Int) = v.coerceIn(uiMin(j), uiMax(j))

    /** Arayüz pozunu servo pozuna çevirir. */
    fun toServoPose(ui: IntArray): Pose =
        Pose(IntArray(Joint.COUNT) { i -> val j = Joint.values()[i]; toServo(j, clampUi(j, ui[i])) })

    fun fromServoPose(p: Pose): IntArray =
        IntArray(Joint.COUNT) { i -> val j = Joint.values()[i]; fromServo(j, p[j]) }
}
