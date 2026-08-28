const SHIKIMORI_TOKEN_URL = 'https://shikimori.io/oauth/token';
const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const GOOGLE_USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';
const ALLOWED_REDIRECT_URIS = new Set(['https://animix.app/callback']);
const ALLOWED_APP_RETURN_URI = 'animix://oauth/callback';
const ACCESS_TTL_SECONDS = 6 * 60 * 60;
const REFRESH_TTL_SECONDS = 90 * 24 * 60 * 60;
const OAUTH_STATE_TTL_SECONDS = 5 * 60;
const AUTH_TICKET_TTL_SECONDS = 2 * 60;

function corsHeaders(request: Request, env: Env): Headers {
  const origin = request.headers.get('Origin');
  const allowedOrigin = env.ANIMIX_PROXY_ORIGIN?.trim();
  const headers = new Headers({
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    Vary: 'Origin',
  });
  if (origin && allowedOrigin && origin === allowedOrigin) {
    headers.set('Access-Control-Allow-Origin', origin);
    headers.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    headers.set('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
  }
  return headers;
}

function json(request: Request, env: Env, payload: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: corsHeaders(request, env) });
}

function nowSeconds(): number { return Math.floor(Date.now() / 1000); }

function epochIso(value: unknown): string | null {
  const raw = Number(value);
  if (!Number.isFinite(raw) || raw <= 0) return null;
  const milliseconds = raw >= 10_000_000_000 ? raw : raw * 1000;
  const date = new Date(milliseconds);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function randomToken(byteLength = 32): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

async function sha256(value: string): Promise<ArrayBuffer> {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
}

async function pepperedHash(value: string, pepper: string): Promise<string> {
  return base64Url(new Uint8Array(await sha256(`${pepper}:${value}`)));
}

async function timingSafeEqual(left: string, right: string): Promise<boolean> {
  const [a, b] = await Promise.all([sha256(left), sha256(right)]);
  return crypto.subtle.timingSafeEqual(a, b);
}

function bearerToken(request: Request): string {
  const header = request.headers.get('Authorization') ?? '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : '';
}

async function readBody(request: Request): Promise<Record<string, unknown>> {
  const value = (await request.json()) as unknown;
  return value && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function normalizeEmail(value: unknown): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

function safeDisplayName(value: unknown, email: string): string {
  const candidate = typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : '';
  const fallback = email.split('@')[0].replace(/[^a-zA-Zа-яА-Я0-9 _.-]/g, '').trim();
  return (candidate || fallback || 'AniMix User').slice(0, 32);
}

const libraryStatuses = new Set(['planned', 'watching', 'completed', 'on_hold', 'dropped', 'rewatching']);
const profileMediaKinds = new Set(['avatar', 'banner']);
const maxProfileMediaBytes = 1500 * 1024;
const lastSeenUpdateIntervalSeconds = 60;

function profileMediaUrl(request: Request, userId: string, kind: 'avatar' | 'banner', updatedAt: number): string {
  const url = new URL(`/v1/users/${encodeURIComponent(userId)}/${kind}`, request.url);
  if (updatedAt > 0) url.searchParams.set('v', String(updatedAt));
  return url.toString();
}

function googleIsConfigured(env: Env): boolean {
  return Boolean(
    env.GOOGLE_CLIENT_ID &&
      !env.GOOGLE_CLIENT_ID.startsWith('SET_') &&
      env.GOOGLE_CLIENT_SECRET &&
      env.GOOGLE_REDIRECT_URI,
  );
}

function appRedirect(returnUri: string, params: Record<string, string>): Response {
  const target = new URL(returnUri);
  for (const [key, value] of Object.entries(params)) target.searchParams.set(key, value);
  return new Response(null, {
    status: 302,
    headers: {
      Location: target.toString(),
      'Cache-Control': 'no-store',
    },
  });
}

async function createSession(env: Env, userId: string): Promise<Record<string, unknown>> {
  const accessToken = randomToken();
  const refreshToken = randomToken(48);
  const now = nowSeconds();
  const accessExpiresAt = now + ACCESS_TTL_SECONDS;
  const refreshExpiresAt = now + REFRESH_TTL_SECONDS;
  const [accessHash, refreshHash] = await Promise.all([
    pepperedHash(accessToken, env.AUTH_TOKEN_PEPPER),
    pepperedHash(refreshToken, env.AUTH_TOKEN_PEPPER),
  ]);
  await env.DB.prepare(
    `INSERT INTO sessions (id,user_id,access_token_hash,refresh_token_hash,access_expires_at,refresh_expires_at,created_at,last_used_at)
     VALUES (?,?,?,?,?,?,?,?)`,
  ).bind(crypto.randomUUID(), userId, accessHash, refreshHash, accessExpiresAt, refreshExpiresAt, now, now).run();
  return {
    access_token: accessToken,
    refresh_token: refreshToken,
    token_type: 'Bearer',
    expires_in: ACCESS_TTL_SECONDS,
    refresh_expires_in: REFRESH_TTL_SECONDS,
  };
}

async function findUserByAccessToken(env: Env, token: string): Promise<Record<string, unknown> | null> {
  if (!token) return null;
  const row = await env.DB.prepare(
    `SELECT u.id,u.email,u.display_name,u.avatar_url,u.banner_url,u.email_verified_at,u.created_at,u.updated_at,u.last_seen_at,s.id AS session_id
       FROM sessions s JOIN users u ON u.id=s.user_id
      WHERE s.access_token_hash=? AND s.revoked_at IS NULL AND s.access_expires_at>? LIMIT 1`,
  ).bind(await pepperedHash(token, env.AUTH_TOKEN_PEPPER), nowSeconds()).first<Record<string, unknown>>();
  if (!row) return null;
  const now = nowSeconds();
  const lastSeenAt = Number(row.last_seen_at ?? 0);
  if (now - lastSeenAt >= lastSeenUpdateIntervalSeconds) {
    await env.DB.batch([
      env.DB.prepare('UPDATE users SET last_seen_at=? WHERE id=?').bind(now, row.id),
      env.DB.prepare('UPDATE sessions SET last_used_at=? WHERE id=?').bind(now, row.session_id),
    ]);
    row.last_seen_at = now;
  }
  return row;
}

async function userResponse(request: Request, env: Env, user: Record<string, unknown>): Promise<Record<string, unknown>> {
  const [stats, media, shikimori] = await env.DB.batch([
    env.DB.prepare(
    `SELECT SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END) AS completed,
            SUM(CASE WHEN status='watching' THEN 1 ELSE 0 END) AS watching,
            SUM(CASE WHEN status='planned' THEN 1 ELSE 0 END) AS planned,
            SUM(CASE WHEN status='on_hold' THEN 1 ELSE 0 END) AS on_hold,
            SUM(CASE WHEN status='dropped' THEN 1 ELSE 0 END) AS dropped,
            SUM(CASE WHEN status='rewatching' THEN 1 ELSE 0 END) AS rewatching,
            SUM(CASE WHEN score > 0 THEN 1 ELSE 0 END) AS scores,
            SUM(episodes_watched) AS episodes_watched,
            COUNT(*) AS total FROM user_anime_entries WHERE user_id=?`,
    ).bind(String(user.id)),
    env.DB.prepare(
      'SELECT kind,updated_at FROM user_profile_media WHERE user_id=?',
    ).bind(String(user.id)),
    env.DB.prepare(
      "SELECT provider_subject FROM external_accounts WHERE user_id=? AND provider='shikimori' LIMIT 1",
    ).bind(String(user.id)),
  ]);
  const statsRow = stats.results[0] as Record<string, unknown> | undefined;
  const mediaRows = media.results as Array<Record<string, unknown>>;
  const avatar = mediaRows.find((row) => row.kind === 'avatar');
  const banner = mediaRows.find((row) => row.kind === 'banner');
  const shikimoriAccount = shikimori.results[0] as Record<string, unknown> | undefined;
  const profileVersion = Math.max(
    Number(user.updated_at ?? 0),
    Number(avatar?.updated_at ?? 0),
    Number(banner?.updated_at ?? 0),
  );
  return {
    id: String(user.id),
    email: user.email,
    display_name: user.display_name,
    avatar_url: avatar == null ? null : profileMediaUrl(request, String(user.id), 'avatar', Number(avatar.updated_at)),
    banner_url: banner == null ? null : profileMediaUrl(request, String(user.id), 'banner', Number(banner.updated_at)),
    email_verified_at: epochIso(user.email_verified_at),
    created_at: epochIso(user.created_at) ?? epochIso(user.email_verified_at),
    last_online_at: Number(user.last_seen_at ?? 0) > 0
      ? new Date(Number(user.last_seen_at) * 1000).toISOString()
      : null,
    shikimori_linked: shikimoriAccount != null,
    shikimori_user_id: shikimoriAccount?.provider_subject ?? null,
    profile_version: profileVersion,
    stats: {
      total: Number(statsRow?.total ?? 0),
      completed: Number(statsRow?.completed ?? 0),
      watching: Number(statsRow?.watching ?? 0),
      planned: Number(statsRow?.planned ?? 0),
      on_hold: Number(statsRow?.on_hold ?? 0),
      dropped: Number(statsRow?.dropped ?? 0),
      rewatching: Number(statsRow?.rewatching ?? 0),
      scores: Number(statsRow?.scores ?? 0),
      episodes_watched: Number(statsRow?.episodes_watched ?? 0),
    },
  };
}

async function googleStart(request: Request, env: Env): Promise<Response> {
  if (!googleIsConfigured(env)) return json(request, env, { error: 'google_auth_not_configured' }, 503);
  const returnUri = new URL(request.url).searchParams.get('return_uri')?.trim() ?? '';
  if (returnUri !== ALLOWED_APP_RETURN_URI) return json(request, env, { error: 'invalid_return_uri' }, 400);
  const state = randomToken(32);
  const now = nowSeconds();
  await env.DB.prepare(
    `INSERT INTO oauth_transactions (id,state_hash,return_uri,created_at,expires_at) VALUES (?,?,?,?,?)`,
  ).bind(crypto.randomUUID(), await pepperedHash(state, env.AUTH_TOKEN_PEPPER), returnUri, now, now + OAUTH_STATE_TTL_SECONDS).run();
  const authUrl = new URL(GOOGLE_AUTH_URL);
  authUrl.search = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    redirect_uri: env.GOOGLE_REDIRECT_URI,
    response_type: 'code',
    scope: 'openid email profile',
    state,
    prompt: 'select_account',
  }).toString();
  return Response.redirect(authUrl.toString(), 302);
}

