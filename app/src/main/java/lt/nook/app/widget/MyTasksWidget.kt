package lt.nook.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.LocalContext
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import lt.nook.app.sync.FlushWorker
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lt.nook.app.MainActivity
import lt.nook.app.NookApplication
import lt.nook.app.core.ItemV
import lt.nook.app.data.Kind
import java.util.concurrent.TimeUnit

/** What the widget shows; built from Nook's own offline copy, so it works without signal. */
data class WidgetState(
    val signedIn: Boolean,
    val tasks: List<ItemV>,
    val undoLabel: String? = null,
    val synced: Boolean = true,
    val selected: Kind = Kind.TASKS,
    val achievements: List<ItemV> = emptyList(),
    /** What to create when Todoist has no Achievements project. */
    val noProject: String? = null,
)

private val ID = ActionParameters.Key<String>("id")
val KIND = ActionParameters.Key<String>("kind")
/** Which list a widget shows; every widget remembers its own. */
private val SELECTED = stringPreferencesKey("selected")

/** Shared tasks and achievements assigned to the signed-in person, tickable from the home screen. */
class MyTasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NookApplication
        val tasks = app.engine.myTasksFlow()
        val achievements = app.engine.myAchievementsFlow()
        val project = app.engine.hasProject(Kind.ACHIEVEMENTS)
        provideContent {
            // Live: the widget follows Nook's database, sign-in and undo while it's on screen.
            val data by tasks.collectAsState(initial = null)
            val ach by achievements.collectAsState(initial = null)
            val hasProject by project.collectAsState(initial = null)
            val undo by app.widgetActions.undoLabel.collectAsState()
            val signedIn by app.signedIn.collectAsState()
            GlanceTheme {
                WidgetContent(WidgetState(signedIn, data?.tasks.orEmpty(), undo.takeIf { signedIn && app.widgetActions.canUndo() },
                    synced = data?.me != null,
                    selected = if (currentState(SELECTED) == Kind.ACHIEVEMENTS.name) Kind.ACHIEVEMENTS else Kind.TASKS,
                    achievements = ach?.tasks.orEmpty(),
                    noProject = if (hasProject == false) app.engine.noProjectText(Kind.ACHIEVEMENTS) else null))
            }
        }
    }
}

fun openNook(context: Context, tab: Kind, add: Boolean = false, itemId: String? = null): Intent = Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_TAB, tab.name).putExtra(MainActivity.EXTRA_ADD, add).putExtra(MainActivity.EXTRA_ITEM, itemId)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

@Composable
fun WidgetContent(s: WidgetState) {
    val c = GlanceTheme.colors
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(c.widgetBackground).cornerRadius(20.dp).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!s.signedIn) Text("My tasks", GlanceModifier.defaultWeight().clickable(actionStartActivity(openNook(context, Kind.TASKS))),
                style = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            else {
                ListName(s, Kind.TASKS, "Tasks ${s.tasks.size}", GlanceModifier)
                Spacer(GlanceModifier.width(4.dp))
                ListName(s, Kind.ACHIEVEMENTS, if (s.noProject == null) "Achievements ${s.achievements.size}" else "Achievements", GlanceModifier.defaultWeight())
                Text("+", GlanceModifier.padding(horizontal = 8.dp).clickable(actionStartActivity(openNook(context, s.selected, add = true))),
                    style = TextStyle(color = c.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }
        val rest = GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 4.dp)
        val ach = s.selected == Kind.ACHIEVEMENTS
        val list = if (ach) s.achievements else s.tasks
        when {
            !s.signedIn -> Note("Open Nook to sign in", rest)
            !s.synced -> Note("Syncing…", rest)
            ach && s.noProject != null -> Note(s.noProject, rest)
            list.isEmpty() -> Note("Nothing assigned to you", rest)
            else -> LazyColumn(rest) {
                // Keyed on id + date: a repeating task that moved to its next date is a new row, so its box resets.
                items(list, itemId = { ("${it.id}|${it.due}").hashCode().toLong() }) { t -> if (ach) AchievementRow(t) else TaskRow(t) }
            }
        }
        s.undoLabel?.let {
            Text("$it · Undo", GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(actionRunCallback<UndoAction>()), maxLines = 1,
                style = TextStyle(color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** One half of the toggle: the list shown opens Nook on it, the other one switches to it. */
@Composable
private fun ListName(s: WidgetState, kind: Kind, text: String, modifier: GlanceModifier) {
    val c = GlanceTheme.colors
    val on = s.selected == kind
    Text(text, modifier.padding(vertical = 6.dp, horizontal = 4.dp).clickable(
            if (on) actionStartActivity(openNook(LocalContext.current, kind)) else actionRunCallback<SelectAction>(actionParametersOf(KIND to kind.name))),
        maxLines = 1, style = TextStyle(color = if (on) c.onSurface else c.onSurfaceVariant, fontSize = 15.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal))
}

@Composable
private fun Note(text: String, modifier: GlanceModifier) = Text(text, modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))

@Composable
private fun TaskRow(t: ItemV) = CheckBox(checked = false, onCheckedChange = actionRunCallback<TickAction>(actionParametersOf(ID to t.id)),
    text = t.content + (t.due?.let { " · ${shortDate(it)}" } ?: ""), modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp), maxLines = 2)

/** The box achieves it; the name opens its details in Nook. */
@Composable
private fun AchievementRow(t: ItemV) = Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    CheckBox(checked = false, onCheckedChange = actionRunCallback<AchieveAction>(actionParametersOf(ID to t.id)))
    Text(t.content, GlanceModifier.defaultWeight().padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
        .clickable(actionStartActivity(openNook(LocalContext.current, Kind.ACHIEVEMENTS, itemId = t.id))), maxLines = 2,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp))
}

private val DATE = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
fun shortDate(d: String): String = runCatching { java.time.LocalDate.parse(d.take(10)).format(DATE) }.getOrDefault(d)

/** Wakes every Nook widget so it reads fresh data (after a sync when no widget session is running). */
suspend fun refreshWidgets(context: Context) {
    try { MyTasksWidget().updateAll(context) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { android.util.Log.w("Nook", "widget refresh failed", e) }
}

class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NookApplication
        val id = parameters[ID] ?: return
        app.widgetActions.tick(id)          // the widget updates itself from the database (live)
        FlushWorker.sendNow(context)        // the network part runs as background work, not inside this tap
    }
}

class AchieveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NookApplication
        app.widgetActions.achieve(parameters[ID] ?: return)
        FlushWorker.sendNow(context)
    }
}

/** Switches this widget between Tasks and Achievements. */
class SelectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters[KIND] ?: return
        updateAppWidgetState(context, glanceId) { it[SELECTED] = kind }
        MyTasksWidget().update(context, glanceId)
    }
}

class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NookApplication
        app.widgetActions.undo()
        FlushWorker.sendNow(context)
    }
}

/** Keeps the widget fresh while it's on a home screen: a quiet sync about every 30 minutes. */
class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NookApplication
        if (app.tokens.get() != null) app.engine.flush()
        refreshWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        const val NAME = "widget-refresh"
        fun start(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        fun stop(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}

class MyTasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MyTasksWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetRefreshWorker.start(context) }
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        WidgetRefreshWorker.start(context)   // KEEP: restores the periodic sync if it was ever lost
    }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetRefreshWorker.stop(context) }
}
