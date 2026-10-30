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
  return {
    databaseUrl: required('DATABASE_URL'),
    mediaDir: optional('MEDIA_DIR', ''),
    mediaPublicBaseUrl: optional('MEDIA_PUBLIC_BASE_URL', '').replace(/\/+$/, ''),
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
