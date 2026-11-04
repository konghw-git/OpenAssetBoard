// Deterministic random BackupFile-shaped inputs for the merge property test. Kept out of the
// test file so a failing seed can be replayed from a scratch script.

import { rowKeyFields } from '../../public/canonical.js';

export const rng = (seed) => {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
};

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

const body = (rand) => (rand() < 0.5 ? '手机' : '网页');

export function side(rand) {
  const pick = (items) => items[Math.floor(rand() * items.length)];
  const id = (prefix, n) => `${prefix}${Math.floor(rand() * n)}`;
  const at = () => Math.floor(rand() * 5) * 10;
  const stamp = () => ({ changedAt: at(), changeId: pick(['a', 'b', 'c', 'R:repair', 'T:odd', 'legacy:x']) });
  const maybe = (value) => (rand() < 0.4 ? value : null);
  const out = blank();

  for (let i = 0; i < 2; i += 1) out.groups.push({ id: `g${i}`, name: pick(['现金', '投资']), isLiability: false, colorIndex: 0, sortOrder: i, ...stamp() });
  for (let i = 0; i < 3; i += 1) {
    out.accounts.push({
      id: `a${i}`, groupId: id('g', 2), name: '银行', kind: 'NORMAL', baselineAt: at(), createdAt: 1,
      includeInTotal: true, isDisabled: false, baselineCost: null, ...stamp(),
    });
  }
  for (let i = 0; i < 4; i += 1) {
    out.flows.push({
      id: `f${i}`, kind: 'EXPENSE', fromAccountId: id('a', 3), toAccountId: maybe(id('a', 3)),
      amount: 1, fee: 0, feeOn: 'FROM', at: at(), createdAt: 1, ...stamp(),
    });
  }
  for (let i = 0; i < 2; i += 1) out.snapshots.push({ id: `s${i}`, at: at(), ...stamp() });
  for (let i = 0; i < 3; i += 1) {
    out.valuations.push({ id: `v${i}`, accountId: id('a', 3), at: at(), value: i, snapshotId: null, createdAt: 1, ...stamp() });
  }
  for (let i = 0; i < 2; i += 1) {
    out.snapshotItems.push({
      snapshotId: id('s', 2), accountId: id('a', 3), valuationId: id('v', 3), included: true, ...stamp(),
    });
  }
  for (let i = 0; i < 2; i += 1) out.todoLists.push({ id: `l${i}`, name: 'L', notes: '', sortOrder: i, createdAt: 1, deletedAt: null, deleteBatch: null, ...stamp() });
  for (let i = 0; i < 2; i += 1) out.todoSections.push({ id: `sec${i}`, listId: id('l', 2), name: 'S', sortOrder: i, deletedAt: null, deleteBatch: null, ...stamp() });
  for (let i = 0; i < 4; i += 1) {
    const completion = rand() < 0.3;
    out.todos.push({
      id: `t${i}`, kind: completion ? 'COMPLETION' : 'ORDINARY', title: 'T',
      listId: maybe(id('l', 2)), sectionId: maybe(id('sec', 2)),
      repeatSourceId: completion ? id('t', 4) : null, planDate: completion ? pick(['2026-10-20', '2026-10-21']) : null,
      createdAt: 1, deletedAt: null, deleteBatch: null, revision: 0, stateSeq: 0, occurrenceCount: 0,
      remindedPeriod: null, snoozeAt: null, stateAt: at(), stateId: pick(['sa', 'sb']), ...stamp(),
    });
  }
  for (let i = 0; i < 3; i += 1) out.checklistItems.push({ id: `ci${i}`, todoId: id('t', 4), text: 'x', done: rand() < 0.5, ...stamp() });
  for (let i = 0; i < 2; i += 1) out.noteTopics.push({ id: `topic${i}`, name: pick(['A', 'B']), createdAt: 1, ...stamp() });
  for (let i = 0; i < 3; i += 1) {
    out.notes.push({
      id: `n${i}`, topicId: maybe(id('topic', 2)), body: pick(['甲', '乙']), bodyRev: 0,
      pinned: rand() < 0.3, position: i, createdAt: 1, updatedAt: 1,
      reviewedAt: maybe(at()), deletedAt: null, deleteBatch: null,
      stateAt: at(), stateId: pick(['sa', 'sb']), ...stamp(),
    });
  }
  for (let i = 0; i < 2; i += 1) out.noteSources.push({ noteId: id('n', 3), kind: 'DAY', ref: pick(['2026-10-20', '2026-10-21']), ...stamp() });
  for (let i = 0; i < 2; i += 1) {
    out.diaryEntries.push({
      id: `d${i}`, date: pick(['2026-10-20', '2026-10-21']), body: body(rand), bodyRev: 0,
      weatherCode: null, tempMin: null, tempMax: null, city: null, lat: null, lon: null, weatherAt: null,
      weatherRev: 0, createdAt: 1, updatedAt: 1, deletedAt: null, deleteBatch: null, mood: null,
      stateAt: at(), stateId: 'sa', ...stamp(),
    });
  }
  for (let i = 0; i < 9; i += 1) {
    out.habits.push({
      key: `h${i}`, label: 'H', icon: 'fitness', color: 0, position: i, addedOn: '2026-01-01',
      removedOn: rand() < 0.3 ? '2026-02-01' : null, ...stamp(),
    });
  }
  for (let i = 0; i < 3; i += 1) out.habitChecks.push({ date: pick(['2026-10-20', '2026-10-21']), habit: id('h', 9), checkedAt: 1, skipped: false, ...stamp() });
  for (let i = 0; i < 2; i += 1) {
    out.countdowns.push({
      id: `c${i}`, title: 'C', date: '2026-01-01', yearly: false, pinned: rand() < 0.6,
      createdAt: 1, updatedAt: 1, stateAt: at(), stateId: pick(['sa', 'sb']), ...stamp(),
    });
  }
  const tombTables = ['groups', 'accounts', 'flows', 'valuations', 'todos', 'checklistItems', 'notes', 'diaryEntries', 'countdowns'];
  for (let i = 0; i < 2; i += 1) {
    const table = pick(tombTables);
    out.tombstones.push({ table, rowKey: `${table}#${pick(['g0', 'a0', 'f0', 'v0', 't0', 'ci0', 'n0', 'd0', 'c0'])}`, deletedAt: at(), changeId: 'T:gone' });
  }
  return out;
}
