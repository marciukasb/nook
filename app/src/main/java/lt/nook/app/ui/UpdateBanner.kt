package lt.nook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import lt.nook.app.update.Check
import lt.nook.app.update.Release
import lt.nook.app.update.Updater

/** The update to offer, if any: what is known already, checked again every time someone comes back to Nook. */
@Composable
fun rememberUpdate(updater: Updater): MutableState<Release?> {
    val update = remember(updater) { mutableStateOf(updater.pending()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(updater, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { update.value = updater.check() }
    }
    return update
}

@Composable
fun UpdateBanner(release: Release, onInstall: () -> Unit, onLater: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Update available (${release.name})", Modifier.weight(1f))
            onLater?.let { TextButton(it) { Text("Later") } }
            TextButton(onInstall) { Text("Install") }
        }
    }
}

/** In Settings: which version this is, and a button to look for a newer one. */
@Composable
fun UpdateSection(version: String, onCheck: suspend () -> Check, onInstall: (Release) -> Unit) {
    var result by remember { mutableStateOf<Check?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Nook $version")
            OutlinedButton({ busy = true; scope.launch { result = onCheck(); busy = false } }, enabled = !busy) { Text("Check for updates") }
        }
        when (val r = result) {
            is Check.Available -> UpdateBanner(r.release, onInstall = { onInstall(r.release) }, onLater = null)
            Check.UpToDate -> Text("You have the newest version")
            Check.Failed -> Text("Couldn't check — try again", color = MaterialTheme.colorScheme.error)
            null -> {}
        }
    }
}
