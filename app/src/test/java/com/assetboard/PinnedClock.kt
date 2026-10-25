package com.assetboard

import com.assetboard.domain.AppClock
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Screenshots are dated from today; this rule makes "today" one fixed Sunday evening, so a baseline
 * stays true on any day (a Sunday after 18:00 also shows the weekly review's nudge). Time still runs
 * from that instant, so anything that measures how long something took sees real seconds. Demo data
 * built in a test reads [AppClock] too, or it would sit around the real date instead.
 */
class PinnedClock(private val at: LocalDateTime = PINNED) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val system = Clock.systemDefaultZone()
            val pinned = at.atZone(ZoneId.systemDefault()).toInstant()
            AppClock.clock = Clock.offset(system, Duration.between(system.instant(), pinned))
            try {
                base.evaluate()
            } finally {
                AppClock.clock = system
            }
        }
    }

    companion object {
        val PINNED: LocalDateTime = LocalDateTime.of(2026, 10, 25, 20, 30)
    }
}
