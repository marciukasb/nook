package lt.nook.app.sync

import java.time.Instant

data class CompletedPage(val items: List<RItem>, val nextCursor: String?)

/** How Nook talks to Todoist. Tests use FakeTodoist. */
interface Transport {
    suspend fun sync(token: String, syncToken: String, commands: List<Command>): SyncResponse
    suspend fun completed(token: String, projectId: String, since: Instant, until: Instant, cursor: String?): CompletedPage
}

sealed class SyncError(msg: String) : Exception(msg) {
    class Auth : SyncError("Token rejected")
    class RateLimited(val retryAfterS: Long?) : SyncError("Rate limited")
    class Server(val code: Int) : SyncError("Server error $code")
    /** [mayHaveArrived]: the connection broke after sending, so Todoist may have done it. */
    class Offline(val mayHaveArrived: Boolean = false) : SyncError("No connection")
    /** Todoist refused the request itself (400), e.g. an invalid sync token or a malformed command. */
    class BadRequest : SyncError("Bad request")
}

const val MAX_COMMANDS = 100
