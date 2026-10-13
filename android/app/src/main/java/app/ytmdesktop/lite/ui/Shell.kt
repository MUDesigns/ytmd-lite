package app.ytmdesktop.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.ui.components.Shape
import app.ytmdesktop.lite.ui.components.hairlineBottom
import app.ytmdesktop.lite.ui.components.hairlineTop
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.mixWith
import kotlin.random.Random

/** `.app-bar` */
@Composable
fun AppBar(breadcrumb: String, signedIn: Boolean, onSignIn: () -> Unit, onSettings: () -> Unit, onCommand: () -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (code) c.low else c.lowest)
            .hairlineBottom(c.outlineVariant)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ytmd-lite", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.primary)
        if (code) {
            // Command center pill: jumps to search, like Ctrl+K on desktop.
            Row(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                    .background(c.container)
                    .border(1.dp, c.outline, androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                    .clickable(onClick = onCommand)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = c.muted)
                Text("music workspace", style = Type.tiny, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(
                "workspace",
                Modifier.border(1.dp, c.outline, Shape).padding(horizontal = 6.dp, vertical = 1.dp),
                style = Type.tiny,
                color = c.muted,
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted.copy(alpha = 0.55f))) { append("~/") }
                    append(breadcrumb)
                },
                Modifier.weight(1f),
                style = Type.small,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Row(
                Modifier
                    .clip(Shape)
                    .background(c.primary)
                    .clickable(onClick = onSignIn)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(15.dp), tint = c.onPrimary)
                Text("Sign in", style = Type.small.copy(fontWeight = FontWeight.Medium), color = c.onPrimary)
            }
        }
        app.ytmdesktop.lite.ui.components.IconBtn(Icons.Outlined.Settings, "Settings", onClick = onSettings)
    }
}

// ── Code theme: editor tabs + breadcrumbs (workbench.ts) ─────────────────────

private val fileTypes = listOf(
    "cs" to "C#", "tsx" to "TSX", "ts" to "TS", "js" to "JS", "jsx" to "JSX", "py" to "PY", "rs" to "RS",
    "go" to "GO", "cpp" to "C++", "java" to "J", "rb" to "RB", "sh" to "$", "lua" to "LUA", "swift" to "SW",
    "kt" to "KT", "sql" to "SQL", "json" to "{ }", "yml" to "YML", "html" to "<>", "css" to "#",
)

/** Pick a varied set of file extensions once, then keep them across restarts. */
object WorkbenchFiles {
    private val files: Map<Panel, Pair<String, String>> by lazy {
        val saved = Prefs.getString("workbenchFiles")?.split(",")?.mapNotNull {
            val (p, ext) = it.split("=").takeIf { s -> s.size == 2 } ?: return@mapNotNull null
            runCatching { Panel.valueOf(p) }.getOrNull()?.let { panel -> panel to ext }
        }?.toMap().orEmpty()
        val used = saved.values.toMutableSet()
        val result = Panel.entries.associateWith { panel ->
            val ext = saved[panel]?.takeIf { e -> fileTypes.any { it.first == e } }
                ?: (fileTypes.filter { it.first !in used }.ifEmpty { fileTypes }).let { it[Random.nextInt(it.size)] }.first
            used.add(ext)
            fileTypes.first { it.first == ext }
        }
        Prefs.putString("workbenchFiles", result.entries.joinToString(",") { "${it.key.name}=${it.value.first}" })
        result
    }

    fun symbol(panel: Panel) = files.getValue(panel).second
    fun filename(panel: Panel, title: String? = null): String {
        val name = if (panel == Panel.detail && title != null) title else panel.label.lowercase()
        return "$name.${files.getValue(panel).first}"
    }
}

