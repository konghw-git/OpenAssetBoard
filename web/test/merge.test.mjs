import test from 'node:test';
import assert from 'node:assert/strict';
import { contentHash, rowKey, rowKeyFields } from '../public/canonical.js';
import { merge } from '../public/merge.js';

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
const hash = async (data) => contentHash(data);

test('the content winner and the state winner are picked separately', async () => {
  const a = blank();
  a.notes = [row({ id: 'n', body: 'A', pinned: false, reviewedAt: null, changedAt: 10, changeId: 'a', stateAt: 5, stateId: 'sa' })];
  const b = blank();
  b.notes = [row({ id: 'n', body: 'B', pinned: true, reviewedAt: 99, changedAt: 20, changeId: 'b', stateAt: 30, stateId: 'sb' })];
  const { data } = await merge(a, b);
  const note = data.notes[0];
  assert.equal(note.body, 'B');
  assert.equal(note.pinned, true);
  assert.equal(note.reviewedAt, 99);
  assert.equal(note.stateId, 'sb');
});

test('a tombstone wins a version tie and loses to a newer edit', async () => {
  const a = blank();
  a.countdowns = [row({
    id: 'c', title: '生日', date: '2026-01-01', yearly: false, pinned: false, createdAt: 1, updatedAt: 1,
    changedAt: 10, changeId: 'edit', stateAt: 0, stateId: '',
  })];
  a.tombstones = [{ table: 'countdowns', rowKey: 'countdowns#c', deletedAt: 10, changeId: 'T:del' }];
  const dead = await merge(a, blank());
  assert.equal(dead.data.countdowns.length, 0);

  a.countdowns[0].changedAt = 20;
  const alive = await merge(a, blank());
  assert.equal(alive.data.countdowns.length, 1);
  assert.equal(alive.data.tombstones.length, 0);
});

test('a diary date collision keeps the loser as a deterministic draft', async () => {
  const a = blank();
  a.diaryEntries = [row({ id: 'd1', date: '2026-10-20', body: '手机写的', changedAt: 10, changeId: 'a' })];
  const b = blank();
  b.diaryEntries = [row({ id: 'd2', date: '2026-10-20', body: '网页写的', changedAt: 20, changeId: 'b' })];
  const ab = await merge(a, b);
  const ba = await merge(b, a);
  assert.equal(ab.data.diaryEntries.length, 1);
  assert.equal(ab.data.diaryEntries[0].id, 'd2');
  assert.equal(ab.data.diaryDrafts.length, 1);
  assert.equal(ab.data.diaryDrafts[0].text, '手机写的');
  assert.equal(ab.data.diaryDrafts[0].reason, '同步合并保留败者版本');
  assert.equal(await hash(ab.data), await hash(ba.data));
  assert.deepEqual(ab.adjustments, ba.adjustments);
});

test('a same-row conflict archives the loser text', async () => {
  const a = blank();
  a.notes = [row({
    id: 'n', body: '旧', topicId: null, pinned: false, reviewedAt: null, createdAt: 1, updatedAt: 1,
    deletedAt: null, deleteBatch: null, position: 0, bodyRev: 0, changedAt: 10, changeId: 'a', stateAt: 0, stateId: '',
  })];
  const b = blank();
  b.notes = [{ ...a.notes[0], body: '新', changedAt: 20, changeId: 'b', stateAt: 30, stateId: 'sb' }];
  const { data } = await merge(a, b);
  assert.equal(data.notes[0].body, '新');
  assert.equal(data.noteDrafts.length, 1);
  assert.equal(data.noteDrafts[0].text, '旧');
  assert.equal(data.noteDrafts[0].noteId, 'n');
  assert.equal(data.notes[0].stateId, 'sb');
});

test('a duplicate stocktake takes its items and its own valuations with it', async () => {
  const a = blank();
  a.groups = [row({ id: 'g', name: 'G', isLiability: false, colorIndex: 0, sortOrder: 0 })];
  a.accounts = [row({
    id: 'acct', groupId: 'g', name: '银行', kind: 'NORMAL', baselineAt: 0, createdAt: 1,
    includeInTotal: true, isDisabled: false, baselineCost: null,
  })];
  a.snapshots = [row({ id: 's1', at: 1000, changedAt: 10, changeId: 'a' })];
  a.valuations = [
    row({ id: 'v1', accountId: 'acct', at: 1000, value: 1, snapshotId: 's1', changedAt: 10, changeId: 'a' }),
    row({ id: 'v-adopt', accountId: 'acct', at: 500, value: 1, snapshotId: null, changedAt: 10, changeId: 'a' }),
  ];
  a.snapshotItems = [row({ snapshotId: 's1', accountId: 'acct', valuationId: 'v1', included: true, changedAt: 10, changeId: 'a' })];
  const b = blank();
  b.groups = a.groups.map((g) => ({ ...g }));
  b.accounts = a.accounts.map((x) => ({ ...x }));
  b.snapshots = [row({ id: 's2', at: 1000, changedAt: 20, changeId: 'b' })];
  b.valuations = [row({ id: 'v2', accountId: 'acct', at: 1000, value: 2, snapshotId: 's2', changedAt: 20, changeId: 'b' })];
  b.snapshotItems = [row({ snapshotId: 's2', accountId: 'acct', valuationId: 'v2', included: true, changedAt: 20, changeId: 'b' })];
  const { data } = await merge(a, b);
  assert.deepEqual(data.snapshots.map((s) => s.id), ['s2']);
  assert.deepEqual(data.valuations.map((v) => v.id).sort(), ['v-adopt', 'v2']);
  assert.deepEqual(data.snapshotItems.map((i) => i.snapshotId), ['s2']);
});

