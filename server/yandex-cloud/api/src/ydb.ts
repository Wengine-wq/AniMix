import { randomUUID } from 'node:crypto';

import { Driver, MetadataAuthService, TypedData, TypedValues, Types } from 'ydb-sdk';

import { RuntimeConfig, UserRow, LibraryEntry } from './types';

type SessionRow = {
  id: string;
  user_id: string;
  access_token_hash: string;
  refresh_token_hash: string;
  access_expires_at: number;
  refresh_expires_at: number;
  created_at: number;
  last_used_at: number;
};

type MediaRow = {
  kind: 'avatar' | 'banner';
  object_key: string;
  content_type: string;
  updated_at: number;
};

type ExternalAccount = {
  provider: string;
  provider_subject: string;
  user_id: string;
  provider_email?: string | null;
};

type OAuthTransaction = {
  state_hash: string;
  return_uri: string;
  expires_at: number;
};

type AuthTicket = {
  ticket_hash: string;
  state_hash: string;
  user_id: string;
  expires_at: number;
  consumed_at?: number | null;
};

function numberValue(value: unknown): number {
  if (typeof value === 'number') return value;
  if (typeof value === 'bigint') return Number(value);
  return Number(value ?? 0);
}

function normalizeRow<T>(value: Record<string, unknown>): T {
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, typeof item === 'bigint' ? Number(item) : item]),
  ) as T;
}

/**
 * Thin, parameterized repository over YDB. The function runtime keeps the
 * driver warm between calls; `ready()` is shared so concurrent cold requests
 * do not open a herd of gRPC connections.
 */
export class YdbStore {
  private static driver: Driver | null = null;
  private static ready: Promise<void> | null = null;

  constructor(private readonly config: RuntimeConfig) {}

  private async driver(): Promise<Driver> {
    if (!YdbStore.driver) {
      YdbStore.driver = new Driver({
        endpoint: this.config.ydbEndpoint,
        database: this.config.ydbDatabase,
        authService: new MetadataAuthService(),
      });
    }
    if (!YdbStore.ready) {
      const driver = YdbStore.driver;
      const readiness = driver.ready(10_000).then((isReady) => {
        if (!isReady) throw new Error('YDB driver did not become ready in 10 seconds.');
      });
      const guardedReadiness = readiness.catch((error: unknown) => {
        // A rejected promise must not poison every following invocation in the
        // same warm Cloud Function instance. Let the next request reconnect.
        if (YdbStore.driver === driver) YdbStore.driver = null;
        if (YdbStore.ready === guardedReadiness) YdbStore.ready = null;
        throw error;
      });
      YdbStore.ready = guardedReadiness;
    }
    await YdbStore.ready;
    if (!YdbStore.driver) throw new Error('YDB driver became unavailable.');
    return YdbStore.driver;
  }

  private async rows<T>(query: string, params: Record<string, unknown> = {}): Promise<T[]> {
    const driver = await this.driver();
    const typed = Object.fromEntries(
      Object.entries(params).map(([key, value]) => [`$${key}`, this.toTypedValue(value)]),
    );
    const declarations = Object.entries(params)
      .map(([key, value]) => `DECLARE $${key} AS ${this.yqlType(value)};`)
      .join('\n');
    const preparedQuery = declarations.length > 0 ? `${declarations}\n${query}` : query;
    const result = await driver.tableClient.withSessionRetry((session) =>
      session.executeQuery(preparedQuery, typed),
    );
    const resultSet = result.resultSets?.[0];
    if (!resultSet) return [];
    return TypedData.createNativeObjects(resultSet).map((row) =>
      normalizeRow<T>(row as Record<string, unknown>),
    );
  }

  private async execute(query: string, params: Record<string, unknown> = {}): Promise<void> {
    await this.rows(query, params);
  }

  private toTypedValue(value: unknown) {
    if (typeof value === 'string') return TypedValues.utf8(value);
    if (typeof value === 'boolean') return TypedValues.bool(value);
    if (typeof value === 'number') return TypedValues.uint64(value);
    if (value === null || value === undefined) {
      throw new Error('YDB query parameter must not be null; use an explicit optional type.');
    }
    throw new Error(`Unsupported YDB parameter type: ${typeof value}`);
  }

