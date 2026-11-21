package lt.nook.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lt.nook.app.core.ItemV
import lt.nook.app.core.Known
import lt.nook.app.core.OTHER
import lt.nook.app.core.nameKey
import lt.nook.app.data.CategoryMemory
import lt.nook.app.data.Kind
import lt.nook.app.sync.SyncEngine

/**
 * The + sheet. Groceries/Shopping: suggestions with their remembered category; a known item goes straight to its
 * category, a new one asks once. Tasks: suggestions + who it's for. Stays open after each add.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSheet(engine: SyncEngine, memory: CategoryMemory, kind: Kind, scope: CoroutineScope, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    var newCatName by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<Known>()) }
    val sections by remember(kind) { engine.sections(kind) }.collectAsState(initial = emptyList())
    val items by remember(kind) { engine.items(kind) }.collectAsState(initial = emptyList())
    val people by remember { engine.people() }.collectAsState(initial = emptyList())
    val focus = remember { FocusRequester() }
    val lists = kind != Kind.TASKS

    LaunchedEffect(text) { suggestions = memory.suggestions(kind, text) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun reset() { text = ""; hint = null; pick = false; newCat = false; newCatName = "" }

    fun duplicate(name: String): ItemV? = if (lists) items.firstOrNull { nameKey(it.content) == nameKey(name) } else null

    fun addTo(name: String, sectionId: String?) {
        reset()
        scope.launch { runCatching { engine.add(kind, name, sectionId, if (lists) null else assignee) } }
    }

    fun place(name: String) = scope.launch {
        duplicate(name)?.let { hint = "${it.content} is already on the list"; return@launch }
        if (!lists) { addTo(name, null); return@launch }
        val known = memory.known(kind, name)
        val sec = memory.sectionFor(kind, name, sections)
        when {
            sec != null -> addTo(known?.name ?: name, sec.id)
            known != null && known.sectionName == null -> addTo(known.name, null)
            sections.isEmpty() -> addTo(name, null)
            else -> { text = known?.name ?: name; pick = true; hint = null }
        }
    }

    fun submit() { val n = text.trim(); if (n.isNotEmpty()) place(n) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add to ${kind.title}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                text, { text = it; hint = null; pick = false },
                Modifier.fillMaxWidth().focusRequester(focus).testTag("addField"),
                singleLine = true, placeholder = { Text(if (lists) "Item" else "Task") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            suggestions.filter { nameKey(it.name) != nameKey(text) || !pick }.forEach { k ->
                val label = if (lists && k.section != null) "${k.name} · ${k.section}" else k.name
                Text(label, Modifier.fillMaxWidth().clickable {
                    if (lists) place(k.name) else { text = k.name }
                }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            if (suggestions.isNotEmpty()) HorizontalDivider()

            if (lists && text.isNotBlank()) {
                if (pick) Text("Pick a category", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEach { s ->
                        AssistChip({ duplicate(text)?.let { hint = "${it.content} is already on the list" } ?: addTo(text.trim(), s.id) },
                            { Text(s.name) }, Modifier.testTag("chip_${s.name}"))
                    }
                    AssistChip({ duplicate(text)?.let { hint = "${it.content} is already on the list" } ?: addTo(text.trim(), null) },
                        { Text(OTHER) }, Modifier.testTag("chip_Other"))
                    AssistChip({ newCat = true }, { Text("+ New category") })
                }
                if (newCat) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newCatName, { newCatName = it }, Modifier.weight(1f).testTag("newCategory"),
                        singleLine = true, placeholder = { Text(if (kind == Kind.SHOPPING) "Shop, e.g. IKEA" else "Category") })
                    Button(enabled = newCatName.isNotBlank(), onClick = {
                        val name = text.trim(); val cat = newCatName.trim()
                        reset()
                        scope.launch {
                            runCatching {
                                val existing = sections.firstOrNull { nameKey(it.name) == nameKey(cat) }
                                val sid = existing?.id ?: engine.newSection(kind, cat)
                                engine.add(kind, name, sid, null)
                            }
                        }
                    }) { Text("Create") }
                }
            }

            if (!lists) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(assignee == null, { assignee = null }, { Text("Anyone") }, Modifier.testTag("assignee_Anyone"))
                people.forEach { p ->
                    FilterChip(assignee == p.id, { assignee = p.id }, { Text(p.name) }, Modifier.testTag("assignee_${p.name}"))
                }
            }

            Button(onClick = { submit() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add") }
        }
    }
}

/** Long-press sheet: move an item to another category/shop (and teach the memory). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(engine: SyncEngine, kind: Kind, item: ItemV, scope: CoroutineScope, onDismiss: () -> Unit) {
    val sections by remember(kind) { engine.sections(kind) }.collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).padding(bottom = 16.dp)) {
            Text("Move ${item.content} to", style = MaterialTheme.typography.titleMedium)
            if (sections.isEmpty()) Text("This list has no categories yet.", Modifier.padding(vertical = 12.dp))
            (sections.map { it.name to it.id } + (OTHER to null)).forEach { (name, id) ->
                val current = item.sectionId == id
                Text(if (current) "$name (now)" else name,
                    Modifier.fillMaxWidth().testTag("move_$name").clickable(enabled = !current) {
                        onDismiss(); scope.launch { engine.move(item.id, id) }
                    }.padding(vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
