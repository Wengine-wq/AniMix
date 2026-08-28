import { randomUUID } from 'node:crypto';

import { runtimeConfig } from './config';
import { epochIso, nowSeconds, pepperedHash, randomToken, timingSafeTextEqual } from './crypto';
import { ProfileMediaStorage } from './media';
import { CloudFunctionEvent, CloudFunctionResponse, LibraryEntry, RuntimeConfig, UserRow } from './types';
import { YdbStore } from './ydb';

const SHIKIMORI_TOKEN_URL = 'https://shikimori.io/oauth/token';
const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const GOOGLE_USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';

const ACCESS_TTL_SECONDS = 6 * 60 * 60;
const REFRESH_TTL_SECONDS = 90 * 24 * 60 * 60;
const OAUTH_STATE_TTL_SECONDS = 5 * 60;
const AUTH_TICKET_TTL_SECONDS = 2 * 60;
const LAST_SEEN_UPDATE_SECONDS = 60;
const MAX_PROFILE_MEDIA_BYTES = 1_500 * 1024;
const MAX_IMPORT_PAYLOAD_ENTRIES = 50_000;
const MAX_IMPORT_UNIQUE_ENTRIES = 5_000;
const LIBRARY_STATUSES = new Set(['planned', 'watching', 'completed', 'on_hold', 'dropped', 'rewatching']);

let cachedConfig: RuntimeConfig | null = null;
let cachedStore: YdbStore | null = null;
let cachedMedia: ProfileMediaStorage | null = null;

function services(): { config: RuntimeConfig; store: YdbStore; media: ProfileMediaStorage } {
  if (!cachedConfig) cachedConfig = runtimeConfig();
  if (!cachedStore) cachedStore = new YdbStore(cachedConfig);
  if (!cachedMedia) cachedMedia = new ProfileMediaStorage(cachedConfig);
  return { config: cachedConfig, store: cachedStore, media: cachedMedia };
}

function header(event: CloudFunctionEvent, name: string): string {
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(event.headers ?? {})) {
    if (key.toLowerCase() === target) return value?.trim() ?? '';
  }
  return '';
}

function corsHeaders(event: CloudFunctionEvent, config: RuntimeConfig): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    Vary: 'Origin',
  };
  const origin = header(event, 'origin');
  if (origin && origin === config.allowedWebOrigin) {
    headers['Access-Control-Allow-Origin'] = origin;
    headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization';
    headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, PATCH, DELETE, OPTIONS';
  }
  return headers;
}

function json(
  event: CloudFunctionEvent,
  config: RuntimeConfig,
  payload: Record<string, unknown>,
  statusCode = 200,
): CloudFunctionResponse {
  return { statusCode, headers: corsHeaders(event, config), body: JSON.stringify(payload) };
}

function redirect(location: string): CloudFunctionResponse {
  return {
    statusCode: 302,
    headers: { Location: location, 'Cache-Control': 'no-store' },
    body: '',
  };
}

function appRedirect(returnUri: string, params: Record<string, string>): CloudFunctionResponse {
  const target = new URL(returnUri);
  for (const [key, value] of Object.entries(params)) target.searchParams.set(key, value);
  return redirect(target.toString());
}

function bearerToken(event: CloudFunctionEvent): string {
  const value = header(event, 'authorization');
  return value.startsWith('Bearer ') ? value.slice('Bearer '.length).trim() : '';
}

function query(event: CloudFunctionEvent, name: string): string {
  return event.queryStringParameters?.[name]?.trim() ?? '';
}

function bodyBuffer(event: CloudFunctionEvent): Buffer {
  const body = event.body ?? '';
  return event.isBase64Encoded ? Buffer.from(body, 'base64') : Buffer.from(body, 'utf8');
}

function jsonBody(event: CloudFunctionEvent): Record<string, unknown> {
  try {
    const parsed = JSON.parse(bodyBuffer(event).toString('utf8')) as unknown;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {};
  } catch {
    return {};
  }
}

