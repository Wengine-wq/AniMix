// Temporary account with two sessions for the two-installation sync test against a real server.
// Run inside the API container (see deploy/live-sync-test.ps1). Prints JSON with short-lived
// tokens to stdout; `cleanup <id>` removes that account and everything it wrote.
const { Pool } = require('pg');
const { randomUUID } = require('node:crypto');
const { PgStore } = require('../dist/pg');
const { runtimeConfig } = require('../dist/config');
const { randomToken, pepperedHash, nowSeconds } = require('../dist/crypto');

const FIXTURE_DOMAIN = 'sync-fixture.invalid';

async function main() {
  const config = runtimeConfig();
  const pool = new Pool({ connectionString: config.databaseUrl });
  const store = new PgStore(pool);
  try {
    if (process.argv[2] === 'cleanup') {
      const id = process.argv[3];
      const user = await store.userById(id);
      if (!user || user.email !== `${id}@${FIXTURE_DOMAIN}`) throw new Error('Not a test fixture account');
      const tables = await pool.query(
        "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'user_id'");
      for (const { table_name: table } of tables.rows) await pool.query(`DELETE FROM ${table} WHERE user_id = $1`, [id]);
      await pool.query('DELETE FROM access_tokens WHERE session_id NOT IN (SELECT id FROM sessions)');
      await pool.query('DELETE FROM refresh_tokens WHERE session_id NOT IN (SELECT id FROM sessions)');
      await pool.query('DELETE FROM users WHERE id = $1', [id]);
      console.log('fixture removed');
      return;
    }
    const id = randomUUID();
    const now = nowSeconds();
    await store.createUser({ id, email: `${id}@${FIXTURE_DOMAIN}`, display_name: 'Sync fixture', email_verified_at: now,
      created_at: now, updated_at: now, last_seen_at: null });
    const tokens = [];
    for (let i = 0; i < 2; i++) {
      const access = randomToken();
      const refresh = randomToken();
      await store.createSession(id, pepperedHash(access, config.authTokenPepper), pepperedHash(refresh, config.authTokenPepper),
        now + 900, now + 900, now);
      tokens.push({ accessToken: access, refreshToken: refresh });
    }
    console.log(JSON.stringify({ id, tokens }));
  } finally {
    await pool.end();
  }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1; });
