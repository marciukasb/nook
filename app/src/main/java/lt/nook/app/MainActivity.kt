package lt.nook.app

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
import lt.nook.app.ui.NookApp
import lt.nook.app.ui.SignInScreen
import lt.nook.app.ui.UpdateBanner
import lt.nook.app.update.Release
import lt.nook.app.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                                scope.launch { app.engine.resetSync(); app.engine.flush(force = true) }
                            })
                    }
                } else {
                    var update by remember { mutableStateOf<Release?>(null) }
                    LaunchedEffect(Unit) { update = updater.check() }
                    NookApp(app.engine, app.memory,
                        onSettings = { settingsOpen = true },
                        onSignOut = { scope.launch { app.session.signOut(); signedIn = false } },
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
