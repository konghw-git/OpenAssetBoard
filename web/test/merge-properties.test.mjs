import test from 'node:test';
import assert from 'node:assert/strict';
import { canonicalJson, rowKey, rowKeyFields } from '../public/canonical.js';
import { merge } from '../public/merge.js';
import { rng, side } from './helpers/random-side.mjs';

function duplicates(data) {
  const bad = [];
  for (const table of Object.keys(rowKeyFields)) {
    const seen = new Set();
    for (const row of data[table]) {
      const key = rowKey(table, row);
      if (seen.has(key)) bad.push(key);
      seen.add(key);
    }
  }
  return bad;
}

test('merge is commutative, idempotent and leaves unique row keys over random inputs', async () => {
  for (let seed = 1; seed <= 40; seed += 1) {
    const rand = rng(seed);
    const a = side(rand);
    const b = side(rand);
    const ab = await merge(a, b);
    const ba = await merge(b, a);
    assert.equal(canonicalJson(ab.data), canonicalJson(ba.data), `seed ${seed}: not commutative`);
    assert.deepEqual(ab.adjustments, ba.adjustments, `seed ${seed}: adjustments differ`);
    assert.deepEqual(duplicates(ab.data), [], `seed ${seed}: duplicate row keys`);

    const again = await merge(a, b);
    assert.equal(canonicalJson(again.data), canonicalJson(ab.data), `seed ${seed}: not deterministic`);

    const twice = await merge(ab.data, ab.data);
    assert.equal(canonicalJson(twice.data), canonicalJson(ab.data), `seed ${seed}: not idempotent`);
  }
});
