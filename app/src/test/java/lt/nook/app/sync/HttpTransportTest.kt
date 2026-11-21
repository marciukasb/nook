package lt.nook.app.sync

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpTransportTest {
    private val server = MockWebServer()
    private lateinit var t: HttpTransport

    @Before fun up() { server.start(); t = HttpTransport(base = server.url("/api/v1").toString().trimEnd('/')) }
    @After fun down() = server.close()

    @Test fun sendsFormWithTokenAndCommands() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"sync_token":"v2","full_sync":false}""").build())
        val r = t.sync("secret", "*", listOf(closeItem("i1")))
        assertEquals("v2", r.syncToken)
        val req = server.takeRequest()
        assertEquals("Bearer secret", req.headers["Authorization"])
        val body = java.net.URLDecoder.decode(req.body!!.utf8(), "UTF-8")
        assertTrue(body, body.contains("sync_token=*") && body.contains("\"item_close\""))
    }

    @Test fun mapsErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "30").build())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(runCatching { t.sync("x", "*", emptyList()) }.exceptionOrNull() is SyncError.Auth)
        assertEquals(30L, (runCatching { t.sync("x", "*", emptyList()) }.exceptionOrNull() as SyncError.RateLimited).retryAfterS)
        assertTrue(runCatching { t.sync("x", "*", emptyList()) }.exceptionOrNull() is SyncError.Server)
        server.close()
        assertTrue(runCatching { t.sync("x", "*", emptyList()) }.exceptionOrNull() is SyncError.Offline)
    }
}