function normalizeEmail(value: unknown): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

function safeDisplayName(value: unknown, email: string): string {
  const candidate = typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : '';
  const fallback = email.split('@')[0].replace(/[^a-zA-Zа-яА-Я0-9 _.-]/g, '').trim();
  return (candidate || fallback || 'AniMix User').slice(0, 32);
}

function readLibraryEntry(payload: Record<string, unknown>): Omit<LibraryEntry, 'shikimori_id' | 'updated_at'> | null {
  const status = typeof payload.status === 'string' ? payload.status.trim() : '';
  const score = Number(payload.score ?? 0);
  const episodesWatched = Number(payload.episodes_watched ?? payload.episodes ?? 0);
  if (!LIBRARY_STATUSES.has(status) || !Number.isInteger(score) || score < 0 || score > 10) return null;
  if (!Number.isInteger(episodesWatched) || episodesWatched < 0) return null;
  return { status, score, episodes_watched: episodesWatched };
}

function shikimoriAnimeId(payload: Record<string, unknown>): number {
  const anime = payload.anime;
  const nestedId = anime && typeof anime === 'object'
    ? (anime as Record<string, unknown>).id
    : undefined;
  return Number(payload.shikimori_id ?? payload.target_id ?? nestedId ?? 0);
}

async function currentSession(event: CloudFunctionEvent, store: YdbStore): Promise<{ user: UserRow; sessionId: string } | null> {
  const token = bearerToken(event);
  if (!token) return null;
  const now = nowSeconds();
  const session = await store.sessionByAccessHash(pepperedHash(token, services().config.authTokenPepper), now);
  if (!session) return null;
  const user = await store.userById(session.user_id);
  if (!user) return null;
  if (now - Number(user.last_seen_at ?? 0) >= LAST_SEEN_UPDATE_SECONDS) {
    await store.touchSession(user.id, session.id, now);
    user.last_seen_at = now;
  }
  return { user, sessionId: session.id };
}

async function profile(user: UserRow, store: YdbStore, media: ProfileMediaStorage): Promise<Record<string, unknown>> {
  const [entries, mediaRows, shikimori] = await Promise.all([
    store.libraryEntries(user.id),
    store.mediaForUser(user.id),
    store.linkedAccount(user.id, 'shikimori'),
  ]);
  const counts = Object.fromEntries([...LIBRARY_STATUSES].map((status) => [status, 0])) as Record<string, number>;
  let scores = 0;
  let episodesWatched = 0;
  for (const entry of entries) {
    counts[entry.status] = (counts[entry.status] ?? 0) + 1;
    if (entry.score > 0) scores++;
    episodesWatched += entry.episodes_watched;
  }
  const avatar = mediaRows.find((row) => row.kind === 'avatar');
  const banner = mediaRows.find((row) => row.kind === 'banner');
  const profileVersion = Math.max(
    Number(user.updated_at),
    Number(avatar?.updated_at ?? 0),
    Number(banner?.updated_at ?? 0),
  );
  return {
    id: user.id,
    email: user.email,
    display_name: user.display_name,
    avatar_url: avatar ? media.publicUrl(avatar.object_key) : null,
    banner_url: banner ? media.publicUrl(banner.object_key) : null,
    email_verified_at: epochIso(user.email_verified_at),
    created_at: epochIso(user.created_at),
    last_online_at: epochIso(user.last_seen_at),
    shikimori_linked: shikimori !== null,
    shikimori_user_id: shikimori?.provider_subject ?? null,
    profile_version: profileVersion,
    stats: {
      total: entries.length,
      completed: counts.completed,
      watching: counts.watching,
      planned: counts.planned,
      on_hold: counts.on_hold,
      dropped: counts.dropped,
      rewatching: counts.rewatching,
      scores,
      episodes_watched: episodesWatched,
    },
  };
}

