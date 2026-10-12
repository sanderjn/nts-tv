package nl.sanderjanssen.ntstv

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("nts", Context.MODE_PRIVATE)

    private val _live = MutableStateFlow(LiveStations.all)
    val live: StateFlow<List<Station>> = _live.asStateFlow()

    private val _mixtapes = MutableStateFlow<List<Station>>(emptyList())
    val mixtapes: StateFlow<List<Station>> = _mixtapes.asStateFlow()

    val lastPlayedId: String?
        get() = prefs.getString(KEY_LAST_PLAYED, null)

    fun rememberPlayed(station: Station) {
        prefs.edit().putString(KEY_LAST_PLAYED, station.id).apply()
    }

    suspend fun refresh() {
        attempt { _live.value = LiveStations.withShows(NtsApi.fetchLiveShows()) }
        if (_mixtapes.value.isEmpty()) {
            attempt { _mixtapes.value = NtsApi.fetchMixtapes() }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or API change: keep what we have and try again on the next refresh.
        }
    }

    private companion object {
        const val KEY_LAST_PLAYED = "last_played"
    }
}
