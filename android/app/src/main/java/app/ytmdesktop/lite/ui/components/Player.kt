package app.ytmdesktop.lite.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.ytmdesktop.lite.data.ArtistLink
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.ui.PlayerUiState
import app.ytmdesktop.lite.ui.Repeat
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.mixWith

class PlayerActions(
    val playPause: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seek: (Long) -> Unit,
    val shuffle: () -> Unit,
    val repeat: () -> Unit,
    val like: (QueueItem) -> Unit,
    val share: (QueueItem) -> Unit,
    val addToPlaylist: (QueueItem) -> Unit,
    val queue: () -> Unit,
    val artist: (ArtistLink) -> Unit,
)

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun artistLinks(q: QueueItem?): List<ArtistLink> = when {
    q == null -> emptyList()
    q.channelId != null && q.author.isNotEmpty() -> listOf(ArtistLink(q.author, q.channelId))
    q.author.isNotEmpty() -> listOf(ArtistLink(q.author))
    else -> emptyList()
}

@Composable
private fun StatePill(tag: String) {
    val c = Ytmd.colors
    val color = when (tag) {
        "run" -> c.ok
        "buf" -> c.warn
        "error" -> c.error
        else -> c.muted
    }
    Text(
        tag.uppercase(),
        Modifier
            .border(1.dp, if (tag == "run" || tag == "buf" || tag == "error") color.mixWith(Color.Transparent, 0.45f) else c.outline)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = Type.tiny.copy(letterSpacing = 0.06.em),
        color = color,
    )
}

@Composable
private fun Pulse(text: String, color: Color) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 0.55f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a",
    )
    Text(text, Modifier.alpha(a), style = Type.small, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** `.term-chrome`: tab, state pill, status path. */
@Composable
private fun TermChrome(state: PlayerUiState, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (code) c.surface else c.termChrome)
            .hairlineBottom(c.outline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Box(
            Modifier
                .fillMaxHeight()
                .background(if (code) Color.Transparent else c.termBg)
                .then(if (code) Modifier.hairlineBottom(c.primary) else Modifier)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (code) "TERMINAL · PLAYBACK" else "PLAYBACK", style = Type.tiny, color = c.onSurface)
        }
        Row(
            Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatePill(state.stateTag)
            when {
                state.error != null -> Text(state.error, style = Type.small, color = c.warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.buffering -> Pulse("resolving stream…", c.warn)
                state.autoplayLoading -> Pulse("autoplay: finding similar songs…", c.muted)
                else -> Text("ytmd://playback", style = Type.small, color = c.muted, maxLines = 1)
            }
        }
        trailing()
    }
}

@Composable
private fun IndeterminateBar() {
    val c = Ytmd.colors
    val x by rememberInfiniteTransition(label = "bar").animateFloat(
        -0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x",
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(2.dp).background(c.outlineVariant)) {
        Box(
            Modifier
                .offset(x = maxWidth * x)
                .width(maxWidth * 0.35f)
                .fillMaxHeight()
                .background(c.primary),
        )
    }
}

/** `ytmd › play "title"` / `--artist name` prompt lines. */
@Composable
private fun Prompt(state: PlayerUiState, onArtist: (ArtistLink) -> Unit, big: Boolean = false) {
    val c = Ytmd.colors
    val q = state.current
    val cmd = when {
        state.error != null -> "retry"
        state.buffering -> "buffer"
        state.isPlaying -> "play"
        else -> "pause"
    }
    val style = if (big) Type.base.copy(fontSize = Type.base.fontSize * 1.15f) else Type.base
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold)) { append("ytmd") }
            withStyle(SpanStyle(color = c.muted)) { append(" › ") }
            withStyle(SpanStyle(color = if (state.buffering) c.warn else c.ok)) { append(cmd) }
            append(" ")
            withStyle(SpanStyle(color = c.onSurface)) { append("\"${q?.title ?: "idle"}\"") }
        },
        style = style,
        maxLines = if (big) 3 else 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (state.buffering && !big) {
        Text("# waiting for audio bytes…", style = Type.small, color = c.muted, maxLines = 1)
    } else {
        ArtistLinksText(
            artistLinks(q), q?.author?.ifEmpty { "—" } ?: "—", onArtist,
            prefix = buildAnnotatedString { withStyle(SpanStyle(color = c.tertiary)) { append("--artist ") } },
            color = c.onSurface.copy(alpha = 0.85f),
        )
        if (big && !q?.album.isNullOrEmpty()) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.tertiary)) { append("--album ") }
                    append(q.album)
                },
                style = Type.small,
                color = c.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bottom player panel (PlayerDock.svelte), compacted for phones. Tap to expand. */
