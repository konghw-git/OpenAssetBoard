package com.assetboard

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The containers a test opened, closed after it: a leftover collector on the app scope would
 * query the database after it is closed and fail the *next* test (UncaughtExceptionsBeforeTest).
 * The scope goes first, so nothing is still querying when the database closes.
 */
object TestContainers {
    private val live = mutableListOf<AppContainer>()

    fun open(context: Context): AppContainer = AppContainer(context).also { live += it }

    fun closeAll() {
        val containers = live.toList()
        live.clear()
        for (c in containers) {
            val job = c.appScope.coroutineContext[Job]!!
            // Wait for the scope to end before the database goes: a query still unwinding from the
            // cancellation would otherwise throw into the next test.
            runCatching {
                runBlocking<Unit> {
                    withTimeoutOrNull(3_000) { job.cancelAndJoin() }
                }
            }
            runCatching { c.db.close() }
        }
    }
}

/** Add as a rule to every test that opens containers; see [TestContainers]. */
class CloseTestContainers : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                TestContainers.closeAll()
            }
        }
    }
}
