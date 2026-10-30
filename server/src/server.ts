import { readFile } from 'node:fs/promises';
import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { join } from 'node:path';

import { runtimeConfig } from './config';
import { handler } from './index';
import { PgStore } from './pg';
import { CloudFunctionEvent } from './types';

// Requests larger than this are rejected before they reach the API (profile
// media is the biggest payload and has its own, smaller limit).
const MAX_BODY_BYTES = 12 * 1024 * 1024;

const TEXT_TYPES = ['application/json', 'text/', 'application/x-www-form-urlencoded'];

async function readBody(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error('payload_too_large'), { status: 413 });
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}

/** Turns a plain Node request into the event shape the Cloud Function uses. */
export async function toEvent(request: IncomingMessage): Promise<CloudFunctionEvent> {
  const url = new URL(request.url ?? '/', 'http://localhost');
  const headers: Record<string, string> = {};
  for (const [key, value] of Object.entries(request.headers)) {
    if (value !== undefined) headers[key] = Array.isArray(value) ? value.join(', ') : value;
  }
  const body = await readBody(request);
  const contentType = headers['content-type'] ?? '';
  const isText = body.length === 0 || TEXT_TYPES.some((type) => contentType.startsWith(type));
  const forwarded = headers['x-forwarded-for']?.split(',')[0]?.trim();
  return {
    httpMethod: request.method ?? 'GET',
    path: url.pathname,
    headers,
    queryStringParameters: Object.fromEntries(url.searchParams.entries()),
    body: body.length === 0 ? null : isText ? body.toString('utf8') : body.toString('base64'),
    isBase64Encoded: !isText,
    requestContext: { identity: { sourceIp: forwarded ?? request.socket.remoteAddress ?? '' } },
  };
}

const MEDIA_PATH = /^\/media\/(profiles\/[0-9a-f-]{36}\/(?:avatar|banner)\/[0-9]{1,16}\.(jpg|png))$/;

/** Profile media kept on this machine (MEDIA_DIR): fixed path shape, never a client-chosen file. */
async function serveMedia(request: IncomingMessage, response: ServerResponse): Promise<boolean> {
  const dir = process.env.MEDIA_DIR?.trim();
  const match = MEDIA_PATH.exec(new URL(request.url ?? '/', 'http://localhost').pathname);
  if (!dir || !match || (request.method !== 'GET' && request.method !== 'HEAD')) return false;
  try {
    const content = await readFile(join(dir, ...match[1].split('/')));
    response.writeHead(200, {
      'Content-Type': match[2] === 'png' ? 'image/png' : 'image/jpeg',
      'Content-Length': content.length,
      // Every upload gets a new name, so a file never changes.
      'Cache-Control': 'public, max-age=31536000, immutable',
    });
    response.end(request.method === 'HEAD' ? undefined : content);
  } catch {
    response.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: 'not_found' }));
  }
  return true;
}

async function handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    if (await serveMedia(request, response)) return;
    const result = await handler(await toEvent(request));
    const body = result.isBase64Encoded ? Buffer.from(result.body, 'base64') : result.body;
    response.writeHead(result.statusCode, result.headers);
    response.end(body);
  } catch (error) {
    const status = (error as { status?: number }).status ?? 500;
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: status === 413 ? 'payload_too_large' : 'internal_error' }));
    if (status === 500) console.error(error);
  }
}

export function createApiServer() {
  return createServer((request, response) => void handle(request, response));
}

async function main(): Promise<void> {
  const config = runtimeConfig();
  await PgStore.fromUrl(config.databaseUrl).migrate();
  const port = Number(process.env.PORT ?? 8080);
  createApiServer().listen(port, () => {
    console.log(`AniMix API listening on :${port}`);
  });
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