async function createSession(userId: string, store: YdbStore, config: RuntimeConfig): Promise<Record<string, unknown>> {
  const accessToken = randomToken();
  const refreshToken = randomToken(48);
  const now = nowSeconds();
  await store.createSession(
    userId,
    pepperedHash(accessToken, config.authTokenPepper),
    pepperedHash(refreshToken, config.authTokenPepper),
    now + ACCESS_TTL_SECONDS,
    now + REFRESH_TTL_SECONDS,
    now,
  );
  return {
    access_token: accessToken,
    refresh_token: refreshToken,
    token_type: 'Bearer',
    expires_in: ACCESS_TTL_SECONDS,
    refresh_expires_in: REFRESH_TTL_SECONDS,
  };
}

async function googleStart(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore): Promise<CloudFunctionResponse> {
  const returnUri = query(event, 'return_uri');
  if (returnUri !== config.allowedAppReturnUri) return json(event, config, { error: 'invalid_return_uri' }, 400);
  const state = randomToken(32);
  const now = nowSeconds();
  await store.createOAuthTransaction(
    pepperedHash(state, config.authTokenPepper),
    returnUri,
    now,
    now + OAUTH_STATE_TTL_SECONDS,
  );
  const authorizeUrl = new URL(GOOGLE_AUTH_URL);
  authorizeUrl.search = new URLSearchParams({
    client_id: config.googleClientId,
    redirect_uri: config.googleRedirectUri,
    response_type: 'code',
    scope: 'openid email profile',
    state,
    prompt: 'select_account',
  }).toString();
  return redirect(authorizeUrl.toString());
}

async function googleCallback(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore): Promise<CloudFunctionResponse> {
  const state = query(event, 'state');
  const code = query(event, 'code');
  const stateHash = state ? pepperedHash(state, config.authTokenPepper) : '';
  const transaction = stateHash ? await store.oauthTransaction(stateHash) : null;
  if (!transaction || Number(transaction.expires_at) <= nowSeconds()) {
    return { statusCode: 400, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'OAuth state is invalid or expired.' };
  }
  if (!code) return appRedirect(transaction.return_uri, { error: query(event, 'error') || 'google_authorization_failed' });

  const tokenResponse = await fetch(GOOGLE_TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams({
      code,
      client_id: config.googleClientId,
      client_secret: config.googleClientSecret,
      redirect_uri: config.googleRedirectUri,
      grant_type: 'authorization_code',
    }),
  });
  if (!tokenResponse.ok) {
    const error = (await tokenResponse.json().catch(() => null) as { error?: string } | null)?.error ?? 'exchange_failed';
    console.warn(JSON.stringify({ event: 'google_token_exchange_failed', status: tokenResponse.status, error }));
    return appRedirect(transaction.return_uri, { error: `google_token_${error}` });
  }
  const googleToken = (await tokenResponse.json() as { access_token?: string }).access_token ?? '';
  if (!googleToken) return appRedirect(transaction.return_uri, { error: 'google_access_token_missing' });
  const userInfoResponse = await fetch(GOOGLE_USERINFO_URL, {
    headers: { Authorization: `Bearer ${googleToken}`, Accept: 'application/json' },
  });
  if (!userInfoResponse.ok) return appRedirect(transaction.return_uri, { error: 'google_profile_failed' });
  const userInfo = await userInfoResponse.json() as Record<string, unknown>;
  const subject = userInfo.sub?.toString().trim() ?? '';
  const email = normalizeEmail(userInfo.email);
  const verified = userInfo.email_verified === true || userInfo.email_verified?.toString() === 'true';
  if (!subject || !email || !verified) return appRedirect(transaction.return_uri, { error: 'google_email_not_verified' });

  let user = await store.userByExternal('google', subject) ?? await store.userByEmail(email);
  const now = nowSeconds();
  if (!user) {
    user = {
      id: randomUUID(),
      email,
      display_name: safeDisplayName(userInfo.name, email),
      email_verified_at: now,
      created_at: now,
      updated_at: now,
      last_seen_at: now,
    };
    await store.createUser(user);
  }
  await store.upsertExternalAccount('google', subject, user.id, email, now);
  await store.deleteOAuthTransaction(stateHash);
  const ticket = randomToken(32);
  await store.createAuthTicket(
    pepperedHash(ticket, config.authTokenPepper),
    stateHash,
    user.id,
    now,
    now + AUTH_TICKET_TTL_SECONDS,
  );
  return appRedirect(transaction.return_uri, { ticket, state });
}