async function googleCallback(request: Request, env: Env): Promise<Response> {
  const query = new URL(request.url).searchParams;
  const state = query.get('state')?.trim() ?? '';
  const code = query.get('code')?.trim() ?? '';
  const transaction = state
    ? await env.DB.prepare(
        `SELECT id,state_hash,return_uri FROM oauth_transactions WHERE state_hash=? AND expires_at>? LIMIT 1`,
      ).bind(await pepperedHash(state, env.AUTH_TOKEN_PEPPER), nowSeconds()).first<{ id: string; state_hash: string; return_uri: string }>()
    : null;
  if (!transaction) return new Response('OAuth state is invalid or expired.', { status: 400 });
  if (!code) return appRedirect(transaction.return_uri, { error: query.get('error') ?? 'google_authorization_failed' });
  if (!googleIsConfigured(env)) return appRedirect(transaction.return_uri, { error: 'google_auth_not_configured' });

  try {

  const tokenResponse = await fetch(GOOGLE_TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: env.GOOGLE_REDIRECT_URI,
      grant_type: 'authorization_code',
    }),
  });
  if (!tokenResponse.ok) {
    const tokenError = await tokenResponse.json().catch(() => null) as Record<string, unknown> | null;
    const code = tokenError?.error?.toString() ?? 'exchange_failed';
    console.warn('AniMix Google token exchange rejected', {
      status: tokenResponse.status,
      code,
    });
    return appRedirect(transaction.return_uri, { error: `google_token_${code}` });
  }
  const tokenPayload = await tokenResponse.json() as Record<string, unknown>;
  const googleAccessToken = tokenPayload.access_token?.toString() ?? '';
  if (!googleAccessToken) return appRedirect(transaction.return_uri, { error: 'google_access_token_missing' });
  const profileResponse = await fetch(GOOGLE_USERINFO_URL, {
    headers: { Authorization: `Bearer ${googleAccessToken}`, Accept: 'application/json' },
  });
  if (!profileResponse.ok) return appRedirect(transaction.return_uri, { error: 'google_profile_failed' });
  const profile = await profileResponse.json() as Record<string, unknown>;
  const subject = profile.sub?.toString().trim() ?? '';
  const email = normalizeEmail(profile.email);
  const verified = profile.email_verified === true || profile.email_verified?.toString() === 'true';
  if (!subject || !email || !verified) return appRedirect(transaction.return_uri, { error: 'google_email_not_verified' });

  let user = await env.DB.prepare(
    `SELECT u.* FROM external_accounts a JOIN users u ON u.id=a.user_id WHERE a.provider='google' AND a.provider_subject=? LIMIT 1`,
  ).bind(subject).first<Record<string, unknown>>();
  const timestamp = nowSeconds();
  if (!user) user = await env.DB.prepare('SELECT * FROM users WHERE email=? LIMIT 1').bind(email).first<Record<string, unknown>>();
  if (!user) {
    const userId = crypto.randomUUID();
    await env.DB.prepare(
      `INSERT INTO users (id,email,display_name,email_verified_at,created_at,updated_at,last_seen_at) VALUES (?,?,?,?,?,?,?)`,
    ).bind(userId, email, safeDisplayName(profile.name, email), timestamp, timestamp, timestamp, timestamp).run();
    await env.DB.prepare('INSERT INTO user_settings (user_id,updated_at) VALUES (?,?)').bind(userId, timestamp).run();
    user = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(userId).first<Record<string, unknown>>();
  }
  if (!user) return appRedirect(transaction.return_uri, { error: 'animix_user_creation_failed' });
  await env.DB.prepare(
    `INSERT INTO external_accounts (provider,provider_subject,user_id,provider_email,created_at,updated_at)
     VALUES ('google',?,?,?,?,?) ON CONFLICT(provider,provider_subject) DO UPDATE SET provider_email=excluded.provider_email,updated_at=excluded.updated_at`,
  ).bind(subject, String(user.id), email, timestamp, timestamp).run();
  await env.DB.prepare('DELETE FROM oauth_transactions WHERE id=?').bind(transaction.id).run();
  const ticket = randomToken(32);
  await env.DB.prepare(
    `INSERT INTO auth_tickets (id,ticket_hash,state_hash,user_id,created_at,expires_at) VALUES (?,?,?,?,?,?)`,
  ).bind(crypto.randomUUID(), await pepperedHash(ticket, env.AUTH_TOKEN_PEPPER), transaction.state_hash, String(user.id), timestamp, timestamp + AUTH_TICKET_TTL_SECONDS).run();
    return appRedirect(transaction.return_uri, { ticket, state });
  } catch (error) {
    console.error('AniMix Google callback failed', {
      error: error instanceof Error ? error.message : String(error),
    });
    return appRedirect(transaction.return_uri, { error: 'google_callback_failed' });
  }
}

