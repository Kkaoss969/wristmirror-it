package io.github.escosis.hwtfaceswap

/** Gadgetbridge 的本地 HWT 登记规则；只分配候选，不执行任何设备操作。 */
internal object DirectInstallTargetPolicy {
    const val VERSION = "1.0.0"

    /**
     * 运动健康 17.0.8.320 会把首位 6～9 的表盘 ID 归一化（首位减 5）并标记为会员免费表盘。
     * 本地生成的 ID 因此固定在首位 2 的九位区间，不进入会员免费判定。
     */
    const val FIRST_DIGIT = '2'
    const val MIN_SAFE_ID = 200_000_000

    /** 同时作为调用方的随机上界，使取样区间与接受区间一致。 */
    const val MAX_SAFE_ID = 299_999_999

    private const val SPAN = MAX_SAFE_ID - MIN_SAFE_ID + 1

    fun isSafeId(value: Int): Boolean = value in MIN_SAFE_ID..MAX_SAFE_ID

    /** 已在区间内时原样保留；低于区间的候选折入区间，保证分布仍覆盖整个首位 2 区间。 */
    fun toSafeId(value: Int): Int? = when {
        value in MIN_SAFE_ID..MAX_SAFE_ID -> value
        value in 1 until MIN_SAFE_ID -> MIN_SAFE_ID + (value - 1) % SPAN
        else -> null
    }

    fun sameNumericId(a: String, b: String): Boolean =
        a.trimStart('0').ifEmpty { "0" } == b.trimStart('0').ifEmpty { "0" }

    fun allocate(existingIds: Collection<String>, next: () -> Int): String? {
        repeat(32) {
            val value = toSafeId(next()) ?: return@repeat
            val id = value.toString()
            if (existingIds.none { sameNumericId(it, id) }) return id
        }
        return null
    }
}