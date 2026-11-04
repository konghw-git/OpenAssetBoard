package com.assetboard.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two devices editing the same account offline, then exchanging snapshots twice. The merge must
 * converge to the same bytes on both sides and stay idempotent when applied again.
 */
class MergeScenarioTest {

    @Test
    fun twoClientsConvergeAcrossTwoOfflineRounds() {
        val base = doc()
        base.put(
            "groups",
            listOf(row("id" to "g", "name" to "现金", "isLiability" to false, "colorIndex" to 0, "sortOrder" to 0, "changedAt" to 10, "changeId" to "base")),
        )
        base.put(
            "accounts",
            listOf(
                row(
                    "id" to "acct", "groupId" to "g", "name" to "银行", "kind" to "NORMAL", "baselineAt" to 0,
                    "createdAt" to 1, "includeInTotal" to true, "isDisabled" to false, "baselineCost" to null,
                    "changedAt" to 10, "changeId" to "base",
                ),
            ),
        )
        base.put(
            "todoLists",
            listOf(row("id" to "l", "name" to "清单", "notes" to "", "sortOrder" to 0, "createdAt" to 1, "deletedAt" to null, "deleteBatch" to null, "changedAt" to 10, "changeId" to "base")),
        )
        base.put(
            "todos",
            listOf(
                row(
                    "id" to "t", "kind" to "ORDINARY", "title" to "买菜", "listId" to "l", "sectionId" to null,
                    "createdAt" to 1, "deletedAt" to null, "deleteBatch" to null, "revision" to 0,
                    "stateSeq" to 0, "occurrenceCount" to 0, "changedAt" to 10, "changeId" to "base",
                ),
            ),
        )
        base.put("notes", listOf(note("n", "原文", 10, "base", 10, "base")))
        base.put(
            "countdowns",
            listOf(
                row(
                    "id" to "c", "title" to "生日", "date" to "2026-01-01", "yearly" to false, "pinned" to false,
                    "createdAt" to 1, "updatedAt" to 1, "changedAt" to 10, "changeId" to "base",
                    "stateAt" to 10, "stateId" to "base",
                ),
            ),
        )

        // Round 1: A writes the note body and a flow; B pins the note, purges the list and adds a countdown.
        val phoneEdits = base.deepCopy()
        phoneEdits.put("notes", listOf(note("n", "手机改", 100, "A1", 10, "base")))
        phoneEdits.put("todos", listOf(todo("t", "手机改", listId = "l", at = 110, changeId = "A1")))
        phoneEdits.put(
            "flows",
            listOf(
                row(
                    "id" to "f", "kind" to "EXPENSE", "fromAccountId" to "acct", "toAccountId" to null,
                    "amount" to 1, "fee" to 0, "feeOn" to "FROM", "at" to 50, "createdAt" to 1,
                    "changedAt" to 120, "changeId" to "A1",
                ),
            ),
        )
        val webEdits = base.deepCopy()
        webEdits.put("notes", listOf(note("n", "网页改", 90, "B1", 200, "B1", pinned = true)))
        webEdits.put(
            "tombstones",
            listOf(row("table" to "todoLists", "rowKey" to "todoLists#l", "deletedAt" to 130, "changeId" to "B1")),
        )
        webEdits.put(
            "countdowns",
            listOf(
                row(
                    "id" to "c2", "title" to "纪念日", "date" to "2026-02-02", "yearly" to false, "pinned" to false,
                    "createdAt" to 1, "updatedAt" to 1, "changedAt" to 140, "changeId" to "B1",
                    "stateAt" to 140, "stateId" to "B1",
                ),
            ),
        )

        val firstPhone = Merge.merge(phoneEdits.json(), webEdits.json())
        val firstWeb = Merge.merge(webEdits.json(), phoneEdits.json())
        assertEquals(CanonicalJson.canonicalJson(firstPhone.data), CanonicalJson.canonicalJson(firstWeb.data))
        val merged = firstPhone.data
        assertEquals("A's body wins the content stamp", "手机改", merged.objectRow("notes", "n")["body"]!!.text())
        assertEquals("B's pin wins the state stamp", "true", merged.objectRow("notes", "n")["pinned"]!!.text())
        assertEquals("the purged list clears the todo's reference", JsonNull, merged.objectRow("todos", "t")["listId"])
        assertTrue("B's new countdown arrives", merged.rows("countdowns").any { it.string("id") == "c2" })

        // Round 2: both start from the merged file and edit again.
        val phone = Doc(LinkedHashMap(merged))
        phone.put("todos", listOf(todo("t", "手机再改", listId = null, at = 300, changeId = "A2")))
        val web = Doc(LinkedHashMap(merged))
        web.put("notes", listOf(note("n", "网页再改", 310, "B2", 310, "B2")))
        val secondPhone = Merge.merge(phone.json(), web.json())
        val secondWeb = Merge.merge(web.json(), phone.json())
        assertEquals(CanonicalJson.canonicalJson(secondPhone.data), CanonicalJson.canonicalJson(secondWeb.data))
        assertEquals("手机再改", secondPhone.data.objectRow("todos", "t")["title"]!!.text())
        assertEquals("网页再改", secondPhone.data.objectRow("notes", "n")["body"]!!.text())

        // Applying the same pair again, and the result to itself, changes nothing.
        assertEquals(
            CanonicalJson.canonicalJson(secondPhone.data),
            CanonicalJson.canonicalJson(Merge.merge(phone.json(), web.json()).data),
        )
        assertEquals(
            CanonicalJson.canonicalJson(secondPhone.data),
            CanonicalJson.canonicalJson(Merge.merge(secondPhone.data, secondPhone.data).data),
        )
    }