async function googleExchange(request: Request, env: Env): Promise<Response> {
  const payload = await readBody(request);
  const ticket = typeof payload.ticket === 'string' ? payload.ticket.trim() : '';
  const state = typeof payload.state === 'string' ? payload.state.trim() : '';
  if (!ticket || !state) return json(request, env, { error: 'invalid_google_ticket' }, 400);
  const row = await env.DB.prepare(
    `SELECT id,user_id,state_hash FROM auth_tickets WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>? LIMIT 1`,
  ).bind(await pepperedHash(ticket, env.AUTH_TOKEN_PEPPER), nowSeconds()).first<{ id: string; user_id: string; state_hash: string }>();
  if (!row || !(await timingSafeEqual(row.state_hash, await pepperedHash(state, env.AUTH_TOKEN_PEPPER)))) return json(request, env, { error: 'invalid_google_ticket' }, 401);
  const user = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(row.user_id).first<Record<string, unknown>>();
  if (!user) return json(request, env, { error: 'account_unavailable' }, 404);
  // Build the profile before consuming the one-time ticket. A transient D1 or
  // serialization failure must not burn the ticket and strand the client.
  const profile = await userResponse(request, env, user);
  const consumed = await env.DB.prepare(
    'UPDATE auth_tickets SET consumed_at=? WHERE id=? AND consumed_at IS NULL',
  ).bind(nowSeconds(), row.id).run();
  if (Number(consumed.meta.changes ?? 0) !== 1) {
    return json(request, env, { error: 'invalid_google_ticket' }, 401);
  }
  return json(request, env, { ...(await createSession(env, row.user_id)), user: profile });
}