@Composable
fun PlayerDock(state: PlayerUiState, actions: PlayerActions, onExpand: () -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    val q = state.current
    Column(Modifier.fillMaxWidth().background(c.termBg).hairlineTop(c.outline)) {
        TermChrome(state) {
            IconBtn(Icons.AutoMirrored.Outlined.QueueMusic, "Queue", size = 30.dp, iconSize = 17.dp, onClick = actions.queue)
        }
        if (state.buffering) IndeterminateBar()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = q != null, onClick = onExpand)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (code) Artwork(q?.thumbnails, 44.dp, codeFile = true)
            else Artwork(q?.thumbnails, 44.dp, modifier = Modifier.alpha(if (state.buffering) 0.6f else 1f))
            Column(Modifier.weight(1f)) { Prompt(state, actions.artist) }
            IconBtn(Icons.Filled.SkipPrevious, "Previous", tint = c.onSurface, size = 36.dp, enabled = q != null, onClick = actions.previous)
            PlayButton(state, 40.dp, actions.playPause)
            IconBtn(Icons.Filled.SkipNext, "Next", tint = c.onSurface, size = 36.dp, enabled = q != null, onClick = actions.next)
        }
        val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.outlineVariant)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.primary))
        }
    }
}

@Composable
private fun PlayButton(state: PlayerUiState, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val c = Ytmd.colors
    Box(
        Modifier
            .size(size)
            .clip(Shape)
            .background(c.primary)
            .clickable(enabled = state.current != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state.buffering) Spinner(size * 0.4f)
        else Icon(
            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (state.isPlaying) "Pause" else "Play",
            Modifier.size(size * 0.55f),
            tint = c.onPrimary,
        )
    }
}

/** Thin terminal seek bar with a square thumb. */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    val c = Ytmd.colors
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = if (scrubbing) scrub else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(fmtTime(if (scrubbing) (scrub * durationMs).toLong() else positionMs), style = Type.small, color = c.muted)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(enabled, durationMs) {
                    if (!enabled || durationMs <= 0) return@pointerInput
                    detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled || durationMs <= 0) return@pointerInput
                    widthPx = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrubbing = true; scrub = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { scrubbing = false; onSeek((scrub * durationMs).toLong()) },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ -> scrub = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val density = LocalDensity.current
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.outline))
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(c.primary))
            with(density) {
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - 5.dp)
                        .size(10.dp)
                        .clip(Shape)
                        .background(c.primary),
                )
            }
        }
        Text(fmtTime(durationMs), style = Type.small, color = c.muted)
    }
}

/** Expanded "terminal" view of the current track. */
@Composable
fun NowPlaying(state: PlayerUiState, liked: Boolean, actions: PlayerActions, onCollapse: () -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    val q = state.current
    Column(Modifier.fillMaxSize().background(c.termBg)) {
        TermChrome(
            state,
            leading = { IconBtn(Icons.Outlined.ExpandMore, "Collapse", tint = c.onSurface, size = 30.dp, iconSize = 20.dp, onClick = onCollapse) },
        ) {
            IconBtn(Icons.Outlined.Share, "Share song", size = 30.dp, iconSize = 16.dp, enabled = q != null) { q?.let(actions.share) }
        }
        if (state.buffering) IndeterminateBar()
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(if (code) Modifier.border(1.dp, c.outline).padding(4.dp) else Modifier.border(1.dp, c.outlineVariant, Shape)),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Artwork(q?.thumbnails, maxWidth, modifier = Modifier.alpha(if (state.buffering) 0.6f else 1f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Prompt(state, actions.artist, big = true) }
            SeekBar(state.positionMs, state.durationMs, enabled = q != null && !state.buffering, onSeek = actions.seek)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.Outlined.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) c.primary else c.muted, onClick = actions.shuffle)
                IconBtn(Icons.Filled.SkipPrevious, "Previous", tint = c.onSurface, size = 48.dp, iconSize = 28.dp, onClick = actions.previous)
                PlayButton(state, 60.dp, actions.playPause)
                IconBtn(Icons.Filled.SkipNext, "Next", tint = c.onSurface, size = 48.dp, iconSize = 28.dp, onClick = actions.next)
                IconBtn(
                    if (state.repeat == Repeat.one) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                    "Repeat ${state.repeat.name}",
                    tint = if (state.repeat != Repeat.off) c.primary else c.muted,
                    onClick = actions.repeat,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdeButton(
                    if (liked) "liked" else "like",
                    icon = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    active = liked, enabled = q != null,
                ) { q?.let(actions.like) }
                IdeButton("playlist", icon = Icons.AutoMirrored.Outlined.PlaylistAdd, enabled = q != null) { q?.let(actions.addToPlaylist) }
                IdeButton("queue", icon = Icons.AutoMirrored.Outlined.QueueMusic, onClick = actions.queue)
            }
            if (state.queue.isNotEmpty()) {
                Muted("# track ${state.queueIndex + 1} of ${state.queue.size}${if (state.repeat != Repeat.off) " · repeat ${state.repeat.name}" else ""}")
            }
            state.autoplayError?.let { Muted("# $it") }
        }
    }
}
