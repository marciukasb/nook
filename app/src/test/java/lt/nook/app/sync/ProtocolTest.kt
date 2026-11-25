package lt.nook.app.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val full = javaClass.getResource("/sync_full.json")!!.readText()

    @Test fun parsesFullSync() {
        val r = parseSync(full)
        assertEquals("tok-1", r.syncToken)
        assertTrue(r.fullSync)
        assertEquals(4, r.projects.size)
        assertEquals(listOf("Dairy", "Fruit & veg"), r.sections.map { it.name })
        assertEquals(3, r.items.size)
        assertNull(r.items[1].sectionId)
        assertTrue(r.items[2].due!!.isRecurring)
        assertEquals("2026-11-24", r.items[2].due!!.date)
        assertEquals("u2", r.items[2].responsibleUid)
        assertEquals("Alex Example", r.user!!.fullName)
        assertEquals(2, r.collaboratorStates.size)
    }

    private fun json(c: Command) = Json.parseToJsonElement(encodeCommands(listOf(c))).let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }

    @Test fun addItemShape_noNullKeys() {
        val c = addItem("Milk", "p1", "s1", null)
        val o = json(c)
        assertEquals(JsonPrimitive("item_add"), o["type"])
        assertEquals(JsonPrimitive(c.uuid), o["uuid"])
        assertEquals(JsonPrimitive(c.tempId), o["temp_id"])
        assertEquals(JsonObject(mapOf("content" to JsonPrimitive("Milk"), "project_id" to JsonPrimitive("p1"),
            "section_id" to JsonPrimitive("s1"))), o["args"])
    }

    @Test fun moveToProjectRootUsesProjectId() {
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("i1"), "project_id" to JsonPrimitive("p1"))), json(moveItem("i1", null, "p1"))["args"])
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("i1"), "section_id" to JsonPrimitive("s2"))), json(moveItem("i1", "s2", "p1"))["args"])
    }

    @Test fun otherCommandShapes() {
        assertEquals("item_close", json(closeItem("i1"))["type"].toString().trim('"'))
        assertEquals("item_uncomplete", json(uncompleteItem("i1"))["type"].toString().trim('"'))
        val s = json(addSection("DEPO", "p2"))
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("DEPO"), "project_id" to JsonPrimitive("p2"))), s["args"])
        assertTrue(s.containsKey("temp_id"))
        assertNull(json(closeItem("i1"))["temp_id"])
    }

    @Test fun descriptions() {
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("i1"), "description" to JsonPrimitive("x"))), json(updateDescription("i1", "x"))["args"])
        assertEquals(JsonPrimitive("x"), json(addItem("A", "p1", null, null, "x"))["args"]!!.jsonObject["description"])
        assertNull(json(addItem("A", "p1", null, null, ""))["args"]!!.jsonObject["description"])
        assertEquals("", parseSync("""{"sync_token":"t","items":[{"id":"1","project_id":"p","description":null}]}""").items[0].description)
    }

    @Test fun syncStatusResults() {
        val r = parseSync("""{"sync_token":"t","full_sync":false,"sync_status":{"a":"ok","b":{"error_code":22,"error":"Item not found","http_code":404}},"temp_id_mapping":{"x":"9"}}""")
        assertEquals(CommandResult.Ok, r.result("a"))
        assertEquals(CommandResult.Failed(22, "Item not found"), r.result("b"))
        assertNull(r.result("c"))
        assertEquals("9", r.tempIdMapping["x"])
    }

    @Test fun fakeAppliesSameUuidOnce() = runTest {
        val fake = FakeTodoist()
        val c = addItem("Milk", fake.groceries, null, null)
        fake.sync(fake.token, "*", listOf(c))
        fake.sync(fake.token, "*", listOf(c))
        assertEquals(1, fake.open(fake.groceries).count { it.content == "Milk" })
    }
}
