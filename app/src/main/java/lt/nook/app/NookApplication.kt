package lt.nook.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.sync.Mutex
import lt.nook.app.auth.KeystoreTokenStore
import lt.nook.app.auth.Session
import lt.nook.app.auth.TokenStore
import lt.nook.app.data.CategoryMemory
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import lt.nook.app.sync.HttpTransport
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.Transport

/** Wires the app together once (no DI framework needed for this size). */
class NookApplication : Application() {
    lateinit var db: NookDb
    lateinit var tokens: TokenStore
    lateinit var settings: Settings
    lateinit var transport: Transport
    lateinit var memory: CategoryMemory
    lateinit var engine: SyncEngine
    lateinit var session: Session
    private val seeding = Mutex()

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, NookDb::class.java, "nook.db").build()
        tokens = KeystoreTokenStore(getSharedPreferences("auth", Context.MODE_PRIVATE))
        settings = Settings(getSharedPreferences("settings", Context.MODE_PRIVATE))
        transport = HttpTransport()
        memory = CategoryMemory(db, System::currentTimeMillis)
        engine = SyncEngine(db, transport, tokens::get, settings::projectNames, System::currentTimeMillis, memory)
        session = Session(tokens, settings, db)
        engine.onSynced = {
            if (seeding.tryLock()) try {
                val token = tokens.get()
                if (token != null) for (k in Kind.entries) engine.projectId(k)?.let { memory.seedIfDue(k, transport, token, it) }
            } finally { seeding.unlock() }
        }
    }
}
