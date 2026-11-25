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

    private val hour = 3_600_000L
    private val v13 = release("v1.3-13").replace("nook-1.2.apk", "nook-1.3.apk")

    @Test fun olderOrSame_isIgnored() = runTest {
        assertNull(updater(current = 12).check())
        assertEquals(12, updater(current = 11).also { now += 24 * hour }.check()!!.versionCode)
    }

    @Test fun asksGithubEveryFewHours_butTheBannerStays() = runTest {
        val u = updater()
        assertEquals(12, u.check()!!.versionCode)
        now += hour
        assertEquals(12, u.check()!!.versionCode)
        assertEquals(1, calls)
        now += 6 * hour
        u.check()
        assertEquals(2, calls)
    }

    @Test fun bannerSurvivesARestart_untilInstalled() = runTest {
        updater().check()
        assertEquals(12, updater().pending()!!.versionCode)
        assertNull(updater(current = 12).pending())
        assertEquals(1, calls)
    }

    @Test fun aFailedCheckIsRetriedSoon_andKeepsWhatWasFound() = runTest {
        body = null
        val u = updater()
        assertNull(u.check())
        u.check()
        assertEquals(1, calls)                       // not again at once...
        now += 6 * 60_000
        body = release("v1.2-12")
        assertEquals(12, u.check()!!.versionCode)    // ...but minutes later
        now += 7 * hour
        body = null
        assertEquals(12, u.check()!!.versionCode)
    }

    @Test fun later_hidesItForADay_andANewerOneShowsAtOnce() = runTest {
        val u = updater()
        u.later(u.check()!!)
        assertNull(u.check()); assertNull(u.pending())
        now += 25 * hour
        assertEquals(12, u.check()!!.versionCode)
        u.later(u.check()!!)
        now += 7 * hour
        body = v13
        assertEquals(13, u.check()!!.versionCode)
    }

    @Test fun aLaterTappedInAnOldVersionOfNook_noLongerHides() = runTest {
        prefs.edit().putInt("dismissed", 12).putLong("last_check", 0L).apply()
        assertEquals(12, updater().check()!!.versionCode)
    }

    @Test fun checkNow_saysWhatItFound() = runTest {
        val u = updater()
        u.later(u.check()!!)
        assertEquals(Check.Available(u.let { Release(12, "1.2", "https://github.com/o/nook/releases/download/v1.2-12/nook-1.2.apk") }), u.checkNow())
        assertEquals(12, u.pending()!!.versionCode)                // asked for: shown again
        assertEquals(Check.UpToDate, updater(current = 12).checkNow())
        body = null
        assertEquals(Check.Failed, u.checkNow())
        body = "<html>"
        assertEquals(Check.Failed, u.checkNow())
    }

    @Test fun aWithdrawnRelease_isNoLongerOffered() = runTest {
        val u = updater()
        u.check()
        now += 7 * hour
        body = release("v1.0-5")
        assertNull(u.check())
        assertNull(u.pending())
    }

    @Test fun onlyFromOurOwnRepo() = runTest {
        body = release("v1.2-12").replace("github.com/o/nook", "example.com/o/nook")
        assertNull(updater().check())
        assertEquals(Check.Failed, updater().checkNow())
    }

    @Test fun clockSetBack_stillChecks() = runTest {
        now = 1000 * hour
        val u = updater(); u.check()
        now = 10 * hour
        u.check()
        assertEquals(2, calls)
    }
}
