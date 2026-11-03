import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { Pool, PoolClient } from 'pg';

import { AuthTicket, ExternalAccount, LibraryEntry, MediaRow, OAuthTransaction, SessionRow, UserRow } from './types';
import { SyncRow } from './sync';
import { DeviceRow } from './social';
import { defaultVisibility, readVisibility, Visibility } from './privacy';

/** The pg-compatible surface we use, so tests can pass an in-memory pool. */
export type Queryable = Pick<Pool, 'query'> & {
  connect?: () => Promise<PoolClient>;
};

// bigint columns come back as strings from node-postgres; the API works with
// plain numbers (seconds and milliseconds fit safely).
const NUMERIC_COLUMNS = new Set([
  'email_verified_at', 'created_at', 'updated_at', 'last_seen_at',
  'access_expires_at', 'refresh_expires_at', 'last_used_at', 'expires_at',
  'consumed_at', 'shikimori_id', 'score', 'episodes_watched', 'unlocked_at',
  'seen_at', 'claimed_at',
]);

function normalize<T>(row: Record<string, unknown>): T {
  return Object.fromEntries(
    Object.entries(row).map(([key, value]) => [
      key,
      NUMERIC_COLUMNS.has(key) && value !== null && value !== undefined
        ? Number(value)
        : value,
    ]),
  ) as T;
}

/** The AniMix account store on PostgreSQL: users, sessions, lists, achievements, friends, sync and devices. */
export class PgStore {
  constructor(private readonly db: Queryable) {}

  static fromUrl(url: string): PgStore {
    return new PgStore(new Pool({ connectionString: url, max: 10 }));
  }

  /** Creates missing tables; safe to run on every start. */
  async migrate(): Promise<void> {
    const sql = readFileSync(
      join(__dirname, '..', 'schema', '001_initial.sql'),
      'utf8',
    );
    await this.db.query(sql);
    await this.db.query(`CREATE TABLE IF NOT EXISTS user_sync_state (
      user_id text PRIMARY KEY, revision bigint NOT NULL, payload jsonb NOT NULL
    )`);
    await this.db.query(`CREATE TABLE IF NOT EXISTS user_devices (
      user_id text NOT NULL, device_id text NOT NULL, label text NOT NULL, platform text NOT NULL,
      active boolean NOT NULL, seen_at bigint NOT NULL, playback jsonb, PRIMARY KEY(user_id, device_id)
    ); CREATE TABLE IF NOT EXISTS user_handoffs (
      id text PRIMARY KEY, user_id text NOT NULL, source_id text NOT NULL, target_id text NOT NULL,
      playback jsonb NOT NULL, created_at bigint NOT NULL, claimed_at bigint
    )`);
    // 1.2: a session knows its device (to sign one device out), privacy is set per part of the profile,
    // and the hot lookups are indexed.
    await this.db.query(`ALTER TABLE sessions ADD COLUMN IF NOT EXISTS device_id text;
      ALTER TABLE user_privacy ADD COLUMN IF NOT EXISTS visibility jsonb;
      CREATE INDEX IF NOT EXISTS sessions_user_device ON sessions (user_id, device_id);
      CREATE INDEX IF NOT EXISTS access_tokens_session ON access_tokens (session_id);
      CREATE INDEX IF NOT EXISTS refresh_tokens_session ON refresh_tokens (session_id);
      CREATE INDEX IF NOT EXISTS user_handoffs_user_created ON user_handoffs (user_id, created_at)`);
  }

  /** Remembers which device a session belongs to; written only when it changes. */
  async bindSessionDevice(sessionId: string, deviceId: string): Promise<void> {
    await this.db.query('UPDATE sessions SET device_id = $2 WHERE id = $1 AND (device_id IS NULL OR device_id <> $2)', [sessionId, deviceId]);
  }

