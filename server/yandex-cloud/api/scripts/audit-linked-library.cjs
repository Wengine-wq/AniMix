const { Driver, TokenAuthService, TypedData } = require('ydb-sdk');

async function main() {
  const token = process.env.ANIMIX_AUDIT_IAM_TOKEN;
  const endpoint = process.env.ANIMIX_AUDIT_YDB_ENDPOINT;
  const database = process.env.ANIMIX_AUDIT_YDB_DATABASE;
  if (!token || !endpoint || !database) throw new Error('Missing audit environment');
  const driver = new Driver({
    endpoint,
    database,
    authService: new TokenAuthService(token),
  });
  if (!(await driver.ready(10_000))) throw new Error('YDB is not ready');
  try {
    const result = await driver.tableClient.withSessionRetry((session) =>
      session.executeQuery(
        "SELECT provider_subject FROM external_accounts WHERE provider = 'shikimori';",
      ),
    );
    const account = TypedData.createNativeObjects(result.resultSets[0])[0];
    if (!account) throw new Error('No linked Shikimori account');

    let total = 0;
    let pages = 0;
    for (let page = 1; page <= 50; page++) {
      const uri = new URL('https://shikimori.io/api/v2/user_rates');
      uri.searchParams.set('user_id', String(account.provider_subject));
      uri.searchParams.set('target_type', 'Anime');
      uri.searchParams.set('page', String(page));
      uri.searchParams.set('limit', '100');
      const response = await fetch(uri, {
        headers: { 'User-Agent': 'AniMix/2.0', Accept: 'application/json' },
      });
      if (!response.ok) throw new Error(`Shikimori HTTP ${response.status}`);
      const entries = await response.json();
      if (!Array.isArray(entries)) throw new Error('Unexpected Shikimori response');
      pages++;
      total += entries.length;
      if (entries.length < 100) break;
    }
    console.log(`REAL_SHIKIMORI_LIBRARY_OK entries=${total} pages=${pages}`);
  } finally {
    await driver.destroy();
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
