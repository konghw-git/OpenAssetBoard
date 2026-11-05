// Regenerates fixtures/sync from the web reference implementation.
//
// The fixtures are the cross-language contract: web/test/canonical.test.mjs and
// web/test/merge-fixtures.test.mjs read them, and the Kotlin tests in
// app/src/test/java/com/assetboard/data/sync read the same files. Run this only when the
// semantics change, and review the diff: nothing here may be derived from a device clock.

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonicalJson, contentHash, rowKeyFields } from '../public/canonical.js';
import { merge } from '../public/merge.js';
import { rng, side } from '../test/helpers/random-side.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, '../../fixtures/sync');
mkdirSync(outDir, { recursive: true });

// ------------------------------------------------------------------ canonical cases

const canonicalInputs = [
  { name: 'object keys sort by UTF-16 code unit', text: '{"b":1,"a":2,"exportedAt":999}' },
  { name: 'integral doubles collapse', text: '{"n":1.0}' },
  { name: 'exponent literals normalize', text: '{"n":1e2}' },
  { name: 'negative zero', text: '{"n":-0.0}' },
  { name: 'large exponent', text: '{"n":1e21}' },
  { name: 'small exponent', text: '{"n":2.5e-7}' },
  { name: 'decimal notation', text: '{"n":0.0001}' },
  { name: 'trailing zero literal', text: '{"n":31.20}' },
  { name: 'shortest double', text: '{"n":0.30000000000000004}' },
  { name: 'unsafe integer literal', text: '{"n":9007199254740993}' },
  { name: 'huge integer literal', text: '{"n":123456789012345678901234567890}' },
  { name: 'int64 timestamp', text: '{"n":1792468800000}' },
  { name: 'strings escape a fixed table', text: '{"s":"a\\"b\\\\c\\n\\t\\b\\f\\r\\u0001中 🎯"}' },
  { name: 'unpaired surrogates become U+FFFD', text: '{"s":"\\ud800x\\udc00"}' },
  { name: 'nested objects and plain arrays', text: '{"z":{"b":[3,1,2],"a":true},"a":[{"y":1,"x":2}]}' },
  {
    name: 'rows sort by row key',
    text: '{"todos":[{"id":"b","changedAt":1,"changeId":"x"},{"id":"a","changedAt":1,"changeId":"y"}]}',
  },
  {
    name: 'row keys escape % | #',
    text: '{"noteSources":[{"noteId":"a|b#c%d","kind":"DAY","ref":"2026-01-01","changedAt":1,"changeId":"a"}]}',
  },
  {
    name: 'tombstones sort by row key and exportedAt is zeroed',
    text: '{"exportedAt":5,"tombstones":[{"table":"flows","rowKey":"flows#b","deletedAt":1,"changeId":"T:2"},{"table":"flows","rowKey":"flows#a","deletedAt":2,"changeId":"T:1"}]}',
  },
  { name: 'empty file shape', text: '{}' },
];

const canonicalCases = [];
for (const input of canonicalInputs) {
  const parsed = JSON.parse(input.text);
  canonicalCases.push({
    name: input.name,
    inputText: input.text,
    canonical: canonicalJson(parsed),
    sha256: await contentHash(parsed),
  });
}

writeFileSync(
  resolve(outDir, 'canonical-cases.json'),
  `${JSON.stringify({ generatedBy: 'web/scripts/gen-sync-fixtures.mjs', cases: canonicalCases }, null, 2)}\n`,
);

// ------------------------------------------------------------------ merge cases

const blank = () => ({
  formatVersion: 16,
  schemaVersion: 15,
  exportedAt: 0,
  ...Object.fromEntries(Object.keys(rowKeyFields).map((t) => [t, []])),
  healthProfile: null,
  netWorthGoal: null,
  netWorthGoalAt: 0,
  netWorthGoalChangeId: '',
  tombstones: [],
});

