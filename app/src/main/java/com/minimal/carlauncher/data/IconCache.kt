package com.minimal.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import com.minimal.carlauncher.R

/**
 * Pre-rasterises launcher icons to exactly the display size and caches the bitmaps.
 *
 * Why this matters: LauncherActivityInfo.getIcon() inflates and masks an AdaptiveIconDrawable
 * on every call. Doing that in onBindViewHolder is what makes an app drawer stutter. Here the
 * expensive work happens once, off the main thread, and every subsequent bind is a plain
 * bitmap draw at native size with no scaling.
 */
class IconCache(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val density = appContext.resources.displayMetrics.densityDpi
    private val sizePx = appContext.resources.getDimensionPixelSize(R.dimen.app_icon_size)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Non-blocking: returns the bitmap only if it is already rasterised. */
    fun peek(key: String): Bitmap? = cache.get(key)

    /** Blocking rasterisation. Call from a background dispatcher. */
    fun load(component: ComponentName): Bitmap? {
        val key = component.flattenToShortString()
        cache.get(key)?.let { return it }

        val drawable = resolveDrawable(component) ?: return null
        val bitmap = rasterise(drawable)
        cache.put(key, bitmap)
        return bitmap
    }

    fun evictPackage(packageName: String) {
        cache.snapshot().keys
            .filter { it.substringBefore('/') == packageName }
            .forEach { cache.remove(it) }
    }

    private fun resolveDrawable(component: ComponentName): Drawable? = try {
        val info = launcherApps
            ?.getActivityList(component.packageName, Process.myUserHandle())
            ?.firstOrNull { it.componentName == component }
        info?.getBadgedIcon(density)
            ?: appContext.packageManager.getApplicationIcon(component.packageName)
    } catch (e: Exception) {
        null
    }

    private fun rasterise(drawable: Drawable): Bitmap {
        // Already a correctly sized bitmap? Skip the redraw.
        if (drawable is BitmapDrawable) {
            val bm = drawable.bitmap
            if (bm != null && bm.width == sizePx && bm.height == sizePx) return bm
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return out
    }
}
