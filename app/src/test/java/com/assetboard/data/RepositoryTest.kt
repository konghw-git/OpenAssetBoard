package com.assetboard.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: LedgerRepository
    private lateinit var gate: WriteGate
    private var now = 1_000_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        gate = WriteGate(db, crashOnMissingEpoch = true)
        repo = LedgerRepository(db, gate, scope, clock = { now })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private inline fun expectRule(block: () -> Unit) {
        try {
            block()
            fail("expected LedgerException")
        } catch (e: LedgerException) {
            // expected
        }
    }

    private fun backupManager() = BackupManager(
        context, db, SettingsStore(context), gate, DiaryRepository(db, gate),
        calendar = { com.assetboard.domain.calendar.WeekdaysOnly },
        effects = object : RestoreEffects { override suspend fun afterRestore(oldTodoIds: List<String>) {} },
        onRestored = {},
    )

    private suspend fun group(liability: Boolean = false) = repo.createGroup(if (liability) "负债" else "资产", liability, 0)

    @Test fun liabilityIsStoredNegativeAndShownPositive() = runBlocking(gate.bound()) {
        val g = group(liability = true)
        val id = repo.createAccount(AccountDraft(g, "校园贷款"), baselineAt = 0, baselineDisplay = 20_000_00, baselineCostDisplay = null)
        val l = repo.loadOnce()
        assertEquals(-20_000_00L, l.currentBalance(id))
        assertEquals(20_000_00L, l.toDisplay(id, l.currentBalance(id)))
        assertEquals(-20_000_00L, l.currentTotals().netWorth)
    }

    @Test fun case6_flowBeforeBaselineIsRejected() = runBlocking(gate.bound()) {
        val g = group()
        val cash = repo.createAccount(AccountDraft(g, "cash"), 0, 10_000_00, null)
        val fund = repo.createAccount(AccountDraft(g, "fund", kind = AccountKind.INVESTMENT), 100, 0, null)
        expectRule {
            repo.saveFlow(MoneyFlow(newId(), FlowKind.BUY, cash, fund, 1_000_00, at = 50, createdAt = 0))
        }
        repo.saveFlow(MoneyFlow(newId(), FlowKind.BUY, cash, fund, 1_000_00, at = 150, createdAt = 0))
        assertEquals(1, repo.loadOnce().flows.size)
    }

    @Test fun case10_backfilledStocktakeUsesThatDaysBalance() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "bank"), 0, 800_00, null)
        repo.addValuation(a, 200, 1_000_00, null)
        val l = repo.loadOnce()
        assertEquals(800_00L, l.balanceAt(a, 100))
        val sid = repo.createSnapshot(100, listOf(StocktakeEntry(a, l.balanceAt(a, 100)!!)))
        val after = repo.loadOnce()
        assertEquals(800_00L, after.snapshotTotals(sid).netWorth)
        assertEquals(1_000_00L, after.currentBalance(a))
    }

    @Test fun case11_existingStocktakeKeepsItsOwnAccountList() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val sid = repo.createSnapshot(10, listOf(StocktakeEntry(a, 100_00)))
        repo.createAccount(AccountDraft(g, "b"), 0, 500_00, null)
        val l = repo.loadOnce()
        assertEquals(1, l.itemsOf(sid).size)
        assertEquals(100_00L, l.snapshotTotals(sid).netWorth)
    }

    @Test fun stocktakeIsAnchoredAgainstLaterEditsElsewhere() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val vid = repo.addValuation(a, 5, 200_00, null)
        val sid = repo.createSnapshot(10, listOf(StocktakeEntry(a, 200_00)))
        repo.updateValuation(vid, 5, 999_00, null)
        assertEquals(200_00L, repo.loadOnce().snapshotTotals(sid).netWorth)
    }

    @Test fun case12_accountWithRecordsCannotBeDeleted() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val b = repo.createAccount(AccountDraft(g, "b"), 0, 0, null)
        repo.saveFlow(MoneyFlow(newId(), FlowKind.TRANSFER, a, b, 10_00, at = 5, createdAt = 0))
        assertFalse(repo.canDelete(a))
        expectRule { repo.deleteAccount(a) }
        val empty = repo.createAccount(AccountDraft(g, "empty"), 0, 0, null)
        assertTrue(repo.canDelete(empty))
        repo.deleteAccount(empty)
        assertEquals(2, repo.loadOnce().accounts.size)
    }

    @Test fun case13_disablingDoesNotTouchHistory() = runBlocking(gate.bound()) {
        val g = group()
        val fund = repo.createAccount(AccountDraft(g, "fund", kind = AccountKind.INVESTMENT), 0, 10_000_00, null)
        val sid = repo.createSnapshot(10, listOf(StocktakeEntry(fund, 10_000_00)))
        val valuationsBefore = repo.loadOnce().valuations.size
        repo.setDisabled(fund, true)
        val l = repo.loadOnce()
        assertEquals(valuationsBefore, l.valuations.size)
        assertEquals(0L, l.currentTotals().netWorth)
        assertEquals(10_000_00L, l.snapshotTotals(sid).netWorth)
    }

    @Test fun stocktakeAtBaselineInstantAdoptsTheBaselineAndDeletingKeepsIt() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 50, 300_00, null)
        val sid = repo.createSnapshot(50, listOf(StocktakeEntry(a, 300_00)))
        assertEquals(1, repo.loadOnce().valuations.size)
        repo.deleteSnapshot(sid)
        val l = repo.loadOnce()
        assertEquals(1, l.valuations.size)
        assertEquals(300_00L, l.currentBalance(a))
    }

    @Test fun editingStocktakeDateMovesItsValuations() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val sid = repo.createSnapshot(10, listOf(StocktakeEntry(a, 150_00)))
        repo.updateSnapshot(sid, 20, listOf(StocktakeEntry(a, 160_00)))
        val l = repo.loadOnce()
        val v = l.valuations.single { it.snapshotId == sid }
        assertEquals(20L, v.at)
        assertEquals(160_00L, v.value)
        assertEquals(100_00L, l.balanceAt(a, 15))
    }

    @Test fun deletingStocktakeRemovesItsValuations() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val sid = repo.createSnapshot(10, listOf(StocktakeEntry(a, 150_00)))
        repo.deleteSnapshot(sid)
        val l = repo.loadOnce()
        assertTrue(l.snapshots.isEmpty())
        assertEquals(100_00L, l.currentBalance(a))
    }

    @Test fun groupWithAccountsCannotFlipLiability() = runBlocking(gate.bound()) {
        val g = group()
        repo.createAccount(AccountDraft(g, "a"), 0, 0, null)
        expectRule { repo.updateGroup(g, "x", true, 0) }
        expectRule { repo.deleteGroup(g) }
    }

    @Test fun accountsCannotCrossTheLiabilityLine() = runBlocking(gate.bound()) {
        val assets = group()
        val debts = group(liability = true)
        val a = repo.createAccount(AccountDraft(assets, "a"), 0, 0, null)
        expectRule { repo.updateAccount(a, AccountDraft(debts, "a")) }
    }

    @Test fun baselineMovesOnlyEarlier() = runBlocking(gate.bound()) {
        val g = group()
        val fund = repo.createAccount(AccountDraft(g, "fund", kind = AccountKind.INVESTMENT), 100, 1_200_00, 1_000_00)
        expectRule { repo.updateBaseline(fund, 200, 1_200_00, null) }
        repo.updateBaseline(fund, 50, 900_00, 900_00)
        val l = repo.loadOnce()
        val acc = l.accountById.getValue(fund)
        assertEquals(50L, acc.baselineAt)
        assertEquals(900_00L, acc.baselineCost)
        assertEquals(2, l.valuationsOf(fund).size)
        assertEquals(1_200_00L, l.currentBalance(fund))
    }

    @Test fun case14_restoreRejectsUnknownFormatAndKeepsData() = runBlocking(gate.bound()) {
        val g = group()
        repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val backup = backupManager()
        val json = backup.exportJson().replace("\"formatVersion\":${BackupManager.FORMAT_VERSION}", "\"formatVersion\":99")
        val file = File(context.cacheDir, "bad.json").apply { writeText(json) }
        expectRule { backup.restore(Uri.fromFile(file)) }
        assertEquals(1, repo.loadOnce().accounts.size)
    }

    @Test fun case15_failedRestoreRollsBack() = runBlocking(gate.bound()) {
        val g = group()
        repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        val backup = backupManager()
        val codec = Json { encodeDefaults = true }
        val good = codec.decodeFromString(BackupFile.serializer(), backup.exportJson())
        val broken = good.copy(
            flows = listOf(MoneyFlow(newId(), FlowKind.TRANSFER, "missing", null, 1_00, at = 5, createdAt = 0)),
        )
        val file = File(context.cacheDir, "broken.json").apply { writeText(codec.encodeToString(BackupFile.serializer(), broken)) }
        try {
            backup.restore(Uri.fromFile(file))
            fail("restore should fail")
        } catch (e: Exception) {
            // expected: rule or foreign key violation
        }
        val l = repo.loadOnce()
        assertEquals(1, l.accounts.size)
        assertEquals("a", l.accounts.single().name)
    }

    @Test fun restoreRoundTrip() = runBlocking(gate.bound()) {
        val g = group()
        val a = repo.createAccount(AccountDraft(g, "a"), 0, 100_00, null)
        repo.createSnapshot(10, listOf(StocktakeEntry(a, 120_00)))
        val backup = backupManager()
        val file = File(context.cacheDir, "good.json").apply { writeText(backup.exportJson()) }
        repo.createAccount(AccountDraft(g, "later"), 0, 1_00, null)
        backup.restore(Uri.fromFile(file))
        val l = repo.loadOnce()
        assertEquals(listOf("a"), l.accounts.map { it.name })
        assertEquals(1, l.snapshots.size)
        assertEquals(120_00L, l.currentBalance(a))
    }

    @Test fun seedMatchesTheScreenshotTotals() = runBlocking(gate.bound()) {
        repo.seedFromSuishouji()
        val t = repo.loadOnce().currentTotals()
        assertEquals(38_482_13L, t.assets)
        assertEquals(-20_000_00L, t.liabilities)
        assertEquals(18_482_13L, t.netWorth)
    }
}