  private yqlType(value: unknown): 'Utf8' | 'Bool' | 'Uint64' {
    if (typeof value === 'string') return 'Utf8';
    if (typeof value === 'boolean') return 'Bool';
    if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return 'Uint64';
    if (value === null || value === undefined) {
      throw new Error('YDB query parameter must not be null; use an explicit optional type.');
    }
    throw new Error(`Unsupported YDB parameter value: ${String(value)}`);
  }

  private async one<T>(query: string, params: Record<string, unknown> = {}): Promise<T | null> {
    return (await this.rows<T>(query, params))[0] ?? null;
  }

  async userById(id: string): Promise<UserRow | null> {
    return this.one<UserRow>('SELECT * FROM users WHERE id = $id;', { id });
  }

  async userByEmail(email: string): Promise<UserRow | null> {
    const mapping = await this.one<{ user_id: string }>(
      'SELECT user_id FROM users_by_email WHERE email = $email;',
      { email },
    );
    return mapping ? this.userById(mapping.user_id) : null;
  }

  async userByExternal(provider: string, subject: string): Promise<UserRow | null> {
    const account = await this.one<ExternalAccount>(
      `SELECT * FROM external_accounts
       WHERE provider = $provider AND provider_subject = $subject;`,
      { provider, subject },
    );
    return account ? this.userById(account.user_id) : null;
  }

  async createUser(user: UserRow): Promise<void> {
    await this.execute(
      `UPSERT INTO users
       (id,email,display_name,email_verified_at,created_at,updated_at,last_seen_at)
       VALUES ($id,$email,$displayName,$verifiedAt,$createdAt,$updatedAt,$lastSeenAt);`,
      {
        id: user.id,
        email: user.email,
        displayName: user.display_name,
        verifiedAt: user.email_verified_at,
        createdAt: user.created_at,
        updatedAt: user.updated_at,
        lastSeenAt: user.last_seen_at ?? user.created_at,
      },
    );
    await this.execute(
      'UPSERT INTO users_by_email (email,user_id) VALUES ($email,$id);',
      { email: user.email, id: user.id },
    );
    await this.execute(
      `UPSERT INTO user_settings
       (user_id,theme_mode,smart_connection_enabled,updated_at)
       VALUES ($id,'system',true,$updatedAt);`,
      { id: user.id, updatedAt: user.updated_at },
    );
  }

  async upsertExternalAccount(
    provider: string,
    subject: string,
    userId: string,
    email: string | null,
    timestamp: number,
  ): Promise<void> {
    await this.execute(
      `UPSERT INTO external_accounts
       (provider,provider_subject,user_id,provider_email,created_at,updated_at)
       VALUES ($provider,$subject,$userId,$email,$createdAt,$updatedAt);`,
      { provider, subject, userId, email: email ?? '', createdAt: timestamp, updatedAt: timestamp },
    );
    await this.execute(
      `UPSERT INTO external_accounts_by_user (user_id,provider,provider_subject)
       VALUES ($userId,$provider,$subject);`,
      { userId, provider, subject },
    );
  }

  async linkedAccount(userId: string, provider: string): Promise<ExternalAccount | null> {
    const mapping = await this.one<{ provider_subject: string }>(
      `SELECT provider_subject FROM external_accounts_by_user
       WHERE user_id = $userId AND provider = $provider;`,
      { userId, provider },
    );
    if (!mapping) return null;
    return this.one<ExternalAccount>(
      `SELECT * FROM external_accounts
       WHERE provider = $provider AND provider_subject = $subject;`,
      { provider, subject: mapping.provider_subject },
    );
  }

  async createSession(
    userId: string,
    accessHash: string,
    refreshHash: string,
    accessExpiresAt: number,
    refreshExpiresAt: number,
    now: number,
  ): Promise<string> {
    const id = randomUUID();
    await this.execute(
      `UPSERT INTO sessions
       (id,user_id,access_token_hash,refresh_token_hash,access_expires_at,refresh_expires_at,created_at,last_used_at)
       VALUES ($id,$userId,$accessHash,$refreshHash,$accessExpiresAt,$refreshExpiresAt,$now,$now);`,
      { id, userId, accessHash, refreshHash, accessExpiresAt, refreshExpiresAt, now },
    );
    await this.execute(
      'UPSERT INTO access_tokens (token_hash,session_id,expires_at) VALUES ($hash,$sessionId,$expiresAt);',
      { hash: accessHash, sessionId: id, expiresAt: accessExpiresAt },
    );
    await this.execute(
      'UPSERT INTO refresh_tokens (token_hash,session_id,expires_at) VALUES ($hash,$sessionId,$expiresAt);',
      { hash: refreshHash, sessionId: id, expiresAt: refreshExpiresAt },
    );
    return id;
  }

