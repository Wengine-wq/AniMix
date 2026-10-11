import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';

import '../../core/achievement_service.dart';
import '../../core/animix_auth_service.dart';
import '../../core/app_logging.dart';
import '../../models/shikimori_anime.dart';
import '../../core/shikimori_api_client.dart';
import '../watch/watch_storage.dart';
import 'wrapped_stats.dart';

String ruPlural(int n, String one, String few, String many) {
  final mod10 = n % 10;
  final mod100 = n % 100;
  if (mod10 == 1 && mod100 != 11) return '$n $one';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return '$n $few';
  }
  return '$n $many';
}

class WrappedData {
  const WrappedData({
    required this.stats,
    required this.anime,
    required this.genres,
    required this.achievementIndices,
  });

  final WrappedStats stats;
  final Map<int, ShikimoriAnime> anime;

  /// Top genres with their share of watched episodes (0–1).
  final List<MapEntry<String, double>> genres;

  /// Positions in [achievements] unlocked during the year.
  final List<int> achievementIndices;

  ShikimoriAnime? get topAnime {
    final id = stats.topAnimeId;
    return id == null ? null : anime[id];
  }

  static Future<WrappedData> load({
    required int year,
    required AniMixAuthService auth,
    required ShikimoriApiClient api,
  }) async {
    final remote = await auth.getUsageStats();
    final events = <Map<String, dynamic>>[
      ..._activity(remote ?? await WatchStorage.getLocalUsageStats()),
      ...await WatchStorage.getPendingUsageActivity(),
    ];
    final stats = WrappedStats.fromEvents(year, events);

    final anime = <int, ShikimoriAnime>{};
    final ids = stats.episodesByAnime.keys.take(100).toList();
    for (var start = 0; start < ids.length; start += 50) {
      final chunk = ids.skip(start).take(50).toList();
      try {
        final items = await api.getAnimes(
          limit: chunk.length,
          filters: {'ids': chunk.join(',')},
        );
        for (final item in items) {
          anime[item.id] = item;
        }
      } catch (error, stackTrace) {
        AppLogBuffer.instance.recordError(
          error,
          stackTrace,
          source: 'Итоги года',
          context: 'Не удалось получить жанры тайтлов',
        );
      }
    }

    final weights = <String, int>{};
    var total = 0;
    stats.episodesByAnime.forEach((id, episodes) {
      for (final genre in anime[id]?.genres ?? const <String>[]) {
        weights[genre] = (weights[genre] ?? 0) + episodes;
        total += episodes;
      }
    });
    final genres =
        weights.entries
            .map((e) => MapEntry(e.key, total == 0 ? 0.0 : e.value / total))
            .toList()
          ..sort((a, b) => b.value.compareTo(a.value));

    final unlocked = await AchievementService.instance.unlocked();
    final achievementIndices = <int>[
      for (var i = 0; i < achievements.length; i++)
        if (unlocked[achievements[i].id]?.year == year) i,
    ];

    return WrappedData(
      stats: stats,
      anime: anime,
      genres: genres.take(3).toList(),
      achievementIndices: achievementIndices,
    );
  }

  static Iterable<Map<String, dynamic>> _activity(Map<String, dynamic> usage) {
    final raw = usage['activity'];
    if (raw is! List) return const [];
    return raw.whereType<Map>().map(Map<String, dynamic>.from);
  }
}

/// A free Creative Commons (Attribution) track from ccMixter, cached on disk
/// so the recap opens with music even on a slow connection the second time.
class WrappedTrack {
  const WrappedTrack({
    required this.file,
    required this.title,
    required this.artist,
    required this.license,
  });

  final File file;
  final String title;
  final String artist;
  final String license;

  String get credit => '«$title» — $artist · $license · ccmixter.org';

  static const _query =
      'https://ccmixter.org/api/query?f=json&tags=instrumental,electronic'
      '&sort=rank&limit=20&lic=by';
  // ccMixter refuses hot-linked downloads without its own referer.
  static final _dio = Dio(
    BaseOptions(
      headers: const {'Referer': 'https://ccmixter.org/'},
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 40),
    ),
  );

  static Future<WrappedTrack?> fetch() async {
    try {
      final response = await _dio.get<dynamic>(_query);
      final data = response.data;
      final list = data is List ? data : const [];
      final candidates = <Map>[];
      for (final item in list.whereType<Map>()) {
        final files = item['files'];
        if (files is! List) continue;
        final mp3 = files.whereType<Map>().firstWhere(
          (f) => '${f['download_url']}'.toLowerCase().endsWith('.mp3'),
          orElse: () => const {},
        );
        if (mp3.isEmpty) continue;
        candidates.add({...item, '_url': mp3['download_url']});
      }
      if (candidates.isEmpty) return null;
      final pick = candidates[Random().nextInt(min(candidates.length, 8))];
      final url = '${pick['_url']}'.replaceFirst('http://', 'https://');
      final dir = Directory(
        '${(await getTemporaryDirectory()).path}/animix_wrapped',
      );
      await dir.create(recursive: true);
      final file = File('${dir.path}/${pick['upload_id']}.mp3');
      if (!await file.exists() || await file.length() < 10000) {
        await _dio.download(url, file.path);
      }
      final artist = '${pick['user_real_name'] ?? ''}'.trim();
      return WrappedTrack(
        file: file,
        title: '${pick['upload_name'] ?? 'Без названия'}',
        artist: artist.isNotEmpty ? artist : '${pick['user_name'] ?? ''}',
        license: RegExp(r'\d\.\d').firstMatch('${pick['license_name']}') == null
            ? 'CC BY'
            : 'CC BY ${RegExp(r'\d\.\d').firstMatch('${pick['license_name']}')!.group(0)}',
      );
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Итоги года',
        context: 'Музыка ccMixter недоступна',
      );
      return null;
    }
  }
}
