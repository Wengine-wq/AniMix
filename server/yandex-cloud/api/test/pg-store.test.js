// Runs the PostgreSQL store against an in-memory PostgreSQL (pg-mem).
// Build first: `npm run build`, then `npm test`.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { newDb } = require('pg-mem');

const { PgStore } = require('../dist/pg');

async function freshStore() {
  const db = newDb();
  db.public.registerFunction({
    name: 'least',
    args: ['bigint', 'bigint'],
    returns: 'bigint',
    implementation: (a, b) => (Number(a) <= Number(b) ? a : b),
  });
  const { Pool } = db.adapters.createPg();
  const store = new PgStore(new Pool());
  await store.migrate();
  return store;
}

const user = {
  id: 'u1',
  email: 'ilya@example.com',
  display_name: 'Илья',
  email_verified_at: 1,
  created_at: 100,
  updated_at: 100,
  last_seen_at: null,
};

test('users, external accounts and sessions', async () => {
  const store = await freshStore();
  await store.createUser(user);
  await store.upsertExternalAccount('google', 'sub-1', 'u1', 'ilya@example.com', 100);
  assert.equal((await store.userByEmail('ilya@example.com')).id, 'u1');
  assert.equal((await store.userByExternal('google', 'sub-1')).display_name, 'Илья');
  assert.equal((await store.linkedAccount('u1', 'google')).provider_subject, 'sub-1');
  assert.deepEqual((await store.searchUsers('ль')).map((row) => row.id), ['u1']);

  const sessionId = await store.createSession('u1', 'acc', 'ref', 1000, 2000, 100);
  const session = await store.sessionByAccessHash('acc', 500);
  assert.equal(session.id, sessionId);
  assert.equal(typeof session.access_expires_at, 'number');
  assert.equal(await store.sessionByAccessHash('acc', 1000), null, 'expired token');
  assert.equal((await store.sessionByRefreshHash('ref', 500)).id, sessionId);
  await store.touchSession('u1', sessionId, 600);
  assert.equal((await store.userById('u1')).last_seen_at, 600);
  await store.deleteSession(session);
  assert.equal(await store.sessionByRefreshHash('ref', 500), null);
});

test('library, history, achievements, friends, privacy', async () => {
  const store = await freshStore();
  await store.createUser(user);
  await store.upsertLibraryEntries('u1', [
    { shikimori_id: 21, status: 'watching', score: 9, episodes_watched: 100, updated_at: 5 },
    { shikimori_id: 1535, status: 'completed', score: 10, episodes_watched: 37, updated_at: 7 },
  ]);
  await store.upsertLibraryEntry('u1', {
    shikimori_id: 21, status: 'watching', score: 9, episodes_watched: 101, updated_at: 9,
  });
  const entries = await store.libraryEntries('u1');
  assert.deepEqual(entries.map((e) => e.shikimori_id), [21, 1535]);
  assert.equal(entries[0].episodes_watched, 101);
  assert.equal((await store.libraryEntry('u1', 1535)).status, 'completed');

  await store.addHistory('u1', 'episode_watched', 10, 21, { episode: '101' }, 'h1');
  await store.addHistory('u1', 'watch_seconds', 20, 21, { seconds: 1400 }, 'h2');
  const history = await store.historyForUser('u1');
  assert.deepEqual(history.map((h) => h.created_at), [20, 10]);

  await store.mergeAchievements('u1', new Map([['first', 500], ['owl', 900]]));
  await store.mergeAchievements('u1', new Map([['first', 300], ['owl', 1200]]));
  const unlocked = Object.fromEntries(
    (await store.achievements('u1')).map((row) => [row.achievement_id, row.unlocked_at]),
  );
  assert.deepEqual(unlocked, { first: 300, owl: 900 }, 'earliest unlock wins');

  await store.setFriendPair('u1', 'u2', 'outgoing', 'incoming');
  assert.equal(await store.friendStatus('u2', 'u1'), 'incoming');
  await store.deleteFriendPair('u2', 'u1');
  assert.deepEqual(await store.friendEdges('u1'), []);

  assert.equal(await store.libraryVisible('u1'), true);
  await store.setLibraryVisible('u1', false);
  assert.equal(await store.libraryVisible('u1'), false);
});

test('oauth transactions, tickets and profile media', async () => {
  const store = await freshStore();
  await store.createOAuthTransaction('state', 'animix://oauth/callback', 1, 600);
  assert.equal((await store.oauthTransaction('state')).expires_at, 600);
  await store.deleteOAuthTransaction('state');
  assert.equal(await store.oauthTransaction('state'), null);

  await store.createAuthTicket('ticket', 'state', 'u1', 1, 60);
  await store.consumeAuthTicket('ticket', 5);
  assert.equal((await store.authTicket('ticket')).consumed_at, 5);

  await store.putMedia({ user_id: 'u1', kind: 'avatar', object_key: 'a/1.jpg', content_type: 'image/jpeg', updated_at: 1 });
  await store.putMedia({ user_id: 'u1', kind: 'avatar', object_key: 'a/2.jpg', content_type: 'image/jpeg', updated_at: 2 });
  assert.equal((await store.mediaForUser('u1'))[0].object_key, 'a/2.jpg');
  assert.equal((await store.deleteMedia('u1', 'avatar')).object_key, 'a/2.jpg');
  assert.deepEqual(await store.mediaForUser('u1'), []);
});
