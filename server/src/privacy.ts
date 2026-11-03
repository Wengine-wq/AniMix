/** Who may see one part of a profile. The owner always sees everything. */
export type Audience = 'all' | 'friends' | 'nobody';
export type Relation = 'self' | 'friend' | 'other';

/** The parts of a profile with their own visibility. Name and avatar are always public. */
export const PRIVACY_KEYS = ['collection', 'stats', 'achievements', 'presence'] as const;
export type PrivacyKey = typeof PRIVACY_KEYS[number];
export type Visibility = Record<PrivacyKey, Audience>;

const AUDIENCES = new Set<Audience>(['all', 'friends', 'nobody']);

/** Profiles saved before per-part settings: one switch for the collection and statistics, presence for friends. */
export function defaultVisibility(libraryVisible = true): Visibility {
  const shared: Audience = libraryVisible ? 'friends' : 'nobody';
  return { collection: shared, stats: shared, achievements: shared, presence: 'friends' };
}

/** A stored or submitted value; unknown parts keep their previous setting, invalid input is rejected. */
export function readVisibility(input: unknown, previous: Visibility): Visibility | null {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const result: Visibility = { ...previous };
  for (const [key, value] of Object.entries(input as Record<string, unknown>)) {
    if (!(PRIVACY_KEYS as readonly string[]).includes(key)) continue;
    if (typeof value !== 'string' || !AUDIENCES.has(value as Audience)) return null;
    result[key as PrivacyKey] = value as Audience;
  }
  return result;
}

export function allows(audience: Audience, relation: Relation): boolean {
  return relation === 'self' || audience === 'all' || (audience === 'friends' && relation === 'friend');
}

/**
 * What a friend's page shows of the collection: the title card and the list facts, never resume
 * positions, playback coverage or sync bookkeeping. Keeps the response small.
 */
export function publicCollection(entries: Record<string, any> | undefined): Array<Record<string, unknown>> {
  if (!entries) return [];
  return Object.values(entries).flatMap((entry: any) => {
    if (!entry?.anime || (!entry.status && !entry.favorite)) return [];
    return [{
      anime: entry.anime,
      status: entry.status ?? null, favorite: entry.favorite === true, score: entry.score ?? null,
      episodesWatched: entry.episodesWatched ?? 0, updatedAt: entry.updatedAt ?? 0,
    }];
  });
}
