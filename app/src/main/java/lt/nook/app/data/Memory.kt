package lt.nook.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import lt.nook.app.core.Known
import lt.nook.app.core.SectionV
import lt.nook.app.core.nameKey
import lt.nook.app.core.suggest
import lt.nook.app.sync.Learner
import lt.nook.app.sync.Transport
import java.time.Instant

/** One remembered item name per list, with the section (category/shop) it last lived in. */
@Entity(primaryKeys = ["kind", "key"])
data class KnownE(val kind: String, val key: String, val name: String, val sectionName: String?, val uses: Int, val lastUsed: Long)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM KnownE WHERE kind = :kind AND `key` = :key") suspend fun get(kind: String, key: String): KnownE?
    @Query("SELECT * FROM KnownE WHERE kind = :kind") suspend fun all(kind: String): List<KnownE>
    @Upsert suspend fun upsert(k: KnownE)
}

/**
 * Remembers where items go ("milk" → Dairy) from everything Nook sees: every sync, every add/move, and the
 * completed history in Todoist. Keyed by section *name* so it survives a section being recreated.
 */
class CategoryMemory(db: NookDb, private val clock: () -> Long) : Learner {
    private val dao = db.memory()
    private val meta = db.dao()

    /** Seen on the list during a sync: the current truth for its section, not a "use". */
    override suspend fun observed(kind: Kind, name: String, sectionName: String?) {
        val key = nameKey(name).ifEmpty { return }
        val old = dao.get(kind.name, key)
        if (old != null && old.name == name && old.sectionName == sectionName) return
        dao.upsert(KnownE(kind.name, key, name, sectionName, old?.uses ?: 0, old?.lastUsed ?: 0))
    }

    /** Added or moved in Nook: section + one more use. */
    override suspend fun used(kind: Kind, name: String, sectionName: String?) {
        val key = nameKey(name).ifEmpty { return }
        val old = dao.get(kind.name, key)
        dao.upsert(KnownE(kind.name, key, name, sectionName, (old?.uses ?: 0) + 1, clock()))
    }

    suspend fun suggestions(kind: Kind, query: String, limit: Int = 6): List<Known> =
        suggest(query, dao.all(kind.name).map { Known(it.name, it.sectionName, it.uses, it.lastUsed) }, limit)

    /** The remembered section if it still exists in [sections]; null means "ask the user". */
    suspend fun sectionFor(kind: Kind, name: String, sections: List<SectionV>): SectionV? {
        val remembered = dao.get(kind.name, nameKey(name))?.sectionName ?: return null
        return sections.firstOrNull { it.name == remembered }
    }

    suspend fun known(kind: Kind, name: String): KnownE? = dao.get(kind.name, nameKey(name))

    /**
     * Learns from ticked-off items in Todoist, newest first, in 90-day windows until a window is empty (max ~2
     * years). Only fills names Nook doesn't know yet. Runs at most once a week per list; errors are ignored.
     */
    suspend fun seedIfDue(kind: Kind, transport: Transport, token: String, projectId: String) {
        val key = "seeded_${kind.name}"
        val last = meta.meta(key)?.toLongOrNull()
        if (last != null && clock() - last < WEEK) return
        val sectionNames = meta.sectionsOf(projectId).associate { it.id to it.name }
        try {
            var until = Instant.ofEpochMilli(clock())
            for (window in 0 until 8) {
                val since = until.minusSeconds(90L * 86400)
                var cursor: String? = null
                var count = 0
                do {
                    val page = transport.completed(token, projectId, since, until, cursor)
                    page.items.forEach { i ->
                        count++
                        val k = nameKey(i.content)
                        if (k.isNotEmpty() && dao.get(kind.name, k) == null)
                            dao.upsert(KnownE(kind.name, k, i.content, i.sectionId?.let { sectionNames[it] }, 0, 0))
                    }
                    cursor = page.nextCursor
                } while (cursor != null)
                if (count == 0) break
                until = since
            }
            meta.setMeta(MetaE(key, clock().toString()))
        } catch (e: Exception) {
            // Seeding is a bonus: offline or limited history just means Nook learns as it goes.
        }
    }

    private companion object { const val WEEK = 7L * 86400_000 }
}
