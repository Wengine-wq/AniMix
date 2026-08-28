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
  ydbEndpoint: string;
  ydbDatabase: string;
  mediaBucket: string;
  mediaEndpoint: string;
  mediaPublicBaseUrl: string;
  mediaAccessKeyId: string;
  mediaSecretAccessKey: string;
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
