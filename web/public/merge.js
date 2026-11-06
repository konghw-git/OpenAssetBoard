// Record-level LWW merge of two BackupFile-shaped objects (docs/sync-merge-semantics.md §5.1).
//
// Pure and deterministic: the same two inputs always make the same output, in either order, in
// both languages. Derived ids and stamps are hashes of the rows they came from, never clocks or
// randomness. validate runs right after it (M3/M4).

import { canonicalValue, rowKey, rowKeyFields } from './canonical.js';

const STAMP_FIELDS = new Set(['changedAt', 'changeId', 'stateAt', 'stateId']);

/** Fields that move the state stamp instead of the content one (§1.2). */
export const stateFields = {
  diaryEntries: ['weatherCode', 'tempMin', 'tempMax', 'city', 'lat', 'lon', 'weatherAt', 'weatherRev'],
  notes: ['reviewedAt', 'pinned'],
  todos: ['remindedPeriod', 'snoozeAt'],
  countdowns: ['pinned'],
};

/** Losers of these tables keep their text in the matching draft table (§2.1). */
const ARCHIVE = { diaryEntries: 'diaryDrafts', notes: 'noteDrafts', weeklyReviews: 'weeklyDrafts' };

/**
 * Natural keys beyond the row key (§3.1). `live` groups only rows still visible; `special`
 * names the rules that take more than dropping a row (a topic re-files notes, a snapshot takes
 * its items with it, a completion record takes its checklist).
 */
const NATURAL = {
  diaryEntries: { fields: ['date'], live: true },
  weeklyReviews: { fields: ['weekStart'], live: true },
  noteTopics: { fields: ['name'], live: true, special: 'topic' },
  bodyMeasurements: { fields: ['date'] },
  snapshots: { fields: ['at'], special: 'snapshot' },
  valuations: { fields: ['accountId', 'at'] },
  todos: { fields: ['repeatSourceId', 'planDate'], completion: true },
};

const sha256 = async (text) => {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
};

const derivedId = async (prefix, parts) => `${prefix}${(await sha256(parts.join('\u0000'))).slice(0, 32)}`;

const contentVersion = (row) => ({ at: Number(row.changedAt) || 0, id: String(row.changeId ?? '') });
const stateVersion = (row) => ({ at: Number(row.stateAt) || 0, id: String(row.stateId ?? '') });
const tombVersion = (t) => ({ at: Number(t.deletedAt) || 0, id: String(t.changeId ?? '') });

const kind = (id) => (id.startsWith('R:') ? 2 : id.startsWith('T:') ? 1 : 0);

/** The comparator of §5.1: changedAt, then repair > tombstone > row, then changeId. */
export function compareVersion(a, b) {
  if (a.at !== b.at) return a.at < b.at ? -1 : 1;
  const ka = kind(a.id);
  const kb = kind(b.id);
  if (ka !== kb) return ka < kb ? -1 : 1;
  return a.id === b.id ? 0 : a.id < b.id ? -1 : 1;
}

const S = (v) => canonicalValue(v);

/** A row without its stamps, for the final deterministic tie-break. */
function contentOfRow(row) {
  const fields = {};
  for (const [key, value] of Object.entries(row)) if (!STAMP_FIELDS.has(key)) fields[key] = value;
  return S(fields);
}

/**
 * The winner by version; an exact version tie falls back to the content text, so the result
 * never depends on which side was passed first (needed for merge(a,b) == merge(b,a)).
 */
function pick(versionOf, x, y) {
  const c = compareVersion(versionOf(x), versionOf(y));
  if (c !== 0) return c > 0 ? x : y;
  return contentOfRow(x) >= contentOfRow(y) ? x : y;
}

/** The fields that decide whether a loser has content worth archiving. */
function contentProjection(table, row) {
  const fields = {};
  for (const [key, value] of Object.entries(row)) {
    if (STAMP_FIELDS.has(key)) continue;
    if ((stateFields[table] ?? []).includes(key)) continue;
    fields[key] = value;
  }
  return S(fields);
}

const live = (row) => row.deletedAt == null;

/** Deterministic derived stamp: at = the newest input this repair was forced by (§0.2-B). */
async function repairStamp(rule, table, row, forces) {
  const key = rowKey(table, row);
  const at = Math.max(0, row.changedAt ?? 0, ...forces.map((v) => v.at));
  const ids = [...new Set(forces.map((v) => v.id))].sort().join(',');
  return { at, id: await derivedId('R:', [rule, key, String(at), ids]) };
}

