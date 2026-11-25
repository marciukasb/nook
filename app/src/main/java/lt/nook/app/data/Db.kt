package lt.nook.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class Kind { GROCERIES, SHOPPING, TASKS, ACHIEVEMENTS }

@Entity
data class ItemE(
    @PrimaryKey val id: String,
    val projectId: String,
    val sectionId: String?,
    val parentId: String?,
    val content: String,
    val order: Int,
    val responsibleUid: String?,
    val dueDate: String?,
    val dueString: String?,
    val recurring: Boolean,
    val hidden: Boolean = false,
    val description: String = "",
)

@Entity
data class SectionE(@PrimaryKey val id: String, val projectId: String, val name: String, val order: Int)

@Entity
data class ProjectE(@PrimaryKey val id: String, val name: String)

@Entity
data class PersonE(@PrimaryKey val id: String, val name: String, val inTasks: Boolean, val inAchievements: Boolean = false)

/** A sync command waiting to be acknowledged by Todoist, sent in [seq] order. */
@Entity(indices = [Index("uuid", unique = true)])
data class PendingE(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val uuid: String,
    val json: String,
    /** Item (or section) id the command touches — protects it from being overwritten by server data. */
    val targetId: String?,
    /** Human name for messages ("Milk was already removed"). */
    val label: String?,
)

/** Temp id → real id, as given by Todoist's temp_id_mapping. */
@Entity
data class IdMapE(@PrimaryKey val temp: String, val real: String)

@Entity
data class MetaE(@PrimaryKey val key: String, val value: String)

@Dao
interface NookDao {
    @Query("SELECT * FROM ItemE WHERE hidden = 0 AND projectId IN (SELECT id FROM ProjectE WHERE name = :project)")
    fun observeItems(project: String): Flow<List<ItemE>>

    @Query("SELECT * FROM SectionE WHERE projectId IN (SELECT id FROM ProjectE WHERE name = :project) ORDER BY `order`")
    fun observeSections(project: String): Flow<List<SectionE>>

    @Query("SELECT * FROM PersonE ORDER BY name")
    fun observePeople(): Flow<List<PersonE>>

    @Query("SELECT * FROM ItemE WHERE id = :id") suspend fun item(id: String): ItemE?
    @Query("SELECT * FROM ItemE WHERE projectId = :project") suspend fun itemsOf(project: String): List<ItemE>
    @Query("SELECT * FROM ItemE") suspend fun allItems(): List<ItemE>
    @Query("SELECT * FROM SectionE WHERE projectId = :project ORDER BY `order`") suspend fun sectionsOf(project: String): List<SectionE>
    @Query("SELECT * FROM SectionE WHERE id = :id") suspend fun section(id: String): SectionE?
    @Upsert suspend fun upsertItems(items: List<ItemE>)
    @Upsert suspend fun upsertSections(s: List<SectionE>)
    @Query("DELETE FROM ItemE WHERE id IN (:ids)") suspend fun deleteItems(ids: List<String>)
    @Query("DELETE FROM SectionE WHERE id IN (:ids)") suspend fun deleteSections(ids: List<String>)
    @Query("UPDATE ItemE SET hidden = :hidden WHERE id IN (:ids)") suspend fun setHidden(ids: List<String>, hidden: Boolean)

    @Query("SELECT * FROM ProjectE") suspend fun projects(): List<ProjectE>
    @Upsert suspend fun upsertProjects(p: List<ProjectE>)
    @Query("DELETE FROM ProjectE WHERE id IN (:ids)") suspend fun deleteProjects(ids: List<String>)

    @Query("SELECT * FROM PersonE") suspend fun people(): List<PersonE>
    @Upsert suspend fun upsertPeople(p: List<PersonE>)

    @Query("SELECT * FROM PendingE ORDER BY seq LIMIT :limit") suspend fun pending(limit: Int): List<PendingE>
    @Query("SELECT * FROM PendingE ORDER BY seq") suspend fun allPending(): List<PendingE>
    @Query("SELECT COUNT(*) FROM PendingE") suspend fun pendingCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPending(p: PendingE)
    @Upsert suspend fun upsertPending(p: List<PendingE>)
    @Query("DELETE FROM PendingE WHERE uuid IN (:uuids)") suspend fun deletePending(uuids: List<String>)

    @Query("SELECT real FROM IdMapE WHERE temp = :temp") suspend fun realId(temp: String): String?
    @Upsert suspend fun saveIds(m: List<IdMapE>)

    @Query("SELECT value FROM MetaE WHERE `key` = :key") suspend fun meta(key: String): String?
    @Query("SELECT value FROM MetaE WHERE `key` = :key") fun observeMeta(key: String): Flow<String?>
    @Upsert suspend fun setMeta(m: MetaE)
}

@Database(
    entities = [ItemE::class, SectionE::class, ProjectE::class, PersonE::class, PendingE::class, MetaE::class, KnownE::class, IdMapE::class],
    version = 2, exportSchema = false,
)
abstract class NookDb : RoomDatabase() {
    abstract fun dao(): NookDao
    abstract fun memory(): MemoryDao
}

/** 1.4: item descriptions and the Achievements project's members. Unsent changes and the category memory stay; the next
 *  sync is a full one, so descriptions of items already here arrive too. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ItemE` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `PersonE` ADD COLUMN `inAchievements` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `MetaE` SET `value` = '*' WHERE `key` = 'sync_token'")
    }
}
