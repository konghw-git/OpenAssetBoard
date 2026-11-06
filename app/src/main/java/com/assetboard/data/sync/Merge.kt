package com.assetboard.data.sync

import com.assetboard.data.sync.CanonicalJson.canonicalValue
import com.assetboard.data.sync.CanonicalJson.rowKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/**
 * Record-level LWW merge of two BackupFile-shaped objects (docs/sync-merge-semantics.md §5.1).
 *
 * Pure and deterministic: the same two inputs always make the same output, in either order, in
 * both languages. Derived ids and stamps are hashes of the rows they came from, never clocks or
 * randomness. `Validate` runs right after it (M3/M4). The web reference implementation is
 * `web/public/merge.js`; cross-language fixtures hold the two to the same results.
 */
object Merge {

    data class Adjustment(val rule: String, val count: Int, val text: String)

    data class Result(val data: JsonObject, val adjustments: List<Adjustment>)

    private val STAMP_FIELDS = setOf("changedAt", "changeId", "stateAt", "stateId")

    /** Fields that move the state stamp instead of the content one (§1.2). */
    private val stateFields = linkedMapOf(
        "diaryEntries" to listOf("weatherCode", "tempMin", "tempMax", "city", "lat", "lon", "weatherAt", "weatherRev"),
        "notes" to listOf("reviewedAt", "pinned"),
        "todos" to listOf("remindedPeriod", "snoozeAt"),
        "countdowns" to listOf("pinned"),
    )

    /** Losers of these tables keep their text in the matching draft table (§2.1). */
    private val ARCHIVE = linkedMapOf(
        "diaryEntries" to "diaryDrafts",
        "notes" to "noteDrafts",
        "weeklyReviews" to "weeklyDrafts",
    )

    private class Natural(
        val fields: List<String>,
        val live: Boolean = false,
        val special: String? = null,
        val completion: Boolean = false,
    )

    /**
     * Natural keys beyond the row key (§3.1). `live` groups only rows still visible; `special`
     * names the rules that take more than dropping a row (a topic re-files notes, a snapshot takes
     * its items with it, a completion record takes its checklist).
     */
    private val NATURAL = linkedMapOf(
        "diaryEntries" to Natural(listOf("date"), live = true),
        "weeklyReviews" to Natural(listOf("weekStart"), live = true),
        "noteTopics" to Natural(listOf("name"), live = true, special = "topic"),
        "bodyMeasurements" to Natural(listOf("date")),
        "snapshots" to Natural(listOf("at"), special = "snapshot"),
        "valuations" to Natural(listOf("accountId", "at")),
        "todos" to Natural(listOf("repeatSourceId", "planDate"), completion = true),
    )

    /** One mutable JSON row; null and a missing field both read as null, like JavaScript. */
    private class Row(fields: MutableMap<String, JsonElement>) {
        val fields: MutableMap<String, JsonElement> = fields

        constructor(element: JsonObject) : this(LinkedHashMap(element))

        operator fun get(key: String): JsonElement? = fields[key]?.takeUnless { it is JsonNull }

        fun exists(key: String): Boolean = fields.containsKey(key)

        fun string(key: String): String? = when (val value = get(key)) {
            is JsonPrimitive -> if (value.isString) value.content else plainText(value)
            else -> null
        }

        fun long(key: String): Long? = when (val value = get(key)) {
            is JsonPrimitive -> value.content.toLongOrNull() ?: value.content.toDoubleOrNull()?.toLong()
            else -> null
        }

        fun bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.content == "true"

        fun put(key: String, value: JsonElement) {
            fields[key] = value
        }

        fun putNullable(key: String, value: JsonElement?) {
            fields[key] = value ?: JsonNull
        }

        fun putString(key: String, value: String) = put(key, JsonPrimitive(value))

        fun putLong(key: String, value: Long) = put(key, JsonPrimitive(value))

        fun putBool(key: String, value: Boolean) = put(key, JsonPrimitive(value))

        fun copy(): Row = Row(LinkedHashMap(fields))

        fun toJson(): JsonObject = JsonObject(fields)
    }

    private data class Version(val at: Long, val id: String)

    private fun contentVersion(row: Row) = Version(row.long("changedAt") ?: 0, row.string("changeId") ?: "")

    private fun stateVersion(row: Row) = Version(row.long("stateAt") ?: 0, row.string("stateId") ?: "")

    private fun tombVersion(tomb: Row) = Version(tomb.long("deletedAt") ?: 0, tomb.string("changeId") ?: "")

    private fun kind(id: String) = when {
        id.startsWith("R:") -> 2
        id.startsWith("T:") -> 1
        else -> 0
    }

    /** The comparator of §5.1: changedAt, then repair > tombstone > row, then changeId. */
    private fun compareVersion(a: Version, b: Version): Int {
        if (a.at != b.at) return if (a.at < b.at) -1 else 1
        val ka = kind(a.id)
        val kb = kind(b.id)
        if (ka != kb) return if (ka < kb) -1 else 1
        return a.id.compareTo(b.id)
    }

    /** A row without its stamps, for the final deterministic tie-break. */
    private fun contentOfRow(row: Row): String {
        val fields = LinkedHashMap<String, JsonElement>()
        for ((key, value) in row.fields) if (key !in STAMP_FIELDS) fields[key] = value
        return canonicalValue(JsonObject(fields))
    }

