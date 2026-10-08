package com.deskcubby.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

/**
 * A global "go to / create / find" overlay. Typing filters pages, quick actions and diary
 * entries with a forgiving fuzzy match; Enter (or the keyboard's Go key) picks the top result.
 * Choosing a row only navigates or opens an existing flow; the palette never writes data.
 */
@Composable
fun CommandPalette(
    entries: List<PaletteEntry>,
    onChoose: (PaletteEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, entries) { rankPaletteEntries(query, entries) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = tr("关闭", "Close"),
                    onClick = onDismiss,
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            GlassPanel(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Swallow taps inside the panel so only the scrim dismisses.
                    .pointerInput(Unit) { detectTapGestures { } },
                cornerRadius = 26.dp,
                role = PanelRole.FEATURE,
                padding = PaddingValues(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(tr("前往页面、执行动作或查找日记", "Go to a page, run an action or find a diary")) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(onChoose) }),
                    )
                    if (results.isEmpty()) {
                        Text(
                            text = tr("没有匹配的结果", "No matches"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(results, key = { it.id }) { entry ->
                                PaletteRow(
                                    entry = entry,
                                    highlighted = entry == results.first() && query.isNotBlank(),
                                    onClick = { onChoose(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, highlighted: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kindLabel = when (entry.kind) {
        PaletteEntryKind.PAGE -> tr("页面", "Page")
        PaletteEntryKind.ACTION -> tr("动作", "Action")
        PaletteEntryKind.DIARY -> tr("日记", "Diary")
    }
    val enterHint = tr("按回车打开", "Press Enter to open")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (highlighted) stateDescription = enterHint }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (entry.kind) {
                PaletteEntryKind.PAGE -> Icons.Outlined.NorthEast
                PaletteEntryKind.ACTION -> Icons.Outlined.Bolt
                PaletteEntryKind.DIARY -> Icons.Outlined.Book
            },
            contentDescription = kindLabel,
            tint = if (highlighted) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (highlighted) {
            Text(
                text = "↵",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
            )
        }
    }
}