  async sessionByAccessHash(hash: string, now: number): Promise<SessionRow | null> {
    const token = await this.one<{ session_id: string; expires_at: number }>(
      'SELECT * FROM access_tokens WHERE token_hash = $hash;',
      { hash },
    );
    if (!token || numberValue(token.expires_at) <= now) return null;
    return this.one<SessionRow>('SELECT * FROM sessions WHERE id = $id;', { id: token.session_id });
  }

  async sessionByRefreshHash(hash: string, now: number): Promise<SessionRow | null> {
    const token = await this.one<{ session_id: string; expires_at: number }>(
      'SELECT * FROM refresh_tokens WHERE token_hash = $hash;',
      { hash },
    );
    if (!token || numberValue(token.expires_at) <= now) return null;
    return this.one<SessionRow>('SELECT * FROM sessions WHERE id = $id;', { id: token.session_id });
  }

  async deleteSession(session: SessionRow): Promise<void> {
    await this.execute('DELETE FROM access_tokens WHERE token_hash = $hash;', { hash: session.access_token_hash });
    await this.execute('DELETE FROM refresh_tokens WHERE token_hash = $hash;', { hash: session.refresh_token_hash });
    await this.execute('DELETE FROM sessions WHERE id = $id;', { id: session.id });
  }

  async touchSession(userId: string, sessionId: string, now: number): Promise<void> {
    await this.execute('UPDATE users SET last_seen_at = $now WHERE id = $userId;', { now, userId });
    await this.execute('UPDATE sessions SET last_used_at = $now WHERE id = $sessionId;', { now, sessionId });
  }

  async updateDisplayName(userId: string, displayName: string, now: number): Promise<void> {
    await this.execute(
      'UPDATE users SET display_name = $displayName, updated_at = $now WHERE id = $userId;',
      { displayName, now, userId },
    );
  }

  async mediaForUser(userId: string): Promise<MediaRow[]> {
    return this.rows<MediaRow>('SELECT * FROM profile_media WHERE user_id = $userId;', { userId });
  }

  async putMedia(metadata: MediaRow & { user_id: string }): Promise<void> {
    await this.execute(
      `UPSERT INTO profile_media (user_id,kind,object_key,content_type,updated_at)
       VALUES ($userId,$kind,$objectKey,$contentType,$updatedAt);`,
      {
        userId: metadata.user_id,
        kind: metadata.kind,
        objectKey: metadata.object_key,
        contentType: metadata.content_type,
        updatedAt: metadata.updated_at,
      },
    );
  }

  async deleteMedia(userId: string, kind: string): Promise<MediaRow | null> {
    const existing = await this.one<MediaRow>(
      'SELECT * FROM profile_media WHERE user_id = $userId AND kind = $kind;',
      { userId, kind },
    );
    await this.execute('DELETE FROM profile_media WHERE user_id = $userId AND kind = $kind;', { userId, kind });
    return existing;
  }

  async libraryEntry(userId: string, shikimoriId: number): Promise<LibraryEntry | null> {
    return this.one<LibraryEntry>(
      `SELECT * FROM user_anime_entries
       WHERE user_id = $userId AND shikimori_id = $shikimoriId;`,
      { userId, shikimoriId },
    );
  }

  async libraryEntries(userId: string): Promise<LibraryEntry[]> {
    const entries = await this.rows<LibraryEntry>(
      'SELECT * FROM user_anime_entries WHERE user_id = $userId;',
      { userId },
    );
    return entries.sort((left, right) => right.updated_at - left.updated_at || right.shikimori_id - left.shikimori_id);
  }

