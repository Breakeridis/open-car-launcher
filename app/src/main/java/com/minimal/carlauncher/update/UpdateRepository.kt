package com.minimal.carlauncher.update

import android.content.Context
import android.net.ConnectivityManager
import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.SemVer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    /** GITHUB_OWNER / GITHUB_REPO still hold their TODO placeholders. */
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Owns the update lifecycle. Held at application scope so a download survives the update
 * dialog being closed and reopened.
 */
class UpdateRepository(
    context: Context,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val client = GitHubReleaseClient()
    private val downloader = ApkDownloader()

    private val _state = MutableStateFlow<UpdateState>(
        if (Constants.isUpdaterConfigured) UpdateState.Idle else UpdateState.NotConfigured
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Drives the red dot on the dock's info icon. Survives an offline reboot via prefs. */
    private val _badgeVisible = MutableStateFlow(computeBadgeFromCache())
    val badgeVisible: StateFlow<Boolean> = _badgeVisible.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        // A successful self-update restarts the process; tidy up the cached APK on the way back.
        val cachedTag = Prefs.cachedLatestTag
        if (cachedTag.isNotEmpty() && !SemVer.isNewer(cachedTag, BuildConfig.VERSION_NAME)) {
            ApkInstaller.clearDownloads(appContext)
            Prefs.pendingApkPath = ""
            Prefs.lastSeenReleaseTag = cachedTag
            _badgeVisible.value = false
        }
    }

    /** Called from HomeActivity.onStart. Respects the 6h throttle and skips when offline. */
    fun maybeCheck() {
        if (!Constants.isUpdaterConfigured) return
        val elapsed = System.currentTimeMillis() - Prefs.lastUpdateCheckMs
        if (elapsed < Constants.UPDATE_CHECK_INTERVAL_MS) return
        if (!isOnline()) return
        check(force = false)
    }

    /** The About dialog's "Check now" button - bypasses the throttle. */
    fun check(force: Boolean) {
        if (!Constants.isUpdaterConfigured) {
            _state.value = UpdateState.NotConfigured
            return
        }
        // Never interrupt an in-flight download to re-check.
        if (_state.value is UpdateState.Downloading) return
        if (checkJob?.isActive == true) return

        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            when (val result = client.fetchLatest()) {
                is GitHubReleaseClient.Result.Success -> onRelease(result.release)
                is GitHubReleaseClient.Result.SoftFailure ->
                    _state.value = if (force) UpdateState.Error("Could not reach GitHub")
                    else restoreCachedState()
                is GitHubReleaseClient.Result.Failure ->
                    _state.value = UpdateState.Error(result.message)
            }
        }
    }

    private fun onRelease(release: ReleaseInfo) {
        Prefs.lastUpdateCheckMs = System.currentTimeMillis()

        if (!SemVer.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
            Prefs.cachedLatestTag = release.tag
            Prefs.cachedLatestUrl = ""
            _badgeVisible.value = false
            _state.value = UpdateState.UpToDate
            return
        }

        Prefs.cachedLatestTag = release.tag
        Prefs.cachedLatestUrl = release.apkUrl

        // Already downloaded on a previous run?
        val apk = ApkInstaller.apkFileFor(appContext, release.tag)
        _state.value = if (apk.exists() && apk.length() > 0L) {
            UpdateState.ReadyToInstall(release, apk)
        } else {
            UpdateState.Available(release)
        }
        _badgeVisible.value = release.tag != Prefs.lastSeenReleaseTag
    }

    fun download(release: ReleaseInfo) {
        if (!release.hasApk) {
            _state.value = UpdateState.Error("This release has no APK attached")
            return
        }
        if (downloadJob?.isActive == true) return

        val dest = ApkInstaller.apkFileFor(appContext, release.tag)
        downloadJob = scope.launch {
            downloader.download(release.apkUrl, dest).collect { progress ->
                _state.value = when (progress) {
                    is DownloadProgress.Running ->
                        UpdateState.Downloading(release, progress.bytes, progress.total)
                    is DownloadProgress.Done -> {
                        Prefs.pendingApkPath = progress.file.absolutePath
                        UpdateState.ReadyToInstall(release, progress.file)
                    }
                    is DownloadProgress.Failed -> UpdateState.Error(progress.message)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val cached = Prefs.cachedLatestTag
        _state.value = if (cached.isNotEmpty()) restoreCachedState() else UpdateState.Idle
    }

    /** Marks the current release as seen, clearing the dock badge. */
    fun markSeen() {
        val tag = Prefs.cachedLatestTag
        if (tag.isNotEmpty()) Prefs.lastSeenReleaseTag = tag
        _badgeVisible.value = false
    }

    private fun restoreCachedState(): UpdateState {
        val tag = Prefs.cachedLatestTag
        if (tag.isEmpty() || !SemVer.isNewer(tag, BuildConfig.VERSION_NAME)) return UpdateState.Idle
        val release = ReleaseInfo(
            tag = tag,
            name = tag,
            body = "",
            htmlUrl = "",
            apkUrl = Prefs.cachedLatestUrl,
            apkSize = 0L
        )
        val apk = ApkInstaller.apkFileFor(appContext, tag)
        return if (apk.exists() && apk.length() > 0L) {
            UpdateState.ReadyToInstall(release, apk)
        } else {
            UpdateState.Available(release)
        }
    }

    private fun computeBadgeFromCache(): Boolean {
        val cached = Prefs.cachedLatestTag
        return cached.isNotEmpty() &&
            SemVer.isNewer(cached, BuildConfig.VERSION_NAME) &&
            cached != Prefs.lastSeenReleaseTag
    }

    private fun isOnline(): Boolean = try {
        appContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    } catch (e: Exception) {
        false
    }
}