    /**
     * The winner by version; an exact version tie falls back to the content text, so the result
     * never depends on which side was passed first (needed for merge(a,b) == merge(b,a)).
     */
    private fun pick(versionOf: (Row) -> Version, x: Row, y: Row): Row {
        val c = compareVersion(versionOf(x), versionOf(y))
        if (c != 0) return if (c > 0) x else y
        return if (contentOfRow(x) >= contentOfRow(y)) x else y
    }

    /** The fields that decide whether a loser has content worth archiving. */
    private fun contentProjection(table: String, row: Row): String {
        val fields = LinkedHashMap<String, JsonElement>()
        val state = stateFields[table].orEmpty()
        for ((key, value) in row.fields) {
            if (key in STAMP_FIELDS || key in state) continue
            fields[key] = value
        }
        return canonicalValue(JsonObject(fields))
    }

    private fun isLive(row: Row): Boolean = row.get("deletedAt") == null

    /** Deterministic derived stamp: at = the newest input this repair was forced by (§0.2-B). */
    private fun repairStamp(rule: String, table: String, row: Row, forces: List<Version>): Version {
        val key = rowKey(table, row.toJson())
        val at = maxOf(0L, row.long("changedAt") ?: 0L, forces.maxOfOrNull { it.at } ?: 0L)
        val ids = forces.map { it.id }.distinct().sorted().joinToString(",")
        return Version(at, derivedId("R:", listOf(rule, key, at.toString(), ids)))
    }

    private fun setContent(row: Row, stamp: Version) {
        row.putLong("changedAt", stamp.at)
        row.putString("changeId", stamp.id)
    }

    private fun setState(row: Row, stamp: Version) {
        row.putLong("stateAt", stamp.at)
        row.putString("stateId", stamp.id)
    }

    fun merge(a: JsonObject, b: JsonObject): Result {
        val (left, right) = stocktakeInputs(a, b)
        return Run(left, right).execute()
    }

    private data class StocktakeGroup(
        val members: Map<String, List<Row>>,
        val rows: List<Row>,
        val version: Version,
        val text: String,
    )

    /**
     * Older writers stamped only the valuation when editing a stocktake. Give each conflicting
     * input group one version before row LWW, so its date, values and items win together. An
     * unchanged group keeps its bytes, including on self-merge. Adopted valuations join the
     * comparison but are not owned by the stocktake's deletion cascade.
     */
    private fun stocktakeInputs(a: JsonObject, b: JsonObject): Pair<JsonObject, JsonObject> {
        val tables = listOf("snapshots", "valuations", "snapshotItems")
        val sources = listOf(a, b)
        val sides = sources.map { source -> tables.associateWith { table ->
            (source[table] as? JsonArray).orEmpty().map { Row(it as JsonObject) }
        } }
        val snapshotsById = sides.map { side ->
            side.getValue("snapshots").groupBy { it.string("id") }
                .mapValues { (_, rows) -> rows.reduce { x, y -> pick(::contentVersion, x, y) } }
        }
        val valuationsById = sides.map { it.getValue("valuations").groupBy { row -> row.string("id") } }
        val ownedBySnapshot = sides.map { it.getValue("valuations").groupBy { row -> row.string("snapshotId") } }
        val itemsBySnapshot = sides.map { it.getValue("snapshotItems").groupBy { row -> row.string("snapshotId") } }
        val tombs = LinkedHashMap<String, Row>()
        for (source in sources) for (value in (source["tombstones"] as? JsonArray).orEmpty()) {
            val tomb = Row(value as JsonObject)
            val key = tomb.string("rowKey") ?: continue
            val old = tombs[key]
            if (old == null || compareVersion(tombVersion(tomb), tombVersion(old)) > 0) tombs[key] = tomb
        }
        val extra = LinkedHashMap<String, Row>()
        val ids = sides.flatMap { it.getValue("snapshots").mapNotNull { row -> row.string("id") } }.distinct().sorted()
        for (id in ids) {
            val groups = sides.indices.map { index ->
                val snapshot = snapshotsById[index][id]
                    ?: return@map null
                val items = itemsBySnapshot[index][id].orEmpty()
                val valuations = (ownedBySnapshot[index][id].orEmpty() +
                    items.flatMap { valuationsById[index][it.string("valuationId")].orEmpty() }).distinct()
                val members = linkedMapOf("snapshots" to listOf(snapshot), "valuations" to valuations, "snapshotItems" to items)
                val rows = members.values.flatten()
                val version = rows.map(::contentVersion).reduce { x, y -> if (compareVersion(x, y) >= 0) x else y }
                val text = canonicalValue(JsonObject(members.mapValues { (table, entries) ->
                    JsonArray(entries.sortedWith(compareBy<Row> { rowKey(table, it.toJson()) }
                        .thenBy { canonicalValue(it.toJson()) }).map { it.toJson() })
                }))
                StocktakeGroup(members, rows, version, text)
            }
            val key = com.assetboard.data.RowKey.of("snapshots", id)
            val tomb = tombs[key]
            val left = groups[0]
            val right = groups[1]
            val different = left != null && right != null && left.text != right.text
            if (!different && tomb == null) continue
            val tie = different && compareVersion(left.version, right.version) == 0
            for (group in groups.filterNotNull()) {
                // A parent delete orders the whole cascade, even if the old writer minted each
                // tombstone a millisecond apart. Adopted valuations remain independent.
                if (tomb != null) {
                    for (table in listOf("valuations", "snapshotItems")) for (row in group.members.getValue(table)) {
                        if (table == "valuations" && row.string("snapshotId") != id) continue
                        val rowKey = rowKey(table, row.toJson())
                        val at = tomb.long("deletedAt") ?: 0L
                        val changeId = derivedId("T:", listOf("snapshot", rowKey, at.toString(), tomb.string("changeId") ?: ""))
                        extra[rowKey] = tombstone(table, rowKey, at, changeId)
                    }
                }
                if (tomb != null && compareVersion(tombVersion(tomb), group.version) >= 0) continue
                if (!tie && group.rows.all { compareVersion(contentVersion(it), group.version) == 0 }) continue
                val stamp = Version(group.version.at, derivedId("R:", listOf("R8", id, group.text)))
                group.rows.forEach { setContent(it, stamp) }
            }
        }
        val prepared = sources.mapIndexed { index, source ->
            JsonObject(source + sides[index].mapValues { (_, rows) -> JsonArray(rows.map { it.toJson() }) } +
                ("tombstones" to JsonArray((source["tombstones"] as? JsonArray).orEmpty() + extra.values.map { it.toJson() })))
        }
        return prepared[0] to prepared[1]
    }