  async upsertLibraryEntry(userId: string, entry: LibraryEntry): Promise<void> {
    await this.execute(
      `UPSERT INTO user_anime_entries
       (user_id,shikimori_id,status,score,episodes_watched,updated_at)
       VALUES ($userId,$shikimoriId,$status,$score,$episodesWatched,$updatedAt);`,
      {
        userId,
        shikimoriId: entry.shikimori_id,
        status: entry.status,
        score: entry.score,
        episodesWatched: entry.episodes_watched,
        updatedAt: entry.updated_at,
      },
    );
  }

  async upsertLibraryEntries(userId: string, entries: LibraryEntry[]): Promise<void> {
    if (entries.length === 0) return;
    const driver = await this.driver();
    const rowType = Types.struct({
      user_id: Types.UTF8,
      shikimori_id: Types.UINT64,
      status: Types.UTF8,
      score: Types.UINT64,
      episodes_watched: Types.UINT64,
      updated_at: Types.UINT64,
    });
    const query = `
      DECLARE $rows AS List<Struct<
        user_id:Utf8,
        shikimori_id:Uint64,
        status:Utf8,
        score:Uint64,
        episodes_watched:Uint64,
        updated_at:Uint64
      >>;
      UPSERT INTO user_anime_entries
      (user_id,shikimori_id,status,score,episodes_watched,updated_at)
      SELECT user_id,shikimori_id,status,score,episodes_watched,updated_at
      FROM AS_TABLE($rows);
    `;
    // Keep each YDB request comfortably below parameter and gRPC message
    // limits. UPSERT is idempotent, so a client retry can safely resume after
    // a transient failure between batches.
    const batchSize = 250;
    for (let offset = 0; offset < entries.length; offset += batchSize) {
      const rows = TypedValues.list(
        rowType,
        entries.slice(offset, offset + batchSize).map((entry) => ({
          user_id: userId,
          shikimori_id: entry.shikimori_id,
          status: entry.status,
          score: entry.score,
          episodes_watched: entry.episodes_watched,
          updated_at: entry.updated_at,
        })),
      );
      await driver.tableClient.withSessionRetry((session) =>
        session.executeQuery(query, { '$rows': rows }),
      );
    }
  }

  async addHistory(
    userId: string,
    action: string,
    timestamp: number,
    shikimoriId?: number,
    metadata?: Record<string, unknown>,
  ): Promise<void> {
    await this.execute(
      `UPSERT INTO user_history (user_id,created_at,id,action,shikimori_id,metadata_json)
       VALUES ($userId,$createdAt,$id,$action,$shikimoriId,$metadata);`,
      {
        userId,
        createdAt: timestamp,
        id: randomUUID(),
        action,
        shikimoriId: shikimoriId ?? 0,
        metadata: JSON.stringify(metadata ?? {}),
      },
    );
  }

  async createOAuthTransaction(stateHash: string, returnUri: string, now: number, expiresAt: number): Promise<void> {
    await this.execute(
      `UPSERT INTO oauth_transactions (state_hash,return_uri,created_at,expires_at)
       VALUES ($stateHash,$returnUri,$now,$expiresAt);`,
      { stateHash, returnUri, now, expiresAt },
    );
  }

  async oauthTransaction(stateHash: string): Promise<OAuthTransaction | null> {
    return this.one<OAuthTransaction>(
      'SELECT * FROM oauth_transactions WHERE state_hash = $stateHash;',
      { stateHash },
    );
  }

  async deleteOAuthTransaction(stateHash: string): Promise<void> {
    await this.execute('DELETE FROM oauth_transactions WHERE state_hash = $stateHash;', { stateHash });
  }

  async createAuthTicket(ticketHash: string, stateHash: string, userId: string, now: number, expiresAt: number): Promise<void> {
    await this.execute(
      `UPSERT INTO auth_tickets (ticket_hash,state_hash,user_id,created_at,expires_at)
       VALUES ($ticketHash,$stateHash,$userId,$now,$expiresAt);`,
      { ticketHash, stateHash, userId, now, expiresAt },
    );
  }

  async authTicket(ticketHash: string): Promise<AuthTicket | null> {
    return this.one<AuthTicket>('SELECT * FROM auth_tickets WHERE ticket_hash = $ticketHash;', { ticketHash });
  }

  async consumeAuthTicket(ticketHash: string, now: number): Promise<void> {
    await this.execute('UPDATE auth_tickets SET consumed_at = $now WHERE ticket_hash = $ticketHash;', { now, ticketHash });
  }
}