async function refreshSession(request: Request, env: Env): Promise<Response> {
  const payload = await readBody(request);
  const refreshToken = typeof payload.refresh_token === 'string' ? payload.refresh_token.trim() : '';
  if (!refreshToken) return json(request, env, { error: 'invalid_refresh_token' }, 401);
  const session = await env.DB.prepare(
    `SELECT s.id,s.user_id,u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.refresh_token_hash=? AND s.revoked_at IS NULL AND s.refresh_expires_at>? LIMIT 1`,
  ).bind(await pepperedHash(refreshToken, env.AUTH_TOKEN_PEPPER), nowSeconds()).first<Record<string, unknown>>();
  if (!session) return json(request, env, { error: 'invalid_refresh_token' }, 401);
  // Do all fallible profile reads before rotating the refresh token. Otherwise
  // a profile failure revokes the only usable session without returning a new one.
  const profile = await userResponse(request, env, session);
  const revoked = await env.DB.prepare(
    'UPDATE sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL',
  ).bind(nowSeconds(), session.id).run();
  if (Number(revoked.meta.changes ?? 0) !== 1) {
    return json(request, env, { error: 'invalid_refresh_token' }, 401);
  }
  return json(request, env, { ...(await createSession(env, String(session.user_id))), user: profile });
}