    private class Run(private val a: JsonObject, private val b: JsonObject) {
        private val adjustments = mutableListOf<Adjustment>()
        private val tombs = LinkedHashMap<String, Row>()
        private val out = LinkedHashMap<String, MutableList<Row>>()
        private val pool = LinkedHashMap<String, Row>()

        fun execute(): Result {
            val header = LinkedHashMap<String, JsonElement>()
            header["formatVersion"] = JsonPrimitive(maxOf(int(a, "formatVersion") ?: 16, int(b, "formatVersion") ?: 16))
            header["schemaVersion"] = JsonPrimitive(maxOf(int(a, "schemaVersion") ?: 15, int(b, "schemaVersion") ?: 15))
            header["exportedAt"] = JsonPrimitive(maxOf(long(a, "exportedAt") ?: 0, long(b, "exportedAt") ?: 0))
            header.putAll(mergeGoal())
            for (table in CanonicalJson.rowKeyFields.keys) out[table] = mutableListOf()

            // Tombstones, indexed by row key, keeping the strongest when both sides have one.
            for (source in listOf(a, b)) {
                for (element in (source["tombstones"] as? JsonArray).orEmpty()) {
                    val tomb = Row(element as JsonObject)
                    val key = tomb.string("rowKey") ?: continue
                    val old = tombs[key]
                    if (old == null || compareVersion(tombVersion(tomb), tombVersion(old)) > 0) tombs[key] = tomb
                }
            }
            val profile = mergeHealthProfile()?.let(::Row)
            val profileTombstone = tombs["healthProfile#self"]
            header["healthProfile"] = if (profile != null && profileTombstone != null &&
                compareVersion(tombVersion(profileTombstone), contentVersion(profile)) >= 0
            ) JsonNull else {
                if (profile != null && profileTombstone != null) tombs.remove("healthProfile#self")
                profile?.toJson() ?: JsonNull
            }

            // The input rows survive for resurrection (R10): a deleted parent's content may only
            // exist on the side that still has the row.
            for (source in listOf(a, b)) {
                for (table in CanonicalJson.rowKeyFields.keys) {
                    for (row in rows(source, table)) {
                        val key = rowKey(table, row.toJson())
                        val old = pool[key]
                        pool[key] = if (old == null) row else pick(::contentVersion, old, row)
                    }
                }
            }

            // 1. row union, content and state fields merged separately. Drafts a conflict produces
            // are held aside until every table is in place, so a draft table's rows cannot
            // overwrite them.
            val merged = LinkedHashMap<String, LinkedHashMap<String, Row>>()
            val pending = linkedMapOf(
                "diaryDrafts" to mutableListOf<Row>(),
                "noteDrafts" to mutableListOf<Row>(),
                "weeklyDrafts" to mutableListOf<Row>(),
            )
            for (table in CanonicalJson.rowKeyFields.keys) {
                val union = LinkedHashMap<String, Row>()
                // Each side folds through mergeRow too: a backup from Room has one row per key,
                // but folding both sides identically keeps either argument order the same even
                // if it does not.
                for (row in rows(a, table)) {
                    val key = rowKey(table, row.toJson())
                    val old = union[key]
                    union[key] = if (old == null) row else mergeRow(table, old, row, pending)
                }
                for (row in rows(b, table)) {
                    val key = rowKey(table, row.toJson())
                    val old = union[key]
                    union[key] = if (old == null) row else mergeRow(table, old, row, pending)
                }
                for ((key, row) in union.toList()) {
                    val tomb = tombs[key] ?: continue
                    if (compareVersion(tombVersion(tomb), contentVersion(row)) >= 0) {
                        union.remove(key)
                    } else {
                        tombs.remove(key) // edited after the delete: the row wins, the tombstone goes away
                    }
                }
                merged[table] = union
            }
            for ((table, rows) in merged) out[table] = rows.values.toMutableList()
            for ((table, drafts) in pending) {
                for (draft in drafts) {
                    val list = out.getValue(table)
                    if (list.none { it.string("id") == draft.string("id") }) list.add(draft)
                }
            }

            // 2-3. natural keys, groups and repairs, to a fixpoint.
            for (pass in 0 until 6) {
                val changed = naturalKeys()
                val repaired = repairs()
                if (!changed && !repaired) break
            }
            // Counted once: it reports a condition, it does not repair anything.
            repairSameInstant()

            val data = LinkedHashMap<String, JsonElement>(header)
            for (table in CanonicalJson.rowKeyFields.keys) {
                data[table] = JsonArray(out.getValue(table).map { it.toJson() })
            }
            data["tombstones"] = JsonArray(
                tombs.values.sortedBy { it.string("rowKey") ?: "" }.map { it.toJson() },
            )
            // The toast only needs the counts, but both devices must phrase the merge the same way.
            adjustments.sortBy { it.rule }
            return Result(JsonObject(data), adjustments.toList())
        }

