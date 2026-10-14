import 'dart:async';

import 'package:dio/dio.dart';

import '../../core/shikimori_api_client.dart';
import '../../models/shikimori_anime.dart';

/// Reads public lists from Anixart (the same API its apps use; endpoints as
/// in the open-source AniAnglia client) and maps titles to Shikimori.
///
/// Only public data is read, by nickname: no Anixart password ever passes
/// through AniMix. Lists hidden by Anixart privacy settings are not visible.
class AnixartClient {
  AnixartClient({Dio? dio})
    : _dio =
          dio ??
          Dio(
            BaseOptions(
              connectTimeout: const Duration(seconds: 12),
              receiveTimeout: const Duration(seconds: 20),
              headers: const {'User-Agent': 'AnixartApp/9.0 (AniMix import)'},
            ),
          );

  final Dio _dio;

  // Mirrors the Anixart apps use; the first that answers wins.
  static const _bases = [
    'https://api-s.anixsekai.com',
    'https://api.anixart.tv',
    'https://api-alt.anixart.app',
  ];
  String? _workingBase;

  Future<Map<String, dynamic>> _request(String path, {Object? data}) async {
    Object? lastError;
    final bases = [?_workingBase, ..._bases.where((b) => b != _workingBase)];
    for (final base in bases) {
      try {
        final response = data == null
            ? await _dio.get<dynamic>('$base/$path')
            : await _dio.post<dynamic>(
                '$base/$path',
                data: data,
                options: Options(contentType: Headers.jsonContentType),
              );
        final body = response.data;
        if (body is Map) {
          _workingBase = base;
          return Map<String, dynamic>.from(body);
        }
      } catch (error) {
        lastError = error;
      }
    }
    throw AnixartUnavailable(lastError);
  }

  Future<List<AnixartProfile>> searchProfiles(String nickname) async {
    final json = await _request(
      'search/profiles/0',
      data: {'query': nickname.trim()},
    );
    final content = json['content'];
    if (content is! List) return const [];
    return content
        .whereType<Map>()
        .map((item) => AnixartProfile.fromJson(Map<String, dynamic>.from(item)))
        .where((profile) => profile.id > 0)
        .toList(growable: false);
  }

  /// Number of titles in each list of [profileId] (0 when hidden or empty).
  Future<Map<AnixartList, int>> listCounts(int profileId) async {
    final counts = <AnixartList, int>{};
    await Future.wait([
      for (final list in AnixartList.values)
        _request('profile/list/all/$profileId/${list.id}/0').then((json) {
          counts[list] = int.tryParse('${json['total_count']}') ?? 0;
        }),
    ]);
    return counts;
  }

  Stream<AnixartRelease> releases(int profileId, AnixartList list) async* {
    for (var page = 0; page < 200; page++) {
      final json = await _request(
        'profile/list/all/$profileId/${list.id}/$page',
      );
      final content = json['content'];
      if (content is! List || content.isEmpty) return;
      for (final item in content.whereType<Map>()) {
        yield AnixartRelease.fromJson(Map<String, dynamic>.from(item), list);
      }
      final pages = int.tryParse('${json['total_page_count']}') ?? 0;
      if (page + 1 >= pages) return;
    }
  }
}

class AnixartUnavailable implements Exception {
  const AnixartUnavailable(this.cause);
  final Object? cause;
}

class AnixartProfile {
  const AnixartProfile({required this.id, required this.login, this.avatar});

  factory AnixartProfile.fromJson(Map<String, dynamic> json) => AnixartProfile(
    id: int.tryParse('${json['id']}') ?? 0,
    login: '${json['login'] ?? ''}',
    avatar: json['avatar']?.toString(),
  );

  final int id;
  final String login;
  final String? avatar;
}

/// Anixart list ids and the matching AniMix library status.
enum AnixartList {
  watching(1, 'watching', 'Смотрю'),
  planned(2, 'planned', 'В планах'),
  completed(3, 'completed', 'Просмотрено'),
  onHold(4, 'on_hold', 'Отложено'),
  dropped(5, 'dropped', 'Брошено');

  const AnixartList(this.id, this.status, this.label);
  final int id;
  final String status;
  final String label;
}

class AnixartRelease {
  const AnixartRelease({
    required this.id,
    required this.list,
    required this.titleRu,
    required this.titleOriginal,
    required this.year,
    required this.episodesTotal,
    required this.isMovie,
    required this.lastViewEpisode,
  });

  factory AnixartRelease.fromJson(Map<String, dynamic> json, AnixartList list) {
    final category = json['category'];
    final categoryName = category is Map ? '${category['name']}' : '';
    return AnixartRelease(
      id: int.tryParse('${json['id']}') ?? 0,
      list: list,
      titleRu: '${json['title_ru'] ?? ''}'.trim(),
      titleOriginal: '${json['title_original'] ?? ''}'.trim(),
      year: int.tryParse('${json['year']}'),
      episodesTotal: int.tryParse('${json['episodes_total']}'),
      isMovie: categoryName.toLowerCase().contains('фильм'),
      lastViewEpisode: int.tryParse('${json['last_view_episode']}'),
    );
  }

  final int id;
  final AnixartList list;
  final String titleRu;
  final String titleOriginal;
  final int? year;
  final int? episodesTotal;
  final bool isMovie;
  final int? lastViewEpisode;

  String get displayTitle => titleRu.isNotEmpty ? titleRu : titleOriginal;

  /// Episodes to record as watched in AniMix.
  int get episodesWatched => switch (list) {
    AnixartList.completed => episodesTotal ?? lastViewEpisode ?? 0,
    _ => lastViewEpisode ?? 0,
  };
}

/// Finds the Shikimori title for an Anixart release by name and year.
class AnixartMatcher {
  AnixartMatcher(this._api);

  final ShikimoriApiClient _api;

  static String normalize(String value) => value
      .toLowerCase()
      .replaceAll('ё', 'е')
      .replaceAll(RegExp(r'[^\p{L}\p{N}]+', unicode: true), '');

  /// Scores how well [anime] fits [release]; higher is better, 0 is no match.
  static int score(AnixartRelease release, ShikimoriAnime anime) {
    final original = normalize(release.titleOriginal);
    final russian = normalize(release.titleRu);
    final names = {normalize(anime.name ?? ''), normalize(anime.russian ?? '')}
      ..remove('');
    final titleMatch =
        (original.isNotEmpty && names.contains(original)) ||
        (russian.isNotEmpty && names.contains(russian));
    var score = titleMatch ? 4 : 0;
    if (release.year != null && anime.year == release.year) score += 2;
    if (release.episodesTotal != null &&
        release.episodesTotal == anime.episodes) {
      score += 1;
    }
    final movie = anime.kind == 'movie';
    if (movie == release.isMovie) score += 1;
    return score;
  }

  /// Best Shikimori match, or null when nothing fits well enough.
  Future<ShikimoriAnime?> match(AnixartRelease release) async {
    ShikimoriAnime? best;
    var bestScore = 0;
    for (final query in {release.titleOriginal, release.titleRu}) {
      if (query.isEmpty) continue;
      final candidates = await _api.getAnimes(
        limit: 10,
        filters: {'search': query, 'censored': 'false'},
      );
      for (final anime in candidates) {
        final value = score(release, anime);
        if (value > bestScore) {
          best = anime;
          bestScore = value;
        }
      }
      // A title match with the right year is as good as it gets.
      if (bestScore >= 6) break;
    }
    // Require the name to match, or at least year + format + episodes.
    return bestScore >= 4 ? best : null;
  }
}
