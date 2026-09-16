package com.minimal.carlauncher.util

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.View

object IntentUtil {

    /**
     * Launcher-grade start: passes source bounds so the system can run the zoom-from-icon
     * animation, and handles other user profiles. Falls back to a plain package launch.
     */
    fun startApp(context: Context, component: ComponentName, source: View?): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val bounds: Rect? = source?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        val opts = source?.let {
            ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        try {
            launcherApps?.startMainActivity(component, Process.myUserHandle(), bounds, opts)
            return true
        } catch (e: Exception) {
            // SecurityException on some ROMs, IllegalStateException if the activity vanished.
        }
        return launchPackage(context, component.packageName)
    }

    /** Launch by package name, so a stored pref survives an app update changing its main class. */
    fun launchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Accepts either "pkg" or the flattened "pkg/cls" form written by the pickers. */
    fun launchStored(context: Context, stored: String, source: View?): Boolean {
        if (stored.isBlank()) return false
        return if (stored.contains('/')) {
            val component = ComponentName.unflattenFromString(stored)
            if (component != null) startApp(context, component, source)
            else launchPackage(context, packageOf(stored))
        } else {
            launchPackage(context, stored)
        }
    }

    fun packageOf(stored: String): String = stored.substringBefore('/')

    fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun openSystemSettings(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_SETTINGS))

    fun openLocationSettings(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openAppDetails(context: Context, packageName: String): Boolean = startSafely(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
    )

    fun openHomeSettings(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_HOME_SETTINGS))
}