const setContent = (row, stamp) => {
  row.changedAt = stamp.at;
  row.changeId = stamp.id;
};

const setState = (row, stamp) => {
  row.stateAt = stamp.at;
  row.stateId = stamp.id;
};

/** Adds one adjustment line; the list is not part of the hashed data, only of the toast. */
function note(adjustments, rule, count, text) {
  if (count <= 0) return;
  const found = adjustments.find((a) => a.rule === rule);
  if (found) found.count += count;
  else adjustments.push({ rule, count, text });
}

/** Tombstones, indexed by row key, keeping the strongest when both sides have one. */
function tombstoneMap(a, b) {
  const map = new Map();
  for (const t of [...(a.tombstones ?? []), ...(b.tombstones ?? [])]) {
    if (!t || typeof t.rowKey !== 'string') continue;
    const old = map.get(t.rowKey);
    if (!old || compareVersion(tombVersion(t), tombVersion(old)) > 0) map.set(t.rowKey, { ...t });
  }
  return map;
}

function mergeGoal(a, b) {
  const va = { at: Number(a.netWorthGoalAt) || 0, id: String(a.netWorthGoalChangeId ?? '') };
  const vb = { at: Number(b.netWorthGoalAt) || 0, id: String(b.netWorthGoalChangeId ?? '') };
  const c = compareVersion(va, vb);
  const win = c !== 0 ? (c > 0 ? a : b) : S([a.netWorthGoal ?? null, String(a.netWorthGoalAt ?? 0), String(a.netWorthGoalChangeId ?? '')])
    >= S([b.netWorthGoal ?? null, String(b.netWorthGoalAt ?? 0), String(b.netWorthGoalChangeId ?? '')]) ? a : b;
  return {
    netWorthGoal: win.netWorthGoal ?? null,
    netWorthGoalAt: win.netWorthGoalAt ?? 0,
    netWorthGoalChangeId: win.netWorthGoalChangeId ?? '',
  };
}

/**
 * Older writers only stamped the valuation when editing a stocktake. Before row LWW, give
 * each conflicting input group one version, so its date, values and items win together. An
 * unchanged group keeps its bytes (including on self-merge). Adopted valuations join the
 * version comparison but remain independently owned when the stocktake is deleted.
 */
async function stocktakeInputs(a, b) {
  const tables = ['snapshots', 'valuations', 'snapshotItems'];
  const sides = [a, b].map(source => ({ ...source,
    ...Object.fromEntries(tables.map(table => [table, (source[table] ?? []).map(row => ({ ...row }))])),
  }));
  const by = (rows, field) => {
    const index = new Map();
    for (const row of rows) {
      const list = index.get(row[field]) ?? [];
      list.push(row);
      index.set(row[field], list);
    }
    return index;
  };
  const indexes = sides.map(side => ({
    snapshots: new Map([...by(side.snapshots, 'id')].map(([id, rows]) =>
      [id, rows.reduce((x, y) => pick(contentVersion, x, y))])),
    valuations: by(side.valuations, 'id'),
    owned: by(side.valuations, 'snapshotId'),
    items: by(side.snapshotItems, 'snapshotId'),
  }));
  const tombs = tombstoneMap(a, b);
  const extra = new Map();
  const ids = [...new Set(sides.flatMap(side => side.snapshots.map(s => s.id)))].sort();
  for (const id of ids) {
    const groups = indexes.map(index => {
      const snapshot = index.snapshots.get(id);
      if (!snapshot) return null;
      const items = index.items.get(id) ?? [];
      const valuations = [...new Set([...(index.owned.get(id) ?? []), ...items.flatMap(i => index.valuations.get(i.valuationId) ?? [])])];
      const members = { snapshots: [snapshot], valuations, snapshotItems: items };
      const rows = Object.values(members).flat();
      const version = rows.map(contentVersion).reduce((x, y) => compareVersion(x, y) >= 0 ? x : y);
      const text = S(Object.fromEntries(tables.map(table => [table, [...members[table]].sort((x, y) => {
        const xKey = rowKey(table, x), yKey = rowKey(table, y);
        if (xKey !== yKey) return xKey < yKey ? -1 : 1;
        return S(x) < S(y) ? -1 : S(x) > S(y) ? 1 : 0;
      })])));
      return { members, rows, version, text };
    });
    const tomb = tombs.get(rowKey('snapshots', { id }));
    const different = groups[0] && groups[1] && groups[0].text !== groups[1].text;
    if (!different && !tomb) continue;
    const tie = different && compareVersion(groups[0].version, groups[1].version) === 0;
    for (const group of groups.filter(Boolean)) {
      // A parent delete orders the whole cascade, even if the old writer minted each tombstone
      // a millisecond apart. Never extend it to an adopted, independently owned valuation.
      if (tomb) {
        for (const table of ['valuations', 'snapshotItems']) for (const row of group.members[table]) {
          if (table === 'valuations' && row.snapshotId !== id) continue;
          const key = rowKey(table, row);
          extra.set(key, { table, rowKey: key, deletedAt: tomb.deletedAt,
            changeId: await derivedId('T:', ['snapshot', key, String(tomb.deletedAt), tomb.changeId]) });
        }
      }
      if (tomb && compareVersion(tombVersion(tomb), group.version) >= 0) continue;
      if (!tie && group.rows.every(row => compareVersion(contentVersion(row), group.version) === 0)) continue;
      const stamp = { at: group.version.at, id: await derivedId('R:', ['R8', id, group.text]) };
      group.rows.forEach(row => setContent(row, stamp));
    }
  }
  return sides.map(side => ({ ...side, tombstones: [...(side.tombstones ?? []), ...extra.values()] }));
}

