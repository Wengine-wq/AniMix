const _supportedAniMixLibraryStatuses = <String>{
  'planned',
  'watching',
  'rewatching',
  'completed',
  'on_hold',
  'dropped',
};

/// Converts both current `/api/v2/user_rates` rows and legacy
/// `/api/users/:id/anime_rates` rows into the AniMix import contract.
Map<String, dynamic>? normalizeShikimoriAnimeRate(Map<String, dynamic> rate) {
  final nestedAnime = rate['anime'];
  final nestedAnimeId = nestedAnime is Map ? nestedAnime['id'] : null;
  final animeId = _asInt(rate['target_id'] ?? nestedAnimeId);
  final status = rate['status']?.toString().trim() ?? '';
  if (animeId <= 0 || !_supportedAniMixLibraryStatuses.contains(status)) {
    return null;
  }

  final score = _asInt(rate['score']);
  final episodes = _asInt(rate['episodes']);
  return {
    'shikimori_id': animeId,
    'status': status,
    'score': score.clamp(0, 10),
    'episodes_watched': episodes < 0 ? 0 : episodes,
  };
}

int _asInt(Object? value) =>
    value is num ? value.toInt() : int.tryParse(value?.toString() ?? '') ?? 0;