    @Test
    fun mergingThousandsOfRowsStaysUnderTheBudget() {
        val groups = (0 until 40).map { i ->
            row("id" to "g$i", "name" to "组$i", "isLiability" to false, "colorIndex" to 0, "sortOrder" to i, "changedAt" to 10, "changeId" to "a")
        }
        val accounts = (0 until 1000).map { i ->
            row(
                "id" to "acct$i", "groupId" to "g${i % 40}", "name" to "账户$i", "kind" to "NORMAL",
                "baselineAt" to 0, "createdAt" to 1, "includeInTotal" to true, "isDisabled" to false,
                "baselineCost" to null, "changedAt" to 10, "changeId" to "a",
            )
        }
        val valuations = (0 until 1500).map { i ->
            row(
                "id" to "v$i", "accountId" to "acct${i % 1000}", "at" to (i.toLong() * 1000),
                "value" to (i % 100), "snapshotId" to null, "createdAt" to 1, "changedAt" to 10, "changeId" to "a",
            )
        }
        val notes = (0 until 2000).map { i -> note("n$i", "正文 $i", 10, "a", 10, "a") }
        val todos = (0 until 1500).map { i ->
            row(
                "id" to "t$i", "kind" to "ORDINARY", "title" to "待办 $i", "listId" to null,
                "sectionId" to null, "createdAt" to 1, "deletedAt" to null, "deleteBatch" to null,
                "revision" to 0, "stateSeq" to 0, "occurrenceCount" to 0, "changedAt" to 10, "changeId" to "a",
            )
        }
        val a = doc()
        a.put("groups", groups)
        a.put("accounts", accounts)
        a.put("valuations", valuations)
        a.put("notes", notes)
        a.put("todos", todos)

        val b = a.deepCopy()
        // Half of the rows move on the other device, so the merge has real work to do.
        b.put("notes", notes.mapIndexed { i, n ->
            if (i % 2 == 0) restamp(n, 20, "b") else n
        })
        b.put("todos", todos.mapIndexed { i, t ->
            if (i % 3 == 0) restamp(t, 30, "b", mapOf("title" to JsonPrimitive("改 $i"))) else t
        })

        val started = System.nanoTime()
        val result = Merge.merge(a.json(), b.json())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(2000, result.data.rows("notes").size)
        assertEquals(1500, result.data.rows("todos").size)
        assertEquals(1500, result.data.rows("valuations").size)
        assertTrue("merging ~6500 rows took ${elapsedMillis}ms", elapsedMillis < 15_000)
    }

    // ---------------------------------------------------------------- builders

    /** A BackupFile-shaped mutable document the test can edit like a client would. */
    private class Doc(val data: MutableMap<String, JsonElement>) {
        constructor() : this(LinkedHashMap())

        fun put(table: String, rows: List<JsonObject>) {
            data[table] = JsonArray(rows)
        }

        fun json(): JsonObject = JsonObject(LinkedHashMap(data))

        fun deepCopy(): Doc = Doc(LinkedHashMap(Json.parseToJsonElement(json().toString()) as JsonObject))
    }

    private fun doc(): Doc {
        val data = LinkedHashMap<String, JsonElement>()
        data["formatVersion"] = JsonPrimitive(16)
        data["schemaVersion"] = JsonPrimitive(15)
        data["exportedAt"] = JsonPrimitive(0)
        data["healthProfile"] = JsonNull
        data["netWorthGoal"] = JsonNull
        data["netWorthGoalAt"] = JsonPrimitive(0)
        data["netWorthGoalChangeId"] = JsonPrimitive("")
        data["tombstones"] = JsonArray(emptyList())
        for (table in CanonicalJson.rowKeyFields.keys) data[table] = JsonArray(emptyList())
        return Doc(data)
    }

    private fun doc(element: JsonObject): Doc = Doc(LinkedHashMap(element))

    private fun row(vararg fields: Pair<String, Any?>): JsonObject =
        JsonObject(LinkedHashMap(fields.associate { (key, value) -> key to json(value) }))

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        else -> error("unsupported field value ${value::class}")
    }

    private fun restamp(source: JsonObject, at: Long, changeId: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        val fields = LinkedHashMap(source)
        fields["changedAt"] = JsonPrimitive(at)
        fields["changeId"] = JsonPrimitive(changeId)
        fields.putAll(extra)
        return JsonObject(fields)
    }

    private fun note(
        id: String,
        body: String,
        at: Long,
        changeId: String,
        stateAt: Long,
        stateId: String,
        pinned: Boolean = false,
    ): JsonObject = row(
        "id" to id, "topicId" to null, "body" to body, "bodyRev" to 0,
        "pinned" to pinned, "position" to 0, "createdAt" to 1,
        "updatedAt" to 1, "reviewedAt" to null, "deletedAt" to null,
        "deleteBatch" to null, "changedAt" to at, "changeId" to changeId,
        "stateAt" to stateAt, "stateId" to stateId,
    )

    private fun todo(id: String, title: String, listId: String?, at: Long, changeId: String): JsonObject = row(
        "id" to id, "kind" to "ORDINARY", "title" to title,
        "listId" to listId, "sectionId" to null,
        "createdAt" to 1, "deletedAt" to null, "deleteBatch" to null,
        "revision" to 0, "stateSeq" to 0, "occurrenceCount" to 0,
        "changedAt" to at, "changeId" to changeId,
        "stateAt" to 0, "stateId" to "",
    )

    private fun JsonElement.text(): String = (this as JsonPrimitive).content

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.rows(table: String): List<JsonObject> =
        (this[table] as JsonArray).map { it as JsonObject }

    private fun JsonObject.objectRow(table: String, id: String): JsonObject =
        rows(table).first { it.string("id") == id }
}
