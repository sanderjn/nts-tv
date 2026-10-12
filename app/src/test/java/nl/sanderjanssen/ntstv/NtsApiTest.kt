package nl.sanderjanssen.ntstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtsApiTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesCurrentShowPerChannel() {
        val shows = NtsApi.parseLive(fixture("live.json"))

        assertEquals("Go Wrong w/ Lina", shows["1"]?.title)
        assertEquals("The Heatstroke Boogie w/ Mr. Manns", shows["2"]?.title)
        assertEquals(
            "https://media.ntslive.co.uk/resize/400x400/9125b330-a3a7-4377-bc15-f2773922c4bd_1789948800.jpeg",
            shows["1"]?.imageUrl,
        )
    }

    @Test
    fun htmlEntitiesInTitlesAreDecoded() {
        val shows = NtsApi.parseLive(
            """{"results":[{"channel_name":"1","now":{"broadcast_title":"Fool&#039;s Game w/ Tim &amp; Barry"}},{"channel_name":"2","now":{"broadcast_title":"A &#x27;B&#x27; &amp;lt; R&B; &bogus; &#xD800;"}}]}""",
        )

        assertEquals("Fool's Game w/ Tim & Barry", shows["1"]?.title)
        assertEquals("A 'B' &lt; R&B; &bogus; &#xD800;", shows["2"]?.title)
    }

    @Test
    fun liveChannelWithoutBroadcastIsSkipped() {
        val shows = NtsApi.parseLive("""{"results":[{"channel_name":"1"},{"channel_name":"2","now":{"broadcast_title":" X "}}]}""")

        assertEquals(mapOf("2" to LiveShow("X", null)), shows)
    }

    @Test
    fun parsesAllMixtapesWithStreams() {
        val mixtapes = NtsApi.parseMixtapes(fixture("mixtapes.json"))

        assertEquals(16, mixtapes.size)
        val poolside = mixtapes.first()
        assertEquals("Poolside", poolside.title)
        assertEquals("https://stream-mixtape-geo.ntslive.net/mixtape4", poolside.streamUrl)
        assertTrue(poolside.id.startsWith("mixtape-"))
        assertTrue(!poolside.isLive)
        assertEquals(
            "https://media.ntslive.co.uk/resize/400x400/cf5afb01-5a68-4fa0-a1c6-415b35d09ed6_1542931200.jpeg",
            poolside.imageUrl,
        )
        assertEquals(mixtapes.size, mixtapes.map { it.id }.toSet().size)
    }

    @Test
    fun mixtapeWithoutStreamIsDropped() {
        val mixtapes = NtsApi.parseMixtapes("""{"results":[{"mixtape_alias":"a","title":"A","subtitle":""},{"mixtape_alias":"b","title":"B","audio_stream_endpoint":"https://x"}]}""")

        assertEquals(listOf("B"), mixtapes.map { it.title })
        assertNull(mixtapes.single().subtitle)
    }

    @Test
    fun liveStationsGetShowNames() {
        val stations = LiveStations.withShows(mapOf("2" to LiveShow("Show", "https://img")))

        assertNull(stations[0].subtitle)
        assertNull(stations[0].imageUrl)
        assertEquals("Show", stations[1].subtitle)
        assertEquals("https://img", stations[1].imageUrl)
    }
}