  /** Signs one device out: its sessions and tokens, its presence and its pending handoffs. Returns the ended sessions. */
  async removeDevice(userId: string, deviceId: string): Promise<string[]> {
    const sessions = (await this.db.query('DELETE FROM sessions WHERE user_id = $1 AND device_id = $2 RETURNING id', [userId, deviceId]))
      .rows.map((row: any) => String(row.id));
    if (sessions.length) {
      await this.db.query('DELETE FROM access_tokens WHERE session_id = ANY($1::text[])', [sessions]);
      await this.db.query('DELETE FROM refresh_tokens WHERE session_id = ANY($1::text[])', [sessions]);
    }
    await this.db.query('DELETE FROM user_devices WHERE user_id = $1 AND device_id = $2', [userId, deviceId]);
    await this.db.query('DELETE FROM user_handoffs WHERE user_id = $1 AND (source_id = $2 OR target_id = $2)', [userId, deviceId]);
    return sessions;
  }

  async visibility(userId: string): Promise<Visibility> {
    const row = await this.one<{ library_visible: boolean; visibility: unknown }>(
      'SELECT library_visible, visibility FROM user_privacy WHERE user_id = $1', [userId]);
    const fallback = defaultVisibility(row?.library_visible ?? true);
    return (row?.visibility ? readVisibility(row.visibility, fallback) : null) ?? fallback;
  }

  async setVisibility(userId: string, value: Visibility): Promise<void> {
    await this.execute(
      `INSERT INTO user_privacy (user_id, library_visible, updated_at, visibility) VALUES ($1, $2, $3, $4::jsonb)
       ON CONFLICT (user_id) DO UPDATE SET library_visible = EXCLUDED.library_visible,
         updated_at = EXCLUDED.updated_at, visibility = EXCLUDED.visibility`,
      [userId, value.collection !== 'nobody', Date.now(), JSON.stringify(value)],
    );
  }

  /**
   * Friends and requests with name, avatar and presence in one query, instead of three queries per person.
   * Presence is filtered by each person's own setting in the handler.
   */
  async friendsOverview(userId: string): Promise<Array<{ id: string; display_name: string; status: string; avatar_key: string | null;
    online: boolean; last_seen_at: number; visibility: unknown; library_visible: boolean | null }>> {
    const rows = await this.db.query(`SELECT u.id, u.display_name, e.status, m.object_key AS avatar_key,
        COALESCE(bool_or(d.active AND d.seen_at > $2), false) AS online, COALESCE(max(d.seen_at), 0) AS last_seen_at,
        p.visibility, p.library_visible
      FROM user_friend_edges e JOIN users u ON u.id = e.peer_id
      LEFT JOIN profile_media m ON m.user_id = u.id AND m.kind = 'avatar'
      LEFT JOIN user_devices d ON d.user_id = u.id
      LEFT JOIN user_privacy p ON p.user_id = u.id
      WHERE e.user_id = $1
      GROUP BY u.id, u.display_name, e.status, m.object_key, p.visibility, p.library_visible`, [userId, Date.now() - 90000]);
    return rows.rows.map((row: any) => ({ ...row, last_seen_at: Number(row.last_seen_at), online: row.online === true }));
  }

  async syncRevision(userId: string): Promise<number> {
    const row = await this.one<{ revision: number }>('SELECT revision FROM user_sync_state WHERE user_id = $1', [userId]);
    return Number(row?.revision ?? 0);
  }

