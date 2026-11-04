package com.assetboard.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical JSON and content hashing for sync (docs/sync-merge-semantics.md §7).
 *
 * The same data must make the same bytes on both clients, so this is deliberately independent of
 * kotlinx's encoder: keys sort by UTF-16 code unit, row arrays sort by their canonical row key,
 * `exportedAt` is zeroed, numbers use ECMAScript's text, and escaping is a fixed table. The web
 * reference implementation is `web/public/canonical.js`; the two are held to the same fixtures.
 */
object CanonicalJson {

    /** The column(s) that name a row across devices (§1.1), by backup field name. */
    val rowKeyFields: Map<String, List<String>> = linkedMapOf(
        "groups" to listOf("id"),
        "accounts" to listOf("id"),
        "snapshots" to listOf("id"),
        "valuations" to listOf("id"),
        "snapshotItems" to listOf("snapshotId", "accountId"),
        "flows" to listOf("id"),
        "todoLists" to listOf("id"),
        "todoSections" to listOf("id"),
        "todos" to listOf("id"),
        "checklistItems" to listOf("id"),
        "diaryEntries" to listOf("id"),
        "diaryDrafts" to listOf("id"),
        "habitChecks" to listOf("date", "habit"),
        "habits" to listOf("key"),
        "bodyMeasurements" to listOf("id"),
        "foodEntries" to listOf("id"),
        "noteTopics" to listOf("id"),
        "notes" to listOf("id"),
        "noteSources" to listOf("noteId", "kind", "ref"),
        "noteDrafts" to listOf("id"),
        "weeklyReviews" to listOf("id"),
        "weeklyDrafts" to listOf("id"),
        "countdowns" to listOf("id"),
    )

    /** `table#a|b` with %, | and # escaped, the same string [com.assetboard.data.RowKey] writes. */
    fun rowKey(table: String, row: JsonObject): String {
        val fields = rowKeyFields[table] ?: error("unknown table $table")
        val parts = fields.map { field ->
            when (val value = row[field]) {
                null, is JsonNull -> "~"
                is JsonPrimitive -> if (value.isString) escapePart(value.content) else primitiveText(value)
                else -> canonicalValue(value)
            }
        }
        return "$table#${parts.joinToString("|")}"
    }

    /** Canonical JSON text of a BackupFile-shaped object. */
    fun canonicalJson(file: JsonObject): String = buildString {
        append('{')
        file.keys.sorted().forEachIndexed { index, key ->
            if (index > 0) append(',')
            append(escape(key))
            append(':')
            val value = file.getValue(key)
            when {
                key == "exportedAt" -> append('0')
                key == "tombstones" -> append(canonicalTombstones(value))
                rowKeyFields.containsKey(key) -> append(canonicalRows(key, value))
                else -> append(canonicalValue(value))
            }
        }
        append('}')
    }

    /** Lowercase SHA-256 hex of [canonicalJson]. */
    fun contentHash(file: JsonObject): String = sha256Hex(canonicalJson(file))

    private fun canonicalRows(table: String, rows: JsonElement): String {
        val array = rows as? JsonArray ?: error("$table must be an array")
        val objects = array.map { it as? JsonObject ?: error("$table row must be an object") }
        val sorted = objects.map { rowKey(table, it) to it }
            .sortedBy { it.first }
            .map { it.second }
        return sorted.joinToString(",", "[", "]") { canonicalValue(it) }
    }

    private fun canonicalTombstones(rows: JsonElement): String {
        val array = rows as? JsonArray ?: error("tombstones must be an array")
        val objects = array.map { it as? JsonObject ?: error("tombstone must be an object") }
        val sorted = objects.sortedBy { (it["rowKey"] as? JsonPrimitive)?.content ?: "" }
        return sorted.joinToString(",", "[", "]") { canonicalValue(it) }
    }

    /**
     * Objects with sorted keys; arrays of anything else by their canonical text. Matches the web
     * `canonicalValue`, including sorting a plain array's encoded elements.
     */
    fun canonicalValue(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> escape(value.content)
            value.content == "true" || value.content == "false" -> value.content
            else -> numberText(value.content)
        }
        is JsonArray -> value.map(::canonicalValue).sorted().joinToString(",", "[", "]")
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
            escape(key) + ":" + canonicalValue(value.getValue(key))
        }
    }

    /**
     * ECMAScript's own number text (§7.1.4). JSON literals are parsed the way `JSON.parse` would,
     * so `1.0`, `1e2` and `-0` normalize to `1`, `100` and `0`. Integers within 2^53 print exactly;
     * larger literals go through the double, exactly as a browser would read them.
     */
    internal fun numberText(literal: String): String {
        literal.toLongOrNull()?.let { if (it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) return it.toString() }
        val value = literal.toDoubleOrNull() ?: error("not a JSON number: $literal")
        return ecmaNumber(value)
    }

    /**
     * `Number.prototype.toString()`: the shortest decimal that parses back to the same double,
     * rounded half-even, then written with ECMAScript's fixed/decimal/exponent thresholds. Working
     * from the exact binary value keeps Android (old FloatingDecimal) and the browser agreeing.
     */
    internal fun ecmaNumber(value: Double): String {
        require(value.isFinite()) { "non-finite number" }
        if (value == 0.0) return "0"
        val bits = value.toRawBits()
        numberCache[bits]?.let { return it }
        val negative = value < 0
        val abs = kotlin.math.abs(value)
        val exact = BigDecimal(abs)
        var digits = ""
        var n = 0
        for (precision in 1..17) {
            val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (rounded.toDouble() == abs) {
                val stripped = rounded.stripTrailingZeros()
                digits = stripped.unscaledValue().toString()
                n = digits.length - stripped.scale()
                break
            }
        }
        check(digits.isNotEmpty()) { "no shortest representation for $value" }
        val body = when {
            n in digits.length..21 -> digits + "0".repeat(n - digits.length)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
                val exponent = n - 1
                mantissa + "e" + if (exponent >= 0) "+$exponent" else exponent.toString()
            }
        }
        val text = if (negative) "-$body" else body
        if (numberCache.size > 4096) numberCache.clear()
        numberCache[bits] = text
        return text
    }

    private val numberCache = ConcurrentHashMap<Long, String>()

    private fun primitiveText(value: JsonPrimitive): String = when {
        value.isString -> value.content
        value.content == "true" || value.content == "false" -> value.content
        else -> numberText(value.content)
    }

    fun escape(text: String): String = buildString(text.length + 2) {
        append('"')
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // An unpaired surrogate cannot survive UTF-8 in either runtime (TextEncoder writes
            // U+FFFD, Java a question mark), so both sides replace it here and hash the same bytes.
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                append(ch).append(text[i + 1])
                i += 2
                continue
            }
            if (ch.isSurrogate()) {
                append('�')
                i += 1
                continue
            }
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
            i += 1
        }
        append('"')
    }

    private fun escapePart(part: String): String =
        part.replace("%", "%25").replace("|", "%7C").replace("#", "%23")

    private const val MAX_SAFE_INTEGER = 9007199254740991L
}

/** Lowercase SHA-256 hex of UTF-8 [text]. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** A deterministic id for a derived row or repair: `prefix` + the first 32 hex of a hash. */
internal fun derivedId(prefix: String, parts: List<String>): String =
    prefix + sha256Hex(parts.joinToString("\u0000")).take(32)
