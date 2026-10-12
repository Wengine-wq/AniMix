import { RuntimeConfig } from './types';

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value || value.startsWith('SET_')) {
    throw new Error(`Missing required runtime variable: ${name}`);
  }
  return value;
}

function optional(name: string, fallback: string): string {
  const value = process.env[name]?.trim();
  return value || fallback;
}

export function runtimeConfig(): RuntimeConfig {
  // DATABASE_URL selects PostgreSQL (any VM / Docker); otherwise YDB.
  const databaseUrl = optional('DATABASE_URL', '');
  const ydb = (name: string) => (databaseUrl ? optional(name, '') : required(name));
  return {
    databaseUrl,
    ydbEndpoint: ydb('YDB_ENDPOINT'),
    ydbDatabase: ydb('YDB_DATABASE'),
    // Profile avatars and banners go to any S3-compatible bucket. Without one
    // the API still works; only uploading profile media is unavailable.
    mediaBucket: optional('MEDIA_BUCKET', ''),
    mediaEndpoint: optional('MEDIA_ENDPOINT', 'https://storage.yandexcloud.net'),
    mediaRegion: optional('MEDIA_REGION', 'ru-central1'),
    mediaPublicBaseUrl: optional('MEDIA_PUBLIC_BASE_URL', '').replace(/\/+$/, ''),
    mediaAccessKeyId: optional('MEDIA_ACCESS_KEY_ID', ''),
    mediaSecretAccessKey: optional('MEDIA_SECRET_ACCESS_KEY', ''),
    googleClientId: required('GOOGLE_CLIENT_ID'),
    googleClientSecret: required('GOOGLE_CLIENT_SECRET'),
    googleRedirectUri: required('GOOGLE_REDIRECT_URI'),
    shikimoriClientId: required('SHIKIMORI_CLIENT_ID'),
    shikimoriClientSecret: required('SHIKIMORI_CLIENT_SECRET'),
    authTokenPepper: required('AUTH_TOKEN_PEPPER'),
    allowedAppReturnUri: optional('ALLOWED_APP_RETURN_URI', 'animix://oauth/callback'),
    allowedWebOrigin: optional('ANIMIX_WEB_ORIGIN', 'https://animix.app'),
  };
}
