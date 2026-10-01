package com.minimal.carlauncher.radio

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.minimal.carlauncher.core.BinaryXml
import com.minimal.carlauncher.core.DexStrings
import com.minimal.carlauncher.core.XmlElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Looks inside a vendor tuner app to find how it shares its state.
 *
 * Built for `com.nwd.radio` (Allwinner T507 / A133, NWD K2401P), which publishes neither a
 * media session nor a notification. Its frequency must travel over a private channel - a
 * broadcast, a bound service, a content provider or a settings value - and all of those are
 * visible from the outside: declared in its manifest, named in its code, or readable in
 * Settings. Everything here is read-only; each section fails independently into a report line.
 */
object ApkInspector {

    private val KEYWORDS = listOf(
        "radio", "freq", "tuner", "band", "rds", "station", "preset", "seek", "fm", "am_", "nwd"
    )

    /** Dotted names (actions, classes) or CONSTANT_CASE keys that mention a keyword. */
    private val CANDIDATE = Regex("""^[A-Za-z][\w$]*(\.[\w$]+)+$|^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$""")

    private const val MAX_STRINGS = 120
    private const val MAX_LEN = 80

    suspend fun inspect(context: Context, pkg: String): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("Package: $pkg")
            val info = section(this, "package") { packageFacts(context, pkg) }
            appendLine()
            val apk = info?.sourceDir
            if (apk == null) {
                appendLine("APK path unknown - cannot read manifest or code.")
            } else {
                appendLine("== Components (manifest) ==")
                section(this, "manifest") { appendComponents(this, apk) }
                appendLine()
                appendLine("== Code strings ==")
                section(this, "code") { appendCodeStrings(this, apk, pkg) }
            }
            appendLine()
            appendLine("== Settings (change station, then Refresh) ==")
            section(this, "settings") { appendSettings(this, context) }
        }
    }

    private inline fun <T> section(sb: StringBuilder, name: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        sb.appendLine("[$name failed: ${e.javaClass.simpleName}: ${e.message?.take(MAX_LEN)}]")
        null
    }

    // ------------------------------------------------------------------ package

    private fun StringBuilder.packageFacts(context: Context, pkg: String): ApplicationInfo {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val ai = pi.applicationInfo ?: throw IllegalStateException("no applicationInfo")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        appendLine("Version: ${pi.versionName} ($code)")
        appendLine("System app: ${ai.flags and ApplicationInfo.FLAG_SYSTEM != 0}")
        appendLine("Shared UID: ${pi.sharedUserId ?: "-"}")
        appendLine("APK: ${ai.sourceDir}")
        ai.splitSourceDirs?.forEach { appendLine("Split: $it") }
        pi.requestedPermissions
            ?.filter { p -> KEYWORDS.any { p.lowercase(Locale.ROOT).contains(it) } || "nwd" in p }
            ?.forEach { appendLine("Uses permission: $it") }
        return ai
    }

    // ----------------------------------------------------------------- manifest

    private fun appendComponents(sb: StringBuilder, apk: String) {
        val xml = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: run {
                sb.appendLine("No AndroidManifest.xml in APK")
                return
            }
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val elements = BinaryXml.parse(xml)
        if (elements.isEmpty()) {
            sb.appendLine("Manifest could not be parsed")
            return
        }

        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
        var i = 0
        var shown = 0
        while (i < elements.size) {
            val e = elements[i]
            if (e.tag in componentTags) {
                // Collect the actions declared under this component.
                val actions = mutableListOf<String>()
                var j = i + 1
                while (j < elements.size && elements[j].depth > e.depth) {
                    if (elements[j].tag == "action") elements[j].attrs["name"]?.let { actions += it }
                    j++
                }
                val exported = e.attrs["exported"]
                // Only what another app could talk to: exported, or with intent filters.
                if (exported == "true" || actions.isNotEmpty() || e.tag == "provider") {
                    sb.appendLine(describe(e, exported))
                    actions.forEach { sb.appendLine("    action ${it.take(MAX_LEN)}") }
                    shown++
                }
                i = j
            } else {
                i++
            }
        }
        if (shown == 0) sb.appendLine("No exported components or intent filters")

        // Permissions the radio defines itself are often what guards its broadcasts.
        elements.filter { it.tag == "permission" }.forEach {
            sb.appendLine("defines permission ${it.attrs["name"]}")
        }
    }

    private fun describe(e: XmlElement, exported: String?): String {
        val name = e.attrs["name"].orEmpty().take(MAX_LEN)
        val parts = mutableListOf("${e.tag} $name")
        exported?.let { parts += "exported=$it" }
        e.attrs["permission"]?.let { parts += "perm=$it" }
        e.attrs["authorities"]?.let { parts += "auth=$it" }
        return "• " + parts.joinToString("  ")
    }

    // --------------------------------------------------------------------- code

    private fun appendCodeStrings(sb: StringBuilder, apk: String, pkg: String) {
        val all = LinkedHashSet<String>()
        var source = "APK dex"
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry -> all += DexStrings.read(zip.getInputStream(entry).use { it.readBytes() }) }
        }
        if (all.isEmpty()) {
            // Pre-optimised system app: the code lives in oat/<abi>/*.vdex|*.odex beside the APK.
            val oat = File(File(apk).parentFile, "oat")
            val files = oat.walkTopDown().maxDepth(2)
                .filter { it.isFile && (it.name.endsWith(".vdex") || it.name.endsWith(".odex")) }
                .toList()
            if (files.isEmpty()) {
                sb.appendLine("No dex in APK and no oat files readable next to it")
                return
            }
            source = files.joinToString { it.name }
            files.forEach { f -> all += DexStrings.rawStrings(f.readBytes()) }
        }

        val picked = all.asSequence()
            .map { it.trim() }
            .filter { it.length in 4..200 && CANDIDATE.matches(it) }
            .filter { s ->
                val l = s.lowercase(Locale.ROOT)
                KEYWORDS.any { it in l } || l.startsWith(pkg.substringBeforeLast('.'))
            }
            // Framework / library classes are noise; vendor names are the point.
            .filterNot { it.startsWith("android.") && !it.startsWith("android.intent.action.") }
            .filterNot { it.startsWith("androidx.") || it.startsWith("kotlin") || it.startsWith("java.") }
            .filterNot { it.startsWith("L") && it.contains('/') }
            .distinct()
            .sortedWith(compareBy({ !it.contains("nwd", ignoreCase = true) }, { it }))
            .take(MAX_STRINGS)
            .toList()

        sb.appendLine("Source: $source  (${all.size} strings, showing ${picked.size})")
        picked.forEach { sb.appendLine("  ${it.take(MAX_LEN)}") }
    }

    // ----------------------------------------------------------------- settings

    private fun appendSettings(sb: StringBuilder, context: Context) {
        var found = 0
        for ((label, uri) in listOf(
            "system" to Settings.System.CONTENT_URI,
            "global" to Settings.Global.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI
        )) {
            try {
                context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)
                    ?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(0) ?: continue
                            val l = name.lowercase(Locale.ROOT)
                            if (KEYWORDS.none { it in l }) continue
                            sb.appendLine("$label/$name = ${c.getString(1)?.take(MAX_LEN)}")
                            found++
                        }
                    }
            } catch (e: Exception) {
                sb.appendLine("$label: not readable (${e.javaClass.simpleName})")
            }
        }
        if (found == 0) sb.appendLine("No radio-related settings keys")
    }
}
