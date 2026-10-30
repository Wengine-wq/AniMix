import { createHash, randomBytes, timingSafeEqual as nodeTimingSafeEqual } from 'node:crypto';

export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

export function randomToken(byteLength = 32): string {
  return randomBytes(byteLength).toString('base64url');
}

export function pepperedHash(value: string, pepper: string): string {
  return createHash('sha256').update(`${pepper}:${value}`).digest('base64url');
}

export function timingSafeTextEqual(left: string, right: string): boolean {
  const a = createHash('sha256').update(left).digest();
  const b = createHash('sha256').update(right).digest();
  return nodeTimingSafeEqual(a, b);
}

export function epochIso(value: number | null | undefined): string | null {
  if (!value || !Number.isFinite(value) || value <= 0) return null;
  // Historical D1/YDB migrations used seconds, milliseconds and (in one
  // version) microseconds. Normalize all three instead of rendering dates in
  // the year 178751.
  const milliseconds = value < 100_000_000_000
    ? value * 1000
    : value < 100_000_000_000_000
      ? value
      : value < 100_000_000_000_000_000
        ? value / 1000
        : value / 1_000_000;
  const date = new Date(milliseconds);
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}
