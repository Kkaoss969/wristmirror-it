package io.github.escosis.hwtfaceswap.account

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Initialized in Application.attachBaseContext, before content providers can initialize WebView. */
internal object HuaweiIdWebProcess {
    private var lease: HuaweiIdSessionFiles.Lease? = null
    private val claimed = AtomicBoolean(false)
    var profileRequest: String? = null
        private set
    var ready: Boolean = false
        private set

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 28 || Application.getProcessName() != context.packageName + ":huawei_id") return
        try {
            lease = HuaweiIdSessionFiles.tryLease(context.noBackupFilesDir) ?: return
            if (!HuaweiIdSessionFiles.purge(context.dataDir, context.cacheDir)) return
            val request = UUID.randomUUID().toString()
            WebView.setDataDirectorySuffix(HuaweiIdSessionFiles.suffix(request))
            profileRequest = request
            ready = true
        } catch (_: Exception) { ready = false }
        // Do NOT close lease: only real process death releases it after all provider threads have stopped.
    }

    fun claim(): Boolean = ready && claimed.compareAndSet(false, true)
}