/**
 * merge(a, b) -> { data, adjustments }. Neither input is changed. See §5.1 for the steps; the
 * repairs run to a fixpoint so their order cannot depend on the input order.
 */
export async function merge(a, b) {
  [a, b] = await stocktakeInputs(a, b);
  const adjustments = [];
  const out = {
    formatVersion: Math.max(a.formatVersion ?? 16, b.formatVersion ?? 16),
    schemaVersion: Math.max(a.schemaVersion ?? 15, b.schemaVersion ?? 15),
    exportedAt: Math.max(a.exportedAt ?? 0, b.exportedAt ?? 0),
    ...mergeGoal(a, b),
  };
  for (const table of Object.keys(rowKeyFields)) out[table] = [];
  const tombs = tombstoneMap(a, b);
  const profile = mergeHealthProfile(a.healthProfile, b.healthProfile);
  const profileTombstone = tombs.get('healthProfile#self');
  if (profile && profileTombstone && compareVersion(tombVersion(profileTombstone), contentVersion(profile)) >= 0) {
    out.healthProfile = null;
  } else {
    out.healthProfile = profile;
    if (profile && profileTombstone) tombs.delete('healthProfile#self');
  }

  // The input rows survive for resurrection (R10): a deleted parent's content may only exist
  // on the side that still has the row.
  const pool = new Map();
  for (const source of [a, b]) {
    for (const table of Object.keys(rowKeyFields)) {
      for (const row of source[table] ?? []) {
        const key = rowKey(table, row);
        const old = pool.get(key);
        pool.set(key, old ? pick(contentVersion, old, row) : row);
      }
    }
  }

  // 1. row union, content and state fields merged separately. Drafts a conflict produces are
  // held aside until every table is in place, so the draft table's own rows cannot overwrite them.
  const merged = new Map();
  const pending = { diaryDrafts: [], noteDrafts: [], weeklyDrafts: [] };
  for (const table of Object.keys(rowKeyFields)) {
    const rows = new Map();
    // Each side folds through mergeRow too: a backup from Room has one row per key, but folding
    // both sides identically keeps the result independent of the argument order even if not.
    for (const row of a[table] ?? []) {
      const key = rowKey(table, row);
      const old = rows.get(key);
      rows.set(key, old ? await mergeRow(table, old, row, pending, adjustments, tombs) : { ...row });
    }
    for (const row of b[table] ?? []) {
      const key = rowKey(table, row);
      const old = rows.get(key);
      rows.set(key, old ? await mergeRow(table, old, row, pending, adjustments, tombs) : { ...row });
    }
    for (const [key, row] of [...rows]) {
      const t = tombs.get(key);
      if (!t) continue;
      if (compareVersion(tombVersion(t), contentVersion(row)) >= 0) rows.delete(key);
      else tombs.delete(key); // edited after the delete: the row wins, the tombstone goes away
    }
    merged.set(table, rows);
  }
  for (const [table, rows] of merged) out[table] = [...rows.values()];
  for (const [table, drafts] of Object.entries(pending)) {
    for (const draft of drafts) if (!out[table].some((d) => d.id === draft.id)) out[table].push(draft);
  }

  // 2-3. natural keys, groups and repairs, to a fixpoint.
  for (let pass = 0; pass < 6; pass++) {
    const changed = await naturalKeys(out, tombs, adjustments);
    const repaired = await repairs(out, tombs, pool, adjustments);
    if (!changed && !repaired) break;
  }
  // Counted once: it reports a condition, it does not repair anything.
  repairSameInstant(out, adjustments);

  out.tombstones = [...tombs.values()].sort((x, y) => (x.rowKey < y.rowKey ? -1 : x.rowKey > y.rowKey ? 1 : 0));
  // The toast only needs the counts, but both devices must phrase the merge the same way.
  adjustments.sort((x, y) => (x.rule < y.rule ? -1 : x.rule > y.rule ? 1 : 0));
  return { data: out, adjustments };
}