        // ------------------------------------------------------------ row union

        private fun mergeRow(
            table: String,
            x: Row,
            y: Row,
            pending: Map<String, MutableList<Row>>,
        ): Row {
            val win = pick(::contentVersion, x, y)
            val lose = if (win === x) y else x
            val draftTable = ARCHIVE[table]
            if (draftTable != null && contentProjection(table, win) != contentProjection(table, lose)) {
                archive(pending.getValue(draftTable), table, lose)
            }
            val row = win.copy()
            val fields = stateFields[table]
            if (fields != null) {
                val stateWin = pick(::stateVersion, x, y)
                // Copy only fields the winning row actually has, so a partial row stays partial.
                for (field in fields) if (stateWin.exists(field)) row.put(field, stateWin.fields.getValue(field))
                // A row from before format 16 has no state stamp; don't invent one.
                if (x.exists("stateAt") || y.exists("stateAt")) {
                    row.putLong("stateAt", stateVersion(stateWin).at)
                    row.putString("stateId", stateVersion(stateWin).id)
                }
            }
            return row
        }

        /**
         * The loser's text goes into the matching draft table, with an id and a stamp derived from
         * the row it came from, so both devices make the same draft and neither loses the text (§2.1).
         */
        private fun archive(drafts: MutableList<Row>, table: String, loser: Row) {
            val key = rowKey(table, loser.toJson())
            val changeId = loser.string("changeId") ?: ""
            val seed = "syncLose:$key:$changeId"
            val id = if (seed.length <= 120) seed else "syncLose:" + sha256Hex(seed).take(32)
            val draft = Row(
                linkedMapOf<String, JsonElement>(
                    "id" to JsonPrimitive(id),
                    "createdAt" to JsonPrimitive(loser.long("changedAt") ?: 0),
                    "changedAt" to JsonPrimitive(loser.long("changedAt") ?: 0),
                    "changeId" to JsonPrimitive(derivedId("R:", listOf("archive", key, changeId))),
                    "text" to JsonPrimitive(loser.string("body") ?: ""),
                    "reason" to JsonPrimitive("同步合并保留败者版本"),
                ),
            )
            when (table) {
                "diaryEntries" -> {
                    draft.putNullable("date", loser.get("date"))
                    draft.putNullable("diaryId", loser.get("id"))
                }
                "notes" -> draft.putNullable("noteId", loser.get("id"))
                "weeklyReviews" -> {
                    draft.putNullable("weekStart", loser.get("weekStart"))
                    draft.putNullable("reviewId", loser.get("id"))
                }
            }
            val tomb = tombs[rowKey(ARCHIVE.getValue(table), draft.toJson())]
            if (tomb != null && compareVersion(tombVersion(tomb), contentVersion(draft)) >= 0) return
            if (drafts.none { it.string("id") == id }) drafts.add(draft)
            note("archive", 1, "冲突的文字已存入草稿")
        }

        /** Natural-key dedup for tables whose key is not the row key (§3.1). */
        private fun naturalKeys(): Boolean {
            var changed = false
            for ((table, def) in NATURAL) {
                val groups = LinkedHashMap<String, MutableList<Row>>()
                for (row in out.getValue(table)) {
                    if (def.live && !isLive(row)) continue
                    if (def.completion) {
                        if (row.string("kind") != "COMPLETION" || row.get("repeatSourceId") == null) continue
                    }
                    val key = def.fields.joinToString("/") { field ->
                        if (field == "name") (row.string("name") ?: "").trim().lowercase() else row.string(field) ?: ""
                    }
                    groups.getOrPut(key) { mutableListOf() }.add(row)
                }
                for (group in groups.values) {
                    if (group.size < 2) continue
                    val win = group.reduce { x, y -> pick(::contentVersion, x, y) }
                    for (lose in group) {
                        if (lose === win) continue
                        changed = true
                        if (ARCHIVE[table] != null && contentProjection(table, lose) != contentProjection(table, win)) {
                            archive(out.getValue(ARCHIVE.getValue(table)), table, lose)
                        }
                        dropRow(table, lose, "dedup")
                        if (def.special == "topic") {
                            for (note in out.getValue("notes")) {
                                if (note.string("topicId") != lose.string("id")) continue
                                note.putNullable("topicId", win.get("id"))
                                setContent(
                                    note,
                                    repairStamp("R1", "notes", note, listOf(contentVersion(lose), contentVersion(win))),
                                )
                            }
                        }
                        if (def.special == "snapshot") cascadeSnapshot(lose.string("id"))
                        if (def.completion) {
                            for (item in out.getValue("checklistItems").filter { it.string("todoId") == lose.string("id") }) {
                                dropRow("checklistItems", item, "dedup")
                            }
                        }
                        val rule = when {
                            def.special == "snapshot" -> "R8"
                            def.completion -> "R9"
                            else -> "dedup"
                        }
                        note(rule, 1, "自然键重复，保留较新的一版")
                    }
                }
            }
            return changed
        }