  async heartbeat(userId: string, deviceId: string, label: string, platform: string, active: boolean, playback: unknown): Promise<void> {
    await this.db.query(`INSERT INTO user_devices (user_id, device_id, label, platform, active, seen_at, playback)
      VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb) ON CONFLICT (user_id,device_id) DO UPDATE SET
      label=EXCLUDED.label, platform=EXCLUDED.platform, active=EXCLUDED.active, seen_at=EXCLUDED.seen_at, playback=EXCLUDED.playback`,
      [userId, deviceId, label, platform, active, Date.now(), JSON.stringify(playback)]);
  }
  async devices(userId: string): Promise<DeviceRow[]> {
    const rows = await this.rows<DeviceRow>('SELECT device_id,label,platform,active,seen_at,playback FROM user_devices WHERE user_id=$1', [userId]);
    return rows.map((row) => ({ ...row, seen_at: Number(row.seen_at) }));
  }
  async handoff(userId: string, source: string, target: string, playback: unknown, id: string = randomUUID()): Promise<string> {
    await this.db.query('DELETE FROM user_handoffs WHERE created_at < $1', [Date.now() - 600000]);
    await this.db.query(`INSERT INTO user_handoffs (id,user_id,source_id,target_id,playback,created_at) VALUES ($1,$2,$3,$4,$5::jsonb,$6) ON CONFLICT (id) DO NOTHING`,
      [id,userId,source,target,JSON.stringify(playback),Date.now()]);
    const owner = await this.db.query('SELECT id FROM user_handoffs WHERE id=$1 AND user_id=$2 AND source_id=$3 AND target_id=$4',[id,userId,source,target]);
    if(!owner.rows.length)throw new Error('Invalid handoff id');
    return id;
  }
  async pendingHandoffs(userId: string, device: string): Promise<any[]> {
    return this.rows('SELECT id,source_id,target_id,playback,claimed_at FROM user_handoffs WHERE user_id=$1 AND created_at > $2 AND (source_id=$3 OR target_id=$3)',
      [userId,Date.now()-120000,device]);
  }
  async claimHandoff(userId: string, device: string, id: string): Promise<boolean> {
    const rows = await this.db.query('UPDATE user_handoffs SET claimed_at=$4 WHERE id=$1 AND user_id=$2 AND target_id=$3 AND claimed_at IS NULL AND created_at > $5 RETURNING id',
      [id,userId,device,Date.now(),Date.now()-120000]);
    if(rows.rows.length)return true;
    const prior=await this.db.query('SELECT id FROM user_handoffs WHERE id=$1 AND user_id=$2 AND target_id=$3 AND claimed_at IS NOT NULL AND created_at > $4',[id,userId,device,Date.now()-120000]);
    return prior.rows.length>0;
  }

  async syncState(userId: string): Promise<SyncRow> {
    const row = await this.one<{ revision: string | number; payload: Record<string, unknown> }>(
      'SELECT revision, payload FROM user_sync_state WHERE user_id = $1', [userId]);
    return row ? { revision: Number(row.revision), payload: row.payload } : { revision: 0, payload: null };
  }

  async putSyncState(userId: string, revision: number, payload: Record<string, unknown>): Promise<SyncRow | null> {
    const client = this.db.connect ? await this.db.connect() : null;
    const db = client ?? this.db;
    try {
    if (client) await client.query('BEGIN');
    // UPDATE's revision predicate is checked after locking the row. Two devices cannot both win.
    const result = revision === 0
      ? await db.query(`INSERT INTO user_sync_state (user_id, revision, payload) VALUES ($1, 1, $2::jsonb)
          ON CONFLICT (user_id) DO NOTHING RETURNING revision, payload`, [userId, JSON.stringify(payload)])
      : await db.query(`UPDATE user_sync_state SET revision = revision + 1, payload = $3::jsonb
          WHERE user_id = $1 AND revision = $2 RETURNING revision, payload`, [userId, revision, JSON.stringify(payload)]);
    const row = result.rows[0];
    const profile = (payload.library as any)?.profile;
    if (row && profile?.name) await db.query('UPDATE users SET display_name = $2, about = $3, badge_achievement_id = $4, updated_at = $5 WHERE id = $1',
      [userId, profile.name, profile.about ?? '', profile.badgeAchievementId ?? null, Math.floor(Date.now() / 1000)]);
    if (client) await client.query('COMMIT');
    return row ? { revision: Number(row.revision), payload: row.payload } : null;
    } catch (error) { if (client) await client.query('ROLLBACK'); throw error; }
    finally { client?.release(); }
  }

  private async rows<T>(sql: string, params: unknown[] = []): Promise<T[]> {
    const result = await this.db.query(sql, params);
    return result.rows.map((row) => normalize<T>(row as Record<string, unknown>));
  }

