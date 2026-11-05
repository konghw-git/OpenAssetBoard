import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Store } from '../public/store.js';
import * as D from '../public/domain.js';
import { contentHash, rowKey } from '../public/canonical.js';

function setup() {
  let clock = 1000;
  const remote = { data: D.empty(), revision: 0 };
  const calls = [];
  let tail = Promise.resolve();
  const request = async (path, opts = {}) => {
    calls.push({ path, opts });
    if (path === '/sync/meta') {
      return { value: { revision: remote.revision, formatVersion: 16, schemaVersion: 15, contentHash: '' } };
    }
    if (path === '/sync') {
      if (opts.method === 'PUT') {
        if (Number(opts.headers['If-Match']) !== remote.revision) {
          throw Object.assign(Error('conflict'), { status: 409, revision: remote.revision });
        }
        const body = JSON.parse(opts.body);
        assert.equal(opts.headers['X-Content-Hash'], await contentHash(body), 'PUT carries the canonical hash');
        remote.data = body;
        remote.revision += 1;
        return { value: { revision: remote.revision } };
      }
      return { text: JSON.stringify(remote.data), value: remote.data, revision: remote.revision };
    }
    if (path.startsWith('/sync/revisions')) return { value: { revisions: [] } };
    throw Error(`unexpected request ${path}`);
  };
  const lock = (fn) => {
    const next = tail.then(fn);
    tail = next.catch(() => {});
    return next;
  };
  const store = new Store(
    { id: 'a' },
    { request, lock, channel: { postMessage() {}, close() {} }, now: () => clock },
  );
  return { store, remote, calls, advance: (ms) => { clock += ms; } };
}

const note = (body, changedAt = 1_000_000, changeId = `edit-${body}`) => {
  const row = D.row('notes', { body });
  row.changedAt = changedAt;
  row.changeId = changeId;
  return row;
};

test('import removes unseen older remote rows and preserves newer remote rows', async () => {
  const { store, remote } = setup();
  await store.sync();
  const old = note('old', 10, 'old');
  const newer = note('new', 2000, 'new');
  remote.data.notes.push(old, newer);
  remote.revision = 1;
  await store.import(JSON.stringify(D.empty()));
  assert.deepEqual(remote.data.notes.map((row) => row.body), ['new']);
  store.close();
});

test('import clears an unseen older cloud goal', async () => {
  const { store, remote } = setup();
  await store.sync();
  Object.assign(remote.data, { netWorthGoal: 100000, netWorthGoalAt: 10, netWorthGoalChangeId: 'old' });
  remote.revision = 1;
  await store.import(JSON.stringify(D.empty()));
  assert.equal(remote.data.netWorthGoal, null);
  store.close();
});

test('import removes an older health profile and keeps its deletion for later merges', async () => {
  const { store, remote } = setup();
  remote.data.healthProfile = D.row('healthProfile', { changedAt: 10, changeId: 'old' });
  remote.revision = 1;
  await store.sync();
  await store.import(JSON.stringify(D.empty()));
  assert.equal(remote.data.healthProfile, null);
  assert.ok(remote.data.tombstones.some(t => t.rowKey === 'healthProfile#self'));
  store.close();
});

test('a newer health profile defeats an earlier profile deletion', async () => {
  const { store, remote } = setup();
  await store.sync();
  await store.import(JSON.stringify(D.empty()));
  const oldTomb = remote.data.tombstones.find(t => t.rowKey === 'healthProfile#self');
  remote.data.healthProfile = D.row('healthProfile', { changedAt: oldTomb.deletedAt + 1, changeId: 'new' });
  remote.revision += 1;
  await store.sync();
  assert.ok(store.data.healthProfile);
  assert.ok(!store.data.tombstones.some(t => t.rowKey === 'healthProfile#self'));
  store.close();
});

test('an edit is stamped, uploaded with If-Match and the canonical hash', async () => {
  const { store, remote, calls } = setup();
  await store.sync();
  await store.change((d) => d.notes.push(D.row('notes', { body: 'mine' })));
  await store.flush();

  assert.equal(remote.data.notes[0].body, 'mine');
  assert.equal(store.state.revision, 1);
  assert.equal(store.state.dirty, false);
  const put = calls.find((c) => c.opts?.method === 'PUT');
  assert.equal(put.opts.headers['If-Match'], '0');
  assert.ok(remote.data.notes[0].changedAt > 0);
  assert.ok(remote.data.notes[0].changeId);
});

