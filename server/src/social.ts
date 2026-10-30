export type DeviceRow = { device_id: string; label: string; platform: string; active: boolean; seen_at: number; playback: any };
export function validDeviceId(id: unknown): id is string { return typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id); }
export function validPlayback(value: any): boolean {
  return value && typeof value === 'object' && Number.isSafeInteger(value.anime?.id) && value.anime.id > 0 &&
    ['Shikimori', 'Yummy'].includes(value.anime.source ?? 'Shikimori') && typeof value.episode === 'string' && value.episode.length <= 24 &&
    typeof value.dubbingKey === 'string' && value.dubbingKey.length <= 200 && typeof value.quality === 'string' && value.quality.length <= 30 &&
    Number.isSafeInteger(value.positionMs) && value.positionMs >= 0 && value.positionMs <= 604800000 && JSON.stringify(value).length < 10000;
}
