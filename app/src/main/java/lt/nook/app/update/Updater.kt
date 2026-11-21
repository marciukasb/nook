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

/**
 * Checks the public GitHub repo for a newer Nook at most once a day. Never throws. Android itself refuses an
 * update that isn't signed with the same key, so a release can only come from the owner's signing key.
 */
class Updater(
    private val fetch: suspend (String) -> String?,
    private val prefs: SharedPreferences,
    private val clock: () -> Long,
    private val current: Int,
    private val repo: String,
) {
    suspend fun check(force: Boolean = false): Release? {
        val last = prefs.getLong("last_check", Long.MIN_VALUE)
        if (!force && last != Long.MIN_VALUE && clock() - last < DAY) return null
        prefs.edit().putLong("last_check", clock()).apply()
        val body = runCatching { fetch("https://api.github.com/repos/$repo/releases/latest") }.getOrNull() ?: return null
        val r = parseLatest(body) ?: return null
        if (!r.apkUrl.startsWith("https://github.com/$repo/releases/download/")) return null
        if (r.versionCode <= current || r.versionCode <= prefs.getInt("dismissed", 0)) return null
        return r
    }

    fun dismiss(r: Release) = prefs.edit().putInt("dismissed", r.versionCode).apply()

    companion object {
        const val DAY = 24L * 3600_000

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
