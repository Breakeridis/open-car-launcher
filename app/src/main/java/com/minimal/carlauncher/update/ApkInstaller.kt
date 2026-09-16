package com.minimal.carlauncher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.core.Constants
import java.io.File

object ApkInstaller {

    /** Where downloaded updates live. Matches the cache-path entry in res/xml/file_paths.xml. */
    fun updateDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun apkFileFor(context: Context, tag: String): File {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(updateDir(context), "car-launcher-$safeTag.apk")
    }

    /**
     * REQUEST_INSTALL_PACKAGES is granted at install time, but it is gated by a per-app op
     * the user grants in "Install unknown apps". This is that check.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    /**
     * Hands the APK to the system package installer.
     *
     * ACTION_VIEW with the package-archive MIME type, not ACTION_INSTALL_PACKAGE - the latter
     * is deprecated as of API 29 (our minSdk) and blocked on newer releases.
     *
     * @return null on success, or a human-readable reason it could not be started.
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists()) return "The downloaded file is gone"

        val uri = try {
            FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + Constants.FILE_PROVIDER_SUFFIX,
                apk
            )
        } catch (e: Exception) {
            return "Could not share the update file"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            // setDataAndType, never setData then setType - the second call clears the first.
            setDataAndType(uri, "application/vnd.android.package-archive")
            // The installer is a different app and cannot read our private cache without this.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            "No package installer on this device - install the APK manually from " +
                apk.absolutePath
        }
    }

    /** Called after a successful self-update so stale APKs do not sit in the cache forever. */
    fun clearDownloads(context: Context) {
        runCatching { updateDir(context).listFiles()?.forEach { it.delete() } }
    }
}
