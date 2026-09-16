package com.minimal.carlauncher.update

import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the latest release straight from the GitHub REST API.
 *
 * Uses HttpURLConnection + org.json, both of which are in android.jar. Retrofit/Moshi would
 * add roughly a megabyte and R8 keep rules for a single GET of one small object.
 */
class GitHubReleaseClient {

    sealed interface Result {
        data class Success(val release: ReleaseInfo) : Result
        /** Rate limited or offline - keep whatever is cached, do not surface an error. */
        data object SoftFailure : Result
        data class Failure(val message: String) : Result
    }

    suspend fun fetchLatest(): Result = withContext(Dispatchers.IO) {
        if (!Constants.isUpdaterConfigured) {
            return@withContext Result.Failure("Updater repository is not configured")
        }
        val base = "https://api.github.com/repos/${Constants.GITHUB_OWNER}/${Constants.GITHUB_REPO}"

        when (val latest = get("$base/releases/latest")) {
            is Body.Ok -> {
                val release = try {
                    ReleaseInfo.from(JSONObject(latest.text))
                } catch (e: Exception) {
                    null
                }
                if (release != null) Result.Success(release)
                else Result.Failure("Malformed release data")
            }
            // /releases/latest 404s when the repo only has pre-releases.
            is Body.NotFound -> fetchFirstFromList("$base/releases?per_page=10")
            is Body.RateLimited -> Result.SoftFailure
            is Body.Error -> if (latest.offline) Result.SoftFailure else Result.Failure(latest.message)
        }
    }

    private fun fetchFirstFromList(url: String): Result = when (val listed = get(url)) {
        is Body.Ok -> {
            val release = try {
                val arr = JSONArray(listed.text)
                (0 until arr.length())
                    .asSequence()
                    .mapNotNull { arr.optJSONObject(it) }
                    .mapNotNull { ReleaseInfo.from(it) }
                    .firstOrNull()
            } catch (e: Exception) {
                null
            }
            if (release != null) Result.Success(release) else Result.Failure("No releases found")
        }
        is Body.NotFound -> Result.Failure("Repository or releases not found")
        is Body.RateLimited -> Result.SoftFailure
        is Body.Error -> if (listed.offline) Result.SoftFailure else Result.Failure(listed.message)
    }

    private sealed interface Body {
        data class Ok(val text: String) : Body
        data object NotFound : Body
        data object RateLimited : Body
        data class Error(val message: String, val offline: Boolean) : Body
    }

    private fun get(url: String): Body {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                // GitHub replies 403 to requests without a User-Agent.
                setRequestProperty("User-Agent", "MinimalCarLauncher/${BuildConfig.VERSION_NAME}")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> Body.Ok(
                    connection.inputStream.bufferedReader().use(BufferedReader::readText)
                )
                404 -> Body.NotFound
                403, 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    if (remaining == "0") Body.RateLimited
                    else Body.Error("GitHub refused the request (HTTP $code)", offline = false)
                }
                else -> Body.Error("HTTP $code", offline = false)
            }
        } catch (e: Exception) {
            // DNS/socket failures on a car with no SIM are expected, not errors worth showing.
            Body.Error(e.message ?: "Network error", offline = true)
        } finally {
            connection?.disconnect()
        }
    }
}
