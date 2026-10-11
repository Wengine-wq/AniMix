/// Year-in-review numbers computed from AniMix usage events.
///
/// Events have the shape returned by `/v1/usage` (and the local outbox):
/// `{created_at: seconds, action, anime_id, metadata: {seconds | episode}}`.
class WrappedStats {
  const WrappedStats({
    required this.year,
    required this.watchSeconds,
    required this.episodes,
    required this.titles,
    required this.episodesByAnime,
    required this.marathonDay,
    required this.marathonEpisodes,
    required this.busiestMonth,
    required this.busiestMonthSeconds,
    required this.activeDays,
  });

  final int year;
  final int watchSeconds;
  final int episodes;
  final int titles;

  /// Anime id → episodes watched this year, most watched first.
  final Map<int, int> episodesByAnime;
  final DateTime? marathonDay;
  final int marathonEpisodes;

  /// 1–12, or 0 when there is no watch time.
  final int busiestMonth;
  final int busiestMonthSeconds;
  final int activeDays;

  double get hours => watchSeconds / 3600;
  bool get isEmpty => watchSeconds == 0 && episodes == 0;
  int? get topAnimeId =>
      episodesByAnime.isEmpty ? null : episodesByAnime.keys.first;

  static const monthNames = [
    'январь',
    'февраль',
    'март',
    'апрель',
    'май',
    'июнь',
    'июль',
    'август',
    'сентябрь',
    'октябрь',
    'ноябрь',
    'декабрь',
  ];

  static WrappedStats fromEvents(
    int year,
    Iterable<Map<String, dynamic>> events,
  ) {
    var seconds = 0;
    var episodes = 0;
    final titles = <int>{};
    final perAnime = <int, int>{};
    final perDayEpisodes = <DateTime, int>{};
    final perMonthSeconds = <int, int>{};
    final days = <DateTime>{};
    final seenEpisodes = <String>{};

    for (final event in events) {
      final raw = int.tryParse('${event['created_at']}') ?? 0;
      if (raw <= 0) continue;
      // Server events are in seconds; tolerate milliseconds too.
      final at = DateTime.fromMillisecondsSinceEpoch(
        raw > 100000000000 ? raw : raw * 1000,
      ).toLocal();
      if (at.year != year) continue;
      final day = DateTime(at.year, at.month, at.day);
      final animeId = int.tryParse('${event['anime_id']}') ?? 0;
      final metadata = event['metadata'] is Map
          ? event['metadata'] as Map
          : const {};
      switch (event['action']) {
        case 'watch_seconds':
          final value = int.tryParse('${metadata['seconds']}') ?? 0;
          if (value <= 0) continue;
          seconds += value;
          perMonthSeconds[at.month] = (perMonthSeconds[at.month] ?? 0) + value;
          days.add(day);
          if (animeId > 0) titles.add(animeId);
        case 'episode_watched':
          final key = '$animeId:${metadata['episode']}';
          if (!seenEpisodes.add(key)) continue;
          episodes++;
          days.add(day);
          perDayEpisodes[day] = (perDayEpisodes[day] ?? 0) + 1;
          if (animeId > 0) {
            titles.add(animeId);
            perAnime[animeId] = (perAnime[animeId] ?? 0) + 1;
          }
      }
    }

    DateTime? marathonDay;
    var marathon = 0;
    perDayEpisodes.forEach((day, count) {
      if (count > marathon) {
        marathon = count;
        marathonDay = day;
      }
    });
    var busiestMonth = 0;
    var busiestSeconds = 0;
    perMonthSeconds.forEach((month, value) {
      if (value > busiestSeconds) {
        busiestSeconds = value;
        busiestMonth = month;
      }
    });
    final sortedAnime = perAnime.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    return WrappedStats(
      year: year,
      watchSeconds: seconds,
      episodes: episodes,
      titles: titles.length,
      episodesByAnime: {for (final e in sortedAnime) e.key: e.value},
      marathonDay: marathonDay,
      marathonEpisodes: marathon,
      busiestMonth: busiestMonth,
      busiestMonthSeconds: busiestSeconds,
      activeDays: days.length,
    );
  }
}
