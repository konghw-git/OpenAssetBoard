package com.assetboard.data

import android.app.Application
import android.net.Uri
import com.assetboard.data.backup.BackupFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The browser writes the existing wire format; Android must restore its output without adapters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebBackupCompatibilityTest {
    @Test fun restoresBrowserEditsAndRepeatCompletionWithoutLosingRows() {
        val f = Fixture()
        try {
            f.run {
                val text = javaClass.getResourceAsStream("/backups/web-roundtrip.json")!!
                    .use { it.readBytes().decodeToString() }
                val codec = Json { encodeDefaults = true }
                val source = codec.decodeFromString<BackupFile>(text)
                val uri = Uri.fromFile(File(f.context.cacheDir, "web-roundtrip.json").apply { writeText(text) })
                f.backup.restore(uri)
                val restored = codec.decodeFromString<BackupFile>(f.backup.exportJson())
                // A format-15 file gains legacy stamps on restore; the content must come through untouched.
                val expected = source.contentOnly()
                val actual = restored.contentOnly()
                assertEquals(expected.accounts.toSet(), actual.accounts.toSet())
                assertEquals(expected.valuations.toSet(), actual.valuations.toSet())
                assertEquals(expected.snapshotItems.toSet(), actual.snapshotItems.toSet())
                assertEquals(expected.flows.toSet(), actual.flows.toSet())
                assertEquals(expected.todos.toSet(), actual.todos.toSet())
                assertEquals(expected.checklistItems.toSet(), actual.checklistItems.toSet())
                assertEquals(expected.notes.toSet(), actual.notes.toSet())
                assertEquals(expected.foodEntries.toSet(), actual.foodEntries.toSet())
                assertTrue(restored.todos.any { it.kind == TodoKind.COMPLETION && it.repeatSourceId == "web-repeat" })
            }
        } finally {
            f.close()
        }
    }

    /** The rows without their merge stamps, so a file from before them compares equal. */
    private fun BackupFile.contentOnly() = copy(
        accounts = accounts.map { it.copy(changedAt = 0, changeId = "") },
        valuations = valuations.map { it.copy(changedAt = 0, changeId = "") },
        snapshotItems = snapshotItems.map { it.copy(changedAt = 0, changeId = "") },
        flows = flows.map { it.copy(changedAt = 0, changeId = "") },
        todos = todos.map { it.copy(changedAt = 0, changeId = "", stateAt = 0, stateId = "") },
        checklistItems = checklistItems.map { it.copy(changedAt = 0, changeId = "") },
        notes = notes.map { it.copy(changedAt = 0, changeId = "", stateAt = 0, stateId = "") },
        foodEntries = foodEntries.map { it.copy(changedAt = 0, changeId = "") },
        tombstones = emptyList(),
        netWorthGoalAt = 0,
        netWorthGoalChangeId = "",
    )
}
