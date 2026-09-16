package com.minimal.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import com.minimal.carlauncher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * The installed-app list, kept live via LauncherApps.Callback.
 *
 * LauncherApps is used rather than PackageManager.queryIntentActivities because it is the
 * purpose-built launcher API: it returns resolved MAIN/LAUNCHER activities per user, gives
 * badged icons without a second round trip, and its callback replaces a manifest
 * BroadcastReceiver for package add/remove (which would be broadcast-restricted anyway).
 * It is still subject to manifest queries filtering - the MAIN/LAUNCHER intent entry in the
 * manifest is what makes it return the full list on API 30+.
 */
class AppRepository(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    /** Emits a package name whenever it is removed, so dock/card prefs can self-heal. */
    val packageRemoved = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val iconCache = IconCache(appContext)

    private var refreshJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            iconCache.evictPackage(packageName)
            scope.launch { packageRemoved.emit(packageName) }
            scheduleRefresh()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleRefresh()

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            iconCache.evictPackage(packageName)
            scheduleRefresh()
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean
        ) = scheduleRefresh()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean
        ) = scheduleRefresh()
    }

    fun start() {
        launcherApps?.registerCallback(callback)
        scheduleRefresh(delayMs = 0L)
    }

    fun stop() {
        launcherApps?.unregisterCallback(callback)
    }

    fun find(component: ComponentName): AppEntry? =
        _apps.value.firstOrNull { it.component == component }

    fun findByPackage(packageName: String): AppEntry? =
        _apps.value.firstOrNull { it.packageName == packageName }

    fun isInstalled(packageName: String): Boolean =
        appContext.packageManager.getLaunchIntentForPackage(packageName) != null

    private fun scheduleRefresh(delayMs: Long = 300L) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            _apps.value = loadApps()
        }
    }

    private suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.Default) {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val entries = queryViaLauncherApps().ifEmpty { queryViaPackageManager() }
        entries
            .filter { it.packageName != BuildConfig.APPLICATION_ID }
            .distinctBy { it.key }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun queryViaLauncherApps(): List<AppEntry> = try {
        launcherApps?.getActivityList(null, user).orEmpty().map { info ->
            val label = info.label?.toString().orEmpty().ifBlank { info.componentName.packageName }
            AppEntry(info.componentName, label, label.lowercase())
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Fallback for ROMs where LauncherApps misbehaves (returns 0 or 1 entries). */
    @Suppress("DEPRECATION")
    private fun queryViaPackageManager(): List<AppEntry> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
        return resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { ai.packageName }
            AppEntry(ComponentName(ai.packageName, ai.name), label, label.lowercase())
        }
    }
}
