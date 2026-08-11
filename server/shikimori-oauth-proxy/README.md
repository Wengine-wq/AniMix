# AniMix Shikimori OAuth proxy

Shikimori requires `client_secret` when exchanging an authorization code. That
secret must live on a trusted server, never in the Flutter asset or IPA.

This directory contains a small Cloudflare Worker endpoint. Deploy it, set the
three Worker secrets below, and put its HTTPS URL into the app's
`SHIKIMORI_OAUTH_PROXY_URL` value.

```bash
npm install -g wrangler
wrangler secret put SHIKIMORI_CLIENT_SECRET
wrangler secret put SHIKIMORI_CLIENT_ID
wrangler secret put ANIMIX_PROXY_ORIGIN
wrangler deploy worker.ts
```

`ANIMIX_PROXY_ORIGIN` is the public app origin used for CORS (for example
`https://animix.app`). Local desktop callbacks are allowed by the worker and do
not require a browser origin.

The worker validates the client id and redirect URI, forwards only the code
exchange to Shikimori, and returns the token response without logging secrets.
