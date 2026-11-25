package lt.nook.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.addItem
import lt.nook.app.sync.encodeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phones' database as Nook 1.3 left it must open in 1.4 with nothing lost. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun version1(name: String, fill: SQLiteDatabase.() -> Unit) {
        val f = ctx.getDatabasePath(name)
        f.parentFile!!.mkdirs(); f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            V1.forEach(db::execSQL)
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.version = 1
            db.fill()
        }
    }

    private fun open(name: String) = Room.databaseBuilder(ctx, NookDb::class.java, name).addMigrations(MIGRATION_1_2).build()

    @Test fun migration_keepsQueueAndMemory() = runBlocking {
        val fake = FakeTodoist()
        val c = addItem("Eggs", fake.groceries, null, null)
        version1("m1.db") {
            execSQL("INSERT INTO ItemE VALUES('${c.tempId}','${fake.groceries}',NULL,NULL,'Eggs',1,NULL,NULL,NULL,0,0)")
            execSQL("INSERT INTO PendingE(uuid,json,targetId,label) VALUES(?,?,?,?)", arrayOf<Any?>(c.uuid, encodeCommand(c), c.tempId, "Eggs"))
            execSQL("INSERT INTO KnownE VALUES('GROCERIES','milk','Milk','Dairy',4,99)")
            execSQL("INSERT INTO PersonE VALUES('u1','Alex',1)")
            execSQL("INSERT INTO ProjectE VALUES('${fake.groceries}','Groceries')")
            execSQL("INSERT INTO MetaE VALUES('sync_token','v7')")
            execSQL("INSERT INTO MetaE VALUES('user_id','u1')")
        }
        val db = open("m1.db")
        assertEquals("", db.dao().item(c.tempId!!)!!.description)
        assertEquals(1, db.dao().pendingCount())
        assertEquals("Dairy", db.memory().get("GROCERIES", "milk")!!.sectionName)
        assertEquals("*", db.dao().meta(SyncEngine.SYNC_TOKEN))          // descriptions arrive with a full sync
        assertEquals("u1", db.dao().meta(SyncEngine.USER_ID))
        assertEquals(false, db.dao().people().single().inAchievements)
        val e = SyncEngine(db, fake, { "good" }, { Settings.DEFAULTS }, { 0L })
        e.flush(); e.flush(force = true)
        assertEquals(listOf("Eggs"), fake.open(fake.groceries).map { it.content })
        assertEquals(1, fake.received.flatten().count { it.type == "item_add" })
        db.close()
    }

    @Test fun anEmptyOldDatabaseOpens() = runBlocking {
        version1("m2.db") {}
        val db = open("m2.db")
        assertNotNull(db.dao().projects())
        db.close()
    }

    private companion object {
        /** Exactly what Nook 1.0-1.3 created. */
        val V1 = listOf(
            "CREATE TABLE `ItemE` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `sectionId` TEXT, `parentId` TEXT, `content` TEXT NOT NULL, `order` INTEGER NOT NULL, `responsibleUid` TEXT, `dueDate` TEXT, `dueString` TEXT, `recurring` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `SectionE` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `name` TEXT NOT NULL, `order` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `ProjectE` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `PersonE` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `inTasks` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `PendingE` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `json` TEXT NOT NULL, `targetId` TEXT, `label` TEXT)",
            "CREATE UNIQUE INDEX `index_PendingE_uuid` ON `PendingE` (`uuid`)",
            "CREATE TABLE `MetaE` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE `KnownE` (`kind` TEXT NOT NULL, `key` TEXT NOT NULL, `name` TEXT NOT NULL, `sectionName` TEXT, `uses` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, PRIMARY KEY(`kind`, `key`))",
            "CREATE TABLE `IdMapE` (`temp` TEXT NOT NULL, `real` TEXT NOT NULL, PRIMARY KEY(`temp`))",
        )
    }
}
