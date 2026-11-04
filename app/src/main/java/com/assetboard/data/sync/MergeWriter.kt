package com.assetboard.data.sync

import com.assetboard.data.backup.BackupManager

/**
 * The endpoint of a sync merge: the gate is frozen, unsaved editor text is committed, the local
 * snapshot is merged with the downloaded one, and the result is written back in one transaction
 * (BackupManager.mergeFromJson). Kept as its own type so the account manager talks about the
 * intent and tests can substitute a writer if one is ever needed.
 */
class MergeWriter(private val backup: BackupManager) {
    suspend fun write(remoteText: String): BackupManager.MergeWriteResult = backup.mergeFromJson(remoteText)
}