test('habits stay between one and seven active', async () => {
  const a = blank();
  a.habits = Array.from({ length: 8 }, (_, i) => row({
    key: `h${i}`, label: `H${i}`, icon: 'fitness', color: 0, position: i, addedOn: '2026-01-01', removedOn: null,
    changedAt: 10 + i, changeId: `h${i}`,
  }));
  const over = await merge(a, blank());
  assert.equal(over.data.habits.filter((h) => h.removedOn == null).length, 7);
  assert.equal(over.data.habits.filter((h) => h.removedOn != null).length, 1);

  const none = blank();
  none.habits = a.habits.map((h, i) => ({ ...h, removedOn: `2026-02-0${i + 1}`, changedAt: 100 + i }));
  const revived = await merge(none, blank());
  assert.equal(revived.data.habits.filter((h) => h.removedOn == null).length, 1);
});

test('one pinned countdown, the state-newest', async () => {
  const a = blank();
  a.countdowns = [
    row({ id: '1', title: 'A', date: '2026-01-01', pinned: true, changedAt: 1, changeId: 'a', stateAt: 5, stateId: 'sa' }),
    row({ id: '2', title: 'B', date: '2026-02-02', pinned: true, changedAt: 1, changeId: 'b', stateAt: 9, stateId: 'sb' }),
  ];
  const { data } = await merge(a, blank());
  assert.equal(data.countdowns.find((c) => c.id === '1').pinned, false);
  assert.equal(data.countdowns.find((c) => c.id === '2').pinned, true);
});

test('a section from another list is cleared', async () => {
  const a = blank();
  a.todoLists = [row({ id: 'l1', name: 'L1', notes: '', sortOrder: 0, createdAt: 1, deletedAt: null, deleteBatch: null })];
  a.todoSections = [row({ id: 's1', listId: 'l1', name: 'S1', sortOrder: 0, deletedAt: null, deleteBatch: null })];
  a.todos = [row({ id: 't1', kind: 'ORDINARY', title: 'T', listId: null, sectionId: 's1', createdAt: 1, deletedAt: null, deleteBatch: null })];
  const { data } = await merge(a, blank());
  assert.equal(data.todos[0].sectionId, null);
});

test('the baseline moves before an earlier flow, and the baseline valuation follows', async () => {
  const a = blank();
  a.groups = [row({ id: 'g', name: 'G', isLiability: false, colorIndex: 0, sortOrder: 0 })];
  a.accounts = [row({
    id: 'acct', groupId: 'g', name: '银行', kind: 'NORMAL', baselineAt: 1000, createdAt: 1,
    includeInTotal: true, isDisabled: false, baselineCost: null,
  })];
  a.valuations = [row({ id: 'v', accountId: 'acct', at: 1000, value: 10, snapshotId: null, createdAt: 1 })];
  a.flows = [row({ id: 'f', kind: 'EXPENSE', fromAccountId: 'acct', toAccountId: null, amount: 1, fee: 0, feeOn: 'FROM', at: 500, createdAt: 1 })];
  const { data } = await merge(a, blank());
  assert.ok(data.accounts[0].baselineAt < 500);
  assert.equal(data.valuations[0].at, data.accounts[0].baselineAt);
});

test('a newer child resurrects a hard-deleted group', async () => {
  const a = blank();
  a.groups = [row({ id: 'g', name: '现金', isLiability: false, colorIndex: 0, sortOrder: 0, changedAt: 100, changeId: 'new' })];
  a.accounts = [row({
    id: 'acct', groupId: 'g', name: '银行', kind: 'NORMAL', baselineAt: 0, createdAt: 1, changedAt: 50, changeId: 'old',
    includeInTotal: true, isDisabled: false, baselineCost: null,
  })];
  const b = blank();
  b.tombstones = [{ table: 'groups', rowKey: 'groups#g', deletedAt: 40, changeId: 'T:del' }];
  const { data } = await merge(a, b);
  assert.equal(data.groups.length, 1);
  assert.equal(data.groups[0].id, 'g');
  assert.equal(data.tombstones.length, 0);
});

test('commutative, idempotent and deterministic', async () => {
  const a = blank();
  a.groups = [row({ id: 'g', name: 'G', isLiability: false, colorIndex: 0, sortOrder: 0 })];
  a.accounts = [row({
    id: 'acct', groupId: 'g', name: '银行', kind: 'NORMAL', baselineAt: 1000, createdAt: 1, changedAt: 10, changeId: 'a',
    includeInTotal: true, isDisabled: false, baselineCost: null,
  })];
  a.todos = [row({
    id: 't', kind: 'ORDINARY', title: 'A', listId: null, sectionId: null, createdAt: 1, changedAt: 10, changeId: 'a',
    deletedAt: null, deleteBatch: null, revision: 0, stateSeq: 0, occurrenceCount: 0,
  })];
  const b = blank();
  b.groups = a.groups.map((g) => ({ ...g }));
  b.accounts = a.accounts.map((x) => ({ ...x, name: '招行', changedAt: 20, changeId: 'b' }));
  b.todos = a.todos.map((x) => ({ ...x, title: 'B', changedAt: 20, changeId: 'b' }));
  b.tombstones = [{ table: 'flows', rowKey: 'flows#gone', deletedAt: 5, changeId: 'T:x' }];
  const ab = await merge(a, b);
  const ba = await merge(b, a);
  assert.equal(await hash(ab.data), await hash(ba.data));
  assert.deepEqual(ab.adjustments, ba.adjustments);
  const aa = await merge(a, a);
  assert.equal(await hash(aa.data), await hash({ ...a, exportedAt: a.exportedAt }));
});