        /** Removes a row and writes the deterministic tombstone that keeps it from coming back. */
        private fun dropRow(table: String, row: Row, reason: String) {
            out.getValue(table).remove(row)
            val key = rowKey(table, row.toJson())
            val changeId = derivedId(
                "T:",
                listOf(reason, key, (row.long("changedAt") ?: 0).toString(), row.string("changeId") ?: ""),
            )
            val tomb = tombstone(table, key, row.long("changedAt") ?: 0, changeId)
            val old = tombs[key]
            if (old == null || compareVersion(tombVersion(tomb), tombVersion(old)) > 0) tombs[key] = tomb
        }

        /** Deleting a stocktake takes its items and its own valuations; adopted ones stay (§3.2-R8). */
        private fun cascadeSnapshot(snapshotId: String?) {
            for (item in out.getValue("snapshotItems").filter { it.string("snapshotId") == snapshotId }) {
                dropRow("snapshotItems", item, "snapshot")
            }
            for (valuation in out.getValue("valuations").filter { it.string("snapshotId") == snapshotId }) {
                dropRow("valuations", valuation, "snapshot")
            }
        }

        // ---------------------------------------------------------------- repairs

        private fun repairs(): Boolean {
            var changed = false
            changed = repairPinned() || changed
            changed = repairHabits() || changed
            changed = repairSections() || changed
            changed = repairBaselines() || changed
            changed = repairParents() || changed
            changed = repairOrphans() || changed
            return changed
        }

        /** R2: at most one pinned countdown, the state-newest one. */
        private fun repairPinned(): Boolean {
            val pinned = out.getValue("countdowns").filter { it.bool("pinned") }
            if (pinned.size <= 1) return false
            val win = pinned.reduce { x, y -> pick(::stateVersion, x, y) }
            for (countdown in pinned) {
                if (countdown === win) continue
                countdown.putBool("pinned", false)
                setState(
                    countdown,
                    repairStamp("R2", "countdowns", countdown, listOf(stateVersion(win), stateVersion(countdown))),
                )
            }
            note("R2", pinned.size - 1, "只保留一个置顶倒数日")
            return true
        }

        /** R3/R4: 1..7 active habits; the UTC date of the repair stamp keeps both devices identical. */
        private fun repairHabits(): Boolean {
            val habits = out.getValue("habits")
            if (habits.isEmpty()) return false
            var changed = false
            val active = habits.filter { it.get("removedOn") == null }
            if (active.isEmpty()) {
                val win = habits.reduce { x, y -> pick(::contentVersion, x, y) }
                setContent(win, repairStamp("R4", "habits", win, habits.map(::contentVersion)))
                win.put("removedOn", JsonNull)
                note("R4", 1, "至少恢复了一项每日打卡")
                changed = true
            } else if (active.size > 7) {
                val ordered = active.sortedWith { x, y ->
                    val c = compareVersion(contentVersion(y), contentVersion(x))
                    if (c != 0) c else rowKey("habits", x.toJson()).compareTo(rowKey("habits", y.toJson()))
                }
                for (habit in ordered.drop(7)) {
                    val stamp = repairStamp("R3", "habits", habit, ordered.map(::contentVersion))
                    habit.putString("removedOn", maxDate(habit.string("addedOn"), utcDate(stamp.at)))
                    setContent(habit, stamp)
                    note("R3", 1, "超出 7 项的每日打卡已停用")
                    changed = true
                }
            }
            return changed
        }

        /** R5: a section must belong to the todo's list; otherwise the todo moves to no section. */
        private fun repairSections(): Boolean {
            var changed = false
            val sections = LinkedHashMap<String, String?>()
            for (section in out.getValue("todoSections")) {
                sections[section.string("id") ?: ""] = section.string("listId")
            }
            for (todo in out.getValue("todos")) {
                val sectionId = todo.string("sectionId") ?: continue
                val listId = sections[sectionId]
                if (sections.containsKey(sectionId) && listId == todo.string("listId")) continue
                todo.put("sectionId", JsonNull)
                setContent(todo, repairStamp("R5", "todos", todo, listOf(contentVersion(todo))))
                note("R5", 1, "待办的分区已失效，已移入无分区")
                changed = true
            }
            return changed
        }