async function logout(request: Request, env: Env): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (user?.session_id) await env.DB.prepare('UPDATE sessions SET revoked_at=? WHERE id=?').bind(nowSeconds(), user.session_id).run();
  return json(request, env, { ok: true });
}

async function me(request: Request, env: Env): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  return json(request, env, { user: await userResponse(request, env, user) });
}

async function updateProfile(request: Request, env: Env): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const payload = await readBody(request);
  const displayName = typeof payload.display_name === 'string'
    ? payload.display_name.trim().replace(/\s+/g, ' ').slice(0, 32)
    : '';
  if (!displayName) return json(request, env, { error: 'invalid_display_name' }, 400);
  const updatedAt = nowSeconds();
  await env.DB.prepare('UPDATE users SET display_name=?,updated_at=? WHERE id=?')
    .bind(displayName, updatedAt, user.id).run();
  const updated = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first<Record<string, unknown>>();
  if (!updated) return json(request, env, { error: 'account_unavailable' }, 404);
  return json(request, env, { user: await userResponse(request, env, updated) });
}

async function uploadProfileMedia(request: Request, env: Env, kind: 'avatar' | 'banner'): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const declaredLength = Number(request.headers.get('content-length') ?? 0);
  if (!Number.isFinite(declaredLength) || declaredLength > maxProfileMediaBytes) {
    return json(request, env, { error: 'profile_media_too_large' }, 413);
  }
  const contentType = request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() ?? '';
  if (contentType !== 'image/jpeg') return json(request, env, { error: 'unsupported_profile_media' }, 415);
  const data = await request.arrayBuffer();
  if (data.byteLength === 0 || data.byteLength > maxProfileMediaBytes) {
    return json(request, env, { error: 'profile_media_too_large' }, 413);
  }
  // Millisecond cache version makes consecutive edits immediately visible.
  const updatedAt = Date.now();
  await env.DB.prepare(
    `INSERT INTO user_profile_media (user_id,kind,content_type,content,updated_at)
     VALUES (?,?,?,?,?)
     ON CONFLICT(user_id,kind) DO UPDATE SET content_type=excluded.content_type,content=excluded.content,updated_at=excluded.updated_at`,
  ).bind(user.id, kind, contentType, data, updatedAt).run();
  const updated = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first<Record<string, unknown>>();
  if (!updated) return json(request, env, { error: 'account_unavailable' }, 404);
  return json(request, env, { user: await userResponse(request, env, updated) });
}

async function deleteProfileMedia(request: Request, env: Env, kind: 'avatar' | 'banner'): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  await env.DB.prepare('DELETE FROM user_profile_media WHERE user_id=? AND kind=?').bind(user.id, kind).run();
  const updated = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first<Record<string, unknown>>();
  if (!updated) return json(request, env, { error: 'account_unavailable' }, 404);
  return json(request, env, { user: await userResponse(request, env, updated) });
}

async function serveProfileMedia(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  userId: string,
  kind: 'avatar' | 'banner',
): Promise<Response> {
  if (!/^[0-9a-f-]{36}$/i.test(userId)) return json(request, env, { error: 'not_found' }, 404);
  const edgeCache = caches.default;
  const cached = await edgeCache.match(request);
  if (cached) return cached;
  const media = await env.DB.prepare(
    'SELECT content_type,content,updated_at FROM user_profile_media WHERE user_id=? AND kind=?',
  ).bind(userId, kind).first<{ content_type: string; content: ArrayBuffer; updated_at: number }>();
  if (!media) return json(request, env, { error: 'not_found' }, 404);
  const eTag = `\"${media.updated_at}\"`;
  const headers = new Headers({
    'Cache-Control': 'public, max-age=31536000, immutable',
    'X-Content-Type-Options': 'nosniff',
    'Content-Type': media.content_type,
    ETag: eTag,
    Vary: 'Origin',
  });
  if (request.headers.get('if-none-match') === eTag) return new Response(null, { status: 304, headers });
  const response = new Response(media.content, { headers });
  ctx.waitUntil(edgeCache.put(request, response.clone()));
  return response;
}

