package lt.nook.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Release(val versionCode: Int, val name: String, val apkUrl: String)

@Serializable private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String)
@Serializable private data class GhRelease(@SerialName("tag_name") val tag: String, val assets: List<GhAsset> = emptyList())

private val tagRe = Regex("""^v([0-9][0-9A-Za-z.]*)-(\d+)$""")
private val json = Json { ignoreUnknownKeys = true }

/** Parses GitHub's "latest release" JSON. Tags look like `v1.2-12` (versionName-versionCode). */
fun parseLatest(body: String): Release? = runCatching {
    val r = json.decodeFromString(GhRelease.serializer(), body)
    val m = tagRe.matchEntire(r.tag) ?: return null
    val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
    Release(m.groupValues[2].toInt(), m.groupValues[1], apk.url)
}.getOrNull()

/** What "Check for updates" found. */
sealed interface Check {
    data class Available(val release: Release) : Check
    data object UpToDate : Check
    data object Failed : Check
}

/**
 * Checks the public GitHub repo for a newer Nook every few hours; a failed check is retried after minutes. What it found
 * is remembered, so the banner stays until it's installed (or put off for a day with "Later"). Never throws. Android
 * itself refuses an update that isn't signed with the same key, so a release can only come from the owner's signing key.
 */
class Updater(
    private val fetch: suspend (String) -> String?,
    private val prefs: SharedPreferences,
    private val clock: () -> Long,
    private val current: Int,
    private val repo: String,
) {
    /** The newer version found earlier, unless it was put off less than a day ago. No network. */
    fun pending(): Release? = found()?.takeUnless { it.versionCode == prefs.getInt("later_code", 0) && within("later_at", DAY) }

    /** Asks GitHub if it's time to, then says what to offer. */
    suspend fun check(): Release? {
        if (!within("checked_at", EVERY) && !within("tried_at", RETRY)) ask()
        return pending()
    }

    /** The person asked: always goes to GitHub, and an update that was put off is offered again. */
    suspend fun checkNow(): Check {
        if (!ask()) return Check.Failed
        prefs.edit().remove("later_code").apply()
        return found()?.let { Check.Available(it) } ?: Check.UpToDate
    }

    /** "Later": not offered again for a day (a newer one is). */
    fun later(r: Release) = prefs.edit().putInt("later_code", r.versionCode).putLong("later_at", clock()).apply()

    private suspend fun ask(): Boolean {
        prefs.edit().putLong("tried_at", clock()).apply()
        val body = runCatching { fetch("https://api.github.com/repos/$repo/releases/latest") }.getOrNull() ?: return false
        val r = parseLatest(body)?.takeIf { ours(it.apkUrl) } ?: return false
        prefs.edit().putLong("checked_at", clock())
            .putInt("found_code", r.versionCode).putString("found_name", r.name).putString("found_url", r.apkUrl).apply()
        return true
    }

    private fun found(): Release? {
        val code = prefs.getInt("found_code", 0)
        val url = prefs.getString("found_url", null) ?: return null
        return if (code > current && ours(url)) Release(code, prefs.getString("found_name", null).orEmpty(), url) else null
    }

    private fun ours(url: String) = url.startsWith("https://github.com/$repo/releases/download/")

    /** Whether [key] was less than [ms] ago (false if the clock was set back since). */
    private fun within(key: String, ms: Long): Boolean {
        val at = prefs.getLong(key, Long.MIN_VALUE)
        return at != Long.MIN_VALUE && clock() - at in 0 until ms
    }

    companion object {
        const val DAY = 24L * 3600_000
        const val EVERY = 4L * 3600_000
        const val RETRY = 5L * 60_000

        val http by lazy { OkHttpClient() }

        suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute()
                    .use { if (it.isSuccessful) it.body.string() else null }
            }.getOrNull()
        }

        /** Downloads the APK and hands it to Android's installer (which asks the person to confirm). */
        suspend fun downloadAndInstall(ctx: Context, r: Release): Boolean {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return false
            }
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                    val f = File(dir, "nook.apk")
                    http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        f.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                    }
                    f
                }.getOrNull()
            } ?: return false
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            return try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) { false }
        }
    }
}
