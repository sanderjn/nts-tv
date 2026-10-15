package nl.sanderjanssen.ntstv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** The newest release on GitHub, since the app has no updater of its own. */
object Updates {
    const val RELEASE_PAGE = "github.com/sanderjn/nts-tv"

    private val json = Json { ignoreUnknownKeys = true }

    /** The version of the latest release, as in "1.2.0". */
    suspend fun latestVersion(): String = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/sanderjn/nts-tv/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", Http.USER_AGENT)
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} for the latest release")
            }
            parseVersion(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun parseVersion(body: String): String =
        json.decodeFromString<Release>(body).tagName.removePrefix("v")

    /** Compares number by number, so 1.10.0 is newer than 1.9.0 and 1.2 is the same as 1.2.0. */
    fun isNewer(version: String, than: String): Boolean {
        val new = numbers(version)
        val old = numbers(than)
        for (index in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(index) { 0 }
            val b = old.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(version: String): List<Int> = version.split(".").map { it.toIntOrNull() ?: 0 }

    @Serializable
    private data class Release(@SerialName("tag_name") val tagName: String)
}