const row = (fields) => ({ changedAt: 10, changeId: 'c1', ...fields });

const group = (fields) => row({ id: 'g', name: 'G', isLiability: false, colorIndex: 0, sortOrder: 0, ...fields });
const account = (fields) => row({
  id: 'acct', groupId: 'g', name: '银行', kind: 'NORMAL', baselineAt: 0, createdAt: 1,
  includeInTotal: true, isDisabled: false, baselineCost: null, ...fields,
});
const todo = (fields) => row({
  id: 't', kind: 'ORDINARY', title: 'T', listId: null, sectionId: null, createdAt: 1,
  deletedAt: null, deleteBatch: null, revision: 0, stateSeq: 0, occurrenceCount: 0, ...fields,
});
const note = (fields) => row({
  id: 'n', topicId: null, body: '', bodyRev: 0, pinned: false, position: 0, createdAt: 1,
  updatedAt: 1, reviewedAt: null, deletedAt: null, deleteBatch: null, stateAt: 0, stateId: '', ...fields,
});
const countdown = (fields) => row({
  id: 'c', title: '生日', date: '2026-01-01', yearly: false, pinned: false, createdAt: 1, updatedAt: 1,
  stateAt: 0, stateId: '', ...fields,
});

const cases = [];

async function add(name, a, b) {
  const { data, adjustments } = await merge(a, b);
  const canonical = canonicalJson(data);
  const reverse = canonicalJson((await merge(b, a)).data);
  if (canonical !== reverse) throw new Error(`${name}: merge is not commutative`);
  const twice = canonicalJson((await merge(data, data)).data);
  if (twice !== canonical) throw new Error(`${name}: merge is not idempotent`);
  // The result itself is the canonical text: it is valid JSON, so both test suites parse it for
  // the idempotence check instead of the fixture carrying a third copy of the file.
  cases.push({
    name,
    a,
    b,
    resultCanonical: canonical,
    resultHash: await contentHash(data),
    adjustments,
  });
}

{
  const a = blank();
  a.notes = [note({ body: 'A', changedAt: 10, changeId: 'a', stateAt: 50, stateId: 'sa' })];
  const b = blank();
  b.notes = [note({ body: 'B', pinned: true, reviewedAt: 99, changedAt: 20, changeId: 'b', stateAt: 30, stateId: 'sb' })];
  await add('content and state winners are picked separately', a, b);
}

{
  const a = blank();
  a.countdowns = [countdown({ changedAt: 10, changeId: 'edit' })];
  a.tombstones = [{ table: 'countdowns', rowKey: 'countdowns#c', deletedAt: 10, changeId: 'T:del' }];
  await add('a tombstone wins a version tie', a, blank());
}

{
  const a = blank();
  a.countdowns = [countdown({ changedAt: 20, changeId: 'edit' })];
  a.tombstones = [{ table: 'countdowns', rowKey: 'countdowns#c', deletedAt: 10, changeId: 'T:del' }];
  await add('an edit newer than the tombstone revives the row', a, blank());
}

{
  const a = blank();
  a.diaryEntries = [row({
    id: 'd', date: '2026-10-20', body: '手机写的', bodyRev: 0, createdAt: 1, updatedAt: 1,
    deletedAt: null, deleteBatch: null, mood: null,
  })];
  const b = blank();
  b.diaryEntries = [{ ...a.diaryEntries[0], body: '网页写的', changedAt: 20, changeId: 'b' }];
  await add('a same-id diary conflict keeps the loser as a draft', a, b);
}

{
  const a = blank();
  a.diaryEntries = [row({
    id: 'd1', date: '2026-10-20', body: '手机写的', createdAt: 1, updatedAt: 1, deletedAt: null, deleteBatch: null, mood: null,
  })];
  const b = blank();
  b.diaryEntries = [row({
    id: 'd2', date: '2026-10-20', body: '网页写的', changedAt: 20, changeId: 'b',
    createdAt: 1, updatedAt: 1, deletedAt: null, deleteBatch: null, mood: null,
  })];
  await add('a diary date collision dedups and archives the loser', a, b);
}

