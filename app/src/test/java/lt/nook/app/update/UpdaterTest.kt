package lt.nook.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    private fun release(tag: String) = """
        {"tag_name":"$tag","name":"Nook","assets":[
          {"name":"notes.txt","browser_download_url":"https://github.com/o/nook/releases/download/$tag/notes.txt"},
          {"name":"nook-1.2.apk","browser_download_url":"https://github.com/o/nook/releases/download/$tag/nook-1.2.apk"}]}"""

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("u", Context.MODE_PRIVATE)
    private var now = 0L
    private var calls = 0
    private var body: String? = release("v1.2-12")
    private fun updater(current: Int = 5) = Updater({ calls++; body }, prefs, { now }, current, "o/nook")

    @Test fun parse_picksApkAndVersion() =
        assertEquals(Release(12, "1.2", "https://github.com/o/nook/releases/download/v1.2-12/nook-1.2.apk"), parseLatest(release("v1.2-12")))

    @Test fun badTagOrNoApk_isNull() {
        assertNull(parseLatest(release("1.2")))
        assertNull(parseLatest("""{"tag_name":"v1.2-12","assets":[]}"""))
        assertNull(parseLatest("not json"))
    }

    @Test fun olderOrSame_isIgnored() = runTest {
        assertNull(updater(current = 12).check(force = true))
        assertEquals(12, updater(current = 11).check(force = true)!!.versionCode)
    }

    @Test fun checksAtMostDaily() = runTest {
        val u = updater()
        assertEquals(12, u.check()!!.versionCode)
        now += 3_600_000
        assertNull(u.check())
        assertEquals(1, calls)
        now += 24 * 3_600_000
        u.check()
        assertEquals(2, calls)
    }

    @Test fun offline_returnsNull() = runTest {
        body = null
        assertNull(updater().check(force = true))
    }

    @Test fun dismissed_isHiddenUntilNewer() = runTest {
        val u = updater()
        u.dismiss(u.check(force = true)!!)
        assertNull(u.check(force = true))
        body = release("v1.3-13").replace("nook-1.2.apk", "nook-1.3.apk")
        assertEquals(13, u.check(force = true)!!.versionCode)
    }
}
