import test from 'node:test';
import assert from 'node:assert/strict';
import * as D from '../public/domain.js';
import { merge } from '../public/merge.js';
import { rowKey, contentHash, canonicalJson } from '../public/canonical.js';
import { Store } from '../public/store.js';

function stocktakeBase() {
  const d = D.empty();
  d.groups.push(D.row('groups', { id: 'g', changedAt: 1, changeId: 'seed' }));
  d.accounts.push(D.row('accounts', { id: 'a', groupId: 'g', baselineAt: 100, changedAt: 1, changeId: 'seed' }));
  d.snapshots.push(D.row('snapshots', { id: 's', at: 200, changedAt: 10, changeId: 'created' }));
  d.valuations.push(D.row('valuations', { id: 'v', accountId: 'a', at: 200, value: 10000, snapshotId: 's', changedAt: 10, changeId: 'created' }));
  d.snapshotItems.push({ snapshotId: 's', accountId: 'a', valuationId: 'v', included: true, changedAt: 10, changeId: 'created' });
  return D.validate(d);
}

async function mergedStocktake(a, b) {
  D.validate(a); D.validate(b);
  const originals = [JSON.stringify(a), JSON.stringify(b)];
  const result = (await merge(a, b)).data;
  D.validate(result);
  const canonical = canonicalJson(result);
  assert.equal(canonicalJson((await merge(b, a)).data), canonical);
  assert.equal(canonicalJson((await merge(result, result)).data), canonical);
  for (const stale of [a, b]) assert.equal(canonicalJson((await merge(result, stale)).data), canonical);
  assert.deepEqual([JSON.stringify(a), JSON.stringify(b)], originals, 'inputs remain untouched');
  return result;
}

test('independent date and value edits must produce a valid stocktake', async () => {
  const a = stocktakeBase(), b = structuredClone(a);
  Object.assign(a.snapshots[0], { at: 300, changedAt: 20, changeId: 'date' });
  Object.assign(a.valuations[0], { at: 300, changedAt: 20, changeId: 'date' });
  Object.assign(b.valuations[0], { value: 20000, changedAt: 30, changeId: 'value' });
  const result = await mergedStocktake(a, b);
  assert.equal(result.snapshots[0].at, 200, 'the newer value edit takes its whole stocktake');
  assert.equal(result.valuations[0].value, 20000);
  assert.equal(result.snapshotItems.length, 1);
});

test('deleting a stocktake versus editing its value must not leave dangling references', async () => {
  const a = stocktakeBase(), b = structuredClone(a);
  for (const table of ['snapshots', 'valuations', 'snapshotItems']) {
    for (const row of a[table]) a.tombstones.push({ table, rowKey: rowKey(table, row), deletedAt: 20, changeId: 'T:delete' });
    a[table] = [];
  }
  Object.assign(b.valuations[0], { value: 20000, changedAt: 30, changeId: 'value' });
  const result = await mergedStocktake(a, b);
  assert.equal(result.snapshots.length, 1);
  assert.equal(result.valuations[0].value, 20000);
  assert.equal(result.snapshotItems.length, 1);
});

test('a stocktake delete orders the whole cascade even when child tombstones are older', async () => {
  const a = stocktakeBase(), b = structuredClone(a);
  let at = 20;
  for (const table of ['snapshotItems', 'valuations', 'snapshots']) {
    for (const row of a[table]) a.tombstones.push({ table, rowKey: rowKey(table, row), deletedAt: at++, changeId: 'T:delete' });
    a[table] = [];
  }
  Object.assign(b.valuations[0], { value: 20000, changedAt: 21, changeId: 'value' });
  const result = await mergedStocktake(a, b);
  assert.equal(result.snapshots.length, 0);
  assert.equal(result.valuations.length, 0);
  assert.equal(result.snapshotItems.length, 0);
});

test('a newer date edit takes the complete stocktake instead of mixing old values', async () => {
  const a = stocktakeBase(), b = structuredClone(a);
  Object.assign(a.snapshots[0], { at: 300, changedAt: 30, changeId: 'date' });
  Object.assign(a.valuations[0], { at: 300, changedAt: 30, changeId: 'date' });
  Object.assign(b.valuations[0], { value: 20000, changedAt: 20, changeId: 'value' });
  const result = await mergedStocktake(a, b);
  assert.equal(result.snapshots[0].at, 300);
  assert.equal(result.valuations[0].value, 10000);
});

test('defensive folding of repeated snapshot keys keeps the newest parent', async () => {
  const a = stocktakeBase(), b = structuredClone(a);
  Object.assign(a.snapshots[0], { changedAt: 30, changeId: 'new' });
  Object.assign(a.valuations[0], { changedAt: 30, changeId: 'new' });
  a.snapshots.push({ ...b.snapshots[0], at: 300, changedAt: 5, changeId: 'older-duplicate' });
  const result = (await merge(a, b)).data;
  D.validate(result);
  assert.equal(result.snapshots[0].at, 200);
});

test('discarding a conflict draft must survive another merge with the old note', async () => {
  const a = D.empty();
  a.notes.push(D.row('notes', { id: 'n', body: 'old', changedAt: 10, changeId: 'old' }));
  const b = structuredClone(a);
  Object.assign(b.notes[0], { body: 'new', changedAt: 20, changeId: 'new' });
  const first = (await merge(a, b)).data;
  const draft = first.noteDrafts[0];
  first.noteDrafts = [];
  first.tombstones.push({ table: 'noteDrafts', rowKey: rowKey('noteDrafts', draft), deletedAt: 30, changeId: 'T:discard' });
  assert.equal((await merge(first, a)).data.noteDrafts.length, 0);
});