        /** R6: a flow or valuation may not sit at or before the account's baseline; move the baseline down. */
        private fun repairBaselines(): Boolean {
            var changed = false
            for (account in out.getValue("accounts")) {
                val id = account.string("id")
                val flows = out.getValue("flows").filter {
                    it.string("fromAccountId") == id || it.string("toAccountId") == id
                }
                val valuations = out.getValue("valuations").filter { it.string("accountId") == id }
                val minFlow = flows.mapNotNull { it.long("at") }.minOrNull()
                val minValuation = valuations.mapNotNull { it.long("at") }.minOrNull()
                val original = account.long("baselineAt")
                var baseline = original ?: 0
                if (minFlow != null && minFlow <= baseline) baseline = minOf(baseline, minFlow - 1)
                if (minValuation != null && minValuation < baseline) baseline = minOf(baseline, minValuation)
                if (baseline == original) continue
                val baselineRow = valuations.firstOrNull {
                    it.long("at") == original && it.get("snapshotId") == null
                }
                if (baselineRow != null) {
                    var at = baseline
                    while (valuations.any { it !== baselineRow && it.long("at") == at }) at -= 1
                    baselineRow.putLong("at", at)
                    setContent(
                        baselineRow,
                        repairStamp("R6", "valuations", baselineRow, listOf(contentVersion(account))),
                    )
                    // A collision moved it below the target instant; the account's baseline follows
                    // it, or the next pass would see a valuation before the baseline.
                    baseline = at
                }
                val forces = listOf(contentVersion(account)) + valuations.map(::contentVersion)
                account.putLong("baselineAt", baseline)
                setContent(account, repairStamp("R6", "accounts", account, forces))
                note("R6", 1, "账户期初已前移，以容纳更早的流水")
                changed = true
            }
            return changed
        }

        /** R7: a flow and a valuation at the same instant can survive a merge; the anchor absorbs it. */
        private fun repairSameInstant() {
            val slots = out.getValue("valuations").map { "${it.string("accountId")}/${it.long("at")}" }.toHashSet()
            val count = out.getValue("flows").count { flow ->
                listOf(flow.string("fromAccountId"), flow.string("toAccountId"))
                    .any { id -> id != null && "$id/${flow.long("at")}" in slots }
            }
            note("R7", count, "有流水与估值同刻，未计入余额")
        }

        /** R10: a hard-deleted parent vs a child created or edited later; newer child ⇒ the parent returns. */
        private fun repairParents(): Boolean {
            var changed = false

            // groups ← accounts (RESTRICT)
            for ((key, tomb) in tombs.toList()) {
                if (tomb.string("table") != "groups") continue
                val id = tomb.string("rowKey").orEmpty().drop("groups#".length)
                val children = out.getValue("accounts").filter { it.string("groupId") == id }
                if (children.isEmpty()) continue
                val newer = children.filter { compareVersion(contentVersion(it), tombVersion(tomb)) > 0 }
                val pooled = pool[key]
                if (newer.isNotEmpty() && pooled != null) {
                    val row = pooled.copy()
                    setContent(row, repairStamp("R10", "groups", row, newer.map(::contentVersion) + tombVersion(tomb)))
                    out.getValue("groups").add(row)
                    tombs.remove(key)
                    note("R10", 1, "分组已随较新的账户恢复")
                    changed = true
                } else {
                    for (account in children) {
                        dropRow("accounts", account, "orphan")
                        cascadeAccount(account.string("id"))
                    }
                    note("R12", children.size, "账户的分组已不存在，账户已删除")
                    changed = true
                }
            }

            // accounts ← flows and valuations (RESTRICT)
            for ((key, tomb) in tombs.toList()) {
                if (tomb.string("table") != "accounts") continue
                val id = tomb.string("rowKey").orEmpty().drop("accounts#".length)
                val children = out.getValue("flows")
                    .filter { it.string("fromAccountId") == id || it.string("toAccountId") == id }
                    .map(::contentVersion) +
                    out.getValue("valuations").filter { it.string("accountId") == id }.map(::contentVersion)
                if (children.isEmpty()) continue
                val newer = children.filter { compareVersion(it, tombVersion(tomb)) > 0 }
                val pooled = pool[key]
                if (newer.isNotEmpty() && pooled != null) {
                    val row = pooled.copy()
                    setContent(row, repairStamp("R10", "accounts", row, newer + tombVersion(tomb)))
                    out.getValue("accounts").add(row)
                    tombs.remove(key)
                    note("R10", 1, "账户已随较新的流水或估值恢复")
                    changed = true
                } else {
                    cascadeAccount(id)
                    note("R12", children.size, "流水的账户已不存在，流水已删除")
                    changed = true
                }
            }

            // valuations ← snapshotItems (CASCADE)
            for ((key, tomb) in tombs.toList()) {
                if (tomb.string("table") != "valuations") continue
                val id = tomb.string("rowKey").orEmpty().drop("valuations#".length)
                val items = out.getValue("snapshotItems").filter { it.string("valuationId") == id }
                if (items.isEmpty()) continue
                val pooled = pool[key]
                val newer = items.filter { compareVersion(contentVersion(it), tombVersion(tomb)) > 0 }
                if (newer.isNotEmpty() && pooled != null) {
                    val row = pooled.copy()
                    setContent(row, repairStamp("R10", "valuations", row, newer.map(::contentVersion) + tombVersion(tomb)))
                    out.getValue("valuations").add(row)
                    tombs.remove(key)
                    changed = true
                } else {
                    for (item in items) dropRow("snapshotItems", item, "orphan")
                    changed = true
                }
            }

            // notes ← noteSources (CASCADE)
            for ((key, tomb) in tombs.toList()) {
                if (tomb.string("table") != "notes") continue
                val id = tomb.string("rowKey").orEmpty().drop("notes#".length)
                val sources = out.getValue("noteSources").filter { it.string("noteId") == id }
                if (sources.isEmpty()) continue
                val pooled = pool[key]
                val newer = sources.filter { compareVersion(contentVersion(it), tombVersion(tomb)) > 0 }
                if (newer.isNotEmpty() && pooled != null) {
                    val row = pooled.copy()
                    setContent(row, repairStamp("R10", "notes", row, newer.map(::contentVersion) + tombVersion(tomb)))
                    out.getValue("notes").add(row)
                    tombs.remove(key)
                    changed = true
                } else {
                    for (source in sources) dropRow("noteSources", source, "orphan")
                    changed = true
                }
            }

            // todos ← checklistItems (CASCADE), and completion rows lose their source (SET_NULL)
            for ((key, tomb) in tombs.toList()) {
                if (tomb.string("table") != "todos") continue
                val id = tomb.string("rowKey").orEmpty().drop("todos#".length)
                val items = out.getValue("checklistItems").filter { it.string("todoId") == id }
                val completions = out.getValue("todos").filter { it.string("repeatSourceId") == id }
                if (items.isEmpty() && completions.isEmpty()) continue
                val pooled = pool[key]
                val newer = items.filter { compareVersion(contentVersion(it), tombVersion(tomb)) > 0 }
                val revived = newer.isNotEmpty() && pooled != null
                if (revived) {
                    val row = pooled.copy()
                    setContent(row, repairStamp("R10", "todos", row, newer.map(::contentVersion) + tombVersion(tomb)))
                    out.getValue("todos").add(row)
                    tombs.remove(key)
                    changed = true
                } else {
                    for (item in items) dropRow("checklistItems", item, "orphan")
                    if (items.isNotEmpty()) changed = true
                    // The source stays deleted, so its completion rows become ordinary todos.
                    for (completion in completions) {
                        completion.put("repeatSourceId", JsonNull)
                        setContent(
                            completion,
                            repairStamp("R10", "todos", completion, listOf(contentVersion(completion), tombVersion(tomb))),
                        )
                        changed = true
                    }
                }
            }

            // SET_NULL lists and sections: a purged list leaves its todos in the inbox.
            for (table in listOf("todoLists", "todoSections")) {
                for ((_, tomb) in tombs.toList()) {
                    if (tomb.string("table") != table) continue
                    val id = tomb.string("rowKey").orEmpty().drop(table.length + 1)
                    val field = if (table == "todoLists") "listId" else "sectionId"
                    for (todo in out.getValue("todos").filter { it.string(field) == id }) {
                        todo.put(field, JsonNull)
                        setContent(
                            todo,
                            repairStamp("R10", "todos", todo, listOf(contentVersion(todo), tombVersion(tomb))),
                        )
                        changed = true
                    }
                    if (table == "todoLists") {
                        for (section in out.getValue("todoSections").filter { it.string("listId") == id }) {
                            dropRow("todoSections", section, "orphan")
                            changed = true
                        }
                    }
                }
            }

            // SET_NULL topics: notes become 未归类.
            for ((_, tomb) in tombs.toList()) {
                if (tomb.string("table") != "noteTopics") continue
                val id = tomb.string("rowKey").orEmpty().drop("noteTopics#".length)
                for (note in out.getValue("notes").filter { it.string("topicId") == id }) {
                    note.put("topicId", JsonNull)
                    setContent(
                        note,
                        repairStamp("R10", "notes", note, listOf(contentVersion(note), tombVersion(tomb))),
                    )
                    changed = true
                }
            }

            return changed
        }

