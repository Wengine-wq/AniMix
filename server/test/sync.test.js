const { test } = require('node:test');
const assert = require('node:assert/strict');
const { newDb } = require('pg-mem');
const { PgStore } = require('../dist/pg');
const { validSyncPayload, syncLibrary } = require('../dist/sync');

const payload = { version: 1, library: { profile: { name: 'Test' }, entries: {
  s21: { anime: { id: 21, source: 'Shikimori' }, status: 'Watching', episodesWatched: 3, score: 8 },
  y10: { anime: { id: 10, source: 'Yummy' }, status: 'Planned' },
} }, achievements: { historical: { ten_completed: 1000 } }, usage: {} };

test('sync payload rejects local files, tokens, unsupported versions and oversized data', () => {
  assert.equal(validSyncPayload(payload), true);
  for (const bad of [{}, { ...payload, version: 2 }, { ...payload, accessToken: 'private' },
    { ...payload, library: { entries: {}, profile: { avatar: { localFileName: 'avatar.png' } } } },
    { ...payload, junk: 'a'.repeat(10 * 1024 * 1024) }]) assert.equal(validSyncPayload(bad), false);
  assert.deepEqual(syncLibrary(payload), [{ shikimori_id: 21, status: 'watching', score: 8, episodes_watched: 3, updated_at: 0 }]);
});

test('sync store preserves account isolation and rejects stale revisions', async () => {
  const db = newDb(); const { Pool } = db.adapters.createPg();
  const store = new PgStore(new Pool()); await store.migrate();
  assert.deepEqual(await store.syncState('a'), { revision: 0, payload: null });
  assert.equal((await store.putSyncState('a', 0, payload)).revision, 1);
  assert.equal((await store.putSyncState('a', 1, { ...payload, usage: { device: {} } })).revision, 2);
  assert.equal(await store.putSyncState('a', 1, payload), null);
  assert.equal((await store.syncState('a')).revision, 2);
  assert.deepEqual(await store.syncState('b'), { revision: 0, payload: null });
});