{
  const a = blank();
  a.groups = [group()];
  a.accounts = [account()];
  a.snapshots = [row({ id: 's1', at: 1000, changedAt: 10, changeId: 'a' })];
  a.valuations = [
    row({ id: 'v1', accountId: 'acct', at: 1000, value: 1, snapshotId: 's1', changedAt: 10, changeId: 'a' }),
    row({ id: 'v-adopt', accountId: 'acct', at: 500, value: 1, snapshotId: null, changedAt: 10, changeId: 'a' }),
  ];
  a.snapshotItems = [row({ snapshotId: 's1', accountId: 'acct', valuationId: 'v1', included: true, changedAt: 10, changeId: 'a' })];
  const b = blank();
  b.groups = a.groups.map((x) => ({ ...x }));
  b.accounts = a.accounts.map((x) => ({ ...x }));
  b.snapshots = [row({ id: 's2', at: 1000, changedAt: 20, changeId: 'b' })];
  b.valuations = [row({ id: 'v2', accountId: 'acct', at: 1000, value: 2, snapshotId: 's2', changedAt: 20, changeId: 'b' })];
  b.snapshotItems = [row({ snapshotId: 's2', accountId: 'acct', valuationId: 'v2', included: true, changedAt: 20, changeId: 'b' })];
  await add('a duplicate stocktake takes its items and its own valuations', a, b);
}

{
  const a = blank();
  a.countdowns = [
    countdown({ id: '1', title: 'A', pinned: true, changedAt: 1, changeId: 'a', stateAt: 5, stateId: 'sa' }),
    countdown({ id: '2', title: 'B', pinned: true, changedAt: 1, changeId: 'b', stateAt: 9, stateId: 'sb' }),
  ];
  await add('only the state-newest countdown stays pinned', a, blank());
}

{
  const a = blank();
  a.habits = Array.from({ length: 8 }, (_, i) => row({
    key: `h${i}`, label: `H${i}`, icon: 'fitness', color: 0, position: i, addedOn: '2026-01-01', removedOn: null,
    changedAt: 10 + i, changeId: `h${i}`,
  }));
  await add('more than seven active habits are disabled, newest kept', a, blank());
}

{
  const a = blank();
  a.habits = [row({
    key: 'h0', label: 'H0', icon: 'fitness', color: 0, position: 0, addedOn: '2026-01-01',
    removedOn: '2026-02-01', changedAt: 10, changeId: 'h0',
  })];
  await add('zero active habits revives the newest one', a, blank());
}

{
  const a = blank();
  a.todoLists = [row({ id: 'l1', name: 'L1', notes: '', sortOrder: 0, createdAt: 1, deletedAt: null, deleteBatch: null })];
  a.todoSections = [row({ id: 's1', listId: 'l1', name: 'S1', sortOrder: 0, deletedAt: null, deleteBatch: null })];
  a.todos = [todo({ listId: 'l1', sectionId: 's1' })];
  const b = blank();
  b.todoLists = a.todoLists.map((x) => ({ ...x }));
  b.todoSections = [row({ id: 's2', listId: 'l1', name: 'S2', sortOrder: 1, deletedAt: null, deleteBatch: null })];
  await add('a section from another list is cleared', a, b);
}

{
  const a = blank();
  a.groups = [group()];
  a.accounts = [account({ baselineAt: 1000 })];
  a.valuations = [
    row({ id: 'v-base', accountId: 'acct', at: 1000, value: 10, snapshotId: null, createdAt: 1 }),
    row({ id: 'v-900', accountId: 'acct', at: 900, value: 9, snapshotId: null, createdAt: 1 }),
  ];
  a.flows = [
    row({ id: 'f1', kind: 'EXPENSE', fromAccountId: 'acct', toAccountId: null, amount: 1, fee: 0, feeOn: 'FROM', at: 500, createdAt: 1 }),
    row({ id: 'f2', kind: 'EXPENSE', fromAccountId: 'acct', toAccountId: null, amount: 1, fee: 0, feeOn: 'FROM', at: 900, createdAt: 1 }),
  ];
  await add('the baseline moves before an earlier flow and a same-instant flow is reported', a, blank());
}