async function googleExchange(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage): Promise<CloudFunctionResponse> {
  const input = jsonBody(event);
  const ticket = typeof input.ticket === 'string' ? input.ticket.trim() : '';
  const state = typeof input.state === 'string' ? input.state.trim() : '';
  if (!ticket || !state) return json(event, config, { error: 'invalid_google_ticket' }, 400);
  const ticketHash = pepperedHash(ticket, config.authTokenPepper);
  const record = await store.authTicket(ticketHash);
  const stateHash = pepperedHash(state, config.authTokenPepper);
  if (!record || record.consumed_at || Number(record.expires_at) <= nowSeconds() || !timingSafeTextEqual(record.state_hash, stateHash)) {
    return json(event, config, { error: 'invalid_google_ticket' }, 401);
  }
  const user = await store.userById(record.user_id);
  if (!user) return json(event, config, { error: 'account_unavailable' }, 404);
  const userProfile = await profile(user, store, media);
  await store.consumeAuthTicket(ticketHash, nowSeconds());
  return json(event, config, { ...(await createSession(user.id, store, config)), user: userProfile });
}

async function refresh(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage): Promise<CloudFunctionResponse> {
  const input = jsonBody(event);
  const refreshToken = typeof input.refresh_token === 'string' ? input.refresh_token.trim() : '';
  if (!refreshToken) return json(event, config, { error: 'invalid_refresh_token' }, 401);
  const session = await store.sessionByRefreshHash(pepperedHash(refreshToken, config.authTokenPepper), nowSeconds());
  if (!session) return json(event, config, { error: 'invalid_refresh_token' }, 401);
  const user = await store.userById(session.user_id);
  if (!user) return json(event, config, { error: 'account_unavailable' }, 404);
  const userProfile = await profile(user, store, media);
  await store.deleteSession(session);
  return json(event, config, { ...(await createSession(user.id, store, config)), user: userProfile });
}

async function updateProfile(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage): Promise<CloudFunctionResponse> {
  const session = await currentSession(event, store);
  if (!session) return json(event, config, { error: 'unauthorized' }, 401);
  const raw = jsonBody(event).display_name;
  const displayName = typeof raw === 'string' ? raw.trim().replace(/\s+/g, ' ').slice(0, 32) : '';
  if (!displayName) return json(event, config, { error: 'invalid_display_name' }, 400);
  await store.updateDisplayName(session.user.id, displayName, nowSeconds());
  const updated = await store.userById(session.user.id);
  return json(event, config, { user: updated ? await profile(updated, store, media) : null });
}

async function changeMedia(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage, kind: 'avatar' | 'banner'): Promise<CloudFunctionResponse> {
  const session = await currentSession(event, store);
  if (!session) return json(event, config, { error: 'unauthorized' }, 401);
  const contentType = header(event, 'content-type').split(';')[0].trim().toLowerCase();
  const data = bodyBuffer(event);
  if (contentType !== 'image/jpeg') return json(event, config, { error: 'unsupported_profile_media' }, 415);
  if (!data.length || data.length > MAX_PROFILE_MEDIA_BYTES) return json(event, config, { error: 'profile_media_too_large' }, 413);
  const updatedAt = Date.now();
  const previous = (await store.mediaForUser(session.user.id)).find((item) => item.kind === kind);
  const objectKey = media.objectKey(session.user.id, kind, updatedAt);
  await media.upload(objectKey, data);
  await store.putMedia({ user_id: session.user.id, kind, object_key: objectKey, content_type: contentType, updated_at: updatedAt });
  if (previous) await media.remove(previous.object_key).catch(() => undefined);
  const updated = await store.userById(session.user.id);
  return json(event, config, { user: updated ? await profile(updated, store, media) : null });
}

