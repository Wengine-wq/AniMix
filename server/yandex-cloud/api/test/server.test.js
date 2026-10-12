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