async function publicUserProfile(request: Request, env: Env, userId: string): Promise<Response> {
  if (!/^[0-9a-f-]{36}$/i.test(userId)) return json(request, env, { error: 'not_found' }, 404);
  const user = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(userId).first<Record<string, unknown>>();
  if (!user) return json(request, env, { error: 'not_found' }, 404);
  const profile = await userResponse(request, env, user);
  delete profile.email;
  delete profile.email_verified_at;
  return json(request, env, { user: profile });
}

function readLibraryPayload(payload: Record<string, unknown>): { status: string; score: number; episodesWatched: number } | null {
  const status = typeof payload.status === 'string' ? payload.status.trim() : '';
  const score = Number(payload.score ?? 0);
  const episodesWatched = Number(payload.episodes_watched ?? 0);
  if (!libraryStatuses.has(status) || !Number.isInteger(score) || score < 0 || score > 10 || !Number.isInteger(episodesWatched) || episodesWatched < 0) return null;
  return { status, score, episodesWatched };
}

async function getLibraryEntry(request: Request, env: Env, animeId: number): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const entry = await env.DB.prepare(
    'SELECT shikimori_id,status,score,episodes_watched,updated_at FROM user_anime_entries WHERE user_id=? AND shikimori_id=?',
  ).bind(user.id, animeId).first<Record<string, unknown>>();
  return json(request, env, { entry: entry ?? null });
}

async function listLibrary(request: Request, env: Env): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const result = await env.DB.prepare(
    `SELECT shikimori_id,status,score,episodes_watched,updated_at
       FROM user_anime_entries
      WHERE user_id=?
      ORDER BY updated_at DESC, shikimori_id DESC
      LIMIT 5000`,
  ).bind(user.id).all<Record<string, unknown>>();
  return json(request, env, { entries: result.results });
}

async function updateLibraryEntry(request: Request, env: Env, animeId: number): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const entry = readLibraryPayload(await readBody(request));
  if (!entry) return json(request, env, { error: 'invalid_library_entry' }, 400);
  const updatedAt = nowSeconds();
  await env.DB.prepare(
    `INSERT INTO user_anime_entries (user_id,shikimori_id,status,score,episodes_watched,updated_at)
     VALUES (?,?,?,?,?,?)
     ON CONFLICT(user_id,shikimori_id) DO UPDATE SET status=excluded.status,score=excluded.score,episodes_watched=excluded.episodes_watched,updated_at=excluded.updated_at`,
  ).bind(user.id, animeId, entry.status, entry.score, entry.episodesWatched, updatedAt).run();
  await env.DB.prepare(
    'INSERT INTO user_history (id,user_id,action,shikimori_id,metadata_json,created_at) VALUES (?,?,?,?,?,?)',
  ).bind(
    crypto.randomUUID(),
    user.id,
    'library_entry_updated',
    animeId,
    JSON.stringify({ status: entry.status, score: entry.score, episodes_watched: entry.episodesWatched }),
    updatedAt,
  ).run();
  const freshUser = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first<Record<string, unknown>>();
  return json(request, env, {
    entry: { shikimori_id: animeId, status: entry.status, score: entry.score, episodes_watched: entry.episodesWatched, updated_at: updatedAt },
    user: freshUser ? await userResponse(request, env, freshUser) : null,
  });
}

type ImportedLibraryEntry = {
  shikimoriId: number;
  status: string;
  score: number;
  episodesWatched: number;
};

function readShikimoriImport(payload: Record<string, unknown>): { shikimoriUserId: string; entries: ImportedLibraryEntry[] } | null {
  const shikimoriUserId = typeof payload.shikimori_user_id === 'string' || typeof payload.shikimori_user_id === 'number'
    ? String(payload.shikimori_user_id).trim()
    : '';
  const rawEntries = payload.entries;
  if (!/^\d+$/.test(shikimoriUserId) || !Array.isArray(rawEntries) || rawEntries.length > 5000) return null;
  const entries = rawEntries.map((value) => {
    if (!value || typeof value !== 'object') return null;
    const source = value as Record<string, unknown>;
    const shikimoriId = Number(source.shikimori_id);
    const normalized = readLibraryPayload(source);
    if (!Number.isSafeInteger(shikimoriId) || shikimoriId <= 0 || !normalized) return null;
    return {
      shikimoriId,
      status: normalized.status,
      score: normalized.score,
      episodesWatched: normalized.episodesWatched,
    };
  });
  return entries.some((entry) => entry == null)
    ? null
    : { shikimoriUserId, entries: entries as ImportedLibraryEntry[] };
}