function mergeHealthProfile(pa, pb) {
  if (!pa) return pb ? { ...pb } : null;
  if (!pb) return { ...pa };
  return { ...pick(contentVersion, pa, pb) };
}

/** One row: content fields from the content winner, state fields from the state winner. */
async function mergeRow(table, x, y, pending, adjustments, tombs) {
  const win = pick(contentVersion, x, y);
  const lose = win === x ? y : x;
  if (ARCHIVE[table] && contentProjection(table, win) !== contentProjection(table, lose)) {
    await archive(pending[ARCHIVE[table]], table, lose, adjustments, tombs);
  }
  const row = { ...win };
  const fields = stateFields[table];
  if (fields) {
    const stateWin = pick(stateVersion, x, y);
    // Copy only fields the winning row actually has, so a partial row stays partial (idempotence).
    for (const field of fields) if (field in stateWin) row[field] = stateWin[field];
    // A row from before format 16 has no state stamp; don't invent one (legacy backfill adds them).
    if ('stateAt' in x || 'stateAt' in y) {
      row.stateAt = stateVersion(stateWin).at;
      row.stateId = stateVersion(stateWin).id;
    }
  }
  return row;
}

/**
 * The loser's text goes into the matching draft table, with an id and a stamp derived from the
 * row it came from, so both devices make the same draft and neither loses the text (§2.1).
 */
async function archive(drafts, table, loser, adjustments, tombs) {
  const key = rowKey(table, loser);
  const seed = `syncLose:${key}:${loser.changeId ?? ''}`;
  const id = seed.length <= 120 ? seed : `syncLose:${(await sha256(seed)).slice(0, 32)}`;
  const draft = {
    id,
    createdAt: loser.changedAt ?? 0,
    changedAt: loser.changedAt ?? 0,
    changeId: await derivedId('R:', ['archive', key, String(loser.changeId ?? '')]),
    text: loser.body ?? '',
    reason: '同步合并保留败者版本',
    ...(table === 'diaryEntries' ? { date: loser.date, diaryId: loser.id ?? null } : {}),
    ...(table === 'notes' ? { noteId: loser.id ?? null } : {}),
    ...(table === 'weeklyReviews' ? { weekStart: loser.weekStart, reviewId: loser.id ?? null } : {}),
  };
  const draftTable = ARCHIVE[table];
  const tomb = tombs.get(rowKey(draftTable, draft));
  if (tomb && compareVersion(tombVersion(tomb), contentVersion(draft)) >= 0) return;
  if (!drafts.some((d) => d.id === id)) drafts.push(draft);
  note(adjustments, 'archive', 1, '冲突的文字已存入草稿');
}

/** Natural-key dedup for the tables whose key is not the row key (§3.1). Returns whether it changed. */
async function naturalKeys(out, tombs, adjustments) {
  let changed = false;
  for (const [table, def] of Object.entries(NATURAL)) {
    const groups = new Map();
    for (const row of out[table]) {
      if (def.live && !live(row)) continue;
      if (def.completion && (row.kind !== 'COMPLETION' || row.repeatSourceId == null)) continue;
      const key = def.fields.map((f) => (f === 'name' ? String(row.name ?? '').trim().toLowerCase() : row[f])).join('/');
      const list = groups.get(key) ?? [];
      list.push(row);
      groups.set(key, list);
    }
    for (const rows of groups.values()) {
      if (rows.length < 2) continue;
      const win = rows.reduce((x, y) => pick(contentVersion, x, y));
      for (const lose of rows) {
        if (lose === win) continue;
        changed = true;
        if (ARCHIVE[table] && contentProjection(table, lose) !== contentProjection(table, win)) {
          await archive(out[ARCHIVE[table]], table, lose, adjustments, tombs);
        }
        await dropRow(out, tombs, table, lose, 'dedup', adjustments);
        if (def.special === 'topic') {
          for (const n of out.notes) {
            if (n.topicId !== lose.id) continue;
            n.topicId = win.id;
            setContent(n, await repairStamp('R1', 'notes', n, [contentVersion(lose), contentVersion(win)]));
          }
        }
        if (def.special === 'snapshot') await cascadeSnapshot(out, tombs, lose.id, adjustments);
        if (def.completion) {
          for (const item of out.checklistItems.filter((c) => c.todoId === lose.id)) {
            await dropRow(out, tombs, 'checklistItems', item, 'dedup', adjustments);
          }
        }
        note(adjustments, def.special === 'snapshot' ? 'R8' : def.completion ? 'R9' : 'dedup', 1, '自然键重复，保留较新的一版');
      }
    }
  }
  return changed;
}