async function removeMedia(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage, kind: 'avatar' | 'banner'): Promise<CloudFunctionResponse> {
  const session = await currentSession(event, store);
  if (!session) return json(event, config, { error: 'unauthorized' }, 401);
  const previous = await store.deleteMedia(session.user.id, kind);
  if (previous) await media.remove(previous.object_key).catch(() => undefined);
  const updated = await store.userById(session.user.id);
  return json(event, config, { user: updated ? await profile(updated, store, media) : null });
}

async function publicMedia(
  event: CloudFunctionEvent,
  config: RuntimeConfig,
  store: YdbStore,
  media: ProfileMediaStorage,
  userId: string,
  kind: 'avatar' | 'banner',
): Promise<CloudFunctionResponse> {
  const item = (await store.mediaForUser(userId)).find((row) => row.kind === kind);
  if (!item) return json(event, config, { error: 'not_found' }, 404);
  // Old clients used this route. Keep it as a redirect so moving media from
  // D1 blobs to Object Storage does not break a cached public profile URL.
  return redirect(media.publicUrl(item.object_key));
}

async function publicProfile(
  event: CloudFunctionEvent,
  config: RuntimeConfig,
  store: YdbStore,
  media: ProfileMediaStorage,
  userId: string,
): Promise<CloudFunctionResponse> {
  const user = await store.userById(userId);
  if (!user) return json(event, config, { error: 'not_found' }, 404);
  const result = await profile(user, store, media);
  delete result.email;
  delete result.email_verified_at;
  return json(event, config, { user: result });
}

async function library(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage, animeId?: number): Promise<CloudFunctionResponse> {
  const session = await currentSession(event, store);
  if (!session) return json(event, config, { error: 'unauthorized' }, 401);
  if (event.httpMethod === 'GET') {
    if (animeId) return json(event, config, { entry: await store.libraryEntry(session.user.id, animeId) });
    return json(event, config, { entries: await store.libraryEntries(session.user.id) });
  }
  if (!animeId) return json(event, config, { error: 'method_not_allowed' }, 405);
  const entry = readLibraryEntry(jsonBody(event));
  if (!entry) return json(event, config, { error: 'invalid_library_entry' }, 400);
  const saved: LibraryEntry = { ...entry, shikimori_id: animeId, updated_at: nowSeconds() };
  await store.upsertLibraryEntry(session.user.id, saved);
  await store.addHistory(session.user.id, 'library_entry_updated', saved.updated_at, animeId, {
    status: saved.status,
    score: saved.score,
    episodes_watched: saved.episodes_watched,
  });
  const user = await store.userById(session.user.id);
  return json(event, config, { entry: saved, user: user ? await profile(user, store, media) : null });
}

