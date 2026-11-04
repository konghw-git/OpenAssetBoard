package com.assetboard.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.TestContainers
import com.assetboard.data.WriteGate
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The error plumbing every screen shares: a rule violation becomes a dialog message, a write
 * from before a restore is refused with its explanation, and the callbacks fire either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ActionViewModelTest {
    private class Probe(container: AppContainer) : ActionViewModel(container) {
        val calls = mutableListOf<String>()
        fun run(block: suspend () -> Unit, onSuccess: () -> Unit = { calls += "ok" }, onFailure: () -> Unit = { calls += "fail" }) {
            action(onSuccess, onFailure, block)
        }
    }

    private fun withContainer(block: suspend (AppContainer) -> Unit) = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val container = TestContainers.open(ApplicationProvider.getApplicationContext())
        try {
            block(container)
        } finally {
            TestContainers.closeAll()
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitSettled(vm: Probe) {
        withTimeout(5_000) { while (vm.calls.isEmpty()) delay(10) }
    }

    @Test fun aRuleViolationBecomesAnErrorAndCallsOnFailure() = withContainer { c ->
        val vm = Probe(c)
        vm.run({ throw LedgerException("金额格式不对") })

        awaitSettled(vm)
        assertEquals("金额格式不对", vm.error.value)
        assertEquals(listOf("fail"), vm.calls)
    }

    @Test fun aSuccessfulActionCallsOnSuccessAndRaisesNoError() = withContainer { c ->
        val vm = Probe(c)
        vm.run({ c.gate.write { } })

        awaitSettled(vm)
        assertNull(vm.error.value)
        assertEquals(listOf("ok"), vm.calls)
    }

    @Test fun aWriteFromBeforeARestoreIsRefusedWithItsExplanation() = withContainer { c ->
        val vm = Probe(c) // binds the epoch the write will be started under
        c.gate.ensureEpochAtLeast(c.gate.epochNow() + 1) // a restore lands in between
        var wrote = false
        vm.run({ c.gate.write { wrote = true } })

        awaitSettled(vm)
        assertFalse("a stale write must not reach the database", wrote)
        assertEquals(WriteGate.MSG_STALE, vm.error.value)
        assertEquals(listOf("fail"), vm.calls)
    }
}
