package com.assetboard.data

enum class SyncAction { UP_TO_DATE, UPLOAD, DOWNLOAD, CONFLICT }

/**
 * Decides what a sync should do from the server's metadata, the profile's last synced state and
 * the current local content hash. Pure, so every branch is cheap to test. There is no merge: two
 * sides that both changed become a question for the user.
 */
object SyncDecision {
    fun decide(remote: RemoteMeta?, profile: Profile, localHash: String, localEmpty: Boolean): SyncAction {
        if (remote == null || remote.revision <= 0) return SyncAction.UPLOAD

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