{
  const a = blank();
  a.groups = [group({ name: 'Ga', changedAt: 100, changeId: 'a' })];
  a.accounts = [account({ changedAt: 130, changeId: 'a' })];
  const b = blank();
  b.groups = [group({ name: 'Gb', changedAt: 90, changeId: 'b' })];
  b.tombstones = [{ table: 'groups', rowKey: 'groups#g', deletedAt: 120, changeId: 'T:del' }];
  await add('a newer child resurrects the content winner of a deleted group', a, b);
}

{
  const a = blank();
  a.groups = [group({ changedAt: 40, changeId: 'a' })];
  a.accounts = [account({ changedAt: 100, changeId: 'a' })];
  a.valuations = [row({ id: 'v', accountId: 'acct', at: 90, value: 1, snapshotId: null, createdAt: 1 })];
  a.flows = [row({ id: 'f', kind: 'EXPENSE', fromAccountId: 'acct', toAccountId: null, amount: 1, fee: 0, feeOn: 'FROM', at: 80, createdAt: 1 })];
  const b = blank();
  b.tombstones = [{ table: 'groups', rowKey: 'groups#g', deletedAt: 200, changeId: 'T:del' }];
  await add('a deleted group takes its accounts and their history with it', a, b);
}

{
  const a = blank();
  a.todoLists = [row({ id: 'l1', name: 'L1', notes: '', sortOrder: 0, createdAt: 1, deletedAt: null, deleteBatch: null })];
  a.todoSections = [row({ id: 's1', listId: 'l1', name: 'S1', sortOrder: 0, deletedAt: null, deleteBatch: null })];
  a.todos = [todo({ listId: 'l1', sectionId: 's1' })];
  const b = blank();
  b.tombstones = [{ table: 'todoLists', rowKey: 'todoLists#l1', deletedAt: 100, changeId: 'T:purge' }];
  await add('a purged list leaves its todos in the inbox and drops its sections', a, b);
}

{
  const a = blank();
  a.noteTopics = [
    row({ id: 't1', name: '工作', createdAt: 1, changedAt: 10, changeId: 'a' }),
    row({ id: 't2', name: '工作', createdAt: 1, changedAt: 20, changeId: 'b' }),
  ];
  a.notes = [note({ topicId: 't1', changedAt: 5, changeId: 'a' })];
  await add('a duplicate topic re-files its notes on the winner', a, blank());
}

{
  const a = blank();
  a.notes = [note()];
  a.noteSources = [
    row({ noteId: 'n', kind: 'DAY', ref: '2026-10-20', changedAt: 10, changeId: 'a' }),
    row({ noteId: 'gone', kind: 'DAY', ref: '2026-10-21', changedAt: 10, changeId: 'a' }),
  ];
  await add('a note source without its note is dropped', a, blank());
}

{
  const a = blank();
  a.todos = [
    todo({ id: 'done1', kind: 'COMPLETION', repeatSourceId: 'r', planDate: '2026-10-20', changedAt: 10, changeId: 'a' }),
    todo({ id: 'done2', kind: 'COMPLETION', repeatSourceId: 'r', planDate: '2026-10-20', changedAt: 20, changeId: 'b' }),
  ];
  a.checklistItems = [row({ id: 'ci1', todoId: 'done1', text: 'x', done: true, changedAt: 10, changeId: 'a' })];
  await add('a duplicate completion keeps the newer and takes its checklist', a, blank());
}

