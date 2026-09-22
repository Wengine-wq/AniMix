import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum AnimeSkipSegmentKind { opening, ending }

@immutable
class AnimeSkipSegment {
  const AnimeSkipSegment({
    required this.id,
    required this.kind,
    required this.start,
    required this.end,
  });

  final String id;
  final AnimeSkipSegmentKind kind;
  final Duration start;
  final Duration end;

  String get buttonLabel => switch (kind) {
    AnimeSkipSegmentKind.opening => 'Пропустить опенинг',
    AnimeSkipSegmentKind.ending => 'Пропустить эндинг',
  };

  Map<String, Object> toJson() => {
    'id': id,
    'kind': kind.name,
    'startMs': start.inMilliseconds,
    'endMs': end.inMilliseconds,
  };

  static AnimeSkipSegment? fromJson(Object? value) {
    if (value is! Map) return null;
    final map = Map<String, dynamic>.from(value);
    final kind = AnimeSkipSegmentKind.values
        .where((candidate) => candidate.name == map['kind'])
        .firstOrNull;
    final start = int.tryParse(map['startMs']?.toString() ?? '');
    final end = int.tryParse(map['endMs']?.toString() ?? '');
    if (kind == null || start == null || end == null || end <= start) {
      return null;
    }
    return AnimeSkipSegment(
      id: map['id']?.toString() ?? '${kind.name}:$start:$end',
      kind: kind,
      start: Duration(milliseconds: start),
      end: Duration(milliseconds: end),
    );
  }
}

/// Resolves community-maintained opening and ending sections from Anime Skip.
///
/// Shikimori anime IDs are MyAnimeList IDs. Anime Skip indexes AniList IDs, so
/// the service resolves MAL -> AniList first, then uses Anime Skip's documented
/// `findShowsByExternalId` lookup. A title search remains as a fallback.
class AnimeSkipService {
  AnimeSkipService._();

  static final instance = AnimeSkipService._();