for (const [table, drafts] of [['diaryEntries', 'diaryDrafts'], ['weeklyReviews', 'weeklyDrafts']]) {
  test(`natural-key dedup respects a discarded ${drafts} archive`, async () => {
    const a = D.empty(), b = D.empty();
    a[table].push(D.row(table, { id: 'old', body: 'old', changedAt: 10, changeId: 'old' }));
    b[table].push(D.row(table, { id: 'new', body: 'new', changedAt: 20, changeId: 'new' }));
    const draft = (await merge(a, b)).data[drafts][0];
    b.tombstones.push({ table: drafts, rowKey: rowKey(drafts, draft), deletedAt: 30, changeId: 'T:discard' });
    D.validate(a); D.validate(b);
    const result = (await merge(a, b)).data;
    D.validate(result);
    assert.equal(result[drafts].length, 0);
    assert.equal(result[table][0].body, 'new');
  });
}

test('adopting an existing valuation must preserve it when the stocktake is deleted', () => {
  const d = stocktakeBase();
  d.snapshots = []; d.snapshotItems = [];
  d.valuations[0].snapshotId = null;
  d.valuations.push(D.row('valuations', { id: 'baseline', accountId: 'a', at: 100, value: 5000 }));
  D.stocktake(d, { a: 10000 }, 200);
  D.validate(d);
  D.remove(d, 'snapshots', d.snapshots[0].id);
  D.validate(d);
  assert.ok(d.valuations.some(v => v.id === 'v'), 'pre-existing standalone valuation must survive');
});

test('profile edits enforce Android target-weight bounds and allow clearing the target', () => {
  for (const targetWeightG of [-1, 0, 1000, 19999, 300001]) {
    const d = D.empty();
    d.healthProfile = D.row('healthProfile', { targetWeightG });
    assert.throws(() => D.validate(d), /目标体重/);
  }
  for (const targetWeightG of [null, 20000, 300000]) {
    const d = D.empty();
    d.healthProfile = D.row('healthProfile', { targetWeightG });
    D.validate(d);
  }
});

for (const table of ['notes', 'diaryEntries', 'weeklyReviews']) test(`an open ${table} editor detects a merged remote body with the same bodyRev`, async () => {
  let clock = 1000;
  const remote = { revision: 1, data: D.empty() };
  remote.data[table].push(D.row(table, { id: 'n', body: 'base', bodyRev: 1, changedAt: 10, changeId: 'base' }));
  let tail = Promise.resolve();
  const request = async (path, opts = {}) => {
    if (path === '/sync/meta') return { value: { revision: remote.revision, formatVersion: 17, schemaVersion: 15, contentHash: await contentHash(remote.data) } };
    if (opts.method === 'PUT') {
      if (Number(opts.headers['If-Match']) !== remote.revision) throw Object.assign(Error('conflict'), { status: 409 });
      remote.data = JSON.parse(opts.body);
      return { value: { revision: ++remote.revision } };
    }
    return { text: JSON.stringify(remote.data), revision: remote.revision };
  };
  const store = new Store({ id: 'a' }, {
    request, now: () => clock, channel: null,
    lock: fn => { const next = tail.then(fn); tail = next.catch(() => {}); return next; },
  });
  try {
    await store.sync();
    const session = { table, id: 'n', rev: 1, baseBody: 'base', created: true, text: 'web',
      date: store.data[table][0].date, weekStart: store.data[table][0].weekStart };
    let saved;
    await store.change(d => { saved = D.saveDocument(d, session); });
    session.rev = saved.rev;
    session.baseBody = session.text;
    await store.flush();
    // The phone edited the same original rev=1 independently and also reached rev=2.
    Object.assign(remote.data[table][0], { body: 'phone', bodyRev: 2, changedAt: 2000, changeId: 'phone' });
    remote.revision++;
    await store.sync();
    assert.equal(store.data[table][0].body, 'phone');
    clock = 3000;
    await store.change(d => { saved = D.saveDocument(d, { ...session, text: 'web continued' }); });
    assert.equal(saved.conflict, true, 'stale editor must draft, not silently replace the phone body');
    assert.equal(store.data[table][0].body, 'phone');
    const drafts = { notes: 'noteDrafts', diaryEntries: 'diaryDrafts', weeklyReviews: 'weeklyDrafts' }[table];
    assert.ok(store.data[drafts].some(d => d.text === 'web continued'));
  } finally { store.close(); }
});

test('a text editor can keep saving after unrelated state updates', () => {
  const d = D.empty();
  d.notes.push(D.row('notes', { id: 'n', body: 'base', bodyRev: 1 }));
  const session = { table: 'notes', id: 'n', rev: 1, baseBody: 'base', created: true };
  for (const text of ['first edit', 'second edit']) {
    d.notes[0].pinned = !d.notes[0].pinned;
    const result = D.saveDocument(d, { ...session, text });
    assert.equal(result.conflict, false);
    session.rev = result.rev;
    session.baseBody = text;
  }
  assert.equal(d.notes[0].body, 'second edit');
  assert.equal(d.noteDrafts.length, 0);
});
