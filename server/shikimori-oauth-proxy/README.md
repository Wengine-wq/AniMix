# AniMix Shikimori OAuth proxy

Shikimori requires `client_secret` when exchanging an authorization code. That
secret must live on a trusted server, never in the Flutter asset or IPA.

This directory contains a small Cloudflare Worker endpoint. Deploy it, set the
three Worker secrets below, and put its HTTPS URL into the app's
`SHIKIMORI_OAUTH_PROXY_URL` value.

```bash
cd server/shikimori-oauth-proxy
npx wrangler secret put SHIKIMORI_CLIENT_SECRET
npx wrangler deploy
```

The public client id and the CORS origin are versioned in `wrangler.jsonc`.
`SHIKIMORI_CLIENT_SECRET` exists only as an encrypted Cloudflare Worker secret.
Local desktop callbacks are allowed by the worker and do not require a browser
origin.

The worker validates the client id and redirect URI, forwards only the code
exchange to Shikimori, and returns the token response without logging secrets.
