package io.github.escosis.hwtfaceswap

/** 仅接受三段非负整数和可选的 v 前缀，不把预发行后缀截成正式版本。 */
internal class UpdateVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<UpdateVersion> {
    val version: String
        get() = "$major.$minor.$patch"

    override fun compareTo(other: UpdateVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        private val pattern = Regex("[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parse(value: String): UpdateVersion? {
            // 三个整数各最多十位；先限长，再匹配，避免超长输入消耗资源。
            if (value.length !in 5..33) return null
            val match = pattern.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return UpdateVersion(major, minor, patch)
        }
    }
}