        /** Deleting an account takes its valuations and its flows with it. */
        private fun cascadeAccount(accountId: String?) {
            for (valuation in out.getValue("valuations").filter { it.string("accountId") == accountId }) {
                dropRow("valuations", valuation, "cascade")
            }
            for (flow in out.getValue("flows").filter {
                it.string("fromAccountId") == accountId || it.string("toAccountId") == accountId
            }) {
                dropRow("flows", flow, "cascade")
            }
        }

        /** R12: references that survived every rule above; nullable ones clear, required ones go. */
        private fun repairOrphans(): Boolean {
            var changed = false
            val accountIds = out.getValue("accounts").mapNotNull { it.string("id") }.toHashSet()
            for (valuation in out.getValue("valuations").toList()) {
                if (valuation.string("accountId") in accountIds) continue
                dropRow("valuations", valuation, "orphan")
                changed = true
            }
            val valuationIds = out.getValue("valuations").mapNotNull { it.string("id") }.toHashSet()
            val snapshotIds = out.getValue("snapshots").mapNotNull { it.string("id") }.toHashSet()
            for (item in out.getValue("snapshotItems").toList()) {
                if (item.string("snapshotId") in snapshotIds &&
                    item.string("valuationId") in valuationIds &&
                    item.string("accountId") in accountIds
                ) continue
                dropRow("snapshotItems", item, "orphan")
                changed = true
            }
            val noteIds = out.getValue("notes").mapNotNull { it.string("id") }.toHashSet()
            for (source in out.getValue("noteSources").toList()) {
                if (source.string("noteId") in noteIds) continue
                dropRow("noteSources", source, "orphan")
                changed = true
            }
            val todoIds = out.getValue("todos").mapNotNull { it.string("id") }.toHashSet()
            for (item in out.getValue("checklistItems").toList()) {
                if (item.string("todoId") in todoIds) continue
                dropRow("checklistItems", item, "orphan")
                changed = true
            }
            val listIds = out.getValue("todoLists").mapNotNull { it.string("id") }.toHashSet()
            val sectionIds = out.getValue("todoSections").mapNotNull { it.string("id") }.toHashSet()
            for (todo in out.getValue("todos")) {
                if (todo.string("listId") != null && todo.string("listId") !in listIds) {
                    todo.put("listId", JsonNull)
                    setContent(todo, repairStamp("R12", "todos", todo, listOf(contentVersion(todo))))
                    changed = true
                }
                if (todo.string("sectionId") != null && todo.string("sectionId") !in sectionIds) {
                    todo.put("sectionId", JsonNull)
                    setContent(todo, repairStamp("R12", "todos", todo, listOf(contentVersion(todo))))
                    changed = true
                }
            }
            val topicIds = out.getValue("noteTopics").mapNotNull { it.string("id") }.toHashSet()
            for (note in out.getValue("notes")) {
                if (note.string("topicId") != null && note.string("topicId") !in topicIds) {
                    note.put("topicId", JsonNull)
                    setContent(note, repairStamp("R12", "notes", note, listOf(contentVersion(note))))
                    changed = true
                }
            }
            val knownHabits = if (out.getValue("habits").isNotEmpty()) {
                out.getValue("habits").mapNotNull { it.string("key") }.toHashSet()
            } else {
                setOf("supplements", "workout", "diet", "water", "sleep")
            }
            for (check in out.getValue("habitChecks").toList()) {
                if (check.string("habit") in knownHabits) continue
                dropRow("habitChecks", check, "orphan")
                changed = true
            }
            val draftTargets = listOf(
                Triple("diaryDrafts", "diaryId", "diaryEntries"),
                Triple("noteDrafts", "noteId", "notes"),
                Triple("weeklyDrafts", "reviewId", "weeklyReviews"),
            )
            for ((table, field, target) in draftTargets) {
                val targets = out.getValue(target).mapNotNull { it.string("id") }.toHashSet()
                for (draft in out.getValue(table)) {
                    if (draft.string(field) != null && draft.string(field) !in targets) {
                        draft.put(field, JsonNull)
                        setContent(draft, repairStamp("R12", table, draft, listOf(contentVersion(draft))))
                        changed = true
                    }
                }
            }
            return changed
        }

