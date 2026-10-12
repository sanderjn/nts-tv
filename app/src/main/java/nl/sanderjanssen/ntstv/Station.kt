package nl.sanderjanssen.ntstv

data class Station(
    val id: String,
    val title: String,
    val subtitle: String?,
    val streamUrl: String,
    val isLive: Boolean,
    val imageUrl: String? = null,
)

object LiveStations {
    val channel1 = Station(
        id = "live-1",
        title = "Channel 1",
        subtitle = null,
        streamUrl = "https://stream-relay-geo.ntslive.net/stream",
        isLive = true,
    )
    val channel2 = Station(
        id = "live-2",
        title = "Channel 2",
        subtitle = null,
        streamUrl = "https://stream-relay-geo.ntslive.net/stream2",
        isLive = true,
    )
    val all = listOf(channel1, channel2)

    fun withShows(shows: Map<String, LiveShow>): List<Station> =
        all.map { station ->
            val show = shows[station.id.removePrefix("live-")]
            station.copy(subtitle = show?.title, imageUrl = show?.imageUrl)
        }
}
