package com.assetboard.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAgentTest {

    @Test fun aJsonLineIsAToolCallAndProseIsNot() {
        assertEquals(AiAgent.Call("todos", mapOf("date" to "2026-10-25")), AiAgent.parseCall("""{"tool":"todos","args":{"date":"2026-10-25"}}"""))
        assertEquals(AiAgent.Call("goal", emptyMap()), AiAgent.parseCall("先查一下\n{\"tool\":\"goal\"}\n"))
        assertNull(AiAgent.parseCall("今天跑了五公里，风很大。"))
        assertNull(AiAgent.parseCall(""))
    }

    @Test fun aLongDiaryIsNeverReadAsAToolCall() {
        val diary = buildString {
            append("　　今天很好。")
            while (length < 400) append("又写了几句。")
            append("\n{\"tool\":\"todos\"}")
        }
        assertNull(AiAgent.parseCall(diary))
    }

    @Test fun theModelPullsDataUntilItWrites() {
        val seen = mutableListOf<String>()
        val replies = ArrayDeque(
            listOf(
                """{"tool":"day_overview","args":{"date":"2026-10-25"}}""",
                """{"tool":"todos","args":{}}""",
                "　　今天按计划把事都做完了。",
            ),
        )
        val tools = listOf(
            AiAgent.Tool("day_overview", "概览", "date") { args -> seen += "overview ${args["date"]}"; "心情：开心" },
            AiAgent.Tool("todos", "待办", "date") { seen += "todos"; "完成的：\n· 买牛奶" },
        )
        val out = runBlocking {
            AiAgent.run(chat = { history -> replies.removeFirst().also { seen += "history ${history.size}" } }, tools = tools, seed = "写日记")
        }
        assertEquals("　　今天按计划把事都做完了。", out)
        assertEquals(listOf("history 1", "overview 2026-10-25", "history 3", "todos", "history 5"), seen)
    }

    @Test fun anUnknownToolIsReportedAndTheRunContinues() {
        val replies = ArrayDeque(listOf("""{"tool":"not_a_tool","args":{}}""", "写完了。"))
        var asked = ""
        val out = runBlocking {
            AiAgent.run(
                chat = { history -> replies.removeFirst().also { asked = history.last().content } },
                tools = emptyList(),
                seed = "写日记",
            )
        }
        assertEquals("写完了。", out)
        assertTrue("the miss is answered, not fatal", asked.contains("没有这个工具"))
    }

    @Test fun aToolThatThrowsIsReportedAndTheRunContinues() {
        val replies = ArrayDeque(listOf("""{"tool":"boom","args":{}}""", "写完了。"))
        var asked = ""
        val out = runBlocking {
            AiAgent.run(
                chat = { history -> replies.removeFirst().also { asked = history.last().content } },
                tools = listOf(AiAgent.Tool("boom", "必炸", "") { error("炸了") }),
                seed = "写日记",
            )
        }
        assertEquals("写完了。", out)
        assertTrue(asked.contains("工具出错"))
    }

    @Test fun theModelIsAskedToWriteOnceItRunsOutOfRounds() {
        var turns = 0
        val out = runBlocking {
            AiAgent.run(
                chat = { history ->
                    turns++
                    if (history.last().content.startsWith("不要再调用")) "最后只能这么写了。"
                    else """{"tool":"goal","args":{}}"""
                },
                tools = listOf(AiAgent.Tool("goal", "目标", "") { "目标 ¥100" }),
                seed = "写日记",
            )
        }
        assertEquals("最后只能这么写了。", out)
        assertEquals(AiAgent.MAX_ROUNDS + 1, turns)
    }

    @Test fun theToolsDocTeachesTheCallProtocol() {
        // The loop only parses {"tool":...}; if the prompt never says so, a real model answers
        // in prose and that prose is read as the diary itself.
        val doc = AiAgent.toolsDoc(listOf(AiAgent.Tool("todos", "待办", "date: yyyy-MM-dd") { "" }))
        assertTrue("the call envelope is spelled out", doc.contains("\"tool\""))
        assertTrue(doc.contains("JSON"))
    }

    @Test fun aModelThatNeverStopsCallingToolsFailsInsteadOfPastingJson() {
        val result = runCatching {
            runBlocking {
                AiAgent.run(
                    chat = { """{"tool":"goal","args":{}}""" },
                    tools = listOf(AiAgent.Tool("goal", "目标", "") { "目标 ¥100" }),
                    seed = "写日记",
                )
            }
        }
        assertTrue("JSON must never come back as the diary body", result.isFailure)
    }
}