  private async one<T>(sql: string, params: unknown[] = []): Promise<T | null> {
    return (await this.rows<T>(sql, params))[0] ?? null;
  }

  private async execute(sql: string, params: unknown[] = []): Promise<void> {
    await this.db.query(sql, params);
  }

  async userById(id: string): Promise<UserRow | null> {
    return this.one<UserRow>('SELECT * FROM users WHERE id = $1', [id]);
  }

  async searchUsers(term: string): Promise<Array<Pick<UserRow, 'id' | 'display_name'>>> {
    return this.rows(
      'SELECT id, display_name FROM users WHERE display_name ILIKE $1 LIMIT 20',
      [`%${term}%`],
    );
  }

  async libraryVisible(userId: string): Promise<boolean> {
    const row = await this.one<{ library_visible: boolean }>(
      'SELECT library_visible FROM user_privacy WHERE user_id = $1',
      [userId],
    );
    return row?.library_visible ?? true;
  }

  async setLibraryVisible(userId: string, visible: boolean): Promise<void> {
    await this.execute(
      `INSERT INTO user_privacy (user_id, library_visible, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (user_id) DO UPDATE SET library_visible = EXCLUDED.library_visible,
         updated_at = EXCLUDED.updated_at`,
      [userId, visible, Date.now()],
    );
  }

  async friendEdges(userId: string): Promise<Array<{ peer_id: string; status: string }>> {
    return this.rows(
      'SELECT peer_id, status FROM user_friend_edges WHERE user_id = $1',
      [userId],
    );
  }

  async friendStatus(userId: string, peerId: string): Promise<string | null> {
    const edge = await this.one<{ status: string }>(
      'SELECT status FROM user_friend_edges WHERE user_id = $1 AND peer_id = $2',
      [userId, peerId],
    );
    return edge?.status ?? null;
  }

  private async upsertEdge(userId: string, peerId: string, status: string, now: number): Promise<void> {
    await this.execute(
      `INSERT INTO user_friend_edges (user_id, peer_id, status, updated_at) VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id, peer_id) DO UPDATE SET status = EXCLUDED.status,
         updated_at = EXCLUDED.updated_at`,
      [userId, peerId, status, now],
    );
  }

  async setFriendPair(userId: string, peerId: string, myStatus: string, peerStatus: string): Promise<void> {
    const now = Date.now();
    // Both directed edges are one atomic write; a crash cannot leave half an accepted friendship.
    const first = userId < peerId ? [userId,peerId,myStatus,peerStatus] : [peerId,userId,peerStatus,myStatus];
    await this.db.query(`INSERT INTO user_friend_edges (user_id,peer_id,status,updated_at)
      VALUES ($1,$2,$3,$5),($2,$1,$4,$5) ON CONFLICT (user_id,peer_id) DO UPDATE
      SET status=EXCLUDED.status,updated_at=EXCLUDED.updated_at`,[...first,now]);
  }

  async achievements(userId: string): Promise<Array<{ achievement_id: string; unlocked_at: number }>> {
    return this.rows(
      'SELECT achievement_id, unlocked_at FROM user_achievements WHERE user_id = $1',
      [userId],
    );
  }

  /** Stores unlocks, keeping the earliest timestamp per achievement. */
  async mergeAchievements(userId: string, unlocks: Map<string, number>): Promise<void> {
    for (const [achievementId, unlockedAt] of unlocks) {
      await this.execute(
        `INSERT INTO user_achievements (user_id, achievement_id, unlocked_at) VALUES ($1, $2, $3)
         ON CONFLICT (user_id, achievement_id) DO UPDATE
           SET unlocked_at = LEAST(user_achievements.unlocked_at, EXCLUDED.unlocked_at)`,
        [userId, achievementId, unlockedAt],
      );
    }
  }

