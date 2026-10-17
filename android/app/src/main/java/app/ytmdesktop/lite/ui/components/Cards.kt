package app.ytmdesktop.lite.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.ArtistLink
import app.ytmdesktop.lite.data.ItemType
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.playback.Downloads
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.mixWith

class ItemActions(
    val open: (MusicItem) -> Unit,
    val play: (MusicItem) -> Unit,
    val menu: (MusicItem) -> Unit,
    val artist: (ArtistLink) -> Unit,
)

private fun parseHex(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

/** MusicCard.svelte, default appearance: 128dp cover with a play button and two text lines. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicCard(item: MusicItem, actions: ItemActions) {
    val c = Ytmd.colors
    val isMood = item.type == ItemType.mood || item.color != null
    val showArtists = item.type == ItemType.song || item.type == ItemType.album || item.type == ItemType.playlist
    val offline by Downloads.state.collectAsState()
    Column(
        Modifier
            .width(140.dp)
            .clip(Shape)
            .combinedClickable(onClick = { actions.open(item) }, onLongClick = { actions.menu(item) })
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Artwork(
                item.thumbnails, 128.dp,
                round = item.type == ItemType.artist,
                color = if (isMood) parseHex(Api.moodColor(item)) else null,
                label = item.title,
            )
            if (offline.isDownloaded(item)) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp).clip(Shape).background(Color(0xC70A0D12)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.OfflinePin, "Available offline", Modifier.size(14.dp), tint = c.primary)
                }
            }
            if (item.canPlay && !isMood) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .shadow(2.dp, Shape)
                        .clip(Shape)
                        .background(c.primary)
                        .clickable { actions.play(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(20.dp), tint = c.onPrimary)
                }
            }
        }
        Column {
            Text(item.title, style = Type.small.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtists && (item.artistLinks != null || item.subtitle != null)) {
                ArtistLinksText(item.artistLinks, item.subtitle.orEmpty(), actions.artist, style = Type.tiny)
            } else {
                Muted(item.subtitle ?: item.type.name, style = Type.tiny)
            }
        }
    }
}

/** MusicCard.svelte, code appearance: a numbered line in a file tree. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeEntry(line: Int, item: MusicItem, actions: ItemActions) {
    val c = Ytmd.colors
    val isMood = item.type == ItemType.mood || item.color != null
    val showArtists = item.type == ItemType.song || item.type == ItemType.album || item.type == ItemType.playlist
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { actions.open(item) }, onLongClick = { actions.menu(item) })
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(line.toString(), Modifier.width(28.dp), style = Type.small, color = c.muted, textAlign = TextAlign.End)
        Artwork(item.thumbnails, 27.dp, codeFile = true)
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.onSurface)) { append(item.type.name) }
                    withStyle(SpanStyle(color = c.muted)) { append("({ title: ") }
                    withStyle(SpanStyle(color = c.primary)) { append("\"${item.title}\"") }
                    withStyle(SpanStyle(color = c.muted)) { append(" });") }
                },
                style = Type.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val comment = buildAnnotatedString { withStyle(SpanStyle(color = c.muted)) { append("// ") } }
            if (showArtists && (item.artistLinks != null || item.subtitle != null)) {
                ArtistLinksText(item.artistLinks, item.subtitle.orEmpty(), actions.artist, style = Type.tiny, prefix = comment)
            } else {
                Muted("// ${item.subtitle ?: "Open ${item.type.name}"}", style = Type.tiny)
            }
        }
        if (item.canPlay && !isMood) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted)) { append("$ ") }
                    append("play")
                },
                Modifier
                    .clip(Shape)
                    .clickable { actions.play(item) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = Type.small,
                color = c.primary,
            )
        }
    }
}

/** ShelfRow.svelte: horizontal cards, or a collapsible folder in the code theme. */
@Composable
fun ShelfRow(shelf: MusicItem, actions: ItemActions) {
    val items = shelf.items.orEmpty()
    if (items.isEmpty()) return
    val c = Ytmd.colors
    if (Ytmd.code) {
        var expanded by rememberSaveable(shelf.id) { mutableStateOf(true) }
        Column(Modifier.padding(bottom = 14.dp).animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Shape)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = c.muted)
                Text(
                    buildAnnotatedString {
                        append(shelf.title)
                        withStyle(SpanStyle(color = c.muted)) { append(" /") }
                    },
                    Modifier.weight(1f),
                    style = Type.base,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Muted("${items.size} items", style = Type.tiny)
            }
            if (expanded) {
                Column(
                    Modifier
                        .padding(start = 8.dp)
                        // Vertical guide line at the artwork column.
                        .drawBehind { drawLine(c.outlineVariant, Offset(39.dp.toPx(), 0f), Offset(39.dp.toPx(), size.height), 1f) },
                ) {
                    items.forEachIndexed { i, item -> CodeEntry(i + 1, item, actions) }
                }
            }
        }
        return
    }
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.muted.copy(alpha = 0.55f))) { append("// ") }
                append(shelf.title.lowercase())
            },
            Modifier.padding(bottom = 6.dp),
            style = Type.small.copy(fontWeight = FontWeight.SemiBold),
            color = c.muted,
        )
        LazyRow(contentPadding = PaddingValues(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(items, key = { i, it -> "$i-${it.id}" }) { _, item -> MusicCard(item, actions) }
        }
    }
}

