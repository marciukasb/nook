package lt.nook.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun appLabelIsNook() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Nook", ctx.getString(R.string.app_name))
    }
}
