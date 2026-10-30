/** Private, revisioned New Horizons state. Existing auth/profile/media endpoints stay intact. */
export type SyncRow = { revision: number; payload: Record<string, unknown> | null };
export interface SyncStore {
  syncState(userId: string): Promise<SyncRow>;
  putSyncState(userId: string, revision: number, payload: Record<string, unknown>): Promise<SyncRow | null>;
}

export function validSyncPayload(input: unknown): input is Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return false;
  const data = input as Record<string, unknown>;
  if (data.version !== 1 || !data.library || !data.achievements || !data.usage) return false;
  const serialized = JSON.stringify(data);
  if (Buffer.byteLength(serialized) > 10 * 1024 * 1024) return false;
  // Device files, sessions and credentials have no place in account state.
  if (/"(?:localFileName|accessToken|refreshToken|downloadId)"\s*:/.test(serialized)) return false;
  const library = data.library as Record<string, unknown>;
  if (!library.entries || typeof library.entries !== 'object' || Array.isArray(library.entries)) return false;
  if (Object.keys(library.entries).length > 50_000) return false;
  const profile = library.profile as Record<string, unknown> | undefined;
  if (profile?.name !== undefined && (typeof profile.name !== 'string' || Array.from(profile.name).length > 140)) return false;
  return true;
}

export function syncLibrary(payload: Record<string, unknown> | null): Array<Record<string, unknown>> | null {
  const entries = (payload?.library as any)?.entries;
  if (!entries) return null;
  return Object.values(entries).flatMap((value: any) => value?.status && value?.anime?.source !== 'Yummy' && value?.anime?.id > 0 ? [{
    shikimori_id: value.anime.id, status: ({ Watching: 'watching', Planned: 'planned', Completed: 'completed', OnHold: 'on_hold', Dropped: 'dropped' } as Record<string, string>)[value.status],
    score: value.score ?? 0, episodes_watched: value.episodesWatched ?? 0, updated_at: Math.floor((value.updatedAt ?? 0) / 1000),
  }] : []);
}
