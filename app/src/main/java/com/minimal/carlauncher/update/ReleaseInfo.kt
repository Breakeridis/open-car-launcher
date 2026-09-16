package com.minimal.carlauncher.update

import org.json.JSONObject

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSize: Long
) {
    val hasApk: Boolean get() = apkUrl.isNotBlank()

    companion object {
        /**
         * Tolerant parse: every field uses opt*, so a GitHub schema change degrades to a
         * null result instead of throwing on the updater's IO thread.
         */
        fun from(json: JSONObject): ReleaseInfo? {
            if (json.optBoolean("draft", false)) return null

            val tag = json.optString("tag_name", "").ifBlank { return null }
            val assets = json.optJSONArray("assets")

            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                var fallbackUrl = ""
                var fallbackSize = 0L
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    if (!assetName.endsWith(".apk", ignoreCase = true)) continue
                    val url = asset.optString("browser_download_url", "")
                    if (url.isBlank()) continue
                    val size = asset.optLong("size", 0L)
                    // Prefer an asset that looks like the release build.
                    if (assetName.contains("release", ignoreCase = true)) {
                        apkUrl = url
                        apkSize = size
                        break
                    }
                    if (fallbackUrl.isBlank()) {
                        fallbackUrl = url
                        fallbackSize = size
                    }
                }
                if (apkUrl.isBlank()) {
                    apkUrl = fallbackUrl
                    apkSize = fallbackSize
                }
            }

            return ReleaseInfo(
                tag = tag,
                name = json.optString("name", "").ifBlank { tag },
                body = json.optString("body", ""),
                htmlUrl = json.optString("html_url", ""),
                apkUrl = apkUrl,
                apkSize = apkSize
            )
        }
    }
}
