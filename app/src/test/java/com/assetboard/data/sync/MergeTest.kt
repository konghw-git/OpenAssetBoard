package com.assetboard.data.sync

import com.assetboard.data.backup.BackupFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Kotlin's merge against the same fixtures the web implementation generates and reads
 * (docs/sync-merge-semantics.md §5.1). Exchanging, merging a result with itself and running the
 * merge twice must all leave the canonical bytes unchanged.
 */
class MergeTest {

    private data class Adj(val rule: String, val count: Int, val text: String)

    @Test
    fun mergeFixturesMatchTheWebReference() {
        val cases = fixture("merge-cases.json")["cases"] as JsonArray
        for (element in cases) {
            val case = element as JsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val a = case.getValue("a").jsonObject
            val b = case.getValue("b").jsonObject
            val expected = case.getValue("resultCanonical").jsonPrimitive.content

            val result = Merge.merge(a, b)
            assertEquals(name, expected, CanonicalJson.canonicalJson(result.data))
            assertEquals(name, case.getValue("resultHash").jsonPrimitive.content, CanonicalJson.contentHash(result.data))
            assertEquals(name, adjustments(case), result.adjustments.map { Adj(it.rule, it.count, it.text) })

            val reversed = Merge.merge(b, a)
            assertEquals("$name (reversed)", expected, CanonicalJson.canonicalJson(reversed.data))
            assertEquals("$name (reversed)", result.adjustments.map { Adj(it.rule, it.count, it.text) },
                reversed.adjustments.map { Adj(it.rule, it.count, it.text) })

            val canonicalResult = Json.parseToJsonElement(expected) as JsonObject
            val twice = Merge.merge(canonicalResult, canonicalResult)
            assertEquals("$name (idempotent)", expected, CanonicalJson.canonicalJson(twice.data))
        }
    }

    /** A real format-16 backup, with every typed field present, survives a merge and decodes back. */
    @Test
    fun aRealBackupMergesWithItselfAndStillDecodes() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        val text = checkNotNull(javaClass.classLoader?.getResource("backups/format-16.json")).readText()
        val file = Json.parseToJsonElement(text) as JsonObject

        val result = Merge.merge(file, file)
        assertEquals(CanonicalJson.canonicalJson(file), CanonicalJson.canonicalJson(result.data))

        val decoded = json.decodeFromJsonElement(BackupFile.serializer(), result.data)
        assertNotNull(decoded)
        assertEquals(16, decoded.formatVersion)
        assertEquals(file["tombstones"], result.data["tombstones"])
    }

    private fun adjustments(case: JsonObject): List<Adj> =
        (case.getValue("adjustments") as JsonArray).map { element ->
            val row = element.jsonObject
            Adj(
                row.getValue("rule").jsonPrimitive.content,
                row.getValue("count").jsonPrimitive.int,
                row.getValue("text").jsonPrimitive.content,
            )
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