test('a remote edit is merged in without a conflict question', async () => {
  const { store, remote } = setup();
  await store.sync();
  remote.data.notes.push(note('phone'));
  remote.revision = 1;

  await store.sync();

  assert.equal(store.data.notes[0].body, 'phone');
  assert.equal(store.state.revision, 1);
  assert.equal(store.state.dirty, false);
});

test('two devices editing at once merge and upload the union', async () => {
  const { store, remote } = setup();
  await store.sync();
  await store.change((d) => d.notes.push(D.row('notes', { body: 'web' })));
  remote.data.notes.push(note('phone'));
  remote.revision = 1;

  await store.flush();

  assert.deepEqual(remote.data.notes.map((n) => n.body).sort(), ['phone', 'web']);
  assert.equal(store.state.dirty, false);
  assert.equal(remote.revision, 2, 'merged result went up over the downloaded base');
});

test('import is a replace: what the file drops becomes a tombstone', async () => {
  const { store, remote } = setup();
  await store.sync();
  await store.change((d) => d.notes.push(D.row('notes', { body: 'old' })));
  await store.flush();
  const oldId = store.data.notes[0].id;

  const target = D.empty();
  target.notes.push(D.row('notes', { body: 'imported' }));
  await store.import(JSON.stringify(target));

  assert.deepEqual(remote.data.notes.map((n) => n.body), ['imported']);
  assert.ok(remote.data.tombstones.some((t) => t.rowKey === rowKey('notes', { id: oldId })));
  assert.equal(store.state.dirty, false);
  assert.equal(store.state.previous.notes[0].body, 'old', 'the session keeps the replaced data for download');
});

test('a network failure leaves the change in memory and can be retried', async () => {
  const { store, remote } = setup();
  await store.sync();
  const realRequest = store.request;
  let failing = true;
  store.request = async (path, opts = {}) => {
    if (failing && opts.method === 'PUT') throw Object.assign(Error('offline'), { status: 0 });
    return realRequest(path, opts);
  };
  await store.change((d) => d.notes.push(D.row('notes', { body: 'kept' })));
  await assert.rejects(store.flush());
  assert.equal(store.state.dirty, true);
  assert.equal(store.data.notes[0].body, 'kept');
  assert.match(store.error, /offline/);

  failing = false;
  await store.flush();
  assert.equal(store.state.dirty, false);
  assert.equal(remote.data.notes[0].body, 'kept');
});

test('a stale form generation cannot overwrite newer data', async () => {
  const { store } = setup();
  await store.sync();
  await store.change((d) => d.notes.push(D.row('notes', { body: 'first' })));
  store.expectedGeneration = store.state.generation - 1;
  await assert.rejects(store.change((d) => d.notes.push(D.row('notes', { body: 'second' }))), /另一处/);
  assert.equal(store.data.notes.length, 1);
  store.expectedGeneration = null;
});

test('invalid data never replaces the in-memory state', async () => {
  const { store } = setup();
  await store.sync();
  await store.change((d) => d.notes.push(D.row('notes', { body: 'safe' })));
  await assert.rejects(store.change((d) => { d.notes[0].body = 123; }), /notes.body/);
  assert.equal(store.data.notes[0].body, 'safe');
});

test('a snapshot from a newer version stops all uploads', async () => {
  const { store, remote } = setup();
  store.request = async (path) => {
    if (path === '/sync/meta') return { value: { revision: 1, formatVersion: 18, schemaVersion: 15 } };
    throw Error('must not download');
  };
  await assert.rejects(store.sync(), /更新版本/);
  assert.equal(store.blocked, true);
  await store.change((d) => d.notes.push(D.row('notes', { body: 'local' })));
  await assert.rejects(store.flush(), /更新版本/);
  assert.equal(remote.revision, 0);
});

test('the store keeps no browser-side data at all', () => {
  const source = readFileSync(new URL('../public/store.js', import.meta.url), 'utf8');
  assert.ok(!/indexedDB\.(open|deleteDatabase)/i.test(source));
  assert.ok(!/localStorage\./i.test(source));
  assert.ok(!/sessionStorage\./i.test(source));
  assert.ok(!/caches\.(open|match)/i.test(source));
});
