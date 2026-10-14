package nl.sanderjanssen.ntstv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

data class LiveShow(val title: String, val imageUrl: String?)

object NtsApi {
    private const val BASE = "https://www.nts.live/api/v2"

    private val json = Json { ignoreUnknownKeys = true }

    /** Channel number ("1", "2") to the show that is on now. */
    suspend fun fetchLiveShows(): Map<String, LiveShow> = parseLive(get("$BASE/live"))

    suspend fun fetchMixtapes(): List<Station> = parseMixtapes(get("$BASE/mixtapes"))

    fun parseLive(body: String): Map<String, LiveShow> =
        json.decodeFromString<LiveResponse>(body).results
            .mapNotNull { r ->
                val now = r.now ?: return@mapNotNull null
                val title = now.broadcastTitle?.unescaped()?.trim() ?: return@mapNotNull null
                r.channelName to LiveShow(title, now.embeds?.details?.media?.picture)
            }
            .toMap()

    fun parseMixtapes(body: String): List<Station> =
        json.decodeFromString<MixtapesResponse>(body).results
            .filter { it.audioStreamEndpoint.isNotBlank() }
            .map {
                Station(
                    id = "mixtape-${it.alias}",
                    title = it.title.unescaped().trim(),
                    subtitle = it.subtitle?.unescaped()?.trim()?.takeIf(String::isNotEmpty),
                    streamUrl = it.audioStreamEndpoint,
                    isLive = false,
                    imageUrl = it.media?.picture,
                )
            }

    private val entity = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    // The API returns its texts HTML-escaped: "Fool&#039;s Game", "Tim &amp; Barry".
    private fun String.unescaped(): String = entity.replace(this) { match ->
        val name = match.groupValues[1]
        val decoded = if (name.startsWith("#")) {
            val hex = name.startsWith("#x", ignoreCase = true)
            (if (hex) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull())
                ?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) }
        } else {
            namedEntities[name]
        }
        decoded ?: match.value
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", Http.USER_AGENT)
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    private data class LiveResponse(val results: List<LiveChannel> = emptyList())

    @Serializable
    private data class LiveChannel(
        @SerialName("channel_name") val channelName: String,
        val now: Broadcast? = null,
    )

    @Serializable
    private data class Broadcast(
        @SerialName("broadcast_title") val broadcastTitle: String? = null,
        val embeds: Embeds? = null,
    )

    @Serializable
    private data class Embeds(val details: Details? = null)

    @Serializable
    private data class Details(val media: Media? = null)

    @Serializable
    private data class Media(@SerialName("picture_medium") val picture: String? = null)

    @Serializable
    private data class MixtapesResponse(val results: List<Mixtape> = emptyList())

    @Serializable
    private data class Mixtape(
        @SerialName("mixtape_alias") val alias: String,
        val title: String,
        val subtitle: String? = null,
        @SerialName("audio_stream_endpoint") val audioStreamEndpoint: String = "",
        val media: Media? = null,
    )
}
