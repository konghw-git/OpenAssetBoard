package com.assetboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDecisionTest {
    private fun profile(revision: Long, hash: String?) = Profile(
        id = "local",
        dbFile = "asset-board-local.db",
        accountId = "a",
        lastAccountId = "a",
        lastRevision = revision,
        lastSyncedHash = hash,
    )

    private fun remote(revision: Long, hash: String) = RemoteMeta(revision = revision, contentHash = hash)

    @Test fun noRemoteSnapshotUploads() {
        assertEquals(SyncAction.UPLOAD, SyncDecision.decide(null, profile(0, null), "h", localEmpty = true))
        assertEquals(SyncAction.UPLOAD, SyncDecision.decide(remote(0, ""), profile(0, null), "h", localEmpty = true))
    }

    @Test fun sameContentIsUpToDate() {
        assertEquals(SyncAction.UP_TO_DATE, SyncDecision.decide(remote(3, "h"), profile(3, "h"), "h", localEmpty = false))
    }

    @Test fun localChangeUploads() {
        assertEquals(SyncAction.UPLOAD, SyncDecision.decide(remote(3, "h"), profile(3, "h"), "local", localEmpty = false))
    }

    @Test fun remoteChangeDownloadsWhenLocalIsUntouched() {
        assertEquals(SyncAction.DOWNLOAD, SyncDecision.decide(remote(4, "new"), profile(3, "old"), "old", localEmpty = false))
    }

    @Test fun firstLoginIntoEmptyDatabaseDownloads() {
        assertEquals(SyncAction.DOWNLOAD, SyncDecision.decide(remote(7, "cloud"), profile(0, null), "empty", localEmpty = true))
    }

    @Test fun firstLoginWithLocalDataConflicts() {
        assertEquals(SyncAction.CONFLICT, SyncDecision.decide(remote(7, "cloud"), profile(0, null), "local", localEmpty = false))
    }

    @Test fun bothSidesChangedConflicts() {
        assertEquals(SyncAction.CONFLICT, SyncDecision.decide(remote(4, "new"), profile(3, "old"), "local", localEmpty = false))
    }

    @Test fun serverGoingBackwardsConflicts() {
        assertEquals(SyncAction.CONFLICT, SyncDecision.decide(remote(2, "old"), profile(4, "old"), "old", localEmpty = false))
    }

    @Test fun sameRevisionWithDifferentHashIsARemoteChange() {
        assertEquals(SyncAction.DOWNLOAD, SyncDecision.decide(remote(3, "other"), profile(3, "old"), "old", localEmpty = false))
    }
}