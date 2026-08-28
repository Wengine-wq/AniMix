const { createHash, randomBytes, randomUUID } = require('node:crypto');
const { Driver, TokenAuthService, TypedValues } = require('ydb-sdk');

const required = [
  'ANIMIX_SMOKE_IAM_TOKEN',
  'ANIMIX_SMOKE_AUTH_PEPPER',
  'ANIMIX_SMOKE_API_ORIGIN',
  'ANIMIX_SMOKE_YDB_ENDPOINT',
  'ANIMIX_SMOKE_YDB_DATABASE',
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing ${name}`);
}

const tokenHash = (token) =>
  createHash('sha256')
    .update(`${process.env.ANIMIX_SMOKE_AUTH_PEPPER}:${token}`)
    .digest('base64url');

const typed = (value) =>
  typeof value === 'number'
    ? TypedValues.uint64(value)
    : TypedValues.utf8(value);

const yqlType = (value) => (typeof value === 'number' ? 'Uint64' : 'Utf8');

async function main() {
  const driver = new Driver({
    endpoint: process.env.ANIMIX_SMOKE_YDB_ENDPOINT,
    database: process.env.ANIMIX_SMOKE_YDB_DATABASE,
    authService: new TokenAuthService(process.env.ANIMIX_SMOKE_IAM_TOKEN),
  });
  if (!(await driver.ready(10_000))) throw new Error('YDB is not ready');

  const userId = randomUUID();
  const sessionId = randomUUID();
  const suffix = randomBytes(6).toString('hex');
  const email = `smoke-${suffix}@invalid.animix`;
  const subject = `9${Date.now()}${Math.floor(Math.random() * 100)}`;
  const accessToken = randomBytes(32).toString('base64url');
  const refreshToken = randomBytes(48).toString('base64url');
  const accessHash = tokenHash(accessToken);
  const refreshHash = tokenHash(refreshToken);
  const now = Math.floor(Date.now() / 1000);

  const execute = async (sql, params = {}) => {
    const declarations = Object.entries(params)
      .map(([name, value]) => `DECLARE $${name} AS ${yqlType(value)};`)
      .join('\n');
    const values = Object.fromEntries(
      Object.entries(params).map(([name, value]) => [`$${name}`, typed(value)]),
    );
    return driver.tableClient.withSessionRetry((session) =>
      session.executeQuery(`${declarations}\n${sql}`, values),
    );
  };

  const authHeaders = {
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
  };
  const api = process.env.ANIMIX_SMOKE_API_ORIGIN.replace(/\/$/, '');

  try {
    await execute(
      `UPSERT INTO users
       (id,email,display_name,email_verified_at,created_at,updated_at,last_seen_at)
       VALUES ($id,$email,'Smoke User',$now,$now,$now,$now);
       UPSERT INTO users_by_email (email,user_id) VALUES ($email,$id);
       UPSERT INTO sessions
       (id,user_id,access_token_hash,refresh_token_hash,access_expires_at,refresh_expires_at,created_at,last_used_at)
       VALUES ($sessionId,$id,$accessHash,$refreshHash,$accessExpiry,$refreshExpiry,$now,$now);
       UPSERT INTO access_tokens (token_hash,session_id,expires_at)
       VALUES ($accessHash,$sessionId,$accessExpiry);
       UPSERT INTO refresh_tokens (token_hash,session_id,expires_at)
       VALUES ($refreshHash,$sessionId,$refreshExpiry);`,
      {
        id: userId,
        email,
        now,
        sessionId,
        accessHash,
        refreshHash,
        accessExpiry: now + 3600,
        refreshExpiry: now + 7200,
      },
    );

    const statuses = [
      'planned',
      'watching',
      'rewatching',
      'completed',
      'on_hold',
      'dropped',
    ];
    const entries = Array.from({ length: 620 }, (_, index) => ({
      shikimori_id: 700_000 + index,
      status: statuses[index % statuses.length],
      score: index % 11,
      episodes_watched: index % 25,
    }));
    const importResponse = await fetch(`${api}/v1/library/import/shikimori`, {
      method: 'POST',
      headers: authHeaders,
      body: JSON.stringify({ shikimori_user_id: subject, entries }),
    });
    const imported = await importResponse.json();
    if (!importResponse.ok || imported.imported !== entries.length) {
      throw new Error(
        `Import failed: HTTP ${importResponse.status} ${JSON.stringify(imported)}`,
      );
    }

    const libraryResponse = await fetch(`${api}/v1/library`, {
      headers: authHeaders,
    });
    const library = await libraryResponse.json();
    if (!libraryResponse.ok || library.entries?.length !== entries.length) {
      throw new Error(`Library verification failed: HTTP ${libraryResponse.status}`);
    }

    const patchResponse = await fetch(`${api}/v1/me/profile`, {
      method: 'PATCH',
      headers: authHeaders,
      body: JSON.stringify({ display_name: 'Smoke Updated' }),
    });
    const patched = await patchResponse.json();
    if (
      !patchResponse.ok ||
      patched.user?.display_name !== 'Smoke Updated' ||
      patched.user?.stats?.total !== entries.length
    ) {
      throw new Error(`Profile verification failed: HTTP ${patchResponse.status}`);
    }

    console.log(
      `LIVE_SMOKE_OK imported=${imported.imported} library=${library.entries.length} profile_total=${patched.user.stats.total}`,
    );
  } finally {
    await execute(
      `DELETE FROM user_history WHERE user_id = $id;
       DELETE FROM user_anime_entries WHERE user_id = $id;
       DELETE FROM external_accounts_by_user WHERE user_id = $id;
       DELETE FROM external_accounts WHERE provider = 'shikimori' AND provider_subject = $subject;
       DELETE FROM access_tokens WHERE token_hash = $accessHash;
       DELETE FROM refresh_tokens WHERE token_hash = $refreshHash;
       DELETE FROM sessions WHERE id = $sessionId;
       DELETE FROM user_settings WHERE user_id = $id;
       DELETE FROM profile_media WHERE user_id = $id;
       DELETE FROM users_by_email WHERE email = $email;
       DELETE FROM users WHERE id = $id;`,
      { id: userId, subject, accessHash, refreshHash, sessionId, email },
    );
    await driver.destroy();
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
