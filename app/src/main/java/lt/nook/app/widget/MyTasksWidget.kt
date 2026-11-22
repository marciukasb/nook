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
import java.util.concurrent.TimeUnit

/** What the widget shows; built from Nook's own offline copy, so it works without signal. */
data class WidgetState(val signedIn: Boolean, val tasks: List<ItemV>, val canUndo: Boolean)

private val ID = ActionParameters.Key<String>("id")

/** "My tasks": Shared tasks assigned to the signed-in person, tickable from the home screen. */
class MyTasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NookApplication
        val state = WidgetState(app.tokens.get() != null, app.engine.myTasks(), app.widgetActions.canUndo())
        provideContent { GlanceTheme { WidgetContent(state) } }
    }
}

fun openNook(context: Context, add: Boolean) = Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_TAB, "TASKS").putExtra(MainActivity.EXTRA_ADD, add)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

@Composable
fun WidgetContent(s: WidgetState) {
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(c.widgetBackground).cornerRadius(20.dp).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.signedIn) "My tasks · ${s.tasks.size}" else "My tasks",
                GlanceModifier.defaultWeight().clickable(actionRunCallback<OpenAction>()),
                style = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            if (s.canUndo) {
                Text("Undo", GlanceModifier.padding(horizontal = 8.dp).clickable(actionRunCallback<UndoAction>()),
                    style = TextStyle(color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
            if (s.signedIn) {
                Spacer(GlanceModifier.width(4.dp))
                Text("+", GlanceModifier.padding(horizontal = 8.dp).clickable(actionRunCallback<AddAction>()),
                    style = TextStyle(color = c.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }
        when {
            !s.signedIn -> Text("Open Nook to sign in", GlanceModifier.padding(top = 8.dp), style = TextStyle(color = c.onSurfaceVariant))
            s.tasks.isEmpty() -> Text("Nothing assigned to you", GlanceModifier.padding(top = 8.dp), style = TextStyle(color = c.onSurfaceVariant))
            else -> LazyColumn(GlanceModifier.fillMaxSize().padding(top = 4.dp)) {
                items(s.tasks, itemId = { it.id.hashCode().toLong() }) { t ->
                    CheckBox(checked = false, onCheckedChange = actionRunCallback<TickAction>(actionParametersOf(ID to t.id)),
                        text = t.content, modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        style = TextStyle(color = c.onSurface, fontSize = 15.sp), maxLines = 2)
                }
            }
        }
    }
}

/** Redraws every Nook widget (after a sync, a tick in the app, a sign-in or out). */
suspend fun refreshWidgets(context: Context) = runCatching { MyTasksWidget().updateAll(context) }

class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NookApplication
        val id = parameters[ID] ?: return
        app.widgetActions.tick(id)
        refreshWidgets(context)
        app.engine.flush(force = true)      // queued safely if offline; the background flush sends it later
        refreshWidgets(context)
    }
}

class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NookApplication
        app.widgetActions.undo()
        refreshWidgets(context)
        app.engine.flush(force = true)
        refreshWidgets(context)
    }
}

class OpenAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(openNook(context, add = false))
    }
}

class AddAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(openNook(context, add = true))
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
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetRefreshWorker.stop(context) }
}