{
  const a = blank();
  a.todos = [
    todo({ id: 't', changedAt: 40, changeId: 'a' }),
    todo({ id: 'c', kind: 'COMPLETION', repeatSourceId: 't', planDate: '2026-10-20', changedAt: 60, changeId: 'a' }),
  ];
  a.checklistItems = [row({ id: 'ci', todoId: 't', text: 'x', done: true, changedAt: 60, changeId: 'a' })];
  const b = blank();
  b.tombstones = [{ table: 'todos', rowKey: 'todos#t', deletedAt: 50, changeId: 'T:del' }];
  await add('a newer checklist item revives its todo and keeps the completion link', a, b);
}

{
  const a = blank();
  a.todos = [
    todo({ id: 't', changedAt: 40, changeId: 'a' }),
    todo({ id: 'c', kind: 'COMPLETION', repeatSourceId: 't', planDate: '2026-10-20', changedAt: 60, changeId: 'a' }),
  ];
  const b = blank();
  b.tombstones = [{ table: 'todos', rowKey: 'todos#t', deletedAt: 50, changeId: 'T:del' }];
  await add('a deleted repeat source turns its completion into an ordinary todo', a, b);
}

{
  const a = blank();
  a.notes = [note({ id: 'n', body: '旧', changedAt: 10, changeId: 'x'.repeat(200) })];
  const b = blank();
  b.notes = [note({ id: 'n', body: '新', changedAt: 20, changeId: 'b' })];
  await add('a long archive seed hashes its draft id', a, b);
}

{
  const a = blank();
  a.noteDrafts = [row({ id: 'd', noteId: 'gone', text: '草稿', reason: '同步合并保留败者版本', createdAt: 1 })];
  await add('a draft whose note is gone loses the link but keeps the text', a, blank());
}

{
  const a = blank();
  a.habitChecks = [
    row({ date: '2026-10-20', habit: 'water', checkedAt: 1, skipped: false, changedAt: 10, changeId: 'a' }),
    row({ date: '2026-10-20', habit: 'unknown', checkedAt: 1, skipped: false, changedAt: 10, changeId: 'a' }),
  ];
  await add('a habit check for no known habit is dropped', a, blank());
}

{
  const a = blank();
  a.netWorthGoal = 100000;
  a.netWorthGoalAt = 10;
  a.netWorthGoalChangeId = 'a';
  a.healthProfile = { height: 170, changedAt: 10, changeId: 'a' };
  const b = blank();
  b.netWorthGoal = 200000;
  b.netWorthGoalAt = 20;
  b.netWorthGoalChangeId = 'b';
  b.healthProfile = { height: 180, changedAt: 20, changeId: 'b' };
  await add('the goal and the health profile follow their own stamps', a, b);
}

{
  const a = blank(), b = blank();
  a.formatVersion = b.formatVersion = 17;
  a.tombstones = [{ table: 'healthProfile', rowKey: 'healthProfile#self', deletedAt: 20, changeId: 'T:delete' }];
  b.healthProfile = { height: 170, changedAt: 10, changeId: 'old' };
  await add('health profile deletion beats an older profile', a, b);
  const newer = structuredClone(b);
  newer.healthProfile = { height: 180, changedAt: 21, changeId: 'new' };
  await add('newer health profile clears its tombstone', a, newer);
}
await add('two empty files merge to an empty file', blank(), blank());

// Tricky combinations (ties, repair/tombstone kinds, dangling references, duplicate keys) that
// the hand-written cases do not enumerate; both languages must still agree byte for byte.
for (let seed = 1; seed <= 5; seed += 1) {
  const random = rng(seed);
  await add(`random inputs, seed ${seed}`, side(random), side(random));
}

writeFileSync(
  resolve(outDir, 'merge-cases.json'),
  `${JSON.stringify({ generatedBy: 'web/scripts/gen-sync-fixtures.mjs', cases }, null, 2)}\n`,
);

console.log(`wrote ${canonicalCases.length} canonical cases and ${cases.length} merge cases`);