/** `.track-row` in collection pages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    index: Int,
    item: MusicItem,
    liked: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onMenu: () -> Unit,
    onArtist: (ArtistLink) -> Unit,
    selected: Boolean = false,
    downloaded: Boolean = false,
    /** Percent of an in-flight download, shown instead of the offline pin. */
    downloadPercent: Int? = null,
) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Shape)
            .background(if (selected) c.primary.mixWith(Color.Transparent, 0.10f) else Color.Transparent)
            .drawBehind { if (selected) drawRect(c.primary, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
            .combinedClickable(onClick = onPlay, onLongClick = onMenu)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${index + 1}", Modifier.width(24.dp), style = Type.small, color = c.muted)
        if (code) Artwork(item.thumbnails, 36.dp, codeFile = true, fallbackIcon = Icons.Outlined.MusicNote)
        else Artwork(item.thumbnails, 40.dp, fallbackIcon = Icons.Outlined.MusicNote)
        Column(Modifier.weight(1f)) {
            Text(
                if (code) buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted)) { append("song({ title: \"") }
                    withStyle(SpanStyle(color = c.primary)) { append(item.title) }
                    withStyle(SpanStyle(color = c.muted)) { append("\" });") }
                } else buildAnnotatedString { append(item.title) },
                style = Type.base,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArtistLinksText(
                item.artistLinks, item.subtitle ?: item.type.name, onArtist,
                prefix = if (code) buildAnnotatedString { append("// ") } else null,
            )
        }
        when {
            downloaded -> Icon(Icons.Outlined.OfflinePin, "Available offline", Modifier.size(15.dp), tint = c.primary)
            downloadPercent != null -> Muted("$downloadPercent%", style = Type.tiny)
        }
        IconBtn(
            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) c.primary else c.muted,
            size = 36.dp, iconSize = 18.dp,
            onClick = onLike,
        )
        IconBtn(Icons.Outlined.MoreVert, "More", size = 32.dp, iconSize = 18.dp, onClick = onMenu)
    }
}

/** Search results: one row per item (more readable on a phone than a horizontal shelf). */
@Composable
fun ResultList(items: List<MusicItem>, actions: ItemActions) {
    val code = Ytmd.code
    Column {
        items.forEachIndexed { i, item ->
            if (code) CodeEntry(i + 1, item, actions) else ResultRow(item, actions)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(item: MusicItem, actions: ItemActions) {
    val c = Ytmd.colors
    val isMood = item.type == ItemType.mood || item.color != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Shape)
            .combinedClickable(onClick = { actions.open(item) }, onLongClick = { actions.menu(item) })
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Artwork(
            item.thumbnails, 48.dp, round = item.type == ItemType.artist,
            color = if (isMood) parseHex(Api.moodColor(item)) else null, label = item.title,
        )
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Type.base, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.type.name,
                    Modifier.border(1.dp, c.outline, Shape).padding(horizontal = 4.dp),
                    style = Type.tiny,
                    color = c.muted,
                )
                ArtistLinksText(item.artistLinks, item.subtitle.orEmpty(), actions.artist)
            }
        }
        if (item.canPlay && !isMood) {
            IconBtn(Icons.Filled.PlayArrow, "Play", tint = c.primary, size = 36.dp) { actions.play(item) }
        }
        IconBtn(Icons.Outlined.MoreVert, "More", size = 32.dp, iconSize = 18.dp) { actions.menu(item) }
    }
}

@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.heightIn(min = h))
