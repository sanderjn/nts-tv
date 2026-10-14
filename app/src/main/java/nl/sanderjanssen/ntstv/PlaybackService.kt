package nl.sanderjanssen.ntstv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private lateinit var sleepGuard: SleepGuard
    private val scope = MainScope()
    private var showUpdates: Job? = null

    // The TV is off, so nobody is listening: stop instead of streaming to a dark screen.
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            session?.player?.pause()
        }
    }

    private val retry = Runnable {
        val player = session?.player ?: return@Runnable
        if (player.playWhenReady && player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        sleepGuard = SleepGuard(this)
        sleepGuard.release()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setUserAgent(Http.USER_AGENT)),
                ),
            )
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handler.removeCallbacks(retry)
                val delayMs = (3_000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
                retryAttempt++
                handler.postDelayed(retry, delayMs)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retryAttempt = 0
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) sleepGuard.hold() else sleepGuard.release()
                showUpdates?.cancel()
                showUpdates = if (playWhenReady) scope.launch { keepShowCurrent(exoPlayer) } else null
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, RadioPlayer(exoPlayer))
            .setSessionActivity(openApp)
            .setCallback(OwnAndSystemOnly())
            .build()

        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // The service has to be exported for the system, and playing lifts the device sleep timeout,
    // so other apps must not be able to drive it.
    private class OwnAndSystemOnly : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            if (controller.uid == Process.myUid() || controller.isTrusted) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }

    // The app only refreshes while it is on screen, and the screensaver takes what plays from the session.
    private suspend fun keepShowCurrent(player: Player) {
        while (true) {
            delay(SHOW_REFRESH_MS)
            if (player.currentMediaItem?.mediaId?.startsWith("live-") != true) continue
            val stations = try {
                LiveStations.withShows(NtsApi.fetchLiveShows())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val current = player.currentMediaItem ?: continue
            val fresh = stations.firstOrNull { it.id == current.mediaId }?.toMediaItem() ?: continue
            // Same stream, so the player swaps the item in without reconnecting.
            if (fresh.mediaMetadata != current.mediaMetadata) {
                player.replaceMediaItem(player.currentMediaItemIndex, fresh)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        scope.cancel()
        unregisterReceiver(screenOff)
        sleepGuard.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

private const val SHOW_REFRESH_MS = 60_000L

/**
 * Android TV sleeps a fixed time after the last remote press, even while audio plays, and takes
 * the TV down with it. Lifting the system sleep timeout while playing leaves the screensaver
 * alone. Needs WRITE_SECURE_SETTINGS, granted once over adb; without it this does nothing.
 */
private class SleepGuard(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("nts", Context.MODE_PRIVATE)

    fun hold() {
        if (prefs.contains(KEY_SAVED)) return
        val current = Settings.Secure.getLong(resolver, SLEEP_TIMEOUT, DEFAULT_TIMEOUT_MS)
        if (write(NEVER)) prefs.edit().putLong(KEY_SAVED, current).commit()
    }

    // The saved value survives process death, so a stale hold is undone on the next start.
    fun release() {
        if (!prefs.contains(KEY_SAVED)) return
        if (write(prefs.getLong(KEY_SAVED, DEFAULT_TIMEOUT_MS))) prefs.edit().remove(KEY_SAVED).commit()
    }

    private fun write(value: Long): Boolean =
        try {
            Settings.Secure.putLong(resolver, SLEEP_TIMEOUT, value)
        } catch (_: SecurityException) {
            false
        }

    private companion object {
        const val SLEEP_TIMEOUT = "sleep_timeout"
        const val KEY_SAVED = "saved_sleep_timeout"
        const val NEVER = 0L
        const val DEFAULT_TIMEOUT_MS = 1_200_000L
    }
}

/**
 * Every NTS stream is endless radio: pausing drops the connection instead of buffering stale
 * audio, and resuming reconnects at the live edge.
 */
private class RadioPlayer(player: Player) : ForwardingPlayer(player) {
    override fun pause() {
        super.pause()
        super.stop()
    }

    override fun prepare() {
        if (playbackState == Player.STATE_IDLE) {
            super.seekToDefaultPosition()
            super.prepare()
        }
    }

    override fun play() {
        prepare()
        super.play()
    }
}

fun Station.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle ?: "NTS")
                .setStation("NTS")
                .setArtworkUri(imageUrl?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()
