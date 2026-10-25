package com.assetboard.domain

import java.time.Clock
import java.time.LocalDate

/**
 * The wall clock behind every "now" in the app: the system's. Screenshot tests pin it, so a page
 * dated from today renders the same on any day; nothing else should read the time directly.
 */
object AppClock {
    @Volatile var clock: Clock = Clock.systemDefaultZone()

    fun millis(): Long = clock.millis()

    fun today(): LocalDate = LocalDate.now(clock)
}
