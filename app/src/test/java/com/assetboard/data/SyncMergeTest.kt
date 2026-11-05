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
import kotlinx.coroutines.withContext
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
    fun offlineResetDeletesPreviouslyUnseenOlderCloudRowsButKeepsNewerOnes() = f.run {
        val base = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        val old = JsonObject(mapOf("id" to JsonPrimitive("old"), "name" to JsonPrimitive("旧"),
            "isLiability" to JsonPrimitive(false), "colorIndex" to JsonPrimitive(0), "sortOrder" to JsonPrimitive(0),
            "changedAt" to JsonPrimitive(f.now - 1000), "changeId" to JsonPrimitive("old")))
        val newer = JsonObject(old + mapOf("id" to JsonPrimitive("new"), "name" to JsonPrimitive("新"),
            "changedAt" to JsonPrimitive(f.now + 60_000), "changeId" to JsonPrimitive("new")))
        f.backup.reset()
        val remote = JsonObject(base + mapOf("groups" to JsonArray(listOf(old, newer))))
        withContext(f.gate.bound()) { f.backup.mergeFromJson(remote.toString()) }
        assertEquals(null, f.db.dao().group("old"))
        assertEquals("新", f.db.dao().group("new")?.name)
    }

    @Test
    fun pendingReplaceSurvivesMultipleDownloadsUntilTheMatchingSyncCompletes() = f.run {
        val base = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        f.backup.reset()
        val pending = f.backup.pendingReplace()!!
        val row = JsonObject(mapOf("id" to JsonPrimitive("remote"), "name" to JsonPrimitive("旧"),
            "isLiability" to JsonPrimitive(false), "colorIndex" to JsonPrimitive(0), "sortOrder" to JsonPrimitive(0),
            "changedAt" to JsonPrimitive(f.now - 1000), "changeId" to JsonPrimitive("remote")))
        withContext(f.gate.bound()) {
            f.backup.mergeFromJson(base.toString())
            f.backup.mergeFromJson(JsonObject(base + mapOf("groups" to JsonArray(listOf(row)))).toString())
        }
        assertEquals(null, f.db.dao().group("remote"))
        assertEquals(pending, f.backup.pendingReplace())
        f.backup.clearPendingReplace("a different replace")
        assertEquals(pending, f.backup.pendingReplace())
        f.backup.clearPendingReplace(pending)
        assertEquals(null, f.backup.pendingReplace())
    }

    @Test
    fun ledgerResetDoesNotDiscardAnEarlierOfflineFullReset() = f.run {
        val base = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        val remote = JsonObject(base + mapOf("groups" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("remote"), "name" to JsonPrimitive("旧"),
            "isLiability" to JsonPrimitive(false), "colorIndex" to JsonPrimitive(0), "sortOrder" to JsonPrimitive(0),
            "changedAt" to JsonPrimitive(f.now - 1000), "changeId" to JsonPrimitive("remote"),
        ))))))
        f.backup.reset()
        withContext(f.gate.bound()) { f.backup.resetLedgerHistory() }
        withContext(f.gate.bound()) { f.backup.mergeFromJson(remote.toString()) }
        assertEquals(null, f.db.dao().group("remote"))
    }

    @Test
    fun offlineResetAlsoClearsAnUnseenOlderCloudGoal() = f.run {
        val base = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        val remote = JsonObject(base + mapOf("netWorthGoal" to JsonPrimitive(100_000),
            "netWorthGoalAt" to JsonPrimitive(f.now - 1000), "netWorthGoalChangeId" to JsonPrimitive("remote")))
        f.backup.reset()
        withContext(f.gate.bound()) { f.backup.mergeFromJson(remote.toString()) }
        val merged = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
        assertEquals(null, merged.netWorthGoal)
    }

    @Test
    fun resetDoesNotResurrectAnOlderHealthProfile() = f.run {
        f.health.saveProfile(Sex.MALE, java.time.LocalDate.parse("1990-01-01"), 1750, null)
        val remote = f.backup.exportForSync().json
        f.backup.reset()
        withContext(f.gate.bound()) { f.backup.mergeFromJson(remote) }
        assertEquals(null, f.db.healthDao().profile())
    }

    @Test
    fun editsAndDeletesAfterReceivingFutureStampsSurviveAnotherMergeAndRestart() = f.run {
        val edited = f.ledger.createGroup("编辑", false, 0)
        val deleted = f.ledger.createGroup("删除", false, 0)
        val root = Json.parseToJsonElement(f.backup.exportForSync().json) as JsonObject
        val future = f.now + 60_000
        val groups = (root["groups"] as JsonArray).map { row ->
            JsonObject(row.jsonObject + mapOf("changedAt" to JsonPrimitive(future), "changeId" to JsonPrimitive("remote")))
        }
        val remote = JsonObject(root + mapOf("groups" to JsonArray(groups)))
        f.backup.mergeFromJson(remote.toString())

        // The process can restart between receiving the snapshot and the next local operation.
        ChangeStamps.resetForTests()
        val restartedGate = WriteGate(f.db, crashOnMissingEpoch = true)
        val ledger = LedgerRepository(f.db, restartedGate, f.scope, clock = { f.now })
        withContext(restartedGate.bound()) {
            ledger.updateGroup(edited, "本机修改", false, 0)
            ledger.deleteGroup(deleted)
        }
        withContext(f.gate.bound()) { f.backup.mergeFromJson(remote.toString()) }
        assertEquals("本机修改", f.db.dao().group(edited)?.name)
        assertEquals(null, f.db.dao().group(deleted))
    }

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
