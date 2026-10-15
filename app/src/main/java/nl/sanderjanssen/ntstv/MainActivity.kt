package nl.sanderjanssen.ntstv

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class PlaybackUi(
    val currentId: String? = null,
    val playWhenReady: Boolean = false,
    val state: Int = Player.STATE_IDLE,
    val hasError: Boolean = false,
) {
    fun statusFor(station: Station): String? = when {
        station.id != currentId -> null
        !playWhenReady -> "Paused"
        hasError -> "Reconnecting…"
        state == Player.STATE_READY -> "Playing"
        else -> "Loading…"
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playback by mutableStateOf(PlaybackUi())

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refresh()
                    delay(60_000)
                }
            }
        }

        val initialFocusId = viewModel.lastPlayedId
        setContent {
            val live by viewModel.live.collectAsStateWithLifecycle()
            val mixtapes by viewModel.mixtapes.collectAsStateWithLifecycle()
            val newVersion by viewModel.newVersion.collectAsStateWithLifecycle()
            NtsScreen(
                live = live,
                mixtapes = mixtapes,
                playback = playback,
                newVersion = newVersion,
                initialFocusId = initialFocusId,
                onSelect = ::select,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        lifecycleScope.launch {
            val connected = runCatching { future.await() }.getOrNull() ?: return@launch
            if (controllerFuture !== future) return@launch
            controller = connected
            connected.addListener(playerListener)
            syncPlayback(connected)
        }
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    private fun syncPlayback(player: Player) {
        playback = PlaybackUi(
            currentId = player.currentMediaItem?.mediaId,
            playWhenReady = player.playWhenReady,
            state = player.playbackState,
            hasError = player.playerError != null,
        )
    }

    private fun select(station: Station) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == station.id) {
            if (player.playWhenReady) player.pause() else player.play()
        } else {
            player.setMediaItem(station.toMediaItem())
            player.prepare()
            player.play()
        }
        viewModel.rememberPlayed(station)
    }
}

private val ArchivoNarrow = FontFamily(
    Font(R.font.archivo_narrow_regular, FontWeight.Normal),
    Font(R.font.archivo_narrow_bold, FontWeight.Bold),
)