async function importShikimori(event: CloudFunctionEvent, config: RuntimeConfig, store: YdbStore, media: ProfileMediaStorage): Promise<CloudFunctionResponse> {
  const session = await currentSession(event, store);
  if (!session) return json(event, config, { error: 'unauthorized' }, 401);
  const input = jsonBody(event);
  const shikimoriUserId = String(input.shikimori_user_id ?? '').trim();
  const rawEntries = input.entries;
  if (!/^\d+$/.test(shikimoriUserId)) {
    return json(event, config, { error: 'invalid_shikimori_import', reason: 'invalid_user_id' }, 400);
  }
  if (!Array.isArray(rawEntries)) {
    return json(event, config, { error: 'invalid_shikimori_import', reason: 'entries_not_array' }, 400);
  }
  if (rawEntries.length > MAX_IMPORT_PAYLOAD_ENTRIES) {
    return json(event, config, { error: 'invalid_shikimori_import', reason: 'too_many_entries' }, 400);
  }
  const linkedToSomeone = await store.userByExternal('shikimori', shikimoriUserId);
  if (linkedToSomeone && linkedToSomeone.id !== session.user.id) {
    return json(event, config, { error: 'shikimori_account_already_linked' }, 409);
  }
  const existing = await store.linkedAccount(session.user.id, 'shikimori');
  if (existing && existing.provider_subject !== shikimoriUserId) {
    return json(event, config, { error: 'shikimori_relink_not_supported' }, 409);
  }
  const normalizedById = new Map<number, LibraryEntry>();
  let skipped = 0;
  const timestamp = nowSeconds();
  for (const raw of rawEntries) {
    if (!raw || typeof raw !== 'object') {
      skipped++;
      continue;
    }
    const source = raw as Record<string, unknown>;
    const shikimoriId = shikimoriAnimeId(source);
    const entry = readLibraryEntry(source);
    if (!entry || !Number.isSafeInteger(shikimoriId) || shikimoriId <= 0) {
      skipped++;
      continue;
    }
    normalizedById.set(shikimoriId, { ...entry, shikimori_id: shikimoriId, updated_at: timestamp });
  }
  const normalized = [...normalizedById.values()];
  if (normalized.length > MAX_IMPORT_UNIQUE_ENTRIES) {
    return json(event, config, { error: 'invalid_shikimori_import', reason: 'too_many_unique_entries' }, 400);
  }
  if (normalized.length === 0) {
    return json(event, config, {
      error: 'invalid_shikimori_import',
      reason: 'no_valid_anime_entries',
      received: rawEntries.length,
      skipped,
    }, 400);
  }
  await store.upsertExternalAccount('shikimori', shikimoriUserId, session.user.id, null, timestamp);
  await store.upsertLibraryEntries(session.user.id, normalized);
  await store.addHistory(session.user.id, 'shikimori_library_imported', timestamp, undefined, { count: normalized.length });
  const user = await store.userById(session.user.id);
  return json(event, config, {
    imported: normalized.length,
    skipped,
    user: user ? await profile(user, store, media) : null,
  });
}

async function shikimoriTokenProxy(event: CloudFunctionEvent, config: RuntimeConfig): Promise<CloudFunctionResponse> {
  const input = jsonBody(event);
  const code = typeof input.code === 'string' ? input.code.trim() : '';
  const refreshToken = typeof input.refresh_token === 'string' ? input.refresh_token.trim() : '';
  const redirectUri = typeof input.redirect_uri === 'string' ? input.redirect_uri.trim() : '';
  const clientId = typeof input.client_id === 'string' ? input.client_id.trim() : '';
  const grantType = typeof input.grant_type === 'string' ? input.grant_type.trim() : code ? 'authorization_code' : '';
  if (clientId !== config.shikimoriClientId) return json(event, config, { error: 'invalid_request' }, 400);
  const form = new URLSearchParams({ grant_type: grantType, client_id: config.shikimoriClientId, client_secret: config.shikimoriClientSecret });
  if (grantType === 'authorization_code') {
    if (!code || redirectUri !== 'https://animix.app/callback') return json(event, config, { error: 'invalid_authorization_request' }, 400);
    form.set('code', code);
    form.set('redirect_uri', redirectUri);
  } else if (grantType === 'refresh_token') {
    if (!refreshToken) return json(event, config, { error: 'invalid_refresh_request' }, 400);
    form.set('refresh_token', refreshToken);
  } else {
    return json(event, config, { error: 'unsupported_grant_type' }, 400);
  }
  const upstream = await fetch(SHIKIMORI_TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json', 'User-Agent': 'AniMix OAuth proxy' },
    body: form,
  });
  return {
    statusCode: upstream.status,
    headers: corsHeaders(event, config),
    body: await upstream.text(),
  };
}