  async deleteFriendPair(userId: string, peerId: string): Promise<void> {
    await this.execute(
      `DELETE FROM user_friend_edges
       WHERE (user_id = $1 AND peer_id = $2) OR (user_id = $2 AND peer_id = $1)`,
      [userId, peerId],
    );
  }

  async userByEmail(email: string): Promise<UserRow | null> {
    const mapping = await this.one<{ user_id: string }>(
      'SELECT user_id FROM users_by_email WHERE email = $1',
      [email],
    );
    return mapping ? this.userById(mapping.user_id) : null;
  }

  async userByExternal(provider: string, subject: string): Promise<UserRow | null> {
    const account = await this.one<ExternalAccount>(
      'SELECT * FROM external_accounts WHERE provider = $1 AND provider_subject = $2',
      [provider, subject],
    );
    return account ? this.userById(account.user_id) : null;
  }

  async createUser(user: UserRow): Promise<void> {
    await this.execute(
      `INSERT INTO users (id, email, display_name, email_verified_at, created_at, updated_at, last_seen_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name,
         email_verified_at = EXCLUDED.email_verified_at, created_at = EXCLUDED.created_at,
         updated_at = EXCLUDED.updated_at, last_seen_at = EXCLUDED.last_seen_at`,
      [
        user.id, user.email, user.display_name, user.email_verified_at,
        user.created_at, user.updated_at, user.last_seen_at ?? user.created_at,
      ],
    );
    await this.execute(
      `INSERT INTO users_by_email (email, user_id) VALUES ($1, $2)
       ON CONFLICT (email) DO UPDATE SET user_id = EXCLUDED.user_id`,
      [user.email, user.id],
    );
    await this.execute(
      `INSERT INTO user_settings (user_id, theme_mode, smart_connection_enabled, updated_at)
       VALUES ($1, 'system', true, $2)
       ON CONFLICT (user_id) DO UPDATE SET theme_mode = EXCLUDED.theme_mode,
         smart_connection_enabled = EXCLUDED.smart_connection_enabled, updated_at = EXCLUDED.updated_at`,
      [user.id, user.updated_at],
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
      `INSERT INTO external_accounts (provider, provider_subject, user_id, provider_email, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $5)
       ON CONFLICT (provider, provider_subject) DO UPDATE SET user_id = EXCLUDED.user_id,
         provider_email = EXCLUDED.provider_email, created_at = EXCLUDED.created_at,
         updated_at = EXCLUDED.updated_at`,
      [provider, subject, userId, email ?? '', timestamp],
    );
    await this.execute(
      `INSERT INTO external_accounts_by_user (user_id, provider, provider_subject) VALUES ($1, $2, $3)
       ON CONFLICT (user_id, provider) DO UPDATE SET provider_subject = EXCLUDED.provider_subject`,
      [userId, provider, subject],
    );
  }

  async linkedAccount(userId: string, provider: string): Promise<ExternalAccount | null> {
    const mapping = await this.one<{ provider_subject: string }>(
      'SELECT provider_subject FROM external_accounts_by_user WHERE user_id = $1 AND provider = $2',
      [userId, provider],
    );
    if (!mapping) return null;
    return this.one<ExternalAccount>(
      'SELECT * FROM external_accounts WHERE provider = $1 AND provider_subject = $2',
      [provider, mapping.provider_subject],
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
      `INSERT INTO sessions (id, user_id, access_token_hash, refresh_token_hash, access_expires_at,
         refresh_expires_at, created_at, last_used_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $7)`,
      [id, userId, accessHash, refreshHash, accessExpiresAt, refreshExpiresAt, now],
    );
    await this.execute(
      `INSERT INTO access_tokens (token_hash, session_id, expires_at) VALUES ($1, $2, $3)
       ON CONFLICT (token_hash) DO UPDATE SET session_id = EXCLUDED.session_id, expires_at = EXCLUDED.expires_at`,
      [accessHash, id, accessExpiresAt],
    );
    await this.execute(
      `INSERT INTO refresh_tokens (token_hash, session_id, expires_at) VALUES ($1, $2, $3)
       ON CONFLICT (token_hash) DO UPDATE SET session_id = EXCLUDED.session_id, expires_at = EXCLUDED.expires_at`,
      [refreshHash, id, refreshExpiresAt],
    );
    return id;
  }