/** Removes a row and writes the deterministic tombstone that keeps it from coming back. */
async function dropRow(out, tombs, table, row, reason, adjustments) {
  const key = rowKey(table, row);
  out[table] = out[table].filter((r) => r !== row);
  const changeId = await derivedId('T:', [reason, key, String(row.changedAt ?? 0), String(row.changeId ?? '')]);
  const tomb = { table, rowKey: key, deletedAt: row.changedAt ?? 0, changeId };
  const old = tombs.get(key);
  if (!old || compareVersion(tombVersion(tomb), tombVersion(old)) > 0) tombs.set(key, tomb);
}

/** Deleting a stocktake takes its items and its own valuations; adopted ones stay (§3.2-R8). */
async function cascadeSnapshot(out, tombs, snapshotId, adjustments) {
  for (const item of out.snapshotItems.filter((i) => i.snapshotId === snapshotId)) {
    await dropRow(out, tombs, 'snapshotItems', item, 'snapshot', adjustments);
  }
  for (const v of out.valuations.filter((v) => v.snapshotId === snapshotId)) {
    await dropRow(out, tombs, 'valuations', v, 'snapshot', adjustments);
  }
}

// ------------------------------------------------------------------ repairs

/** All repairs, each deterministic; returns whether any row changed. */
async function repairs(out, tombs, pool, adjustments) {
  let changed = false;
  changed = (await repairPinned(out, adjustments)) || changed;
  changed = (await repairHabits(out, adjustments)) || changed;
  changed = (await repairSections(out, adjustments)) || changed;
  changed = (await repairBaselines(out, adjustments)) || changed;
  changed = (await repairParents(out, tombs, pool, adjustments)) || changed;
  changed = (await repairOrphans(out, tombs, adjustments)) || changed;
  return changed;
}

/** R2: at most one pinned countdown, the state-newest one. */
async function repairPinned(out, adjustments) {
  const pinned = out.countdowns.filter((c) => c.pinned);
  if (pinned.length <= 1) return false;
  const win = pinned.reduce((x, y) => pick(stateVersion, x, y));
  for (const c of pinned) {
    if (c === win) continue;
    c.pinned = false;
    setState(c, await repairStamp('R2', 'countdowns', c, [stateVersion(win), stateVersion(c)]));
  }
  note(adjustments, 'R2', pinned.length - 1, '只保留一个置顶倒数日');
  return true;
}

/** R3/R4: 1..7 active habits; the UTC date of the repair stamp keeps both devices identical. */
async function repairHabits(out, adjustments) {
  if (out.habits.length === 0) return false;
  let changed = false;
  const active = out.habits.filter((h) => h.removedOn == null);
  if (active.length === 0) {
    const win = out.habits.reduce((x, y) => pick(contentVersion, x, y));
    setContent(win, await repairStamp('R4', 'habits', win, out.habits.map(contentVersion)));
    win.removedOn = null;
    note(adjustments, 'R4', 1, '至少恢复了一项每日打卡');
    changed = true;
  } else if (active.length > 7) {
    const ordered = [...active].sort((x, y) => compareVersion(contentVersion(y), contentVersion(x)) || (rowKey('habits', x) < rowKey('habits', y) ? -1 : 1));
    for (const h of ordered.slice(7)) {
      const stamp = await repairStamp('R3', 'habits', h, ordered.map(contentVersion));
      h.removedOn = maxDate(h.addedOn, utcDate(stamp.at));
      setContent(h, stamp);
      note(adjustments, 'R3', 1, '超出 7 项的每日打卡已停用');
      changed = true;
    }
  }
  return changed;
}

