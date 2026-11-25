package io.github.escosis.hwtfaceswap

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebProcess
import io.github.escosis.hwtfaceswap.account.HuaweiIdWebSession

/** Keep website initialization out of the process that owns Bluetooth and device credentials. */
class WristMirrorApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        HuaweiIdWebProcess.initialize(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28 && getProcessName() == packageName) {
            HuaweiIdWebSession.pruneAtStartup(this)
        }
    }
}
