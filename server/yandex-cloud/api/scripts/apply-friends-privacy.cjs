// Apply the friends/privacy migration to the existing AniMix YDB database.
// Uses the already installed ydb-sdk and a short-lived token from yc CLI.
// The token stays in this process and is never written to disk or stdout.
const { execFileSync } = require('node:child_process');
const {
  Column, Driver, TableDescription, TokenAuthService, Types,
} = require('ydb-sdk');

const endpoint = process.argv[2];
const database = process.argv[3];
if (!endpoint || !database) {
  process.stderr.write('Usage: node scripts/apply-friends-privacy.cjs <endpoint> <database>\n');
  process.exit(2);
}

async function main() {
  const token = execFileSync('yc', ['iam', 'create-token'], { encoding: 'utf8' }).trim();
  const driver = new Driver({ endpoint, database, authService: new TokenAuthService(token) });
  try {
    if (!await driver.ready(10000)) throw new Error('YDB did not become ready.');
    const listing = await driver.schemeClient.listDirectory('');
    const existing = new Set((listing.children ?? []).map((entry) => entry.name));
    const tables = [
      {
        name: 'user_privacy',
        description: new TableDescription()
          .withColumns(
            new Column('user_id', Types.UTF8),
            new Column('library_visible', Types.BOOL),
            new Column('updated_at', Types.UINT64),
          )
          .withPrimaryKey('user_id'),
      },
      {
        name: 'user_friend_edges',
        description: new TableDescription()
          .withColumns(
            new Column('user_id', Types.UTF8),
            new Column('peer_id', Types.UTF8),
            new Column('status', Types.UTF8),
            new Column('updated_at', Types.UINT64),
          )
          .withPrimaryKeys('user_id', 'peer_id'),
      },
    ];
    for (const table of tables) {
      if (!existing.has(table.name)) {
        await driver.tableClient.withSessionRetry((session) =>
          session.createTable(table.name, table.description));
        process.stdout.write(`Created ${table.name}\n`);
      } else {
        process.stdout.write(`Already exists: ${table.name}\n`);
      }
      const description = await driver.tableClient.withSessionRetry((session) =>
        session.describeTable(table.name));
      if (description.primaryKey.join(',') !== table.description.primaryKey.join(',')) {
        throw new Error(`Unexpected primary key for ${table.name}.`);
      }
      const expectedColumns = table.description.columns.map((column) => column.name).sort();
      const actualColumns = description.columns.map((column) => column.name).sort();
      if (actualColumns.join(',') !== expectedColumns.join(',')) {
        throw new Error(`Unexpected columns for ${table.name}.`);
      }
    }
  } finally {
    await driver.destroy();
  }
}

main().catch((error) => {
  process.stderr.write(`Migration failed: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
