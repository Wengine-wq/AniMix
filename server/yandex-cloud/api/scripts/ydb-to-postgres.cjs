#!/usr/bin/env node
// Copies every AniMix table from YDB into PostgreSQL (same tables and keys).
// Idempotent: rows are upserted, so it can be re-run to catch up.
//
//   YDB_ENDPOINT=grpcs://ydb.serverless.yandexcloud.net:2135 \
//   YDB_DATABASE=/ru-central1/... \
//   YDB_ACCESS_TOKEN="$(yc iam create-token)" \
//   DATABASE_URL=postgres://animix:...@host:5432/animix \
//   node scripts/ydb-to-postgres.cjs
const { Driver, TokenAuthService, TypedData } = require('ydb-sdk');
const { Pool } = require('pg');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const TABLES = {
  users: ['id'],
  users_by_email: ['email'],
  sessions: ['id'],
  access_tokens: ['token_hash'],
  refresh_tokens: ['token_hash'],
  external_accounts: ['provider', 'provider_subject'],
  external_accounts_by_user: ['user_id', 'provider'],
  oauth_transactions: ['state_hash'],
  auth_tickets: ['ticket_hash'],
  user_anime_entries: ['user_id', 'shikimori_id'],
  user_history: ['user_id', 'created_at', 'id'],
  user_settings: ['user_id'],
  profile_media: ['user_id', 'kind'],
  user_privacy: ['user_id'],
  user_friend_edges: ['user_id', 'peer_id'],
  user_achievements: ['user_id', 'achievement_id'],
};

function env(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Set ${name}`);
  return value;
}

function plain(value) {
  if (typeof value === 'bigint') return Number(value);
  if (value && typeof value === 'object' && 'toNumber' in value) return value.toNumber();
  return value ?? null;
}

async function readTable(driver, table) {
  const rows = [];
  await driver.tableClient.withSession(async (session) => {
    const stream = session.streamReadTable(table, (result) => {
      if (!result.resultSet) return;
      for (const row of TypedData.createNativeObjects(result.resultSet)) {
        rows.push(Object.fromEntries(Object.entries(row).map(([k, v]) => [k, plain(v)])));
      }
    });
    await stream;
  });
  return rows;
}

async function writeRows(pool, table, keys, rows) {
  for (let offset = 0; offset < rows.length; offset += 200) {
    const batch = rows.slice(offset, offset + 200);
    const columns = Object.keys(batch[0]);
    const params = [];
    const values = batch.map((row) => {
      const placeholders = columns.map((column) => {
        params.push(row[column]);
        return `$${params.length}`;
      });
      return `(${placeholders.join(', ')})`;
    });
    const updates = columns
      .filter((column) => !keys.includes(column))
      .map((column) => `${column} = EXCLUDED.${column}`);
    await pool.query(
      `INSERT INTO ${table} (${columns.join(', ')}) VALUES ${values.join(', ')}
       ON CONFLICT (${keys.join(', ')}) DO ${updates.length ? `UPDATE SET ${updates.join(', ')}` : 'NOTHING'}`,
      params,
    );
  }
}

async function main() {
  const driver = new Driver({
    endpoint: env('YDB_ENDPOINT'),
    database: env('YDB_DATABASE'),
    authService: new TokenAuthService(env('YDB_ACCESS_TOKEN')),
  });
  if (!(await driver.ready(15_000))) throw new Error('YDB is not reachable.');
  const pool = new Pool({ connectionString: env('DATABASE_URL') });
  await pool.query(readFileSync(join(__dirname, '..', 'schema', 'postgres', '001_initial.sql'), 'utf8'));
  try {
    for (const [table, keys] of Object.entries(TABLES)) {
      const rows = await readTable(driver, table);
      if (rows.length) await writeRows(pool, table, keys, rows);
      console.log(`${table}: ${rows.length}`);
    }
  } finally {
    await pool.end();
    await driver.destroy();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