        // ---------------------------------------------------------------- helpers

        private fun mergeGoal(): Map<String, JsonElement> {
            val va = Version(long(a, "netWorthGoalAt") ?: 0, string(a, "netWorthGoalChangeId") ?: "")
            val vb = Version(long(b, "netWorthGoalAt") ?: 0, string(b, "netWorthGoalChangeId") ?: "")
            val c = compareVersion(va, vb)
            val win = if (c != 0) {
                if (c > 0) a else b
            } else {
                val ta = goalText(a)
                val tb = goalText(b)
                if (ta >= tb) a else b
            }
            return linkedMapOf(
                "netWorthGoal" to (win["netWorthGoal"] ?: JsonNull),
                "netWorthGoalAt" to JsonPrimitive(long(win, "netWorthGoalAt") ?: 0),
                "netWorthGoalChangeId" to JsonPrimitive(string(win, "netWorthGoalChangeId") ?: ""),
            )
        }

        private fun goalText(file: JsonObject): String = canonicalValue(
            JsonArray(
                listOf(
                    file["netWorthGoal"] ?: JsonNull,
                    JsonPrimitive(raw(file, "netWorthGoalAt") ?: "0"),
                    JsonPrimitive(raw(file, "netWorthGoalChangeId") ?: ""),
                ),
            ),
        )

        private fun mergeHealthProfile(): JsonObject? {
            val pa = a["healthProfile"] as? JsonObject
            val pb = b["healthProfile"] as? JsonObject
            if (pa == null) return pb?.let { JsonObject(it) }
            if (pb == null) return JsonObject(pa)
            return pick(::contentVersion, Row(pa), Row(pb)).toJson()
        }

        private fun note(rule: String, count: Int, text: String) {
            if (count <= 0) return
            val found = adjustments.find { it.rule == rule }
            if (found != null) {
                val index = adjustments.indexOf(found)
                adjustments[index] = found.copy(count = found.count + count)
            } else {
                adjustments.add(Adjustment(rule, count, text))
            }
        }

        private fun rows(file: JsonObject, table: String): List<Row> =
            (file[table] as? JsonArray).orEmpty().map { Row(it as JsonObject) }
    }

    // ------------------------------------------------------------ small JSON helpers

    private fun int(file: JsonObject, key: String): Int? =
        (file[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun long(file: JsonObject, key: String): Long? =
        (file[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun string(file: JsonObject, key: String): String? =
        (file[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun raw(file: JsonObject, key: String): String? =
        (file[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun plainText(value: JsonPrimitive): String = when {
        value.content == "true" || value.content == "false" -> value.content
        else -> CanonicalJson.numberText(value.content)
    }

    private fun tombstone(table: String, rowKey: String, deletedAt: Long, changeId: String): Row = Row(
        linkedMapOf<String, JsonElement>(
            "table" to JsonPrimitive(table),
            "rowKey" to JsonPrimitive(rowKey),
            "deletedAt" to JsonPrimitive(deletedAt),
            "changeId" to JsonPrimitive(changeId),
        ),
    )

    private fun utcDate(at: Long): String =
        Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun maxDate(first: String?, second: String): String =
        if (first.isNullOrEmpty() || first < second) second else first
}
