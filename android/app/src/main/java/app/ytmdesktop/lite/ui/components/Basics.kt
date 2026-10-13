package app.ytmdesktop.lite.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.ArtistLink
import app.ytmdesktop.lite.ui.theme.Radius
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.mixWith
import coil3.compose.AsyncImage

val Shape = RoundedCornerShape(Radius)

/** Bottom hairline, like `border-bottom: var(--hairline)`. */
fun Modifier.hairlineBottom(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

fun Modifier.hairlineTop(color: Color) = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
}

/** `.btn` / `.btn.primary` */
@Composable
fun IdeButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Ytmd.colors
    val bg = when {
        primary -> c.primary
        active -> c.primary.mixWith(Color.Transparent, 0.12f)
        else -> c.container
    }
    val fg = when {
        primary -> c.onPrimary
        active -> c.primary
        else -> c.onSurface
    }
    val border = when {
        primary -> c.primary
        active -> c.primary.mixWith(c.outline, 0.45f)
        else -> c.outline
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(Shape)
            .background(bg)
            .border(1.dp, border, Shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(text, style = Type.small, color = fg, maxLines = 1)
    }
}

/** `.icon-btn` */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Ytmd.colors.muted,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(Shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(iconSize), tint = tint)
    }
}

/** `.chip` / `.filter-chip` */
@Composable
fun IdeChip(text: String, active: Boolean = false, onClick: () -> Unit) {
    val c = Ytmd.colors
    Box(
        Modifier
            .clip(Shape)
            .background(if (active) c.primary.mixWith(Color.Transparent, 0.18f) else c.low)
            .border(1.dp, if (active) c.primary else c.outline, Shape)
            .clickable(onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.small, color = if (active) c.primary else c.onSurface, maxLines = 1)
    }
}

/** `.callout` / `.callout.error` */
@Composable
fun Callout(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val c = Ytmd.colors
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(Shape)
            .background(c.container)
            .border(1.dp, if (error) c.error.mixWith(Color.Transparent, 0.35f) else c.outlineVariant, Shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        style = Type.small,
        color = if (error) c.error else c.onSurface,
    )
}

/** `.page-title` — "# title" (default) or "// Title" (code theme). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.muted, fontWeight = FontWeight.Normal)) { append(if (code) "// " else "# ") }
            append(if (code) text else text.lowercase())
        },
        modifier,
        style = (if (code) Type.hero else Type.title),
        color = c.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `.page-head`: title row with trailing actions and a hairline. */
@Composable
fun PageHead(title: String, actions: @Composable () -> Unit = {}) {
    val c = Ytmd.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .hairlineBottom(c.outlineVariant)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageTitle(title, Modifier.weight(1f))
        actions()
    }
}

/** `.settings-label` — "// label" or, in the code theme, `"label": {`. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Text(
        buildAnnotatedString {
            if (code) {
                append("\"${text.lowercase()}\"")
                withStyle(SpanStyle(color = c.muted)) { append(": {") }
            } else {
                withStyle(SpanStyle(color = c.muted.copy(alpha = 0.6f))) { append("// ") }
                append(text.lowercase())
            }
        },
        modifier.padding(bottom = 10.dp),
        style = Type.small.copy(fontWeight = FontWeight.SemiBold),
        color = if (code) c.primary else c.muted,
    )
}

/** Square spinner from the desktop (rotating box with an accent edge). */
@Composable
fun Spinner(size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val c = Ytmd.colors
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "angle",
    )
    Canvas(modifier.size(size).rotate(angle)) {
        val w = 2.dp.toPx()
        val inset = w / 2
        drawRect(c.outline, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
        drawLine(c.primary, Offset(0f, inset), Offset(this.size.width, inset), w)
    }
}

@Composable
fun SpinnerOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}

/**
 * Album art. Default appearance: rounded-2dp square (circle for artists).
 * Code theme: a folded "file" silhouette containing a small cover, as in code-theme.css.
 */
@Composable
fun Artwork(
    thumbnails: List<String>?,
    size: Dp,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    codeFile: Boolean = false,
    fallbackIcon: ImageVector = Icons.Outlined.Album,
    color: Color? = null,
    label: String? = null,
) {
    val c = Ytmd.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val url = Api.thumbUrl(thumbnails, with(density) { (size * 2).roundToPx() })
    if (codeFile) {
        val w = size * 0.82f
        Box(
            modifier
                .size(w, size)
                .background(c.high)
                .border(1.dp, c.outline)
                .drawBehind {
                    val fold = 7.dp.toPx()
                    val p = Path().apply {
                        moveTo(this@drawBehind.size.width - fold, 0f)
                        lineTo(this@drawBehind.size.width, 0f)
                        lineTo(this@drawBehind.size.width, fold)
                        close()
                    }
                    drawPath(p, c.surface)
                    drawLine(c.outline, Offset(this.size.width - fold, 0f), Offset(this.size.width, fold), 1f)
                }
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(fallbackIcon, null, Modifier.size(size * 0.5f), tint = c.muted.copy(alpha = 0.6f))
            }
        }
        return
    }
    val shape = if (round) CircleShape else Shape
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(color ?: c.highest),
        contentAlignment = Alignment.Center,
    ) {
        when {
            color != null -> Text(
                label?.take(1).orEmpty(),
                style = Type.hero.copy(fontSize = Type.hero.fontSize * 1.6f),
                color = Color.White.copy(alpha = 0.92f),
            )
            url != null -> AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Icon(fallbackIcon, null, Modifier.size(size * 0.3f).alpha(0.35f), tint = c.onSurface)
        }
    }
}

/** Clickable artist names (ArtistLinks.svelte). */
@Composable
fun ArtistLinksText(
    links: List<ArtistLink>?,
    fallback: String,
    onArtist: (ArtistLink) -> Unit,
    style: TextStyle = Type.small,
    color: Color = Ytmd.colors.muted,
    prefix: AnnotatedString? = null,
    modifier: Modifier = Modifier,
) {
    val c = Ytmd.colors
    val list = links?.takeIf { it.isNotEmpty() }
        ?: fallback.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { ArtistLink(it) }
    val text = buildAnnotatedString {
        prefix?.let { append(it) }
        list.forEachIndexed { i, link ->
            if (i > 0) append(", ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = link.name,
                    styles = TextLinkStyles(pressedStyle = SpanStyle(color = c.primary)),
                ) { onArtist(link) },
            ) { append(link.name) }
        }
        if (list.isEmpty()) append(fallback)
    }
    Text(text, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.small) {
    Text(text, modifier, style = style, color = Ytmd.colors.muted)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ytmd.colors.outlineVariant))
}
