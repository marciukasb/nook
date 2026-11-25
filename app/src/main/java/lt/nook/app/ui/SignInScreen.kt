package lt.nook.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lt.nook.app.auth.TestResult
import lt.nook.app.data.Kind

/** Paste the Todoist token, check it, save. Save only works after a successful Test of exactly these values. */
@Composable
fun SignInScreen(
    initialNames: Map<Kind, String>,
    onTest: suspend (String, Map<Kind, String>) -> TestResult,
    onSave: (String, Map<Kind, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var token by remember { mutableStateOf("") }
    var names by remember { mutableStateOf(initialNames) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var testedWith by remember { mutableStateOf<Pair<String, Map<Kind, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nook", style = MaterialTheme.typography.headlineMedium)
        Text("Paste your Todoist API token (Todoist → Settings → Integrations → Developer). It stays on this phone.")
        OutlinedTextField(token, { token = it; result = null }, Modifier.fillMaxWidth().testTag("token"),
            label = { Text("Todoist token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        listOf(Kind.GROCERIES to "Groceries project", Kind.SHOPPING to "Shopping project", Kind.TASKS to "Tasks project",
            Kind.ACHIEVEMENTS to "Achievements project (optional)").forEach { (k, label) ->
            OutlinedTextField(names.getValue(k), { v -> names = names + (k to v); result = null },
                Modifier.fillMaxWidth().testTag("name_${k.name}"), label = { Text(label) }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = token.isNotBlank() && !busy, onClick = {
                busy = true
                val t = token; val n = names
                scope.launch { result = onTest(t, n); testedWith = t to n; busy = false }
            }) { Text("Test") }
            Button(enabled = result is TestResult.Ok && testedWith == (token to names), onClick = { onSave(token.trim(), names) }) { Text("Save") }
        }
        when (val r = result) {
            is TestResult.Ok -> {
                Text("Found ${r.projects.joinToString(", ")}")
                Text("People: ${r.people.joinToString(", ")}")
                r.noAchievements?.let { Text("No \"$it\" project yet. That tab will tell you how to make one.") }
            }
            TestResult.Rejected -> Text("Token rejected — copy it again from Todoist", color = MaterialTheme.colorScheme.error)
            is TestResult.Missing -> r.names.forEach { Text("Project \"$it\" not found", color = MaterialTheme.colorScheme.error) }
            TestResult.NoConnection -> Text("No connection — try again", color = MaterialTheme.colorScheme.error)
            null -> if (busy) Text("Checking…")
        }
    }
}
