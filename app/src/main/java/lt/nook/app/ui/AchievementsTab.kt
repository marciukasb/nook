package lt.nook.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lt.nook.app.core.ItemV
import lt.nook.app.core.dateText
import lt.nook.app.core.text
import lt.nook.app.data.Kind
import lt.nook.app.sync.SyncEngine

/** Open achievements in their sections and, folded below them, the finished ones. A row opens its details. */
@Composable
fun AchievementsContent(
    engine: SyncEngine,
    onAchieve: (ItemV) -> Unit, onTick: (ItemV) -> Unit, onReopen: (ItemV) -> Unit,
    onOpen: (ItemV) -> Unit, onLongPress: (ItemV) -> Unit,
) {
    val lists by remember { engine.achievements() }.collectAsState(initial = null)
    val project by remember { engine.hasProject(Kind.ACHIEVEMENTS) }.collectAsState(initial = null)
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val l = lists ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (project == false) item(key = "none") { Note(engine.noProjectText(Kind.ACHIEVEMENTS)) }
        else if (l.open.isEmpty()) item(key = "none") { Note("Nothing here — tap + to add") }
        l.open.forEach { g ->
            if (g.title.isNotEmpty()) item(key = "h_" + (g.sectionId ?: "other")) { SectionTitle(g.title) }
            items(g.rows, key = { it.item.id }) { row ->
                val sub = row.depth > 0
                ItemLine(row.item, row.depth, "Tick ${row.item.content}",
                    lines = listOf(listOfNotNull(row.item.assignee ?: "Anyone", dueText(row.item.due)).joinToString(" · "), firstLine(row.item)),
                    onTick = if (sub || row.item.recurring) onTick else onAchieve, onOpen = onOpen, onLongPress = onLongPress)
            }
        }
        if (l.done.isNotEmpty()) {
            item(key = "done") {
                Text("Done · ${l.done.count { it.depth == 0 }}", Modifier.fillMaxWidth().clickable { unfolded = !unfolded }
                    .padding(start = 16.dp, top = 20.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (unfolded) items(l.done, key = { it.item.id }) { r ->
                val sub = r.depth > 0
                ItemLine(r.item, r.depth, if (sub) "Tick ${r.item.content}" else "Reopen ${r.item.content}",
                    lines = listOf(listOfNotNull(r.date?.let(::dateText), r.item.assignee).joinToString(" · ")),
                    onTick = if (sub) onTick else onReopen, onOpen = onOpen, onLongPress = null, done = !sub)
            }
        }
    }
}

private fun firstLine(item: ItemV) = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