const utcDate = (at) => new Date(at).toISOString().slice(0, 10);
const maxDate = (a, b) => (!a || a < b ? b : a);

/** R5: a section must belong to the todo's list; otherwise the todo moves to no section. */
async function repairSections(out, adjustments) {
  let changed = false;
  const sections = new Map(out.todoSections.map((s) => [s.id, s]));
  for (const t of out.todos) {
    if (t.sectionId == null) continue;
    const s = sections.get(t.sectionId);
    if (s && s.listId === t.listId) continue;
    t.sectionId = null;
    setContent(t, await repairStamp('R5', 'todos', t, [contentVersion(t)]));
    note(adjustments, 'R5', 1, '待办的分区已失效，已移入无分区');
    changed = true;
  }
  return changed;
}

/** R6: a flow or valuation may not sit at or before the account's baseline; move the baseline down. */
async function repairBaselines(out, adjustments) {
  let changed = false;
  for (const account of out.accounts) {
    const flows = out.flows.filter((f) => f.fromAccountId === account.id || f.toAccountId === account.id);
    const valuations = out.valuations.filter((v) => v.accountId === account.id);
    const minFlow = flows.length ? Math.min(...flows.map((f) => f.at)) : null;
    const minValuation = valuations.length ? Math.min(...valuations.map((v) => v.at)) : null;
    let baseline = account.baselineAt;
    if (minFlow != null && minFlow <= baseline) baseline = Math.min(baseline, minFlow - 1);
    if (minValuation != null && minValuation < baseline) baseline = Math.min(baseline, minValuation);
    if (baseline === account.baselineAt) continue;
    const baselineRow = valuations.find((v) => v.at === account.baselineAt && v.snapshotId == null);
    if (baselineRow) {
      let at = baseline;
      while (valuations.some((v) => v !== baselineRow && v.at === at)) at -= 1;
      baselineRow.at = at;
      setContent(baselineRow, await repairStamp('R6', 'valuations', baselineRow, [contentVersion(account)]));
      // A collision moved it below the target instant; the account's baseline follows it, or the
      // next pass would see a valuation before the baseline and move everything again.
      baseline = at;
    }
    const forces = [contentVersion(account), ...valuations.map(contentVersion)];
    account.baselineAt = baseline;
    setContent(account, await repairStamp('R6', 'accounts', account, forces));
    note(adjustments, 'R6', 1, '账户期初已前移，以容纳更早的流水');
    changed = true;
  }
  return changed;
}

/** R7: a flow and a valuation at the same instant can survive a merge; the anchor absorbs it. */
function repairSameInstant(out, adjustments) {
  const at = new Set(out.valuations.map((v) => `${v.accountId}/${v.at}`));
  const count = out.flows.filter((f) => [f.fromAccountId, f.toAccountId].some((id) => id != null && at.has(`${id}/${f.at}`))).length;
  note(adjustments, 'R7', count, '有流水与估值同刻，未计入余额');
}