@Composable
fun WorkbenchTabs(panel: Panel, detailTitle: String?, breadcrumb: String, onPanel: (Panel) -> Unit) {
    val c = Ytmd.colors
    val tabs = listOf(Panel.home, Panel.explore, Panel.library, Panel.queue).let { if (panel in it) it else it + panel }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(c.lowest)
                .hairlineBottom(c.outlineVariant)
                .horizontalScroll(rememberScrollState()),
        ) {
            tabs.forEach { tab ->
                val active = tab == panel
                Row(
                    Modifier
                        .fillMaxHeight()
                        .background(if (active) c.surface else Color.Transparent)
                        .drawBehind {
                            if (active) drawLine(c.primary, Offset(0f, 0.5f), Offset(size.width, 0.5f), 2f)
                            drawLine(c.outlineVariant, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                        }
                        .clickable { onPanel(tab) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(WorkbenchFiles.symbol(tab), style = Type.small, color = c.primary)
                    Text(
                        WorkbenchFiles.filename(tab, detailTitle),
                        style = Type.small,
                        color = if (active) c.onSurface else c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(c.surface)
                .hairlineBottom(c.outlineVariant)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("music", Modifier.clickable { onPanel(Panel.home) }, style = Type.tiny, color = c.muted)
            Text("›", style = Type.tiny, color = c.muted)
            Text(breadcrumb, style = Type.tiny, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("›", style = Type.tiny, color = c.muted)
            Text(WorkbenchFiles.symbol(panel), style = Type.tiny, color = c.primary)
            Text(WorkbenchFiles.filename(panel, detailTitle), style = Type.tiny, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `.status` footer. */
@Composable
fun StatusLine(message: String, signedIn: Boolean, apiReady: Boolean, track: String?) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(c.lowest)
            .hairlineTop(c.outlineVariant)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (code) {
            Box(Modifier.fillMaxHeight().width(30.dp).background(c.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Code, "Code theme", Modifier.size(14.dp), tint = c.onPrimary)
            }
        } else {
            Spacer(Modifier.width(0.dp))
        }
        Text(message, Modifier.weight(1f), style = Type.tiny, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (code && track != null) Text(track, style = Type.tiny, color = c.muted, maxLines = 1)
        Text("${if (signedIn) "signed in" else "signed out"} · API ${if (apiReady) "ready" else "…"}", style = Type.tiny, color = c.muted, maxLines = 1)
    }
}

private data class RailEntry(val panel: Panel, val label: String, val icon: ImageVector, val activeIcon: ImageVector)

/** The desktop's left `.rail`, laid out along the bottom edge. */
@Composable
fun Rail(active: Panel?, lastFmEnabled: Boolean, lastFmOk: Boolean, onPanel: (Panel) -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    val entries = listOf(
        RailEntry(Panel.home, "home", if (code) Icons.Outlined.FileCopy else Icons.Outlined.Home, if (code) Icons.Outlined.FileCopy else Icons.Filled.Home),
        RailEntry(Panel.explore, "discover", Icons.Outlined.Explore, Icons.Filled.Explore),
        RailEntry(Panel.library, "library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
        RailEntry(Panel.queue, "queue", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
        RailEntry(Panel.lastfm, "last.fm", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
        RailEntry(Panel.settings, "config", Icons.Outlined.Tune, Icons.Filled.Tune),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.lowest)
            .hairlineTop(c.outlineVariant)
            .navigationBarsPadding()
            .height(54.dp),
    ) {
        entries.forEach { e ->
            val on = e.panel == active
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on && !code) c.primary.mixWith(Color.Transparent, 0.10f) else Color.Transparent)
                    .drawBehind { if (on) drawLine(c.primary, Offset(0f, 1f), Offset(size.width, 1f), 2.dp.toPx()) }
                    .clickable { onPanel(e.panel) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (on) e.activeIcon else e.icon, e.label,
                        Modifier.size(if (code) 24.dp else 21.dp),
                        tint = when {
                            on && code -> c.onSurface
                            on -> c.primary
                            else -> c.muted
                        },
                    )
                    if (!code) Text(e.label, style = Type.tiny, color = if (on) c.primary else c.muted, maxLines = 1)
                }
                if (e.panel == Panel.lastfm && lastFmEnabled) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 14.dp)
                            .size(6.dp)
                            .background(if (lastFmOk) Color(0xFF81C995) else c.error, Shape),
                    )
                }
            }
        }
    }
}
