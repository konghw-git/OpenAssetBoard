package com.assetboard.data

import android.app.Application
import com.assetboard.data.backup.BackupFile
import com.assetboard.data.sync.AccountManager
import com.assetboard.data.sync.CanonicalJson
import com.assetboard.data.sync.RemoteMeta
import com.assetboard.data.sync.RemoteSnapshot
import com.assetboard.data.sync.RemoteUser
import com.assetboard.data.sync.ServerSession
import com.assetboard.data.sync.SyncGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncSafetyTest {
    private val f = Fixture()
    private val codec = Json { encodeDefaults = true }

    @After
    fun close() { f.close() }

    private suspend fun export() = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
    private fun encode(file: BackupFile) = codec.encodeToString(BackupFile.serializer(), file)

    private suspend fun stocktake(): Pair<String, String> {
        val g = f.ledger.createGroup("资产", false, 0)
        val a = f.ledger.createAccount(AccountDraft(g, "账户"), f.now - 10_000, 10_000, null)
        return a to f.ledger.createSnapshot(f.now - 5_000, listOf(StocktakeEntry(a, 20_000)))
    }

    @Test fun stocktakeDateAndValueEditsMustProduceAValidMerge() = f.run {
        val (a, sid) = stocktake()
        val base = export()
        val remote = base.copy(valuations = base.valuations.map {
            if (it.snapshotId == sid) it.copy(value = 30_000, changedAt = f.now + 2_000, changeId = "remote-value") else it
        })
        f.now += 1_000
        f.ledger.updateSnapshot(sid, f.now - 1_000, listOf(StocktakeEntry(a, 20_000)))
        f.backup.mergeFromJson(encode(remote))
        val merged = export()
        assertEquals(base.snapshots.single().at, merged.snapshots.single().at)
        assertEquals(30_000L, merged.valuations.single { it.snapshotId == sid }.value)
        assertEquals(base.snapshotItems.size, merged.snapshotItems.size)
        val hash = f.backup.exportForSync().contentHash
        f.backup.mergeFromJson(encode(base))
        assertEquals("an old copy cannot undo the group decision", hash, f.backup.exportForSync().contentHash)
    }

    @Test fun stocktakeDeletionAndNewerValueEditMustProduceAValidMerge() = f.run {
        val (_, sid) = stocktake()
        val base = export()
        val remote = base.copy(valuations = base.valuations.map {
            if (it.snapshotId == sid) it.copy(value = 30_000, changedAt = f.now + 2_000, changeId = "remote-value") else it
        })
        f.now += 1_000
        f.ledger.deleteSnapshot(sid)
        f.backup.mergeFromJson(encode(remote))
        val merged = export()
        assertEquals(sid, merged.snapshots.single().id)
        assertEquals(30_000L, merged.valuations.single { it.snapshotId == sid }.value)
        assertEquals(base.snapshotItems.size, merged.snapshotItems.size)
    }

    @Test fun ledgerOnlyResetMustNotOverruleAnUnrelatedRemoteTodoEdit() = f.run {
        val id = f.todos.create(TodoDraft("old title"))
        val base = export()
        val remote = base.copy(todos = base.todos.map {
            it.copy(title = "cloud title", revision = it.revision + 1, changedAt = f.now + 100, changeId = "remote-edit")
        })
        f.now += 1_000
        f.backup.resetLedgerHistory()
        withContext(f.gate.bound()) { f.backup.mergeFromJson(encode(remote)) }
        assertEquals("cloud title", f.todos.todo(id)!!.title)
    }

    @Test fun ledgerOnlyResetPreservesUnrelatedVersionsAndTombstones() = f.run {
        stocktake()
        f.todos.create(TodoDraft("unchanged"))
        val note = f.notes.create("unchanged body", null)
        val draft = f.notes.saveDraft(note, "discarded", "test")
        f.notes.discardDraft(draft)
        f.health.saveProfile(Sex.MALE, java.time.LocalDate.parse("2000-01-01"), 1750, 70_000)
        GoalRepository(f.db, f.gate, clock = { f.now }).set(100_000)
        CountdownRepository(f.db, f.gate, clock = { f.now }).save(null, "event", date("2026-12-01"), false, true)
        val before = export()
        f.now += 1_000
        f.backup.resetLedgerHistory()
        val after = export()
        assertEquals(before.groups, after.groups)
        assertEquals(before.todos, after.todos)
        assertEquals(before.notes, after.notes)
        assertEquals(before.healthProfile, after.healthProfile)
        assertEquals(before.countdowns, after.countdowns)
        assertEquals(before.netWorthGoal, after.netWorthGoal)
        assertEquals(before.netWorthGoalAt, after.netWorthGoalAt)
        assertEquals(before.netWorthGoalChangeId, after.netWorthGoalChangeId)
        val ledgerTables = setOf("snapshots", "valuations", "snapshotItems", "flows")
        assertEquals(before.tombstones.filter { it.table !in ledgerTables }, after.tombstones.filter { it.table !in ledgerTables })
        assertEquals(f.now, after.accounts.single().baselineAt)
        assertTrue(after.accounts.single().changedAt > before.accounts.single().changedAt)
    }

    @Test fun deletedConflictDraftMustStayDeletedWhenAnOldNoteReturns() = f.run {
        f.notes.create("old text", null)
        val old = export()
        val newer = old.copy(notes = old.notes.map { it.copy(body = "new text", bodyRev = it.bodyRev + 1, changedAt = f.now + 100, changeId = "new") })
        f.backup.mergeFromJson(encode(newer))
        val draft = f.db.noteDao().allDrafts().single()
        withContext(f.gate.bound()) { f.notes.discardDraft(draft.id) }
        f.backup.mergeFromJson(encode(old))
        assertTrue("a discarded conflict draft must not return", f.db.noteDao().allDrafts().isEmpty())
    }

    @Test fun syncAndHistoryMustNotRotateTheSameRefreshTokenTwice() = f.run {
        val profile = Profile("review", "review.db", accountId = "review-user", serverUrl = "https://example.com")
        val profiles = ProfileStore(f.context)
        profiles.save(profile)
        val session = SessionStore(f.context, profile.id)
        session.save("original-refresh-token")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refreshes = mutableListOf<String>()
        val hash = CanonicalJson.contentHash(Json.parseToJsonElement(f.backup.exportJson()) as JsonObject)
        val saved = profile.copy(lastRevision = 1, lastSyncedHash = hash)
        profiles.save(saved)
        val gateway = object : SyncGateway {
            override suspend fun probe(serverUrl: String) = 1L
            override suspend fun login(serverUrl: String, username: String, password: String) = error("unused")
            override suspend fun refresh(serverUrl: String, refreshToken: String): ServerSession {
                refreshes += refreshToken
                entered.complete(Unit)
                release.await()
                return ServerSession("access", "rotated-refresh", 900, RemoteUser("review-user", "review"))
            }
            override suspend fun logout(serverUrl: String, refreshToken: String) {}
            override suspend fun changePassword(serverUrl: String, access: String, oldPassword: String, newPassword: String) = error("unused")
            override suspend fun meta(serverUrl: String, access: String) = RemoteMeta(1, hash, 17, 15)
            override suspend fun download(serverUrl: String, access: String): RemoteSnapshot? = null
            override suspend fun upload(serverUrl: String, access: String, baseRevision: Long, snapshotJson: String, contentHash: String) = error("unused")
        }
        val manager = AccountManager(f.context, f.settings, profiles, saved, f.backup, f.scope, {}, gateway)
        try {
            val sync = launch { manager.syncNow() }
            entered.await()
            val history = launch { manager.loadRevisions() }
            yield()
            release.complete(Unit)
            sync.join(); history.join()
            assertEquals("one refresh request should be shared", listOf("original-refresh-token"), refreshes)
        } finally { session.clear() }
    }
}
