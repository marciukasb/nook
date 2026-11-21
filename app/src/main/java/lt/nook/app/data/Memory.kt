package lt.nook.app.data

import androidx.room.Dao
import androidx.room.Entity

/** Placeholder until Task 6 (category memory). */
@Entity(primaryKeys = ["kind", "key"])
data class KnownE(val kind: String, val key: String, val name: String, val sectionName: String?, val uses: Int, val lastUsed: Long)

@Dao
interface MemoryDao