  private async sessionByToken(table: 'access_tokens' | 'refresh_tokens', hash: string, now: number): Promise<SessionRow | null> {
    const token = await this.one<{ session_id: string; expires_at: number }>(
      `SELECT * FROM ${table} WHERE token_hash = $1`,
      [hash],
    );
    if (!token || token.expires_at <= now) return null;
    return this.one<SessionRow>('SELECT * FROM sessions WHERE id = $1', [token.session_id]);
  }

  async sessionByAccessHash(hash: string, now: number): Promise<SessionRow | null> {
    return this.sessionByToken('access_tokens', hash, now);
  }

  async sessionByRefreshHash(hash: string, now: number): Promise<SessionRow | null> {
    return this.sessionByToken('refresh_tokens', hash, now);
  }

  async deleteSession(session: SessionRow): Promise<void> {
    await this.execute('DELETE FROM access_tokens WHERE token_hash = $1', [session.access_token_hash]);
    await this.execute('DELETE FROM refresh_tokens WHERE token_hash = $1', [session.refresh_token_hash]);
    await this.execute('DELETE FROM sessions WHERE id = $1', [session.id]);
  }

  async touchSession(userId: string, sessionId: string, now: number): Promise<void> {
    await this.execute('UPDATE users SET last_seen_at = $1 WHERE id = $2', [now, userId]);
    await this.execute('UPDATE sessions SET last_used_at = $1 WHERE id = $2', [now, sessionId]);
  }

  async updateProfileExtras(userId: string, about: string | null, badge: string | null, now: number): Promise<void> {
    await this.execute(
      'UPDATE users SET about = $1, badge_achievement_id = $2, updated_at = $3 WHERE id = $4',
      [about, badge, now, userId],
    );
  }

  async updateDisplayName(userId: string, displayName: string, now: number): Promise<void> {
    await this.execute(
      'UPDATE users SET display_name = $1, updated_at = $2 WHERE id = $3',
      [displayName, now, userId],
    );
  }

  async mediaForUser(userId: string): Promise<MediaRow[]> {
    return this.rows<MediaRow>('SELECT * FROM profile_media WHERE user_id = $1', [userId]);
  }

  async putMedia(metadata: MediaRow & { user_id: string }): Promise<void> {
    await this.execute(
      `INSERT INTO profile_media (user_id, kind, object_key, content_type, updated_at) VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (user_id, kind) DO UPDATE SET object_key = EXCLUDED.object_key,
         content_type = EXCLUDED.content_type, updated_at = EXCLUDED.updated_at`,
      [metadata.user_id, metadata.kind, metadata.object_key, metadata.content_type, metadata.updated_at],
    );
  }

  async deleteMedia(userId: string, kind: string): Promise<MediaRow | null> {
    const existing = await this.one<MediaRow>(
      'SELECT * FROM profile_media WHERE user_id = $1 AND kind = $2',
      [userId, kind],
    );
    await this.execute('DELETE FROM profile_media WHERE user_id = $1 AND kind = $2', [userId, kind]);
    return existing;
  }

  async libraryEntry(userId: string, shikimoriId: number): Promise<LibraryEntry | null> {
    return this.one<LibraryEntry>(
      'SELECT * FROM user_anime_entries WHERE user_id = $1 AND shikimori_id = $2',
      [userId, shikimoriId],
    );
  }

  async libraryEntries(userId: string): Promise<LibraryEntry[]> {
    return this.rows<LibraryEntry>(
      `SELECT * FROM user_anime_entries WHERE user_id = $1
       ORDER BY updated_at DESC, shikimori_id DESC`,
      [userId],
    );
  }

  async upsertLibraryEntry(userId: string, entry: LibraryEntry): Promise<void> {
    await this.upsertLibraryEntries(userId, [entry]);
  }

