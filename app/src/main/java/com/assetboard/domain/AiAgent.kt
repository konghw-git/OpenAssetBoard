package com.assetboard.domain

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * The tool-using loop behind 生成日记: the model may ask for the user's data (never the notes) as
 * often as it likes before it writes. A turn is either the diary itself or one JSON line —
 * `{"tool":"name","args":{...}}` — which [run] executes and feeds back as the next user message.
 * The protocol is plain text on purpose: it works with any chat endpoint, tool-calling or not.
 */
object AiAgent {
    /** Rounds of tool calls before the model is asked to write regardless. */
    const val MAX_ROUNDS = 8

    /** Rounds the forced "write now" message gets before the run gives up rather than hand back JSON. */
    const val FORCED_ROUNDS = 2

    /** One message of the conversation so far: "user" or "assistant". */
    data class Turn(val role: String, val content: String)

    /** One tool the model may call: its name, a one-line description and how to run it. */
    data class Tool(
        val name: String,
        val description: String,
        /** Argument names and shapes, for the prompt, e.g. `date: yyyy-MM-dd（可省，默认当天）`. */
        val args: String,
        val run: suspend (args: Map<String, String>) -> String,
    )

    data class Call(val name: String, val args: Map<String, String>)

    /**
     * The tools section of the system prompt: what each tool is, plus the plain-text call
     * protocol [run] parses. The envelope is spelled out here because nowhere else tells the
     * model about it — without the exact shape it answers in prose and the loop never runs.
     */
    fun toolsDoc(tools: List<Tool>): String =
        tools.joinToString("\n") { "- ${it.name}（${it.args}）：${it.description}" } +
            "\n要查数据就只输出一行 JSON：{\"tool\":\"工具名\",\"args\":{\"参数名\":\"值\"}}" +
            "（没有参数就写 {\"tool\":\"工具名\"}）；除此之外直接输出日记正文，不要输出解释。"

    /**
     * Writes the diary: asks for data until the model answers with prose. The last reply is
     * unwrapped but otherwise untouched; a model that never calls a tool just gets the [seed]
     * answered. [chat] is one turn of the conversation (see [com.assetboard.data.ai.AiGateway]).
     */
    suspend fun run(
        chat: suspend (history: List<Turn>) -> String,
        tools: List<Tool>,
        seed: String,
    ): String {
        val byName = tools.associateBy { it.name }
        val history = mutableListOf(Turn("user", seed))
        repeat(MAX_ROUNDS) {
            val reply = AiEdit.unwrap(chat(history))
            val call = parseCall(reply) ?: return reply
            history += Turn("assistant", reply)
            val tool = byName[call.name]
            val result = if (tool == null) {
                "没有这个工具。可用工具：${tools.joinToString("、") { it.name }}"
            } else {
                try {
                    tool.run(call.args)
                } catch (e: CancellationException) {
                    throw e // a cancelled run must stop the loop, not read as a broken tool
                } catch (e: Exception) {
                    "工具出错：${e.message}"
                }
            }
            history += Turn("user", "【工具结果 · ${call.name}】\n$result\n\n数据够了就写日记；还要查就继续调用工具。")
        }
        // The model may still answer with a call; handing that JSON back as the diary body is
        // worse than failing, so the forced write gets a couple of tries and then gives up.
        repeat(FORCED_ROUNDS) {
            val instruction = "不要再调用工具，根据已有信息直接输出日记正文。"
            val last = history.removeAt(history.lastIndex)
            if (last.role == "user") {
                // Strict endpoints reject two user turns in a row: fold the instruction into
                // the pending tool-result message instead of stacking another one on top.
                history += Turn("user", last.content + "\n\n" + instruction)
            } else {
                history += last
                history += Turn("user", instruction)
            }
            val reply = AiEdit.unwrap(chat(history))
            if (parseCall(reply) == null) return reply
            history += Turn("assistant", reply)
        }
        throw IllegalStateException("模型一直在调用工具，没有写出日记")
    }

    /**
     * The tool call a reply asks for, or null when it is the diary itself. Accepts a reply that is
     * just the JSON object, or a short reply carrying one on its own line — but a long reply is
     * writing, and never gets read as a call no matter what a line in it looks like.
     */
    fun parseCall(reply: String): Call? {
        val t = AiEdit.unwrap(reply).trim()
        if (t.isEmpty()) return null
        val candidates = buildList {
            add(t)
            if (t.length <= 400) {
                t.lineSequence().map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }.forEach { add(it) }
            }
        }
        for (candidate in candidates) {
            if (candidate.length > 400) continue
            val obj = runCatching { Json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject ?: continue
            val name = (obj["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val args = (obj["args"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }
                ?.toMap()
                .orEmpty()
            return Call(name, args)
        }
        return null
    }

}

/** Reads `args[key]` as an ISO date, falling back to [default] when absent or malformed. */
fun Map<String, String>.date(key: String, default: LocalDate): LocalDate =
    this[key]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: default
