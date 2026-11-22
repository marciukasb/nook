package lt.nook.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import lt.nook.app.auth.testConnection
import lt.nook.app.data.Kind
import lt.nook.app.widget.refreshWidgets
import lt.nook.app.ui.NookApp
import lt.nook.app.ui.SignInScreen
import lt.nook.app.ui.UpdateBanner
import lt.nook.app.update.Release
import lt.nook.app.update.Updater

/** Opened from the widget: show a tab, maybe with the add sheet open. */
data class OpenRequest(val tab: Kind, val add: Boolean)

class MainActivity : ComponentActivity() {
    private var request by mutableStateOf<OpenRequest?>(null)

    private fun readRequest(intent: Intent?) {
        val tab = intent?.getStringExtra(EXTRA_TAB)?.let { t -> Kind.entries.firstOrNull { it.name == t } } ?: return
        request = OpenRequest(tab, intent.getBooleanExtra(EXTRA_ADD, false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readRequest(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readRequest(intent)
        val app = application as NookApplication
        val updater = Updater(Updater::httpGet, getSharedPreferences("update", MODE_PRIVATE), System::currentTimeMillis,
            BuildConfig.VERSION_CODE, BuildConfig.UPDATE_REPO)
        setContent {
            NookTheme {
                var signedIn by remember { mutableStateOf(app.tokens.get() != null) }
                var settingsOpen by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                if (!signedIn || settingsOpen) {
                    if (signedIn) BackHandler { settingsOpen = false }
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        SignInScreen(app.settings.projectNames(),
                            onTest = { t, n -> testConnection(app.transport, t, n) },
                            onSave = { t, n ->
                                app.session.signIn(t, n)
                                signedIn = true; settingsOpen = false
                                scope.launch { app.engine.resetSync(); app.engine.flush(force = true); refreshWidgets(app) }
                            })
                    }
                } else {
                    var update by remember { mutableStateOf<Release?>(null) }
                    LaunchedEffect(Unit) { update = updater.check() }
                    NookApp(app.engine, app.memory,
                        onSettings = { settingsOpen = true },
                        onSignOut = { scope.launch { app.session.signOut(); signedIn = false; refreshWidgets(app) } },
                        openRequest = request, onRequestHandled = { request = null },
                        updateBanner = {
                            update?.let { r ->
                                UpdateBanner(r,
                                    onInstall = { scope.launch { Updater.downloadAndInstall(this@MainActivity, r) } },
                                    onLater = { updater.dismiss(r); update = null })
                            }
                        })
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "lt.nook.app.TAB"
        const val EXTRA_ADD = "lt.nook.app.ADD"
    }
}

@Composable
fun NookTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
