package lt.nook.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Real Todoist over HTTPS. Never logs the token. */
class HttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val base: String = "https://api.todoist.com/api/v1",
) : Transport {

    override suspend fun sync(token: String, syncToken: String, commands: List<Command>): SyncResponse {
        val form = FormBody.Builder()
            .add("sync_token", syncToken)
            .add("resource_types", """["user","projects","sections","items","collaborators"]""")
        if (commands.isNotEmpty()) form.add("commands", encodeCommands(commands))
        val req = Request.Builder().url("$base/sync").header("Authorization", "Bearer $token").post(form.build()).build()
        return parseSync(call(req))
    }

    @Serializable
    private data class Page(val items: List<RItem> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)

    override suspend fun completed(token: String, projectId: String, since: Instant, until: Instant, cursor: String?): CompletedPage {
        val url = "$base/tasks/completed/by_completion_date".toHttpUrl().newBuilder()
            .addQueryParameter("since", since.truncatedTo(ChronoUnit.SECONDS).toString())
            .addQueryParameter("until", until.truncatedTo(ChronoUnit.SECONDS).toString())
            .addQueryParameter("project_id", projectId)
            .addQueryParameter("limit", "200")
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        val p = TodoistJson.decodeFromString(Page.serializer(), call(req))
        return CompletedPage(p.items, p.nextCursor)
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> throw SyncError.Auth()
                    r.code == 400 -> throw SyncError.BadRequest()
                    r.code == 429 -> throw SyncError.RateLimited(r.header("Retry-After")?.toLongOrNull())
                    !r.isSuccessful -> throw SyncError.Server(r.code)
                    else -> r.body.string()
                }
            }
        } catch (e: IOException) {
            val neverSent = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException
            throw SyncError.Offline(mayHaveArrived = !neverSent)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw SyncError.Server(0)
        }
    }
}