/** R10: a hard-deleted parent vs a child created or edited later; newer child ⇒ the parent returns. */
async function repairParents(out, tombs, pool, adjustments) {
  let changed = false;

  // groups ← accounts (RESTRICT)
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'groups') continue;
    const id = tomb.rowKey.slice('groups#'.length);
    const children = out.accounts.filter((a) => a.groupId === id);
    if (!children.length) continue;
    const newer = children.filter((a) => compareVersion(contentVersion(a), tombVersion(tomb)) > 0);
    const pooled = pool.get(tomb.rowKey);
    if (newer.length && pooled) {
      const row = { ...pooled };
      setContent(row, await repairStamp('R10', 'groups', row, [...newer.map(contentVersion), tombVersion(tomb)]));
      out.groups.push(row);
      tombs.delete(key);
      note(adjustments, 'R10', 1, '分组已随较新的账户恢复');
      changed = true;
    } else {
      for (const a of children) {
        await dropRow(out, tombs, 'accounts', a, 'orphan', adjustments);
        await cascadeAccount(out, tombs, a.id, adjustments);
      }
      note(adjustments, 'R12', children.length, '账户的分组已不存在，账户已删除');
      changed = true;
    }
  }

  // accounts ← flows and valuations (RESTRICT)
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'accounts') continue;
    const id = tomb.rowKey.slice('accounts#'.length);
    const children = [
      ...out.flows.filter((f) => f.fromAccountId === id || f.toAccountId === id).map((r) => contentVersion(r)),
      ...out.valuations.filter((v) => v.accountId === id).map((r) => contentVersion(r)),
    ];
    if (!children.length) continue;
    const newer = children.filter((v) => compareVersion(v, tombVersion(tomb)) > 0);
    const pooled = pool.get(tomb.rowKey);
    if (newer.length && pooled) {
      const row = { ...pooled };
      setContent(row, await repairStamp('R10', 'accounts', row, [...newer, tombVersion(tomb)]));
      out.accounts.push(row);
      tombs.delete(key);
      note(adjustments, 'R10', 1, '账户已随较新的流水或估值恢复');
      changed = true;
    } else {
      await cascadeAccount(out, tombs, id, adjustments);
      note(adjustments, 'R12', children.length, '流水的账户已不存在，流水已删除');
      changed = true;
    }
  }

  // valuations ← snapshotItems (CASCADE)
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'valuations') continue;
    const id = tomb.rowKey.slice('valuations#'.length);
    const items = out.snapshotItems.filter((i) => i.valuationId === id);
    if (!items.length) continue;
    const pooled = pool.get(tomb.rowKey);
    const newer = items.filter((i) => compareVersion(contentVersion(i), tombVersion(tomb)) > 0);
    if (newer.length && pooled) {
      const row = { ...pooled };
      setContent(row, await repairStamp('R10', 'valuations', row, [...newer.map(contentVersion), tombVersion(tomb)]));
      out.valuations.push(row);
      tombs.delete(key);
      changed = true;
    } else {
      for (const item of items) await dropRow(out, tombs, 'snapshotItems', item, 'orphan', adjustments);
      changed = true;
    }
  }

  // notes ← noteSources (CASCADE)
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'notes') continue;
    const id = tomb.rowKey.slice('notes#'.length);
    const sources = out.noteSources.filter((s) => s.noteId === id);
    if (!sources.length) continue;
    const pooled = pool.get(tomb.rowKey);
    const newer = sources.filter((s) => compareVersion(contentVersion(s), tombVersion(tomb)) > 0);
    if (newer.length && pooled) {
      const row = { ...pooled };
      setContent(row, await repairStamp('R10', 'notes', row, [...newer.map(contentVersion), tombVersion(tomb)]));
      out.notes.push(row);
      tombs.delete(key);
      changed = true;
    } else {
      for (const s of sources) await dropRow(out, tombs, 'noteSources', s, 'orphan', adjustments);
      changed = true;
    }
  }

  // todos ← checklistItems (CASCADE), and completion rows lose their source (SET_NULL)
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'todos') continue;
    const id = tomb.rowKey.slice('todos#'.length);
    const items = out.checklistItems.filter((c) => c.todoId === id);
    const completions = out.todos.filter((t) => t.repeatSourceId === id);
    if (!items.length && !completions.length) continue;
    const pooled = pool.get(tomb.rowKey);
    const newer = items.filter((c) => compareVersion(contentVersion(c), tombVersion(tomb)) > 0);
    const revived = newer.length > 0 && pooled;
    if (revived) {
      const row = { ...pooled };
      setContent(row, await repairStamp('R10', 'todos', row, [...newer.map(contentVersion), tombVersion(tomb)]));
      out.todos.push(row);
      tombs.delete(key);
      changed = true;
    } else {
      for (const item of items) await dropRow(out, tombs, 'checklistItems', item, 'orphan', adjustments);
      changed = changed || items.length > 0;
      // The source stays deleted, so its completion rows become ordinary todos.
      for (const c of completions) {
        c.repeatSourceId = null;
        setContent(c, await repairStamp('R10', 'todos', c, [contentVersion(c), tombVersion(tomb)]));
        changed = true;
      }
    }
  }

  // SET_NULL lists and sections: a purged list leaves its todos in the inbox.
  for (const table of ['todoLists', 'todoSections']) {
    for (const [key, tomb] of [...tombs]) {
      if (tomb.table !== table) continue;
      const id = tomb.rowKey.slice(table.length + 1);
      const field = table === 'todoLists' ? 'listId' : 'sectionId';
      for (const t of out.todos.filter((t) => t[field] === id)) {
        t[field] = null;
        setContent(t, await repairStamp('R10', 'todos', t, [contentVersion(t), tombVersion(tomb)]));
        changed = true;
      }
      if (table === 'todoLists') {
        for (const s of out.todoSections.filter((s) => s.listId === id)) {
          await dropRow(out, tombs, 'todoSections', s, 'orphan', adjustments);
          changed = true;
        }
      }
    }
  }

  // SET_NULL topics: notes become 未归类.
  for (const [key, tomb] of [...tombs]) {
    if (tomb.table !== 'noteTopics') continue;
    const id = tomb.rowKey.slice('noteTopics#'.length);
    for (const n of out.notes.filter((n) => n.topicId === id)) {
      n.topicId = null;
      setContent(n, await repairStamp('R10', 'notes', n, [contentVersion(n), tombVersion(tomb)]));
      changed = true;
    }
  }

  return changed;
}