  async upsertLibraryEntries(userId: string, entries: LibraryEntry[]): Promise<void> {
    // Batches keep each statement well below PostgreSQL's parameter limit.
    const batchSize = 500;
    for (let offset = 0; offset < entries.length; offset += batchSize) {
      const batch = entries.slice(offset, offset + batchSize);
      const params: unknown[] = [];
      const values = batch.map((entry, index) => {
        const base = index * 6;
        params.push(
          userId, entry.shikimori_id, entry.status, entry.score,
          entry.episodes_watched, entry.updated_at,
        );
        return `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, $${base + 5}, $${base + 6})`;
      });
      await this.execute(
        `INSERT INTO user_anime_entries (user_id, shikimori_id, status, score, episodes_watched, updated_at)
         VALUES ${values.join(', ')}
         ON CONFLICT (user_id, shikimori_id) DO UPDATE SET status = EXCLUDED.status,
           score = EXCLUDED.score, episodes_watched = EXCLUDED.episodes_watched,
           updated_at = EXCLUDED.updated_at`,
        params,
      );
    }
  }

  async addHistory(
    userId: string,
    action: string,
    timestamp: number,
    shikimoriId?: number,
    metadata?: Record<string, unknown>,
    id: string = randomUUID(),
  ): Promise<void> {
    await this.execute(
      `INSERT INTO user_history (user_id, created_at, id, action, shikimori_id, metadata_json)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (user_id, created_at, id) DO UPDATE SET action = EXCLUDED.action,
         shikimori_id = EXCLUDED.shikimori_id, metadata_json = EXCLUDED.metadata_json`,
      [userId, timestamp, id, action, shikimoriId ?? 0, JSON.stringify(metadata ?? {})],
    );
  }

  async historyForUser(userId: string): Promise<Array<{
    created_at: number;
    action: string;
    shikimori_id: number;
    metadata_json: string;
  }>> {
    return this.rows(
      `SELECT created_at, action, shikimori_id, metadata_json FROM user_history
       WHERE user_id = $1 ORDER BY created_at DESC`,
      [userId],
    );
  }

  async createOAuthTransaction(stateHash: string, returnUri: string, now: number, expiresAt: number): Promise<void> {
    await this.execute(
      `INSERT INTO oauth_transactions (state_hash, return_uri, created_at, expires_at) VALUES ($1, $2, $3, $4)
       ON CONFLICT (state_hash) DO UPDATE SET return_uri = EXCLUDED.return_uri,
         created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at`,
      [stateHash, returnUri, now, expiresAt],
    );
  }

  async oauthTransaction(stateHash: string): Promise<OAuthTransaction | null> {
    return this.one<OAuthTransaction>('SELECT * FROM oauth_transactions WHERE state_hash = $1', [stateHash]);
  }

  async deleteOAuthTransaction(stateHash: string): Promise<void> {
    await this.execute('DELETE FROM oauth_transactions WHERE state_hash = $1', [stateHash]);
  }

  async createAuthTicket(ticketHash: string, stateHash: string, userId: string, now: number, expiresAt: number): Promise<void> {
    await this.execute(
      `INSERT INTO auth_tickets (ticket_hash, state_hash, user_id, created_at, expires_at) VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (ticket_hash) DO UPDATE SET state_hash = EXCLUDED.state_hash, user_id = EXCLUDED.user_id,
         created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at`,
      [ticketHash, stateHash, userId, now, expiresAt],
    );
  }

  async authTicket(ticketHash: string): Promise<AuthTicket | null> {
    return this.one<AuthTicket>('SELECT * FROM auth_tickets WHERE ticket_hash = $1', [ticketHash]);
  }

  async consumeAuthTicket(ticketHash: string, now: number): Promise<void> {
    await this.execute('UPDATE auth_tickets SET consumed_at = $1 WHERE ticket_hash = $2', [now, ticketHash]);
  }
}

/** Everything the API handlers use from storage. */
export type Store = Pick<PgStore, keyof PgStore>;
