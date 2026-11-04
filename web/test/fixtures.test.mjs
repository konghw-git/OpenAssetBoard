import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { canonicalJson, contentHash } from '../public/canonical.js';
import { merge } from '../public/merge.js';
import { parseBackup } from '../public/domain.js';

const load = (name) => JSON.parse(readFileSync(new URL(`../../fixtures/sync/${name}`, import.meta.url)));
const canonicalCases = load('canonical-cases.json').cases;
const mergeCases = load('merge-cases.json').cases;

test('canonical fixtures match the reference text and hash', async () => {
  for (const item of canonicalCases) {
    const input = JSON.parse(item.inputText);
    assert.equal(canonicalJson(input), item.canonical, item.name);
    assert.equal(await contentHash(input), item.sha256, item.name);
  }
});

test('a real Android format-16 backup keeps its canonical hash through the web parser', async () => {
  const text = readFileSync(new URL('../../app/src/test/resources/backups/format-16.json', import.meta.url), 'utf8');
  const parsed = parseBackup(text);
  assert.equal(await contentHash(parsed), await contentHash(JSON.parse(text)));
  assert.equal(parsed.formatVersion, 16);
  assert.equal(parsed.schemaVersion, 15);
});

test('merge fixtures match, in both orders and when merged with themselves', async () => {
  for (const item of mergeCases) {
    const { data, adjustments } = await merge(item.a, item.b);
    assert.equal(canonicalJson(data), item.resultCanonical, item.name);
    assert.equal(await contentHash(data), item.resultHash, item.name);
    assert.deepEqual(adjustments, item.adjustments, item.name);

    const reverse = await merge(item.b, item.a);
    assert.equal(canonicalJson(reverse.data), item.resultCanonical, `${item.name} (reversed)`);
    assert.deepEqual(reverse.adjustments, item.adjustments, `${item.name} (reversed)`);

    const canonicalResult = JSON.parse(item.resultCanonical);
    const twice = await merge(canonicalResult, canonicalResult);
    assert.equal(canonicalJson(twice.data), item.resultCanonical, `${item.name} (idempotent)`);
  }
});
