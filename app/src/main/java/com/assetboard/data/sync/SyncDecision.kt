package com.assetboard.data.sync

import com.assetboard.data.AppDatabase
import com.assetboard.data.Profile
import com.assetboard.data.backup.BackupManager

enum class SyncAction { UP_TO_DATE, UPLOAD, DOWNLOAD, CONFLICT, REMOTE_TOO_NEW }

/**
 * Decides what a sync should do from the server's metadata, the profile's last synced state and
 * the current local content hash. Pure, so every branch is cheap to test. There is no merge: two
 * sides that both changed become a question for the user.
 */
object SyncDecision {
    fun decide(remote: RemoteMeta?, profile: Profile, localHash: String, localEmpty: Boolean): SyncAction {
        if (remote == null || remote.revision <= 0) return SyncAction.UPLOAD

        // The same gates as BackupManager.restoreFromJson, but before anything is downloaded or
        // uploaded: a snapshot from a newer app cannot be read here, and uploading over it would
        // throw away data this app does not even understand. Old servers report 0 and pass.
        if (remote.formatVersion > BackupManager.FORMAT_VERSION || remote.schemaVersion > AppDatabase.SCHEMA_VERSION) {
            return SyncAction.REMOTE_TOO_NEW
        }

        val remoteChanged = remote.revision != profile.lastRevision || remote.contentHash != profile.lastSyncedHash
        val localChanged = localHash != profile.lastSyncedHash
        return when {
            !remoteChanged && !localChanged -> SyncAction.UP_TO_DATE
            !remoteChanged -> SyncAction.UPLOAD
            // A first login into an empty database is a restore, not a conflict.
            remoteChanged && profile.lastRevision == 0L && localEmpty -> SyncAction.DOWNLOAD
            // The server going backwards (a restore there) is never applied silently.
            remoteChanged && remote.revision < profile.lastRevision -> SyncAction.CONFLICT
            remoteChanged && !localChanged -> SyncAction.DOWNLOAD
            else -> SyncAction.CONFLICT
        }
    }
}