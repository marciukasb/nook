package lt.nook.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlushWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private lateinit var engine: SyncEngine
    private var scheduled = 0

    @Before fun up() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, NookDb::class.java).allowMainThreadQueries().build()
        engine = SyncEngine(db, fake, { "good" },
            { mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks") }, { 0L })
        engine.onStillPending = { scheduled++ }
        FlushWorker.engineFor = { engine }
    }
    @After fun down() = db.close()

    @Test fun pendingAfterOffline_asksForBackgroundFlush() = runTest {
        engine.flush()
        fake.failNext(SyncError.Offline())
        engine.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(1, scheduled)
    }

    @Test fun schedule_isUniqueAndNeedsNetwork() {
        FlushWorker.schedule(ctx); FlushWorker.schedule(ctx)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(FlushWorker.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    @Test fun worker_flushesQueue() = runTest {
        engine.flush()
        fake.failNext(SyncError.Offline())
        engine.add(Kind.GROCERIES, "Milk", null, null)
        val r = TestListenableWorkerBuilder<FlushWorker>(ctx).build().doWork()
        assertEquals(ListenableWorker.Result.success(), r)
        assertEquals(listOf("Milk"), fake.open(fake.groceries).map { it.content })
    }

    @Test fun worker_offline_retries() = runTest {
        engine.flush()
        fake.failNext(SyncError.Offline())
        engine.add(Kind.GROCERIES, "Milk", null, null)
        fake.failNext(SyncError.Offline())
        assertTrue(TestListenableWorkerBuilder<FlushWorker>(ctx).build().doWork() is ListenableWorker.Result.Retry)
    }

    @Test fun worker_authError_doesNotRetry() = runTest {
        engine.flush()
        fake.revokeToken()
        engine.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<FlushWorker>(ctx).build().doWork())
    }
}