async function importShikimoriLibrary(request: Request, env: Env): Promise<Response> {
  const user = await findUserByAccessToken(env, bearerToken(request));
  if (!user) return json(request, env, { error: 'unauthorized' }, 401);
  const payload = readShikimoriImport(await readBody(request));
  if (!payload) return json(request, env, { error: 'invalid_shikimori_import' }, 400);
  const linked = await env.DB.prepare(
    "SELECT user_id FROM external_accounts WHERE provider='shikimori' AND provider_subject=? LIMIT 1",
  ).bind(payload.shikimoriUserId).first<{ user_id: string }>();
  if (linked && linked.user_id !== user.id) return json(request, env, { error: 'shikimori_account_already_linked' }, 409);
  const existingLink = await env.DB.prepare(
    "SELECT provider_subject FROM external_accounts WHERE provider='shikimori' AND user_id=? LIMIT 1",
  ).bind(user.id).first<{ provider_subject: string }>();
  if (existingLink && existingLink.provider_subject !== payload.shikimoriUserId) {
    return json(request, env, { error: 'shikimori_relink_not_supported' }, 409);
  }
  const now = nowSeconds();
  await env.DB.prepare(
    `INSERT INTO external_accounts (provider,provider_subject,user_id,provider_email,created_at,updated_at)
     VALUES ('shikimori',?,?,?,?,?)
     ON CONFLICT(provider,provider_subject) DO UPDATE SET user_id=excluded.user_id,updated_at=excluded.updated_at`,
  ).bind(payload.shikimoriUserId, user.id, null, now, now).run();

  for (var start = 0; start < payload.entries.length; start += 100) {
    const chunk = payload.entries.slice(start, start + 100);
    await env.DB.batch(chunk.map((entry) => env.DB.prepare(
      `INSERT INTO user_anime_entries (user_id,shikimori_id,status,score,episodes_watched,updated_at)
       VALUES (?,?,?,?,?,?)
       ON CONFLICT(user_id,shikimori_id) DO UPDATE SET status=excluded.status,score=excluded.score,episodes_watched=excluded.episodes_watched,updated_at=excluded.updated_at`,
    ).bind(user.id, entry.shikimoriId, entry.status, entry.score, entry.episodesWatched, now)));
  }
  await env.DB.prepare(
    'INSERT INTO user_history (id,user_id,action,metadata_json,created_at) VALUES (?,?,?,?,?)',
  ).bind(crypto.randomUUID(), user.id, 'shikimori_library_imported', JSON.stringify({ count: payload.entries.length }), now).run();
  const freshUser = await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first<Record<string, unknown>>();
  return json(request, env, {
    imported: payload.entries.length,
    user: freshUser ? await userResponse(request, env, freshUser) : null,
  });
}

