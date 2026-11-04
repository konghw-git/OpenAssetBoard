import { empty, validate, parseBackup, stampChanges, replaceData, maxStamp } from './domain.js';
import { canonicalJson, contentHash } from './canonical.js';
import { merge } from './merge.js';

let expectedUser = null;

/** The small JSON proxy in server.mjs; errors carry the HTTP status and a 409's revision. */
export async function api(path, options = {}) {
  const r = await fetch(`/api${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-Gain-Request': '1',
      ...(expectedUser ? { 'X-Gain-User': expectedUser } : {}),
      ...options.headers,
    },
  });
  const text = await r.text();
  let value = null;
  try { value = text ? JSON.parse(text) : null; } catch { throw Error('服务器响应格式错误'); }
  if (!r.ok) throw Object.assign(Error(value?.error?.message || '网络请求失败'), { status: r.status, revision: value?.revision });
  return { value, text, revision: Number(r.headers.get('x-revision')) };
}

/**
 * The browser client: the cloud snapshot is the only storage. Everything lives in memory, every
 * mutation is stamped, merges before uploads on a 409, and the visible tab polls the revision
 * every 20 seconds. Closing the tab discards anything that has not reached the server — there is
 * deliberately no IndexedDB, localStorage data copy or CacheStorage entry.
 */
export class Store extends EventTarget {
  constructor(user, options = {}) {
    super();
    expectedUser = user.id;
    this.user = user;
    this.request = options.request || api;
    this.lockProvider = options.lock;
    this.now = options.now || (() => Date.now());
    this.channel = options.channel === undefined ? new BroadcastChannel('gain-data') : options.channel;
    if (this.channel) this.channel.onmessage = (e) => { if (e.data === user.id) this.sync().catch(() => {}); };
    this.state = { data: empty(), revision: 0, dirty: false, initialized: false, generation: 0, previous: null, lastSync: null };
    this.error = '';
    this.busy = false;
    this.conflict = null; // kept for old callers; a merge never creates one
    this.expectedGeneration = null;
    this.uploadTimer = null;
    this.pollTimer = null;
    this.closed = false;
    this.blocked = false;
    this.onVisibility = () => { if (document.hidden) this.flushOnHide(); };
    this.onPageHide = () => this.flushOnHide();
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibility);
      window.addEventListener('pagehide', this.onPageHide);
    }
  }

  get data() { return this.state.data; }

  emit() { this.dispatchEvent(new Event('change')); }

  fail(e) { this.error = e.message; this.emit(); }

  async lock(fn) {
    if (this.lockProvider) return this.lockProvider(fn);
    if (!navigator.locks) throw Error('此浏览器不支持安全的多标签页写入，请使用新版 Chrome、Edge、Firefox 或 Safari');
    return navigator.locks.request(`gain:${this.user.id}`, fn);
  }

  /** There is nothing to read from disk any more; kept so app.js has one start path. */
  async load() { this.emit(); return this; }

  /**
   * Runs a mutation in memory. One operation stamp is shared by every row it touched; content
   * and state stamps move separately, removals leave tombstones, and the upload starts after a
   * short quiet period (1 s) so a burst of edits is one snapshot.
   */
  async change(fn) {
    return this.lock(async () => {
      if (this.expectedGeneration != null && this.state.generation !== this.expectedGeneration) {
        throw Error('数据已在另一处更新，请关闭表单后重新编辑');
      }
      const before = structuredClone(this.state.data);
      const after = structuredClone(this.state.data);
      fn(after, this.state);
      const at = Math.max(this.now(), maxStamp(after) + 1, maxStamp(before) + 1);
      stampChanges(before, after, at, crypto.randomUUID());
      after.formatVersion = 16;
      after.schemaVersion = 15;
      after.exportedAt = this.now();
      validate(after);
      this.state = { ...this.state, data: after, dirty: true, generation: this.state.generation + 1 };
      this.error = '';
      this.emit();
      this.channel?.postMessage(this.user.id);
      this.scheduleUpload();
    });
  }

  scheduleUpload() {
    clearTimeout(this.uploadTimer);
    this.uploadTimer = setTimeout(() => { this.flush().catch(() => {}); }, 1000);
  }

  /** Uploads the in-memory state; a 409 pulls and merges first, then retries (at most three). */
  async flush() {
    if (this.closed || this.busy || !this.state.dirty) return;
    await this.lock(() => this.uploadLocked());
  }

  async uploadLocked() {
    // A snapshot this client cannot read must never be overwritten with an older format.
    if (this.blocked) throw Error('云端数据来自更新版本，已停止同步，请升级网页版');
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (!this.state.dirty) return;
      const generation = this.state.generation;
      const hash = await contentHash(this.state.data);
      this.busy = true;
      this.emit();
      try {
        const result = await this.request('/sync', {
          method: 'PUT',
          headers: { 'If-Match': String(this.state.revision), 'X-Content-Hash': hash },
          body: JSON.stringify(this.state.data),
        });
        const revision = Number(result.value?.revision ?? result.revision) || this.state.revision;
        const stillDirty = this.state.generation !== generation;
        this.state = { ...this.state, revision, dirty: stillDirty, initialized: true, lastSync: this.now() };
        this.channel?.postMessage(this.user.id);
        this.error = '';
      } catch (e) {
        if (e.status !== 409) {
          this.error = e.message || '网络错误，稍后会自动重试';
          this.emit();
          throw e;
        }
        await this.pullMergeLocked();
      } finally {
        this.busy = false;
        this.emit();
      }
    }
    if (this.state.dirty) this.error = '云端更新频繁，稍后会自动重试';
    this.emit();
  }

  /** Pulls the remote snapshot and merges it into the in-memory data. Caller holds the lock. */
  async pullMergeLocked() {
    const metaResult = await this.request('/sync/meta');
    const meta = metaResult.value || {};
    if ((Number(meta.formatVersion) || 0) > 16 || (Number(meta.schemaVersion) || 0) > 15) {
      this.blocked = true;
      throw Error('云端数据来自更新版本，已停止同步，请升级网页版');
    }
    this.blocked = false;
    const revision = Number(meta.revision) || 0;
    if (!revision) return false;
    if (this.state.initialized && revision === this.state.revision) return false;
    const remote = await this.request('/sync');
    const incoming = parseBackup(remote.text);
    const merged = await merge(this.state.data, incoming);
    validate(merged.data);
    const changed = canonicalJson(merged.data) !== canonicalJson(this.state.data);
    const dirty = canonicalJson(merged.data) !== canonicalJson(incoming);
    this.state = {
      ...this.state,
      data: merged.data,
      revision: Number(remote.revision) || revision,
      dirty,
      initialized: true,
      generation: changed ? this.state.generation + 1 : this.state.generation,
    };
    if (changed) {
      this.channel?.postMessage(this.user.id);
      this.emit();
    }
    return true;
  }

  /**
   * One sync: read the metadata, merge a changed remote body, then upload local changes. Also
   * the poll tick; a visible tab calls it every 20 s and pays only for the metadata read when
   * nothing changed.
   */
  async sync() {
    if (this.closed || this.busy) return;
    await this.lock(async () => {
      this.busy = true;
      this.emit();
      try {
        await this.pullMergeLocked();
        await this.uploadLocked();
        this.state = { ...this.state, initialized: true, lastSync: this.now() };
        this.error = '';
      } catch (e) {
        if (!this.error) this.error = e.message || '同步失败';
        throw e;
      } finally {
        this.busy = false;
        this.emit();
      }
    });
  }

  startPolling(ms = 20000) {
    clearInterval(this.pollTimer);
    if (!ms) return;
    this.pollTimer = setInterval(() => {
      if (this.closed || document.hidden || this.busy) return;
      this.sync().catch(() => {});
    }, ms);
  }

  /** Import is a replace: fresh stamps and tombstones for what the file no longer has. */
  async import(text) {
    await this.lock(async () => {
      const target = parseBackup(text);
      const before = structuredClone(this.state.data);
      const at = Math.max(this.now(), maxStamp(target) + 1, maxStamp(before) + 1);
      const data = replaceData(before, target, at, crypto.randomUUID());
      validate(data);
      this.state = { ...this.state, previous: before, data, dirty: true, initialized: true, generation: this.state.generation + 1 };
      this.error = '';
      this.emit();
      this.channel?.postMessage(this.user.id);
    });
    await this.flush();
  }

  /**
   * Last-chance upload when the tab goes away. The hash header is omitted (WebCrypto is async and
   * the page may already be unloading): the server stores the raw body hash, and the next merge
   * uploads once more with the canonical hash. Only small bodies make this trade.
   */
  flushOnHide() {
    if (this.closed || this.blocked || !this.state.dirty) return;
    const body = JSON.stringify(this.state.data);
    if (body.length > 60000 || typeof fetch !== 'function') return;
    try {
      fetch('/api/sync', {
        method: 'PUT',
        keepalive: true,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-Gain-Request': '1',
          'X-Gain-User': this.user.id,
          'If-Match': String(this.state.revision),
        },
        body,
      }).catch(() => {});
    } catch { /* the tab is going away */ }
  }

  close() {
    this.closed = true;
    clearTimeout(this.uploadTimer);
    clearInterval(this.pollTimer);
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibility);
      window.removeEventListener('pagehide', this.onPageHide);
    }
    this.channel?.close();
  }
}
