package com.minimal.carlauncher

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.data.AppRepository
import com.minimal.carlauncher.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarLauncherApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var appRepository: AppRepository
        private set

    lateinit var updateRepository: UpdateRepository
        private set

    /**
     * Held in memory rather than in prefs so the dashcam is started once per launcher process.
     * The launcher process starts at boot and stays alive, so in practice that is once per boot,
     * and it survives the activity recreate that a theme switch causes.
     */
    var dashcamAutoStartDone = false

    override fun onCreate() {
        super.onCreate()

        Prefs.init(this)

        // Applied before any activity exists, so the very first inflate already uses the
        // right resources - no theme flash, no recreate at boot.
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode)

        installCrashLogger()

        appRepository = AppRepository(this, appScope)
        appRepository.start()

        updateRepository = UpdateRepository(this, appScope)
    }

    /**
     * A launcher that crash-loops on a head unit with no second home app is only recoverable
     * over adb. Persisting the stack trace is the one realistic way to debug a device that is
     * sitting in a car - the About dialog can show this file.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(cacheDir, CRASH_LOG).appendText(
                    "\n=== $stamp on ${thread.name} (v${BuildConfig.VERSION_NAME}) ===\n$writer\n"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_LOG = "crash.log"
    }
}
