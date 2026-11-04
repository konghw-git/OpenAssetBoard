package com.assetboard.data.sync

import com.assetboard.domain.AppClock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How far this device's clock is from the sync server's, learned from the HTTP `Date` header.
 * Merge orders rows by `changedAt`, so a phone whose clock is an hour off would otherwise lose
 * every edit to edits made elsewhere during that hour. Only stamps are shifted; the times the
 * user sees stay the device's own.
 */
object ServerClock {
    @Volatile
    private var offset = 0L

    val offsetMs: Long get() = offset

    /** A Date header in RFC 1123 form, or null when it is absent or implausible. */
    fun learn(dateHeader: String?, deviceMillis: Long = AppClock.millis()) {
        val server = dateHeader?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .getOrNull()
        } ?: return
        if (server <= 0) return
        val next = server - deviceMillis
        // A captive portal or a misconfigured proxy can answer with any date; a day is already
        // far more than a usable clock error, so anything beyond it is ignored.
        if (next > MAX_OFFSET_MS || next < -MAX_OFFSET_MS) return
        offset = next
    }

    internal fun resetForTests() {
        offset = 0
    }

    private const val MAX_OFFSET_MS = 24L * 60 * 60 * 1000
}
