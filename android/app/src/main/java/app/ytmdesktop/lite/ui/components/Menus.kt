package app.ytmdesktop.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.ytmdesktop.lite.data.PlaylistOption
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.parseColorInput
import app.ytmdesktop.lite.ui.theme.rgbToHex
import app.ytmdesktop.lite.ui.theme.toColor

data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** ContextMenu.svelte as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val c = Ytmd.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.high,
        contentColor = c.onSurface,
        shape = Shape,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(32.dp, 3.dp).background(c.outline)) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Column(Modifier.fillMaxWidth().hairlineBottom(c.outline).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, style = Type.base, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotEmpty() }?.let { Muted("// $it", style = Type.tiny) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); action.onClick() }
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(action.icon, null, Modifier.size(18.dp), tint = c.muted)
                        Text(action.label, style = Type.base)
                    }
                }
            }
        }
    }
}

/** Add-to-playlist picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(load: suspend () -> List<PlaylistOption>, onPick: (PlaylistOption) -> Unit, onDismiss: () -> Unit) {
    val c = Ytmd.colors
    var options by remember { mutableStateOf<List<PlaylistOption>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { options = load() } catch (e: Exception) { error = e.message }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.high,
        contentColor = c.onSurface,
        shape = Shape,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(32.dp, 3.dp).background(c.outline)) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionLabel("Add to playlist")
            when {
                error != null -> Callout(error!!, error = true)
                options == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                options!!.isEmpty() -> Muted("No editable playlists in your library.")
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    options!!.forEach { option ->
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = c.muted)) { append("› ") }
                                append(option.title)
                            },
                            Modifier
                                .fillMaxWidth()
                                .clickable { onDismiss(); onPick(option) }
                                .padding(vertical = 13.dp),
                            style = Type.base,
                        )
                    }
                }
            }
        }
    }
}

private val presets = listOf(
    "#a8c7fa", "#c4a8e8", "#7fd99a", "#e6c07b", "#f2b8b5", "#4dd0e1", "#ff8a65", "#f06292", "#aed581", "#d7dde8",
)

/** AccentPicker.svelte: swatches plus a free-form hex / RGB field. */
@Composable
fun AccentPicker(value: String, onChange: (String) -> Unit) {
    val c = Ytmd.colors
    var text by remember(value) { mutableStateOf(value) }
    val parsed = parseColorInput(text)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { hex ->
                val selected = hex.equals(value, ignoreCase = true)
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(Shape)
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.onSurface else c.outline, Shape)
                        .padding(4.dp)
                        .background(parseColorInput(hex)!!.toColor(), Shape)
                        .clickable { onChange(hex) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(34.dp)
                    .border(1.dp, c.outline, Shape)
                    .padding(4.dp)
                    .background(parsed?.toColor() ?: Color.Transparent, Shape),
            )
            Row(
                Modifier
                    .weight(1f)
                    .background(c.lowest, Shape)
                    .border(1.dp, if (parsed == null) c.error else c.outline, Shape)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    text,
                    onValueChange = { text = it },
                    textStyle = Type.base.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let { onChange(rgbToHex(it)) } }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IdeButton("apply", enabled = parsed != null) { parsed?.let { onChange(rgbToHex(it)) } }
        }
        Muted("hex (#a8c7fa), rgb(168, 199, 250), or \"168 199 250\"", style = Type.tiny)
    }
}
