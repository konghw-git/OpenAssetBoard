// Canonical JSON for the merge protocol (docs/sync-merge-semantics.md §7).
//
// One byte string for the same data on every device and in both languages: object keys sorted
// by UTF-16 code unit, arrays of rows sorted by their canonical row key, no whitespace, a fixed
// number format, and exportedAt zeroed. contentHash is SHA-256 over those bytes.

/** The column(s) that name a row across devices (§1.1), by backup field name. */
export const rowKeyFields = {
  groups: ['id'],
  accounts: ['id'],
  snapshots: ['id'],
  valuations: ['id'],
  snapshotItems: ['snapshotId', 'accountId'],
  flows: ['id'],
  todoLists: ['id'],
  todoSections: ['id'],
  todos: ['id'],
  checklistItems: ['id'],
  diaryEntries: ['id'],
  diaryDrafts: ['id'],
  habitChecks: ['date', 'habit'],
  habits: ['key'],
  bodyMeasurements: ['id'],
  foodEntries: ['id'],
  noteTopics: ['id'],
  notes: ['id'],
  noteSources: ['noteId', 'kind', 'ref'],
  noteDrafts: ['id'],
  weeklyReviews: ['id'],
  weeklyDrafts: ['id'],
  countdowns: ['id'],
};

/** `table#a|b` with %, | and # escaped, the same string the Kotlin side writes. */
export function rowKey(table, row) {
  const parts = rowKeyFields[table].map((field) => {
    const value = row[field];
    if (value == null) return '~';
    return String(value).replace(/%/g, '%25').replace(/\|/g, '%7C').replace(/#/g, '%23');
  });
  return `${table}#${parts.join('|')}`;
}

/** Canonical JSON text of a BackupFile-shaped object. */
export function canonicalJson(file) {
  const out = ['{'];
  const keys = Object.keys(file).sort();
  keys.forEach((key, index) => {
    if (index) out.push(',');
    out.push(escapeString(key), ':');
    if (key === 'exportedAt') out.push('0');
    else if (key === 'tombstones') out.push(canonicalTombstones(file[key]));
    else if (key in rowKeyFields) out.push(canonicalRows(key, file[key]));
    else out.push(canonicalValue(file[key]));
  });
  out.push('}');
  return out.join('');
}

/** Lowercase SHA-256 hex of [canonicalJson]. */
export async function contentHash(file) {
  const bytes = new TextEncoder().encode(canonicalJson(file));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function canonicalRows(table, rows) {
  if (!Array.isArray(rows)) throw new Error(`${table} must be an array`);
  const sorted = [...rows].sort((a, b) => (rowKey(table, a) < rowKey(table, b) ? -1 : rowKey(table, a) > rowKey(table, b) ? 1 : 0));
  return `[${sorted.map(canonicalValue).join(',')}]`;
}

function canonicalTombstones(rows) {
  if (!Array.isArray(rows)) throw new Error('tombstones must be an array');
  const sorted = [...rows].sort((a, b) => (a.rowKey < b.rowKey ? -1 : a.rowKey > b.rowKey ? 1 : 0));
  return `[${sorted.map(canonicalValue).join(',')}]`;
}

/** Objects with sorted keys; arrays of anything else by their canonical text. */
export function canonicalValue(value) {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') return canonicalNumber(value);
  if (typeof value === 'string') return escapeString(value);
  if (Array.isArray(value)) {
    return `[${value.map(canonicalValue).sort().join(',')}]`;
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value).sort();
    return `{${keys.map((k) => `${escapeString(k)}:${canonicalValue(value[k])}`).join(',')}}`;
  }
  throw new Error(`cannot encode ${typeof value}`);
}

/**
 * ECMAScript's own number text (§7.1.4). Integers within 2^53 print as plain decimals; larger
 * values are doubles and follow String() (which may use an exponent). The web validator rejects
 * unsafe JSON integers before they get here, so a decimal literal never silently loses digits.
 */
export function canonicalNumber(n) {
  if (!Number.isFinite(n)) throw new Error('non-finite number');
  return String(n);
}

const ESCAPES = {
  0x22: '\\"',
  0x5c: '\\\\',
  0x08: '\\b',
  0x09: '\\t',
  0x0a: '\\n',
  0x0c: '\\f',
  0x0d: '\\r',
};

export function escapeString(text) {
  let out = '"';
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    // An unpaired surrogate cannot survive UTF-8 in either runtime (TextEncoder writes U+FFFD,
    // Java a question mark), so both sides replace it here and hash the same bytes.
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const next = text.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        out += text[i] + text[i + 1];
        i += 1;
        continue;
      }
    }
    if (code >= 0xd800 && code <= 0xdfff) {
      out += '�';
      continue;
    }
    const fixed = ESCAPES[code];
    if (fixed) out += fixed;
    else if (code < 0x20) out += `\\u${code.toString(16).padStart(4, '0')}`;
    else out += text[i];
  }
  return out + '"';
}
