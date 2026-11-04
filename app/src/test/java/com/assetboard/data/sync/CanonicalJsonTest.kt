package com.assetboard.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Canonical text and hashing, held to the web implementation through fixtures/sync. A byte
 * difference here would make the two clients disagree about whether they hold the same data.
 */
class CanonicalJsonTest {

    @Test
    fun numbersUseEcmaScriptText() {
        val cases = mapOf(
            "1.0" to "1",
            "1e2" to "100",
            "-0.0" to "0",
            "1e21" to "1e+21",
            "2.5e-7" to "2.5e-7",
            "0.0001" to "0.0001",
            "31.20" to "31.2",
            "0.30000000000000004" to "0.30000000000000004",
            "9007199254740993" to "9007199254740992",
            "123456789012345678901234567890" to "1.2345678901234568e+29",
            "1792468800000" to "1792468800000",
            "1e-6" to "0.000001",
            "1e-7" to "1e-7",
            "1e7" to "10000000",
            "1e23" to "1e+23",
        )
        for ((literal, expected) in cases) {
            assertEquals("literal $literal", expected, CanonicalJson.numberText(literal))
        }
    }

    @Test
    fun theHashIsSha256OfTheCanonicalBytes() {
        val file = Json.parseToJsonElement("""{"a":1}""") as JsonObject
        assertEquals("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862", CanonicalJson.contentHash(file))
    }

    @Test
    fun rowKeysEscapePercentPipeAndHash() {
        val row = Json.parseToJsonElement(
            """{"noteId":"a|b#c%d","kind":"DAY","ref":"2026-01-01"}""",
        ) as JsonObject
        assertEquals(
            "noteSources#a%7Cb%23c%25d|DAY|2026-01-01",
            CanonicalJson.rowKey("noteSources", row),
        )
    }

    @Test
    fun canonicalFixturesMatchTheWebReference() {
        val cases = fixture("canonical-cases.json")["cases"] as JsonArray
        for (element in cases) {
            val case = element as JsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val input = Json.parseToJsonElement(case.getValue("inputText").jsonPrimitive.content) as JsonObject
            assertEquals(name, case.getValue("canonical").jsonPrimitive.content, CanonicalJson.canonicalJson(input))
            assertEquals(name, case.getValue("sha256").jsonPrimitive.content, CanonicalJson.contentHash(input))
        }
    }

    private fun fixture(name: String): JsonObject {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidate = File(dir, "fixtures/sync/$name")
            if (candidate.isFile) {
                return Json.parseToJsonElement(candidate.readText()) as JsonObject
            }
            dir = dir.parentFile ?: error("fixtures/sync/$name not found")
        }
    }
}
