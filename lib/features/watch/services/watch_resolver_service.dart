import 'dart:math';
import 'package:dio/dio.dart';
import '../models/watch_mapping.dart';
import '../repositories/watch_mapping_repository.dart';
import 'package:flutter/foundation.dart';
import '../../../core/config.dart';
import '../../../core/app_settings.dart';
import 'provider_response_cache.dart';

class WatchResolverService {
  final _dio = Dio(
    BaseOptions(
      headers: Config.yummyApiHeaders,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 15),
    ),
  );

  final _repo = WatchMappingRepository();
  final _responseCache = ProviderResponseCache.instance;
  static final Map<String, Future<dynamic>> _inFlight =
      <String, Future<dynamic>>{};

  Future<dynamic> _cachedGet(
    String url, {
    Map<String, dynamic>? queryParameters,
    Map<String, dynamic>? headers,
    required Duration freshFor,
  }) async {
    if (!AppSettingsController.instance.smartConnectionEnabled) {
      final response = await _dio.get(
        url,
        queryParameters: queryParameters,
        options: headers == null ? null : Options(headers: headers),
      );
      return response.data;
    }
    final uri = Uri.parse(url).replace(
      queryParameters: queryParameters?.map(
        (key, value) => MapEntry(key, value.toString()),
      ),
    );
    final key = uri.toString();
    final cached = await _responseCache.get(key, maxAge: freshFor);
    if (cached != null) return cached;

    final existing = _inFlight[key];
    if (existing != null) return existing;
    final request = () async {
      try {
        final response = await _dio.get(
          url,
          queryParameters: queryParameters,
          options: headers == null ? null : Options(headers: headers),
        );
        await _responseCache.put(key, response.data);
        return response.data;
      } catch (_) {
        final stale = await _responseCache.get(
          key,
          maxAge: const Duration(days: 7),
        );
        if (stale != null) return stale;
        rethrow;
      } finally {
        _inFlight.remove(key);
      }
    }();
    _inFlight[key] = request;
    return request;
  }

  Future<dynamic> resolve({
    required int shikimoriId,
    required String provider,
    required String searchNameRu,
    required String searchNameEn,
    bool forcePicker = false,
  }) async {
    final smart = AppSettingsController.instance.smartConnectionEnabled;
    final mapping = smart ? await _repo.get('${shikimoriId}_$provider') : null;

    if (smart &&
        !forcePicker &&
        mapping != null &&
        mapping.releaseId.isNotEmpty) {
      return _loadMappedRelease(
        provider: provider,
        releaseId: mapping.releaseId,
        shikimoriId: shikimoriId,
        searchNameRu: searchNameRu,
        searchNameEn: searchNameEn,
      );
    }

    final candidates = provider == 'yummyanime'
        ? await _searchYummyCandidates(searchNameRu, searchNameEn, shikimoriId)
        : await _searchCandidates(provider, searchNameRu, searchNameEn);

    if (candidates.isEmpty) throw Exception('Релиз не найден в базе $provider');

    final highScorers = candidates
        .where((c) => (c['matchScore'] as int) >= 90)
        .toList();

    if (smart && !forcePicker && highScorers.length == 1) {
      final best = highScorers.first;
      final newMapping = WatchMapping(
        shikimoriId: shikimoriId,
        provider: provider,
        releaseId: best['id'].toString(),
        releaseTitle: best['title'],
        posterUrl: best['poster'],
        savedAt: DateTime.now(),
      );
      await saveMapping(newMapping);

      return _loadMappedRelease(
        provider: provider,
        releaseId: best['id'].toString(),
        shikimoriId: shikimoriId,
        searchNameRu: searchNameRu,
        searchNameEn: searchNameEn,
      );
    }

    return {
      'needsPicker': true,
      'candidates': candidates,
      'shikimoriId': shikimoriId,
      'provider': provider,
    };
  }

  Future<List<Map<String, dynamic>>> searchManual(
    String provider,
    String query,
  ) async {
    if (provider == 'yummyanime') {
      return await _searchYummyCandidates(query, '', null);
    } else {
      return await _searchCandidates(provider, query, '');
    }
  }

  Future<void> saveMapping(WatchMapping mapping) =>
      AppSettingsController.instance.smartConnectionEnabled
      ? _repo.save(mapping)
      : Future<void>.value();

  Future<dynamic> _loadMappedRelease({
    required String provider,
    required String releaseId,
    required int shikimoriId,
    required String searchNameRu,
    required String searchNameEn,
  }) async {
    if (provider != 'yummyanime') {
      return loadEpisodesDirect(provider, releaseId);
    }
    final groups = await loadYummyStudios(releaseId);
    if (!AppSettingsController.instance.smartConnectionEnabled) return groups;
    return _enrichYummyWithAniLiberty(
      groups: groups,
      shikimoriId: shikimoriId,
      searchNameRu: searchNameRu,
      searchNameEn: searchNameEn,
    );
  }

  Future<List<Map<String, dynamic>>> _enrichYummyWithAniLiberty({
    required List<Map<String, dynamic>> groups,
    required int shikimoriId,
    required String searchNameRu,
    required String searchNameEn,
  }) async {
    try {
      var mapping = await _repo.get('${shikimoriId}_anilibria');
      if (mapping == null) {
        final candidates = await _searchCandidates(
          'anilibria',
          searchNameRu,
          searchNameEn,
        );
        final best = candidates.isEmpty ? null : candidates.first;
        if (best == null || (best['matchScore'] as int? ?? 0) < 88) {
          return groups;
        }
        mapping = WatchMapping(
          shikimoriId: shikimoriId,
          provider: 'anilibria',
          releaseId: best['id'].toString(),
          releaseTitle: best['title'].toString(),
          posterUrl: best['poster']?.toString() ?? '',
          savedAt: DateTime.now(),
        );
        await _repo.save(mapping);
      }

      final directEpisodes = await loadEpisodesDirect(
        'anilibria',
        mapping.releaseId,
      );
      final directByNumber = <String, Map<String, dynamic>>{
        for (final episode in directEpisodes)
          _episodeKey(episode['number']): episode,
      };
      for (final group in groups) {
        final name = group['name']?.toString().toLowerCase() ?? '';
        if (!name.contains('anilibria') && !name.contains('анилибрия')) {
          continue;
        }
        final episodes = group['episodes'] as List;
        var enrichedCount = 0;
        for (final rawEpisode in episodes) {
          if (rawEpisode is! Map) continue;
          final direct = directByNumber[_episodeKey(rawEpisode['number'])];
          if (direct == null || direct['videoUrl'] == null) continue;
          rawEpisode['videoUrl'] = direct['videoUrl'];
          rawEpisode['qualities'] = direct['qualities'];
          rawEpisode['source'] = 'aniliberty-direct';
          enrichedCount++;
        }
        if (enrichedCount > 0) group['hasDirectStreams'] = true;
      }
      return groups;
    } catch (error) {
      debugPrint('[AniMix resolver] AniLiberty enrichment skipped: $error');
      return groups;
    }
  }

  static String _episodeKey(dynamic value) {
    final number = double.tryParse(value?.toString() ?? '');
    if (number == null) return value?.toString() ?? '';
    return number == number.roundToDouble()
        ? number.toInt().toString()
        : number.toString();
  }

  // =====================================================================
  // 🟢 БЛОК YUMMY ANIME (С УМНОЙ ГРУППИРОВКОЙ)
  // =====================================================================

  Future<List<Map<String, dynamic>>> loadYummyStudios(String yummyId) async {
    debugPrint('🌐 [YUMMY API] Получение студий для релиза: $yummyId');
    try {
      final url = '${Config.yummyApiBase}/anime/$yummyId/videos';
      final data = await _cachedGet(
        url,
        headers: Config.yummyApiHeaders,
        freshFor: const Duration(minutes: 15),
      );
      List<dynamic> videos = [];
      if (data is List) {
        videos = data;
      } else if (data is Map && data['response'] != null) {
        videos = data['response'];
      } else if (data is Map && data['data'] != null) {
        videos = data['data'];
      }

      debugPrint(
        '📦 [YUMMY API] Найдено сырых элементов (серий): ${videos.length}',
      );

      // A dubbing can be available through several incompatible players.
      // Keep them separate; mixing Alloha/Sibnet URLs into a Kodik group made
      // episode selection non-deterministic and broke HLS interception.
      final groupedStudios = <String, Map<String, dynamic>>{};

      for (var v in videos) {
        if (v is! Map) continue;

        var translationName = 'Неизвестная озвучка';
        var playerName = 'Неизвестный плеер';

        // 🔥 ИЩЕМ ИМЯ СТУДИИ В ОБЪЕКТЕ DATA (Прямо как в твоем JSON)
        if (v['data'] is Map) {
          final dataMap = v['data'] as Map;
          final dubbing = dataMap['dubbing']?.toString().trim() ?? '';
          final player = dataMap['player']?.toString().trim() ?? '';
          if (dubbing.isNotEmpty) translationName = dubbing;
          if (player.isNotEmpty) playerName = player;
        }
        // Фоллбэки на случай других форматов ответа
        else {
          final translation = v['translation'];
          final studio = v['studio'];
          if (translation is Map) {
            translationName =
                translation['name']?.toString() ??
                translation['title']?.toString() ??
                translationName;
          } else if (translation is String && translation.isNotEmpty) {
            translationName = translation;
          } else {
            translationName =
                v['translation_name']?.toString() ??
                v['author']?.toString() ??
                (studio is Map ? studio['name']?.toString() : null) ??
                v['name']?.toString() ??
                translationName;
          }
          playerName = v['player_name']?.toString() ?? playerName;
        }

        translationName = translationName.trim();
        playerName = playerName.trim();
        final groupKey =
            '${playerName.toLowerCase()}|${translationName.toLowerCase()}';
        final epNumber = v['number'] ?? v['episode'] ?? v['episode_number'];
        final url =
            v['iframe_url'] ?? v['player_url'] ?? v['url'] ?? v['link'] ?? '';

        if (!groupedStudios.containsKey(groupKey)) {
          groupedStudios[groupKey] = {
            'name': translationName,
            'player': playerName,
            'displayName':
                '$translationName • ${playerName.replaceFirst('Плеер ', '')}',
            'isKodik': playerName.toLowerCase().contains('kodik'),
            'episodes': <Map<String, dynamic>>[],
            'url': url,
          };
        }

        if (epNumber != null) {
          final epList = groupedStudios[groupKey]!['episodes'] as List;
          if (!epList.any(
            (e) => e['number'].toString() == epNumber.toString(),
          )) {
            epList.add({
              'id': v['video_id']?.toString(),
              'number': epNumber.toString(),
              'url': url.toString(),
              'player': playerName,
            });
          }
        } else if ((groupedStudios[groupKey]!['episodes'] as List).isEmpty) {
          (groupedStudios[groupKey]!['episodes'] as List).add({
            'id': v['video_id']?.toString(),
            'number': '1',
            'url': url.toString(),
            'player': playerName,
          });
        }
      }

      final result = groupedStudios.values.toList();

      // Kodik is the most reliable direct-stream source. Preserve the other
      // players as explicit fallbacks instead of silently mixing their URLs.
      result.sort((a, b) {
        final providerOrder = (b['isKodik'] == true ? 1 : 0).compareTo(
          a['isKodik'] == true ? 1 : 0,
        );
        if (providerOrder != 0) return providerOrder;
        return (b['episodes'] as List).length.compareTo(
          (a['episodes'] as List).length,
        );
      });
      for (var map in result) {
        (map['episodes'] as List).sort(
          (a, b) => (int.tryParse(a['number'].toString()) ?? 0).compareTo(
            int.tryParse(b['number'].toString()) ?? 0,
          ),
        );
      }

      debugPrint(
        '📦 [YUMMY API] Сгруппировано уникальных студий: ${result.length}',
      );
      return result;
    } catch (e) {
      debugPrint('🛑 Ошибка загрузки студий YummyAnime: $e');
      throw Exception('Не удалось загрузить плееры YummyAnime');
    }
  }

  Future<List<Map<String, dynamic>>> _searchYummyCandidates(
    String nameRu,
    String nameEn,
    int? shikimoriId,
  ) async {
    final cleanRu = _cleanSearchQuery(nameRu);
    final cleanEn = _cleanSearchQuery(nameEn);

    final Map<int, dynamic> uniqueReleases = {};

    Future<void> fetchUrl(String q) async {
      try {
        final url = '${Config.yummyApiBase}/anime';
        final data = await _cachedGet(
          url,
          queryParameters: {'q': q, 'limit': 15},
          headers: Config.yummyApiHeaders,
          freshFor: const Duration(hours: 1),
        );
        List<dynamic> releases = [];

        if (data is Map && data['response'] != null) {
          releases = data['response'];
        } else if (data is Map && data['data'] != null) {
          releases = data['data'];
        } else if (data is List) {
          releases = data;
        }

        for (var r in releases) {
          final id = r['anime_id'] as int? ?? r['id'] as int?;
          if (id != null) uniqueReleases[id] = r;
        }
      } catch (_) {}
    }

    final queries = [
      if (cleanRu.isNotEmpty) cleanRu,
      if (cleanEn.isNotEmpty) cleanEn,
    ];

    for (final q in queries) {
      await fetchUrl(q);
      if (uniqueReleases.isNotEmpty) break;
    }

    if (uniqueReleases.isEmpty) return [];

    List<dynamic> combinedReleases = uniqueReleases.values.toList();

    for (var r in combinedReleases) {
      int score = 0;
      final remoteShikiId = r['remote_ids']?['shikimori_id'];

      if (shikimoriId != null &&
          remoteShikiId != null &&
          remoteShikiId.toString() == shikimoriId.toString()) {
        score = 100;
      } else {
        final titleRu =
            (r['title'] ?? r['name_ru'] ?? r['russian'] ?? r['name'] ?? '')
                .toString();
        final titleEn =
            (r['title_en'] ??
                    r['name_en'] ??
                    r['english'] ??
                    r['original'] ??
                    '')
                .toString();

        final adapter = {
          'name': {'main': titleRu, 'english': titleEn, 'alternative': ''},
        };
        score = _calculateMatchScore(adapter, nameRu, nameEn);
      }
      r['matchScore'] = score;
    }

    combinedReleases.sort((a, b) {
      final scoreA = a['matchScore'] as int;
      final scoreB = b['matchScore'] as int;
      if (scoreA != scoreB) return scoreB.compareTo(scoreA);

      final yearA = a['year'] as int? ?? 0;
      final yearB = b['year'] as int? ?? 0;
      return yearB.compareTo(yearA);
    });

    return combinedReleases.take(15).map((r) {
      final titleRu =
          (r['title'] ?? r['name_ru'] ?? r['russian'] ?? r['name'] ?? '')
              .toString();
      final titleEn =
          (r['title_en'] ?? r['name_en'] ?? r['english'] ?? r['original'] ?? '')
              .toString();
      final title = titleEn.isNotEmpty && titleRu != titleEn
          ? '$titleRu / $titleEn'
          : titleRu;

      final posterObj = r['poster'];
      String poster = '';
      if (posterObj is Map) {
        poster =
            posterObj['fullsize'] ??
            posterObj['medium'] ??
            posterObj['small'] ??
            '';
      } else if (posterObj is String) {
        poster = posterObj;
      }
      if (poster.startsWith('//')) poster = 'https:$poster';

      return {
        'id': r['anime_id'] ?? r['id'],
        'shikimori_id': r['remote_ids']?['shikimori_id'],
        'title': title,
        'year': r['year'] ?? 0,
        'episodes': r['episodes_count'] ?? r['episodes'] ?? 0,
        'poster': poster,
        'matchScore': r['matchScore'],
      };
    }).toList();
  }

  // =====================================================================
  // 🟣 БЛОК ANILIBRIA
  // =====================================================================

  Future<List<Map<String, dynamic>>> loadEpisodesDirect(
    String provider,
    String releaseId,
  ) async {
    if (provider == 'anilibria') {
      final data = await _cachedGet(
        '${Config.aniLibertyApiBase}/anime/releases/$releaseId',
        freshFor: const Duration(hours: 1),
      );
      final playlist = data['episodes'] as List<dynamic>? ?? [];

      return playlist
          .map((ep) {
            final qualities = <String, String>{};
            void addQuality(String label, dynamic rawUrl) {
              if (rawUrl == null || rawUrl.toString().isEmpty) return;
              var url = rawUrl.toString();
              if (url.startsWith('//')) url = 'https:$url';
              if (url.startsWith('/')) {
                url = Uri.parse(
                  Config.aniLibertyApiBase,
                ).resolve(url).toString();
              }
              qualities[label] = url;
            }

            addQuality('1080p', ep['hls_1080']);
            addQuality('720p', ep['hls_720']);
            addQuality('480p', ep['hls_480']);
            final videoUrl =
                qualities['1080p'] ?? qualities['720p'] ?? qualities['480p'];
            final number = ep['ordinal'] ?? ep['episode'] ?? 0;
            return {
              'number': number,
              'title': ep['name']?.toString() ?? 'Серия $number',
              'videoUrl': videoUrl,
              'qualities': qualities,
            };
          })
          .where((e) => e['number'] != 0)
          .toList();
    }
    throw Exception('Неизвестный провайдер видео: $provider');
  }

  Future<List<Map<String, dynamic>>> _searchCandidates(
    String provider,
    String nameRu,
    String nameEn,
  ) async {
    if (provider != 'anilibria') return [];

    final cleanRu = _cleanSearchQuery(nameRu);
    final cleanEn = _cleanSearchQuery(nameEn);

    final Map<int, dynamic> uniqueReleases = {};

    try {
      final results = await Future.wait<dynamic>([
        if (cleanRu.isNotEmpty)
          _cachedGet(
            '${Config.aniLibertyApiBase}/app/search/releases',
            queryParameters: {'query': cleanRu},
            freshFor: const Duration(hours: 1),
          ),
        if (cleanEn.isNotEmpty)
          _cachedGet(
            '${Config.aniLibertyApiBase}/app/search/releases',
            queryParameters: {'query': cleanEn},
            freshFor: const Duration(hours: 1),
          ),
      ]);

      for (final data in results) {
        final releases = data is List
            ? data
            : (data['releases'] as List? ?? []);
        for (var r in releases) {
          final id = r['id'] as int;
          uniqueReleases[id] = r;
        }
      }
    } catch (e) {
      debugPrint('Ошибка поиска в Anilibria API: $e');
    }

    if (uniqueReleases.isEmpty) return [];

    List<dynamic> combinedReleases = uniqueReleases.values.toList();

    for (var r in combinedReleases) {
      r['matchScore'] = _calculateMatchScore(r, nameRu, nameEn);
    }

    combinedReleases.sort((a, b) {
      final scoreA = a['matchScore'] as int;
      final scoreB = b['matchScore'] as int;
      if (scoreA != scoreB) return scoreB.compareTo(scoreA);

      final epsA = (a['episodes']?['total'] as int?) ?? 1;
      final epsB = (b['episodes']?['total'] as int?) ?? 1;
      if (epsA != epsB) return epsB.compareTo(epsA);

      return (b['year'] as int? ?? 0).compareTo(a['year'] as int? ?? 0);
    });

    return combinedReleases.take(15).map((r) {
      final titleMain = r['name']?['main'] ?? '';
      final titleAlt = r['name']?['english'] ?? r['name']?['alternative'] ?? '';
      final title = titleAlt.isNotEmpty ? '$titleMain / $titleAlt' : titleMain;

      return {
        'id': r['id'],
        'title': title,
        'year': r['year'] ?? 0,
        'episodes': r['episodes']?['total'] ?? 1,
        'poster': r['poster']?['original'] ?? r['poster']?['preview'] ?? '',
        'matchScore': r['matchScore'],
      };
    }).toList();
  }

  // =====================================================================
  // ⚙️ ОБЩАЯ ЛОГИКА УМНОГО ПОИСКА И СОВПАДЕНИЙ
  // =====================================================================

  String _cleanSearchQuery(String query) {
    return query.replaceAll(RegExp(r'[\(\[\{]?\d{4}[\)\]\}]?'), '').trim();
  }

  int _calculateMatchScore(dynamic release, String queryRu, String queryEn) {
    final mainName = (release['name']?['main'] ?? '').toString().toLowerCase();
    final engName = (release['name']?['english'] ?? '')
        .toString()
        .toLowerCase();
    final altName = (release['name']?['alternative'] ?? '')
        .toString()
        .toLowerCase();

    final score1 = _compareStrings(queryRu.toLowerCase(), mainName);
    final score2 = _compareStrings(queryRu.toLowerCase(), altName);
    final score3 = _compareStrings(queryEn.toLowerCase(), engName);
    final score4 = _compareStrings(queryEn.toLowerCase(), altName);
    final score5 = _compareStrings(queryEn.toLowerCase(), mainName);

    return [score1, score2, score3, score4, score5].reduce(max);
  }

  int _compareStrings(String query, String target) {
    if (query.isEmpty || target.isEmpty) return 0;

    String normQ = _normalizeForComparison(query);
    String normT = _normalizeForComparison(target);

    if (normQ == normT) return 100;

    final wordsQ = normQ.split(' ').where((w) => w.isNotEmpty).toList();
    final wordsT = normT.split(' ').where((w) => w.isNotEmpty).toList();

    if (wordsQ.isEmpty || wordsT.isEmpty) return 0;

    var matches = 0;
    bool missingVitalNumber = false;

    final numsQ = wordsQ
        .where(
          (w) =>
              RegExp(r'^\d+$').hasMatch(w) ||
              w == 'ii' ||
              w == 'iii' ||
              w == 'iv',
        )
        .toList();
    final numsT = wordsT
        .where(
          (w) =>
              RegExp(r'^\d+$').hasMatch(w) ||
              w == 'ii' ||
              w == 'iii' ||
              w == 'iv',
        )
        .toList();

    if (numsQ.isNotEmpty) {
      for (var n in numsQ) {
        if (!numsT.contains(n)) missingVitalNumber = true;
      }
    } else if (numsT.isNotEmpty) {
      missingVitalNumber = true;
    }

    for (var w in wordsQ) {
      if (wordsT.contains(w)) {
        matches++;
      }
    }

    final recall = matches / wordsQ.length;
    final precision = matches / wordsT.length;
    var score = recall + precision == 0
        ? 0.0
        : (2 * recall * precision / (recall + precision)) * 100;

    if (missingVitalNumber) {
      score -= 60;
    }

    if (normT.contains(normQ) && normQ.length > 4 && !missingVitalNumber) {
      score += 8;
    }

    return score.clamp(0, 100).round();
  }

  String _normalizeForComparison(String s) {
    String n = s.replaceAll(RegExp(r'[\(\[\{]?\d{4}[\)\]\}]?'), ' ');
    n = n.replaceAll(RegExp(r'[^\w\sа-яА-ЯёЁ]'), ' ');
    return n.replaceAll(RegExp(r'\s+'), ' ').trim();
  }
}
