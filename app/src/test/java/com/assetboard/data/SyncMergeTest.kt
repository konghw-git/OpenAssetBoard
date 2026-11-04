package com.assetboard.data

import android.app.Application
import com.assetboard.data.backup.BackupFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M3's write-back path: the merge is applied through the gate, a restore is a replace that leaves
 * tombstones, and the local data is copied before either touches the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncMergeTest {
    private val f = Fixture()
    private val codec = Json { encodeDefaults = true }

    @Test
    fun aMergeWritesTheUnionAndKeepsALocalCopy() = f.run {
        val local = f.ledger.createGroup("本地", isLiability = false, colorIndex = 0)
        val snapshot = f.backup.exportForSync()
        val root = Json.parseToJsonElement(snapshot.json) as JsonObject

        // The other device renamed the group later and added one of its own.
        val groups = (root["groups"] as JsonArray).map { row ->
            val fields = LinkedHashMap(row.jsonObject)
            if (fields["id"]?.let { (it as JsonPrimitive).content } == local) {
                fields["name"] = JsonPrimitive("远端改")
                fields["changedAt"] = JsonPrimitive(f.now + 10_000)
                fields["changeId"] = JsonPrimitive("remote-edit")
            }
            JsonObject(fields)
        }.toMutableList()
        val extra = LinkedHashMap(groups.first())
        extra["id"] = JsonPrimitive("remote-group")
        extra["name"] = JsonPrimitive("远端新增")
        extra["changedAt"] = JsonPrimitive(f.now + 10_000)
        extra["changeId"] = JsonPrimitive("remote-add")
        groups += JsonObject(extra)
        val remote = JsonObject(root + mapOf("groups" to JsonArray(groups)))

        val result = f.backup.mergeFromJson(remote.toString())

        val names = f.db.dao().groups().map { it.name }.toSet()
        assertEquals(setOf("远端改", "远端新增"), names)
        assertEquals("what was written is what will be uploaded", f.backup.exportForSync().contentHash, result.snapshot.contentHash)
        val copy = File(f.context.filesDir, "presync/asset-board-presync.json")
        assertTrue("the pre-merge copy exists", copy.isFile)
        assertTrue(copy.readText().contains("本地"))
    }

    @Test
    fun aResetLeavesTombstonesSoTheDeletionTravels() = f.run {
        f.ledger.createGroup("会被清空", isLiability = false, colorIndex = 0)
        f.backup.reset()
        val file = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
        assertTrue(file.groups.isEmpty())
        assertTrue(
            "a replace must make the removal merge-visible",
            file.tombstones.any { it.table == "groups" && it.rowKey.startsWith("groups#") },
        )
    }

    @Test
    fun aRestoreStampsItsRowsAboveEverythingTheFileCarries() = f.run {
        val snapshot = f.backup.exportForSync()
        val root = Json.parseToJsonElement(snapshot.json) as JsonObject
        val future = f.now + 9_000_000_000L
        val groups = (root["groups"] as JsonArray).map { row ->
            val fields = LinkedHashMap(row.jsonObject)
            fields["changedAt"] = JsonPrimitive(future)
            fields["changeId"] = JsonPrimitive("from-a-future-clock")
            JsonObject(fields)
        }
        val restored = JsonObject(root + mapOf("groups" to JsonArray(groups)))
        val uri = java.io.File(f.context.cacheDir, "future.json").apply { writeText(restored.toString()) }

        f.backup.restore(android.net.Uri.fromFile(uri))

        val after = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
        assertTrue("the replace must win over a file from a fast clock", after.groups.all { it.changedAt > future })
    }

    @Test
    fun aRestoredTombstoneStillBeatsANewerRemoteRow() = f.run {
        val base = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        val tombstone = JsonObject(
            linkedMapOf(
                "table" to JsonPrimitive("groups"),
                "rowKey" to JsonPrimitive("groups#g"),
                "deletedAt" to JsonPrimitive(f.now - 10_000),
                "changeId" to JsonPrimitive("T:old"),
            ),
        )
        val target = JsonObject(base + mapOf("tombstones" to JsonArray(listOf(tombstone))))
        val uri = File(f.context.cacheDir, "tombstone.json").apply { writeText(target.toString()) }
        f.backup.restore(android.net.Uri.fromFile(uri))

        // The other device still has the row, from after the file's old delete but before the restore.
        val group = JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive("g"), "name" to JsonPrimitive("远端"), "isLiability" to JsonPrimitive(false),
                "colorIndex" to JsonPrimitive(0), "sortOrder" to JsonPrimitive(0),
                "changedAt" to JsonPrimitive(f.now - 5_000), "changeId" to JsonPrimitive("remote"),
            ),
        )
        val remote = JsonObject(base + mapOf("groups" to JsonArray(listOf(group))))
        f.backup.mergeFromJson(remote.toString())

        assertTrue("the replace re-stated the delete, so the row stays gone", f.db.dao().groups().isEmpty())
    }

    private fun JsonElement.content(): String = (this as JsonPrimitive).content
}