/** Deleting an account takes its valuations and its flows with it. */
async function cascadeAccount(out, tombs, accountId, adjustments) {
  for (const v of out.valuations.filter((v) => v.accountId === accountId)) {
    await dropRow(out, tombs, 'valuations', v, 'cascade', adjustments);
  }
  for (const f of out.flows.filter((f) => f.fromAccountId === accountId || f.toAccountId === accountId)) {
    await dropRow(out, tombs, 'flows', f, 'cascade', adjustments);
  }
}

/** R12: references that survived every rule above; nullable ones clear, required ones go. */
async function repairOrphans(out, tombs, adjustments) {
  let changed = false;
  const ids = new Set(out.accounts.map((a) => a.id));
  for (const v of [...out.valuations]) {
    if (ids.has(v.accountId)) continue;
    await dropRow(out, tombs, 'valuations', v, 'orphan', adjustments);
    changed = true;
  }
  const valuationIds = new Set(out.valuations.map((v) => v.id));
  const snapshotIds = new Set(out.snapshots.map((s) => s.id));
  for (const i of [...out.snapshotItems]) {
    if (snapshotIds.has(i.snapshotId) && valuationIds.has(i.valuationId) && ids.has(i.accountId)) continue;
    await dropRow(out, tombs, 'snapshotItems', i, 'orphan', adjustments);
    changed = true;
  }
  const noteIds = new Set(out.notes.map((n) => n.id));
  for (const s of [...out.noteSources]) {
    if (noteIds.has(s.noteId)) continue;
    await dropRow(out, tombs, 'noteSources', s, 'orphan', adjustments);
    changed = true;
  }
  const todoIds = new Set(out.todos.map((t) => t.id));
  for (const c of [...out.checklistItems]) {
    if (todoIds.has(c.todoId)) continue;
    await dropRow(out, tombs, 'checklistItems', c, 'orphan', adjustments);
    changed = true;
  }
  const listIds = new Set(out.todoLists.map((l) => l.id));
  const sectionIds = new Set(out.todoSections.map((s) => s.id));
  for (const t of out.todos) {
    if (t.listId != null && !listIds.has(t.listId)) {
      t.listId = null;
      setContent(t, await repairStamp('R12', 'todos', t, [contentVersion(t)]));
      changed = true;
    }
    if (t.sectionId != null && !sectionIds.has(t.sectionId)) {
      t.sectionId = null;
      setContent(t, await repairStamp('R12', 'todos', t, [contentVersion(t)]));
      changed = true;
    }
  }
  const topicIds = new Set(out.noteTopics.map((t) => t.id));
  for (const n of out.notes) {
    if (n.topicId != null && !topicIds.has(n.topicId)) {
      n.topicId = null;
      setContent(n, await repairStamp('R12', 'notes', n, [contentVersion(n)]));
      changed = true;
    }
  }
  const knownHabits = out.habits.length
    ? new Set(out.habits.map((h) => h.key))
    : new Set(['supplements', 'workout', 'diet', 'water', 'sleep']);
  for (const c of [...out.habitChecks]) {
    if (knownHabits.has(c.habit)) continue;
    await dropRow(out, tombs, 'habitChecks', c, 'orphan', adjustments);
    changed = true;
  }
  const draftTargets = {
    diaryDrafts: ['diaryId', 'diaryEntries'],
    noteDrafts: ['noteId', 'notes'],
    weeklyDrafts: ['reviewId', 'weeklyReviews'],
  };
  for (const [table, [field, target]] of Object.entries(draftTargets)) {
    const targets = new Set(out[target].map((r) => r.id));
    for (const d of out[table]) {
      if (d[field] != null && !targets.has(d[field])) {
        d[field] = null;
        setContent(d, await repairStamp('R12', table, d, [contentVersion(d)]));
        changed = true;
      }
    }
  }
  return changed;
}