// A typeface of your own takes the place of the bundled one: regular.ttf and bold.ttf in the app's files/fonts folder.
private fun typeface(context: Context): FontFamily {
    val fonts = File(context.getExternalFilesDir(null), "fonts")
    val regular = File(fonts, "regular.ttf")
    val bold = File(fonts, "bold.ttf")
    if (!regular.canRead() || !bold.canRead()) return ArchivoNarrow
    return FontFamily(Font(regular, FontWeight.Normal), Font(bold, FontWeight.Bold))
}
private val Dim = Color(0xFF8A8A8A)
private val Placeholder = Color(0xFF1A1A1A)
private val Hairline = Color(0xFF333333)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NtsScreen(
    live: List<Station>,
    mixtapes: List<Station>,
    playback: PlaybackUi,
    newVersion: String?,
    initialFocusId: String?,
    onSelect: (Station) -> Unit,
) {
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(id: String) = focusRequesters.getOrPut(id) { FocusRequester() }
    val mixtapeListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var initialFocusDone by remember { mutableStateOf(false) }
    var mixtapesFocused by remember { mutableStateOf(false) }
    val playingMixtape = mixtapes.indexOfFirst { it.id == playback.currentId }.coerceAtLeast(0)

    LaunchedEffect(playingMixtape, mixtapesFocused, mixtapes.size) {
        if (!mixtapesFocused && initialFocusDone && mixtapes.isNotEmpty()) {
            mixtapeListState.animateScrollToItem(playingMixtape)
        }
    }

    LaunchedEffect(mixtapes) {
        if (initialFocusDone) return@LaunchedEffect
        val mixtapeIndex = mixtapes.indexOfFirst { it.id == initialFocusId }
        val target = when {
            initialFocusId == null || live.any { it.id == initialFocusId } -> initialFocusId ?: live.first().id
            mixtapeIndex >= 0 -> {
                mixtapeListState.scrollToItem(mixtapeIndex)
                initialFocusId
            }
            mixtapes.isEmpty() -> return@LaunchedEffect
            else -> live.first().id
        }
        withFrameNanos { }
        if (runCatching { requesterFor(target).requestFocus() }.isSuccess) {
            initialFocusDone = true
        }
    }

    val nowPlaying = (live + mixtapes).firstOrNull { it.id == playback.currentId }
    val context = LocalContext.current
    val typeface = remember { typeface(context) }

    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides TextStyle(fontFamily = typeface, letterSpacing = 0.sp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(top = 28.dp, bottom = 12.dp),
            ) {
                Header(nowPlaying, nowPlaying?.let(playback::statusFor), newVersion)
                Spacer(Modifier.height(20.dp))

                SectionLabel("Live")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (mixtapes.isEmpty() || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) {
                                scope.launch {
                                    mixtapeListState.scrollToItem(playingMixtape)
                                    withFrameNanos { }
                                    runCatching { requesterFor(mixtapes[playingMixtape].id).requestFocus() }
                                }
                            }
                            true
                        },
                ) {
                    items(live, key = { it.id }) { station ->
                        LiveTile(
                            station = station,
                            status = playback.statusFor(station),
                            modifier = Modifier
                                .size(420.dp, 116.dp)
                                .focusRequester(requesterFor(station.id)),
                            onClick = { onSelect(station) },
                        )
                    }
                }

                if (mixtapes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Infinite Mixtapes")
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(48.dp)) {
                        LazyRow(
                            state = mixtapeListState,
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { mixtapesFocused = it.hasFocus },
                        ) {
                            items(mixtapes, key = { it.id }) { station ->
                                MixtapeTile(
                                    station = station,
                                    status = playback.statusFor(station),
                                    modifier = Modifier
                                        .size(MixtapeSize, MixtapeSize + 56.dp)
                                        .focusRequester(requesterFor(station.id)),
                                    onClick = { onSelect(station) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MixtapeSize = 136.dp

/**
 * Keeps the focused tile pinned at the row's start padding, like the Google TV home rows. A
 * critically damped spring picks up from the current velocity when keys repeat quickly, so a
 * held D-pad glides instead of restarting a new animation on every press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberPivotSpec(pivot: Dp): BringIntoViewSpec {
    val pivotPx = with(LocalDensity.current) { pivot.toPx() }
    return remember(pivotPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - pivotPx
        }
    }
}

@Composable
private fun Header(nowPlaying: Station?, status: String?, newVersion: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.nts_logo),
            contentDescription = "NTS",
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            if (nowPlaying != null) {
                if (status != null) {
                    Text(status.uppercase(), color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Text(
                    text = listOfNotNull(nowPlaying.title, nowPlaying.subtitle).joinToString(" · ").uppercase(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (newVersion != null) {
            Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("VERSION $newVersion AVAILABLE", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(Updates.RELEASE_PAGE, color = Dim, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Dim,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
    )
}

@Composable
private fun TileSurface(
    isCurrent: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color(0xFFDDDDDD),
            pressedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(if (isCurrent) 2.dp else 1.dp, if (isCurrent) Color.White else Hairline),
                shape = RectangleShape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        content()
    }
}

@Composable
private fun Artwork(url: String?, status: String?, modifier: Modifier) {
    Box(modifier.background(Placeholder)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (status != null) {
            Text(
                text = status.uppercase(),
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.White)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun LiveTile(
    station: Station,
    status: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TileSurface(isCurrent = status != null, modifier = modifier, onClick = onClick) {
        val content = LocalContentColor.current
        Row(Modifier.fillMaxSize()) {
            Artwork(station.imageUrl, status, Modifier.aspectRatio(1f))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .background(content),
                    ) {
                        Text(
                            text = station.id.removePrefix("live-"),
                            color = if (content == Color.White) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("LIVE NOW", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = (station.subtitle ?: station.title).uppercase(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MixtapeTile(
    station: Station,
    status: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TileSurface(isCurrent = status != null, modifier = modifier, onClick = onClick) {
        Column(Modifier.fillMaxSize()) {
            Artwork(station.imageUrl, status, Modifier.size(MixtapeSize))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = station.title.uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (station.subtitle != null) {
                    Text(
                        text = station.subtitle,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
