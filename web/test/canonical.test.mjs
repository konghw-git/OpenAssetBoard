import test from 'node:test';
import assert from 'node:assert/strict';
import { canonicalJson, canonicalNumber, contentHash, rowKey } from '../public/canonical.js';

function file(rows = {}) {
  return {
    formatVersion: 16,
    schemaVersion: 15,
    exportedAt: 1792468800000,
    ...Object.fromEntries(
      ['groups', 'accounts', 'snapshots', 'valuations', 'snapshotItems', 'flows', 'todoLists', 'todoSections', 'todos',
        'checklistItems', 'diaryEntries', 'diaryDrafts', 'habitChecks', 'habits', 'bodyMeasurements', 'foodEntries',
        'noteTopics', 'notes', 'noteSources', 'noteDrafts', 'weeklyReviews', 'weeklyDrafts', 'countdowns'].map((t) => [t, []]),
    ),
    healthProfile: null,
    netWorthGoal: null,
    netWorthGoalAt: 0,
    netWorthGoalChangeId: '',
    tombstones: [],
    ...rows,
  };
}

test('object keys sort by UTF-16 code units and exportedAt is zeroed', () => {
  assert.equal(canonicalJson({ b: 1, a: 2, exportedAt: 999 }), '{"a":2,"b":1,"exportedAt":0}');
});

test('numbers use ECMAScript text; integral doubles collapse to integers', () => {
  assert.equal(canonicalNumber(1.0), '1');
  assert.equal(canonicalNumber(31.2), '31.2');
  assert.equal(canonicalNumber(0.0001), '0.0001');
  assert.equal(canonicalNumber(1e21), '1e+21');
  assert.equal(canonicalNumber(-2.5e-7), '-2.5e-7');
  assert.equal(canonicalNumber(-0), '0');
  assert.equal(canonicalNumber(9007199254740992), '9007199254740992');
});

test('strings escape only the fixed table', () => {
  assert.equal(canonicalJson({ s: 'a"b\\c\n\t\b\f\r\u0001中 ' }), '{"s":"a\\"b\\\\c\\n\\t\\b\\f\\r\\u0001中 "}');
});

test('row arrays sort by the canonical row key', () => {
  const text = canonicalJson(file({ todos: [
    { id: 'b', changedAt: 1, changeId: 'x' },
    { id: 'a', changedAt: 1, changeId: 'y' },
  ] }));
  assert.ok(text.indexOf('"id":"a"') < text.indexOf('"id":"b"'));
});

test('row keys escape %, | and #', () => {
  assert.equal(rowKey('noteSources', { noteId: 'a|b#c%d', kind: 'DAY', ref: '2026-01-01' }), 'noteSources#a%7Cb%23c%25d|DAY|2026-01-01');
  assert.equal(rowKey('habitChecks', { date: '2026-11-05', habit: 'read' }), 'habitChecks#2026-11-05|read');
});

test('the hash is SHA-256 of the canonical bytes', async () => {
  // printf '{"a":1}' | sha256sum
  assert.equal(await contentHash({ a: 1 }), '015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862');
});

test('tombstones sort by row key too', () => {
  const text = canonicalJson(file({ tombstones: [
    { table: 'flows', rowKey: 'flows#b', deletedAt: 1, changeId: 'T:2' },
    { table: 'flows', rowKey: 'flows#a', deletedAt: 2, changeId: 'T:1' },
  ] }));
  assert.ok(text.indexOf('flows#a') < text.indexOf('flows#b'));
});
