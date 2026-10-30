export type CloudFunctionEvent = {
  httpMethod?: string;
  path?: string;
  headers?: Record<string, string | undefined>;
  multiValueHeaders?: Record<string, string[] | undefined>;
  queryStringParameters?: Record<string, string | undefined>;
  body?: string | null;
  isBase64Encoded?: boolean;
  requestContext?: { requestId?: string; identity?: { sourceIp?: string } };
};

export type CloudFunctionResponse = {
  statusCode: number;
  headers: Record<string, string>;
  body: string;
  isBase64Encoded?: boolean;
};

export type RuntimeConfig = {
  databaseUrl: string;
  /** Profile media directory on this machine, served under /media/. Empty: uploads unavailable. */
  mediaDir: string;
  mediaPublicBaseUrl: string;
  googleClientId: string;
  googleClientSecret: string;
  googleRedirectUri: string;
  shikimoriClientId: string;
  shikimoriClientSecret: string;
  authTokenPepper: string;
  allowedAppReturnUri: string;
  allowedWebOrigin: string;
};

export type UserRow = {
  /** PostgreSQL only: the profile description and the pinned achievement. */
  about?: string | null;
  badge_achievement_id?: string | null;
  id: string;
  email: string;
  display_name: string;
  email_verified_at: number;
  created_at: number;
  updated_at: number;
  last_seen_at: number | null;
};

export type LibraryEntry = {
  shikimori_id: number;
  status: string;
  score: number;
  episodes_watched: number;
  updated_at: number;
};

export type SessionRow = {
  id: string;
  user_id: string;
  access_token_hash: string;
  refresh_token_hash: string;
  access_expires_at: number;
  refresh_expires_at: number;
  created_at: number;
  last_used_at: number;
};

export type MediaRow = {
  kind: 'avatar' | 'banner';
  object_key: string;
  content_type: string;
  updated_at: number;
};

export type ExternalAccount = {
  provider: string;
  provider_subject: string;
  user_id: string;
  provider_email?: string | null;
};

export type OAuthTransaction = {
  state_hash: string;
  return_uri: string;
  expires_at: number;
};

export type AuthTicket = {
  ticket_hash: string;
  state_hash: string;
  user_id: string;
  expires_at: number;
  consumed_at?: number | null;
};
