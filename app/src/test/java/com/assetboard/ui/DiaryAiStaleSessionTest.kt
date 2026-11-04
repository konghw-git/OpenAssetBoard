package com.assetboard.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.ai.AiGateway
import com.assetboard.ui.diary.DiaryViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 生成日记 finishing after the user already left that day must not write into it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryAiStaleSessionTest {
    private class GatedAi : AiGateway {
        val release = CompletableDeferred<Unit>()
        override suspend fun complete(system: String, user: String): String {
            release.await()
            return "　　生成出来的一天。"
        }
    }

    @Test
    fun aGeneratedDiaryDoesNotLandInADayTheUserLeft() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ai = GatedAi()
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        c.settings.setBool(c.settings.networkEnabled, false)
        try {
            val vm = DiaryViewModel(c)
            val today = LocalDate.now()
            vm.open(today)
            withTimeout(5_000) { while (vm.session.value?.status?.value != DiarySession.Status.READY) delay(10) }
            val left = vm.session.value!!
            val before = left.text.value
            val msgs = mutableListOf<String>()
            val collect = launch { vm.messages.collect { msgs += it } }

            vm.aiGenerate()
            // The user leaves for another day while the model still works.
            vm.open(today.plusDays(1))
            ai.release.complete(Unit)
            withTimeout(5_000) { while (vm.aiBusy.value != null) delay(10) }
            collect.cancel()

            assertEquals("the generated body must not land in the day the user left", before, left.text.value)
            assertTrue("the drop is explained: $msgs", msgs.any { it.contains("已切换日期") })
        } finally {
            TestContainers.closeAll()
            Dispatchers.resetMain()
        }
    }
}
