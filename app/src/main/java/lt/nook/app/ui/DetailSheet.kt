package lt.nook.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lt.nook.app.core.ItemV
import lt.nook.app.core.MAX_DESCRIPTION
import lt.nook.app.core.dateText
import lt.nook.app.core.text
import lt.nook.app.data.Kind
import lt.nook.app.sync.SyncEngine

/** "16001 of 16000 characters", or null while it fits. */
fun tooLong(text: String): String? =
    text.trimEnd().length.takeIf { it > MAX_DESCRIPTION }?.let { "$it of $MAX_DESCRIPTION characters" }

/** One achievement, full screen over the app: its name, who it's for and its description, which can be edited. */
@Composable
fun DetailSheet(engine: SyncEngine, id: String, scope: CoroutineScope, onUnsaved: (Boolean) -> Unit = {}, onDismiss: () -> Unit) {
    var item by remember(id) { mutableStateOf<ItemV?>(null) }
    var seen by rememberSaveable(id) { mutableStateOf(false) }     // it has been shown: if it goes now, it was removed
    var gone by remember(id) { mutableStateOf(false) }
    var base by rememberSaveable(id) { mutableStateOf("") }
    var text by rememberSaveable(id) { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }

    val lists by remember { engine.achievements() }.collectAsState(initial = null)

    LaunchedEffect(id) {
        engine.item(Kind.ACHIEVEMENTS, id).collect { now ->
            gone = now == null
            if (now == null) { if (!seen) onDismiss(); return@collect }
            // Text not touched yet follows what others write; typed text is never replaced.
            if (!seen || text == base) text = now.text
            if (!seen || text == now.text) base = now.text
            item = now; seen = true
        }
    }

    val unsaved = seen && text != base
    LaunchedEffect(unsaved) { onUnsaved(unsaved) }

    fun save() = scope.launch {
        if (engine.describe(id, text, sync = false)) { onDismiss(); engine.flush(force = true) }
    }
    fun close() { if (unsaved) asking = true else onDismiss() }

    BackHandler { close() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text(item?.content.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                }
                IconButton({ close() }) { Icon(Icons.Filled.Close, "Close") }
            }
            item?.let { i ->
                val day = lists?.done?.firstOrNull { it.item.id == i.id }?.date
                Text(listOfNotNull(i.assignee ?: "Anyone", day?.let { "Done ${dateText(it)}" }).joinToString(" · "),
                    Modifier.testTag("detailWho"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (seen) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f).testTag("detailText"),
                label = { Text("Description") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            val problem = if (gone) "This was removed" else tooLong(text)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({ save() }, Modifier.fillMaxWidth().testTag("detailSave"), enabled = unsaved && problem == null) { Text("Save") }
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Save the changes?") },
        text = { Text("The description has changes that aren't saved yet.") },
        confirmButton = {
            TextButton({ asking = false; save() }, Modifier.testTag("askSave"), enabled = !gone && tooLong(text) == null) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton({ asking = false; onDismiss() }) { Text("Discard") }
                TextButton({ asking = false }) { Text("Keep editing") }
            }
        },
    )
}
