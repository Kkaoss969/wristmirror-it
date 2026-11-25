package io.github.escosis.hwtfaceswap.account

import java.util.concurrent.atomic.AtomicBoolean

/** A launch may be claimed once, and a retired owner can never become current again. Contains no account data. */
internal class HuaweiIdOwnerState(val token: String) {
    private val claimed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    init { require(HuaweiIdWebPolicy.validRequest(token)) }
    val isRetired: Boolean get() = retired.get()
    fun isCurrent(request: String?): Boolean = request == token && !retired.get()
    fun claim(request: String?): Boolean = isCurrent(request) && claimed.compareAndSet(false, true) && isCurrent(request)
    fun retire() { retired.set(true) }
}
