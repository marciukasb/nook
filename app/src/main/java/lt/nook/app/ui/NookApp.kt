package lt.nook.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.nook.app.core.Group
import lt.nook.app.core.ItemV
import lt.nook.app.core.Row as ItemRow
import lt.nook.app.core.group
import lt.nook.app.data.CategoryMemory
import lt.nook.app.data.Kind
import lt.nook.app.sync.Status
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.TickResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val Kind.title get() = when (this) { Kind.GROCERIES -> "Groceries"; Kind.SHOPPING -> "Shopping"; Kind.TASKS -> "Tasks" }
private val Kind.icon: ImageVector get() = when (this) {
    Kind.GROCERIES -> Icons.Filled.ShoppingCart; Kind.SHOPPING -> Icons.Filled.Place; Kind.TASKS -> Icons.Filled.CheckCircle
}

private val nextFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
fun nextDateText(date: String?): String =
    date?.let { runCatching { "Next: " + LocalDate.parse(it.take(10)).format(nextFmt) }.getOrNull() } ?: "Moved to its next date"

/** The whole app once signed in: three tabs, a floating + and nothing else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NookApp(
    engine: SyncEngine,
    memory: CategoryMemory,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    autoRefresh: Boolean = true,
    updateBanner: @Composable () -> Unit = {},
    openRequest: lt.nook.app.OpenRequest? = null,
    onRequestHandled: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(Kind.GROCERIES) }
    var adding by remember { mutableStateOf(false) }
    var addForMe by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openRequest) {   // from the home-screen widget: the Tasks tab, maybe adding a task for me
        val r = openRequest ?: return@LaunchedEffect
        tab = r.tab
        if (r.add) { addForMe = engine.meId(); adding = true }
        onRequestHandled()
    }
    var moving by remember { mutableStateOf<ItemV?>(null) }
    var confirm by remember { mutableStateOf<Pair<ItemV, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    if (autoRefresh) LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { engine.flush(); delay(30_000) }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); engine.clearMessage() }
    }

    fun sync() = scope.launch { engine.flush(force = true) }

    fun doTick(item: ItemV) = scope.launch {
        // Normal items: the row goes and Undo shows at once; the network happens in the background.
        // Repeating tasks wait for the sync so the snackbar can name the next date (the row stays meanwhile).
        when (val r = engine.tick(item.id, sync = item.recurring)) {
            is TickResult.Hidden -> {
                sync()
                snack.currentSnackbarData?.dismiss()
                val res = snack.showSnackbar("${item.content} ticked off", "Undo", duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) { engine.undo(r.undo, sync = false); sync() }
            }
            TickResult.AlreadyQueued -> {
                snack.currentSnackbarData?.dismiss()
                snack.showSnackbar("Will move to its next date when back online")
            }
            is TickResult.Advanced -> {
                snack.currentSnackbarData?.dismiss()
                snack.showSnackbar(nextDateText(r.nextDate))
            }
            TickResult.NotFound -> {}
        }
    }

    fun onTick(item: ItemV) = scope.launch {
        val kids = engine.openChildren(item.id)
        if (kids > 0 && !item.recurring) confirm = item to kids else doTick(item)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab.title)
                        StatusLine(state.status, state.pending, onSettings)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Settings") }, { menu = false; onSettings() })
                            DropdownMenuItem({ Text("Sign out") }, {
                                menu = false
                                if (state.pending > 0) confirmSignOut = true else onSignOut()
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Kind.entries.forEach { k ->
                    NavigationBarItem(selected = tab == k, onClick = { tab = k },
                        icon = { Icon(k.icon, null) }, label = { Text(k.title) })
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add") }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            updateBanner()
            PullToRefreshBox(refreshing, onRefresh = {
                scope.launch { refreshing = true; engine.flush(force = true); refreshing = false }
            }, Modifier.fillMaxSize()) {
                ListContent(engine, tab, onTick = { onTick(it) }, onLongPress = { moving = it })
            }
        }
    }

    if (adding) androidx.compose.runtime.key(tab, addForMe) {   // reopened from the widget: fresh sheet, me preselected
        AddSheet(engine, memory, tab, scope, initialAssignee = addForMe, onDismiss = { adding = false; addForMe = null })
    }
    moving?.let { item -> MoveSheet(engine, tab, item, scope, onDismiss = { moving = null }) }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("${state.pending} changes not synced yet will be lost.") },
        confirmButton = { TextButton({ confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } },
    )
    confirm?.let { (item, n) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Tick ${item.content}?") },
            text = { Text("Also ticks $n sub-items?") },
            confirmButton = { TextButton({ confirm = null; doTick(item) }) { Text("Tick all") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusLine(status: Status, pending: Int, onSettings: () -> Unit) {
    val (text, error) = when {
        status == Status.SignInAgain -> "Sign in again" to true
        status is Status.ProjectsMissing -> "Project \"${status.names.first()}\" not found" to true
        pending > 0 && status != Status.Syncing -> "Not synced yet ($pending changes)" to true
        status == Status.Syncing -> "Syncing…" to false
        status == Status.Offline -> "Offline" to true
        else -> "Synced" to false
    }
    val mod = if (status == Status.SignInAgain || status is Status.ProjectsMissing)
        Modifier.combinedClickableCompat(onSettings) else Modifier
    Text(text, mod, style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListContent(engine: SyncEngine, kind: Kind, onTick: (ItemV) -> Unit, onLongPress: (ItemV) -> Unit) {
    val items by remember(kind) { engine.items(kind) }.collectAsState(initial = null)
    val sections by remember(kind) { engine.sections(kind) }.collectAsState(initial = emptyList())
    val list = items ?: return
    val groups: List<Group> = remember(list, sections) { group(list, sections) }
    if (groups.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("Nothing here — tap + to add", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        groups.forEach { g ->
            if (g.title.isNotEmpty()) item(key = "h_" + (g.sectionId ?: "other")) {
                Text(g.title.uppercase(), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            items(g.rows, key = { it.item.id }) { row -> ItemLine(row, kind, onTick, onLongPress) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemLine(row: ItemRow, kind: Kind, onTick: (ItemV) -> Unit, onLongPress: (ItemV) -> Unit) {
    val item = row.item
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onTick(item) }, onLongClick = { onLongPress(item) })
            .padding(start = (8 + 24 * row.depth).dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onTick(item) }, Modifier.semantics { contentDescription = "Tick ${item.content}" }) {
            if (item.recurring) Icon(Icons.Filled.Refresh, null)
            else Box(Modifier.size(22.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(item.content, style = MaterialTheme.typography.bodyLarge)
            if (kind == Kind.TASKS) {
                val due = item.due?.let { d -> runCatching { LocalDate.parse(d.take(10)).format(nextFmt) }.getOrDefault(d) }
                Text(listOfNotNull(item.assignee ?: "Anyone", due).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
