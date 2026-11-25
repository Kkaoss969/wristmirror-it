package io.github.escosis.hwtfaceswap.account

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Main-process coordinator. Never initialize WebView/CookieManager or make synchronous cross-process Binder calls here. */
object HuaweiIdWebSession {
    private class Session(val state: HuaweiIdOwnerState) {
        var closeEndpoint: IBinder? = null // guarded by this Session's monitor, together with owner retirement
        val capability = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != HuaweiIdWebOwner.HELLO) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(HuaweiIdWebOwner.DESCRIPTOR)
                val token = data.readString()
                val endpoint = data.readStrongBinder()
                val accepted = synchronized(this@Session) {
                    val current = getCallingUid() == android.os.Process.myUid() && active.get() === this@Session
                    if (current && endpoint != null && state.claim(token)) { closeEndpoint = endpoint; true } else false
                }
                if (endpoint != null) HuaweiIdWebOwner.acknowledge(endpoint, token, accepted)
                return true
            }
        }
    }
    private val active = AtomicReference<Session?>(null)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanup = Mutex()

    fun reserve(token: String): Boolean {
        if (!HuaweiIdWebPolicy.validRequest(token)) return false
        while (true) {
            val old = active.get()
            if (old != null && !old.state.isRetired) return false
            if (active.compareAndSet(old, Session(HuaweiIdOwnerState(token)))) return true
        }
    }
    fun release(token: String) {
        val session = active.get()?.takeIf { it.state.token == token } ?: return
        synchronized(session) { session.state.retire(); active.compareAndSet(session, null) }
    }
    internal fun owner(token: String): IBinder? = active.get()?.takeIf { it.state.isCurrent(token) }?.capability

    suspend fun prepare(context: Context, token: String): Boolean = withContext(Dispatchers.IO) {
        cleanup.withLock {
            active.get()?.state?.isCurrent(token) == true && purgeIfUnlocked(context.applicationContext) == true
        }
    }

    /** An Activity result is not proof of process death. The file lease must actually become available. */
    suspend fun clearAfterExit(context: Context): Boolean = withContext(Dispatchers.IO) {
        cleanup.withLock {
            val app = context.applicationContext
            repeat(100) {
                when (purgeIfUnlocked(app)) {
                    true -> return@withLock true
                    false -> return@withLock false
                    null -> delay(50)
                }
            }
            false
        }
    }

    private fun purgeIfUnlocked(context: Context): Boolean? = try {
        val lease = HuaweiIdSessionFiles.tryLease(context.noBackupFilesDir)
        lease?.use { HuaweiIdSessionFiles.purge(context.dataDir, context.cacheDir) }
    } catch (_: Exception) { false }

    fun pruneAtStartup(context: Context) {
        val app = context.applicationContext
        io.launch {
            val old = active.get()
            if (old == null || old.state.isRetired) {
                if (clearAfterExit(app) && old != null) release(old.state.token)
            }
        }
    }

    /** A stale owner only addresses its own existing Binder; cancellation can never cold-start or kill a newer Activity. */
    fun abandon(context: Context, token: String, launched: Boolean) {
        val app = context.applicationContext
        val old = active.get()?.takeIf { it.state.token == token } ?: return
        val endpoint = synchronized(old) { old.state.retire(); old.closeEndpoint }
        io.launch {
            if (launched && endpoint != null) HuaweiIdWebOwner.close(endpoint, token)
            if (clearAfterExit(app)) release(token)
        }
    }
}

/** Asynchronous live capability. One-way IPC never synchronously calls a possibly cached/frozen process. */
internal object HuaweiIdWebOwner {
    const val EXTRA = "huawei_id_owner"
    const val DESCRIPTOR = "io.github.escosis.hwtfaceswap.HuaweiIdOwner"
    const val HELLO = IBinder.FIRST_CALL_TRANSACTION
    const val ACK = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2
    fun hello(owner: IBinder, request: String, endpoint: IBinder): Boolean = send(owner, request, HELLO, endpoint, false)
    fun acknowledge(endpoint: IBinder, request: String?, accepted: Boolean) { send(endpoint, request, ACK, null, accepted) }
    fun close(endpoint: IBinder, request: String) { send(endpoint, request, CLOSE, null, false) }
    private fun send(target: IBinder, request: String?, code: Int, endpoint: IBinder?, accepted: Boolean): Boolean {
        if (!HuaweiIdWebPolicy.validRequest(request) || !target.isBinderAlive) return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(request)
            if (code == HELLO) data.writeStrongBinder(endpoint)
            if (code == ACK) data.writeInt(if (accepted) 1 else 0)
            target.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) { false }
        finally { data.recycle() }
    }
}
