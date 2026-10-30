// The plain Node server answers like the Cloud Function behind API Gateway.
const assert = require('node:assert/strict');
const { test } = require('node:test');

Object.assign(process.env, {
  DATABASE_URL: 'postgres://unused',
  GOOGLE_CLIENT_ID: 'id',
  GOOGLE_CLIENT_SECRET: 'secret',
  GOOGLE_REDIRECT_URI: 'https://example.test/v1/auth/google/callback',
  SHIKIMORI_CLIENT_ID: 'id',
  SHIKIMORI_CLIENT_SECRET: 'secret',
  AUTH_TOKEN_PEPPER: 'pepper',
});

const { createApiServer } = require('../dist/server');

test('health and unauthorized routes over HTTP', async () => {
  const server = createApiServer().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await fetch(`${base}/health`);
    assert.equal(health.status, 200);
    assert.equal((await health.json()).ok, true);
    const me = await fetch(`${base}/v1/me`);
    assert.equal(me.status, 401);
  } finally {
    server.close();
  }
});

test('desktop loopback return addresses are accepted only in their exact form', () => {
  const { isLoopbackReturnUri } = require('../dist/index');
  assert.equal(isLoopbackReturnUri('http://127.0.0.1:53111/oauth/callback'), true);
  for (const bad of ['http://127.0.0.1/oauth/callback', 'http://localhost:53111/oauth/callback',
    'https://127.0.0.1:53111/oauth/callback', 'http://127.0.0.1:53111/oauth/callback/x',
    'http://127.0.0.1:99999/oauth/callback', 'http://127.0.0.1:53111@evil.test/oauth/callback']) {
    assert.equal(isLoopbackReturnUri(bad), false, bad);
  }
});

test('profile media on disk is served only by its exact path', async () => {
  const { mkdtempSync, mkdirSync, writeFileSync } = require('node:fs');
  const { join } = require('node:path');
  const { tmpdir } = require('node:os');
  const dir = mkdtempSync(join(tmpdir(), 'animix-media-'));
  const key = 'profiles/11111111-2222-3333-4444-555555555555/avatar/1793350000000.png';
  mkdirSync(join(dir, 'profiles/11111111-2222-3333-4444-555555555555/avatar'), { recursive: true });
  writeFileSync(join(dir, key), Buffer.from([137, 80, 78, 71]));
  process.env.MEDIA_DIR = dir;
  const server = createApiServer().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const image = await fetch(`${base}/media/${key}`);
    assert.equal(image.status, 200);
    assert.equal(image.headers.get('content-type'), 'image/png');
    assert.equal((await image.arrayBuffer()).byteLength, 4);
    assert.equal((await fetch(`${base}/media/profiles/../../etc/passwd`)).status, 404);
    assert.equal((await fetch(`${base}/media/profiles/11111111-2222-3333-4444-555555555555/avatar/1.png`)).status, 404);
  } finally {
    server.close();
    delete process.env.MEDIA_DIR;
  }
});