  static const _apiUrl = 'https://api.anime-skip.com/graphql';
  static const _anilistUrl = 'https://graphql.anilist.co';
  static const _sharedClientId = String.fromEnvironment(
    'ANIME_SKIP_CLIENT_ID',
    defaultValue: 'ZGfO0sMF3eCwLYf8yMSCJjlynwNGRXWE',
  );
  static const _cachePrefix = 'anime_skip_segments_v1_';
  static const _cacheLifetime = Duration(days: 14);

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 30),
      sendTimeout: const Duration(seconds: 10),
      contentType: Headers.jsonContentType,
    ),
  );
  final Map<String, Future<List<AnimeSkipSegment>>> _requests = {};
  final Map<int, Future<String?>> _showIds = {};
  final Map<String, Future<List<_AnimeSkipEpisode>>> _episodes = {};

  Future<List<AnimeSkipSegment>> segmentsForEpisode({
    required int malId,
    required String episodeNumber,
    required Duration videoDuration,
    String? title,
  }) {
    final key = '$malId:$episodeNumber:${videoDuration.inSeconds}';
    final request = _requests.putIfAbsent(
      key,
      () => _loadSegments(
        malId: malId,
        episodeNumber: episodeNumber,
        videoDuration: videoDuration,
        title: title,
      ),
    );
    return request.then((segments) {
      if (segments.isEmpty) _requests.remove(key);
      return segments;
    });
  }

  Future<List<AnimeSkipSegment>> _loadSegments({
    required int malId,
    required String episodeNumber,
    required Duration videoDuration,
    String? title,
  }) async {
    final cached = await _readCache(malId, episodeNumber, videoDuration);
    if (cached != null) return cached;

    try {
      final showId = await _findShowId(malId, title);
      if (showId == null) return const [];
      final episode = await _findEpisode(showId, episodeNumber);
      if (episode == null) return const [];
      final timestamps = await _animeSkipQuery(
        r'''
          query AniMixTimestamps($episodeId: ID!) {
            findTimestampsByEpisodeId(episodeId: $episodeId) {
              id
              at
              type { name }
            }
          }
        ''',
        {'episodeId': episode.id},
      );
      final raw = timestamps['findTimestampsByEpisodeId'];
      final segments = buildSegments(
        raw is List ? raw : const [],
        videoDuration: videoDuration,
        baseDurationSeconds: episode.baseDuration,
      );
      await _writeCache(malId, episodeNumber, videoDuration, segments);
      return segments;
    } catch (error) {
      debugPrint('[Anime Skip] timestamps unavailable: $error');
      return const [];
    }
  }

  Future<String?> _findShowId(int malId, String? title) {
    final request = _showIds.putIfAbsent(
      malId,
      () => _resolveShowId(malId, title),
    );
    return request.then((id) {
      if (id == null) _showIds.remove(malId);
      return id;
    });
  }

  Future<String?> _resolveShowId(int malId, String? title) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        _anilistUrl,
        data: {
          'query': r'''
            query AniMixAniList($malId: Int!) {
              Media(idMal: $malId, type: ANIME) { id }
            }
          ''',
          'variables': {'malId': malId},
        },
      );
      final media = response.data?['data']?['Media'];
      final anilistId = media is Map ? media['id']?.toString() : null;
      if (anilistId != null) {
        final data = await _animeSkipQuery(
          r'''
            query AniMixShowByExternalId($serviceId: String!) {
              findShowsByExternalId(service: ANILIST, serviceId: $serviceId) {
                id
              }
            }
          ''',
          {'serviceId': anilistId},
        );
        final shows = data['findShowsByExternalId'];
        if (shows is List && shows.isNotEmpty && shows.first is Map) {
          return (shows.first as Map)['id']?.toString();
        }
      }
    } catch (error) {
      debugPrint('[Anime Skip] AniList mapping unavailable: $error');
    }

    final search = title?.trim();
    if (search == null || search.isEmpty) return null;
    final data = await _animeSkipQuery(
      r'''
        query AniMixShowSearch($search: String!) {
          searchShows(search: $search, limit: 5) { id name originalName }
        }
      ''',
      {'search': search},
    );
    final shows = data['searchShows'];
    if (shows is! List || shows.isEmpty || shows.first is! Map) return null;
    return (shows.first as Map)['id']?.toString();
  }

  Future<_AnimeSkipEpisode?> _findEpisode(
    String showId,
    String episodeNumber,
  ) async {
    final episodes = await _episodes.putIfAbsent(
      showId,
      () => _loadEpisodes(showId),
    );
    if (episodes.isEmpty) _episodes.remove(showId);
    final wanted = _normalizedEpisode(episodeNumber);
    for (final episode in episodes) {
      if (episode.number == wanted || episode.absoluteNumber == wanted) {
        return episode;
      }
    }
    return null;
  }

  Future<List<_AnimeSkipEpisode>> _loadEpisodes(String showId) async {
    final data = await _animeSkipQuery(
      r'''
        query AniMixEpisodes($showId: ID!) {
          findEpisodesByShowId(showId: $showId) {
            id
            number
            absoluteNumber
            baseDuration
          }
        }
      ''',
      {'showId': showId},
    );
    final episodes = data['findEpisodesByShowId'];
    if (episodes is! List) return const [];
    final result = <_AnimeSkipEpisode>[];
    for (final value in episodes) {
      if (value is! Map) continue;
      final number = _normalizedEpisode(value['number']?.toString());
      final absolute = _normalizedEpisode(value['absoluteNumber']?.toString());
      result.add(
        _AnimeSkipEpisode(
          id: value['id'].toString(),
          number: number,
          absoluteNumber: absolute,
          baseDuration: (value['baseDuration'] as num?)?.toDouble(),
        ),
      );
    }
    return result;
  }

  Future<Map<String, dynamic>> _animeSkipQuery(
    String query,
    Map<String, Object> variables,
  ) async {
    final response = await _dio.post<Map<String, dynamic>>(
      _apiUrl,
      options: Options(headers: {'X-Client-ID': _sharedClientId}),
      data: {'query': query, 'variables': variables},
    );
    final body = response.data;
    final errors = body?['errors'];
    if (errors is List && errors.isNotEmpty) {
      throw StateError(errors.first.toString());
    }
    final data = body?['data'];
    if (data is! Map) {
      throw const FormatException('Anime Skip returned no data');
    }
    return Map<String, dynamic>.from(data);
  }

  @visibleForTesting
  static List<AnimeSkipSegment> buildSegments(
    List<dynamic> timestamps, {
    required Duration videoDuration,
    double? baseDurationSeconds,
  }) {
    final items =
        timestamps
            .whereType<Map>()
            .map((value) {
              final map = Map<String, dynamic>.from(value);
              final type = map['type'];
              return (
                id: map['id']?.toString() ?? '',
                at: (map['at'] as num?)?.toDouble(),
                type: type is Map ? type['name']?.toString() : null,
              );
            })
            .where((item) => item.at != null && item.type != null)
            .toList()
          ..sort((left, right) => left.at!.compareTo(right.at!));

    final videoSeconds = videoDuration.inMilliseconds / 1000;
    final calculatedOffset = baseDurationSeconds == null
        ? 0.0
        : videoSeconds - baseDurationSeconds;
    final offset = calculatedOffset.abs() <= 120 ? calculatedOffset : 0.0;
    final result = <AnimeSkipSegment>[];
    for (var index = 0; index < items.length; index++) {
      final item = items[index];
      final kind = _kindForType(item.type!);
      if (kind == null) continue;
      final start = (item.at! + offset).clamp(0.0, videoSeconds);
      final rawEnd = index + 1 < items.length
          ? items[index + 1].at! + offset
          : videoSeconds;
      final end = rawEnd.clamp(0.0, videoSeconds);
      if (end - start < 5) continue;
      result.add(
        AnimeSkipSegment(
          id: item.id.isEmpty ? '${kind.name}:$start:$end' : item.id,
          kind: kind,
          start: Duration(milliseconds: (start * 1000).round()),
          end: Duration(milliseconds: (end * 1000).round()),
        ),
      );
    }
    return result;
  }

  static AnimeSkipSegmentKind? _kindForType(String value) {
    final type = value.toLowerCase();
    if (type == 'intro' || type == 'mixed intro' || type == 'new intro') {
      return AnimeSkipSegmentKind.opening;
    }
    if (type == 'credits' || type == 'mixed credits' || type == 'new credits') {
      return AnimeSkipSegmentKind.ending;
    }
    return null;
  }

  static String _normalizedEpisode(String? value) {
    final parsed = double.tryParse(value?.trim() ?? '');
    return parsed == null ? value?.trim().toLowerCase() ?? '' : '$parsed';
  }

  Future<List<AnimeSkipSegment>?> _readCache(
    int malId,
    String episodeNumber,
    Duration duration,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('$_cachePrefix${malId}_$episodeNumber');
    if (raw == null) return null;
    try {
      final map = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      final savedAt = DateTime.tryParse(map['savedAt']?.toString() ?? '');
      final cachedDuration = int.tryParse(map['duration']?.toString() ?? '');
      if (savedAt == null ||
          DateTime.now().difference(savedAt) > _cacheLifetime ||
          cachedDuration == null ||
          (cachedDuration - duration.inSeconds).abs() > 3) {
        return null;
      }
      final values = map['segments'];
      if (values is! List) return null;
      return values
          .map(AnimeSkipSegment.fromJson)
          .whereType<AnimeSkipSegment>()
          .toList();
    } catch (_) {
      return null;
    }
  }

  Future<void> _writeCache(
    int malId,
    String episodeNumber,
    Duration duration,
    List<AnimeSkipSegment> segments,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      '$_cachePrefix${malId}_$episodeNumber',
      jsonEncode({
        'savedAt': DateTime.now().toIso8601String(),
        'duration': duration.inSeconds,
        'segments': segments.map((segment) => segment.toJson()).toList(),
      }),
    );
  }
}

class _AnimeSkipEpisode {
  const _AnimeSkipEpisode({
    required this.id,
    required this.number,
    required this.absoluteNumber,
    required this.baseDuration,
  });

  final String id;
  final String number;
  final String absoluteNumber;
  final double? baseDuration;
}