async function legacyShikimoriTokenExchange(request: Request, env: Env): Promise<Response> {
  const payload = await readBody(request);
  const code = typeof payload.code === 'string' ? payload.code.trim() : '';
  const refreshToken = typeof payload.refresh_token === 'string' ? payload.refresh_token.trim() : '';
  const redirectUri = typeof payload.redirect_uri === 'string' ? payload.redirect_uri.trim() : '';
  const clientId = typeof payload.client_id === 'string' ? payload.client_id.trim() : '';
  const requestedGrant = typeof payload.grant_type === 'string' ? payload.grant_type.trim() : '';
  const grantType = requestedGrant || (code ? 'authorization_code' : '');
  if (clientId !== env.SHIKIMORI_CLIENT_ID) return json(request, env, { error: 'invalid_request' }, 400);
  const form = new URLSearchParams({ grant_type: grantType, client_id: env.SHIKIMORI_CLIENT_ID, client_secret: env.SHIKIMORI_CLIENT_SECRET });
  if (grantType === 'authorization_code') {
    if (!code || !ALLOWED_REDIRECT_URIS.has(redirectUri)) return json(request, env, { error: 'invalid_authorization_request' }, 400);
    form.set('code', code); form.set('redirect_uri', redirectUri);
  } else if (grantType === 'refresh_token') {
    if (!refreshToken) return json(request, env, { error: 'invalid_refresh_request' }, 400);
    form.set('refresh_token', refreshToken);
  } else return json(request, env, { error: 'unsupported_grant_type' }, 400);
  const upstream = await fetch(SHIKIMORI_TOKEN_URL, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'AniMix OAuth proxy', Accept: 'application/json' }, body: form });
  const headers = corsHeaders(request, env);
  headers.set('Content-Type', upstream.headers.get('Content-Type') ?? 'application/json');
  return new Response(upstream.body, { status: upstream.status, headers });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders(request, env) });
    if (request.method === 'GET' && (url.pathname === '/health' || url.pathname === '/v1/health')) return json(request, env, { ok: true, service: 'animix-auth' });
    if (url.pathname === '/v1/auth/google/start' && request.method === 'GET') {
      try { return await googleStart(request, env); } catch (_) { return json(request, env, { error: 'auth_unavailable' }, 503); }
    }
    if (url.pathname === '/v1/auth/google/callback' && request.method === 'GET') {
      try { return await googleCallback(request, env); } catch (_) { return new Response('OAuth provider error.', { status: 502 }); }
    }
    if (url.pathname === '/v1/auth/google/exchange' && request.method === 'POST') {
      try { return await googleExchange(request, env); } catch (error) {
        console.error('AniMix Google exchange failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'auth_unavailable' }, 503);
      }
    }
    if (url.pathname === '/v1/auth/refresh' && request.method === 'POST') {
      try { return await refreshSession(request, env); } catch (error) {
        console.error('AniMix session refresh failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'auth_unavailable' }, 503);
      }
    }
    if (url.pathname === '/v1/auth/logout' && request.method === 'POST') {
      try { return await logout(request, env); } catch (_) { return json(request, env, { error: 'auth_unavailable' }, 503); }
    }
    if (url.pathname === '/v1/me' && request.method === 'GET') {
      try { return await me(request, env); } catch (error) {
        console.error('AniMix profile request failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'auth_unavailable' }, 503);
      }
    }
    if (url.pathname === '/v1/me/profile' && request.method === 'PATCH') {
      try { return await updateProfile(request, env); } catch (_) { return json(request, env, { error: 'profile_unavailable' }, 503); }
    }
    const ownMediaMatch = /^\/v1\/me\/media\/(avatar|banner)$/.exec(url.pathname);
    if (ownMediaMatch && request.method === 'PUT') {
      try { return await uploadProfileMedia(request, env, ownMediaMatch[1] as 'avatar' | 'banner'); } catch (error) {
        console.error('AniMix profile media upload failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'profile_media_unavailable' }, 503);
      }
    }
    if (ownMediaMatch && request.method === 'DELETE') {
      try { return await deleteProfileMedia(request, env, ownMediaMatch[1] as 'avatar' | 'banner'); } catch (_) { return json(request, env, { error: 'profile_media_unavailable' }, 503); }
    }
    const publicMediaMatch = /^\/v1\/users\/([0-9a-f-]{36})\/(avatar|banner)$/i.exec(url.pathname);
    if (publicMediaMatch && request.method === 'GET') {
      try { return await serveProfileMedia(request, env, ctx, publicMediaMatch[1], publicMediaMatch[2].toLowerCase() as 'avatar' | 'banner'); } catch (_) { return json(request, env, { error: 'profile_media_unavailable' }, 503); }
    }
    const publicProfileMatch = /^\/v1\/users\/([0-9a-f-]{36})$/i.exec(url.pathname);
    if (publicProfileMatch && request.method === 'GET') {
      try { return await publicUserProfile(request, env, publicProfileMatch[1]); } catch (_) { return json(request, env, { error: 'profile_unavailable' }, 503); }
    }
    if (url.pathname === '/v1/library' && request.method === 'GET') {
      try { return await listLibrary(request, env); } catch (error) {
        console.error('AniMix library list failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'library_unavailable' }, 503);
      }
    }
    const libraryMatch = /^\/v1\/library\/(\d+)$/.exec(url.pathname);
    if (libraryMatch && request.method === 'GET') {
      try { return await getLibraryEntry(request, env, Number(libraryMatch[1])); } catch (_) { return json(request, env, { error: 'library_unavailable' }, 503); }
    }
    if (libraryMatch && request.method === 'PUT') {
      try { return await updateLibraryEntry(request, env, Number(libraryMatch[1])); } catch (_) { return json(request, env, { error: 'library_unavailable' }, 503); }
    }
    if ((url.pathname === '/v1/library/import/shikimori' || url.pathname === '/v1/integrations/shikimori/import') && request.method === 'POST') {
      try { return await importShikimoriLibrary(request, env); } catch (error) {
        console.error('AniMix Shikimori import failed', { error: error instanceof Error ? error.message : String(error) });
        return json(request, env, { error: 'library_import_unavailable' }, 503);
      }
    }
    if (request.method !== 'POST') return json(request, env, { error: 'method_not_allowed' }, 405);
    try { return await legacyShikimoriTokenExchange(request, env); } catch (_) { return json(request, env, { error: 'bad_request' }, 400); }
  },
} satisfies ExportedHandler<Env>;