function requestPath(event: CloudFunctionEvent): string {
  const path = event.path?.trim() || '/';
  return path.startsWith('/') ? path : `/${path}`;
}

/** Yandex Cloud Functions entry point: `index.handler`. */
export async function handler(event: CloudFunctionEvent): Promise<CloudFunctionResponse> {
  let config: RuntimeConfig;
  let store: YdbStore;
  let media: ProfileMediaStorage;
  try {
    ({ config, store, media } = services());
  } catch (error) {
    console.error(JSON.stringify({ event: 'configuration_error', message: error instanceof Error ? error.message : String(error) }));
    return { statusCode: 503, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'service_unconfigured' }) };
  }
  const method = (event.httpMethod ?? 'GET').toUpperCase();
  const path = requestPath(event);
  if (method === 'OPTIONS') return { statusCode: 204, headers: corsHeaders(event, config), body: '' };
  try {
    if (method === 'GET' && (path === '/health' || path === '/v1/health')) return json(event, config, { ok: true, service: 'animix-yandex-api' });
    if (path === '/v1/auth/google/start' && method === 'GET') return googleStart(event, config, store);
    if (path === '/v1/auth/google/callback' && method === 'GET') return googleCallback(event, config, store);
    if (path === '/v1/auth/google/exchange' && method === 'POST') return googleExchange(event, config, store, media);
    if (path === '/v1/auth/refresh' && method === 'POST') return refresh(event, config, store, media);
    if (path === '/v1/auth/logout' && method === 'POST') {
      const current = await currentSession(event, store);
      if (current) {
        const token = bearerToken(event);
        const session = await store.sessionByAccessHash(pepperedHash(token, config.authTokenPepper), nowSeconds());
        if (session) await store.deleteSession(session);
      }
      return json(event, config, { ok: true });
    }
    if (path === '/v1/me' && method === 'GET') {
      const current = await currentSession(event, store);
      return current ? json(event, config, { user: await profile(current.user, store, media) }) : json(event, config, { error: 'unauthorized' }, 401);
    }
    if (path === '/v1/me/profile' && method === 'PATCH') return updateProfile(event, config, store, media);
    const mediaMatch = /^\/v1\/me\/media\/(avatar|banner)$/.exec(path);
    if (mediaMatch && method === 'PUT') return changeMedia(event, config, store, media, mediaMatch[1] as 'avatar' | 'banner');
    if (mediaMatch && method === 'DELETE') return removeMedia(event, config, store, media, mediaMatch[1] as 'avatar' | 'banner');
    const publicMediaMatch = /^\/v1\/users\/([0-9a-f-]{36})\/(avatar|banner)$/i.exec(path);
    if (publicMediaMatch && method === 'GET') {
      return publicMedia(event, config, store, media, publicMediaMatch[1], publicMediaMatch[2].toLowerCase() as 'avatar' | 'banner');
    }
    const publicProfileMatch = /^\/v1\/users\/([0-9a-f-]{36})$/i.exec(path);
    if (publicProfileMatch && method === 'GET') return publicProfile(event, config, store, media, publicProfileMatch[1]);
    if (path === '/v1/library' && method === 'GET') return library(event, config, store, media);
    const libraryMatch = /^\/v1\/library\/(\d+)$/.exec(path);
    if (libraryMatch && (method === 'GET' || method === 'PUT')) return library(event, config, store, media, Number(libraryMatch[1]));
    if ((path === '/v1/library/import/shikimori' || path === '/v1/integrations/shikimori/import') && method === 'POST') return importShikimori(event, config, store, media);
    if (method === 'POST') return shikimoriTokenProxy(event, config);
    return json(event, config, { error: 'not_found' }, 404);
  } catch (error) {
    console.error(JSON.stringify({
      event: 'request_failed',
      path,
      method,
      message: error instanceof Error ? error.message : String(error),
    }));
    return json(event, config, { error: 'service_unavailable' }, 503);
  }
}
