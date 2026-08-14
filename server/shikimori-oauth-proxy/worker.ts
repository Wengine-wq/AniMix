const SHIKIMORI_TOKEN_URL = 'https://shikimori.io/oauth/token';
const ALLOWED_REDIRECT_URIS = new Set([
  'https://animix.app/callback',
  'http://localhost:33333/callback',
  // Kept for the manual-code flow used by older Shikimori applications.
  'urn:ietf:wg:oauth:2.0:oob',
]);

interface Env {
  SHIKIMORI_CLIENT_ID: string;
  SHIKIMORI_CLIENT_SECRET: string;
  ANIMIX_PROXY_ORIGIN?: string;
}

function corsHeaders(request: Request, env: Env): Headers {
  const origin = request.headers.get('Origin');
  const allowedOrigin = env.ANIMIX_PROXY_ORIGIN?.trim();
  const headers = new Headers({
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
    Vary: 'Origin',
  });
  if (origin && allowedOrigin && origin === allowedOrigin) {
    headers.set('Access-Control-Allow-Origin', origin);
    headers.set('Access-Control-Allow-Headers', 'Content-Type');
    headers.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  }
  return headers;
}

function json(
  request: Request,
  env: Env,
  body: Record<string, unknown>,
  status = 200,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: corsHeaders(request, env),
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders(request, env) });
    if (request.method !== 'POST') return json(request, env, { error: 'method_not_allowed' }, 405);

    try {
      const payload = (await request.json()) as Record<string, unknown>;
      const code = typeof payload.code === 'string' ? payload.code.trim() : '';
      const redirectUri = typeof payload.redirect_uri === 'string' ? payload.redirect_uri.trim() : '';
      const clientId = typeof payload.client_id === 'string' ? payload.client_id.trim() : '';

      if (!code || !redirectUri || clientId !== env.SHIKIMORI_CLIENT_ID) {
        return json(request, env, { error: 'invalid_request' }, 400);
      }
      if (!ALLOWED_REDIRECT_URIS.has(redirectUri)) {
        return json(request, env, { error: 'invalid_redirect_uri' }, 400);
      }

      const form = new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: env.SHIKIMORI_CLIENT_ID,
        client_secret: env.SHIKIMORI_CLIENT_SECRET,
        code,
        redirect_uri: redirectUri,
      });
      const upstream = await fetch(SHIKIMORI_TOKEN_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'User-Agent': 'AniMix OAuth proxy',
          Accept: 'application/json',
        },
        body: form,
      });
      const responseText = await upstream.text();
      let responseBody: unknown;
      try {
        responseBody = JSON.parse(responseText);
      } catch (_) {
        responseBody = { error: 'invalid_upstream_response' };
      }
      return new Response(JSON.stringify(responseBody), {
        status: upstream.status,
        headers: corsHeaders(request, env),
      });
    } catch (_) {
      return json(request, env, { error: 'bad_request' }, 400);
    }
  },
};
