import 'dart:math';
import 'package:dio/dio.dart';
import '../models/watch_mapping.dart';
import '../repositories/watch_mapping_repository.dart';
import 'package:flutter/foundation.dart';

class WatchResolverService {
  final _dio = Dio(BaseOptions(
    headers: {
      'User-Agent': 'AniMix/1.0 (Flutter) Mozilla/5.0',
    },
  ));

  final _repo = WatchMappingRepository();

  /// Главный метод для поиска или получения видео.
  Future<dynamic> resolve({
    required int shikimoriId,
    required String provider,
    required String searchNameRu,
    required String searchNameEn,
    bool forcePicker = false, // <-- НОВЫЙ ПАРАМЕТР
  }) async {
    // 1. Проверяем локальную память.
    final mapping = await _repo.get('${shikimoriId}_$provider');
    // Если мы не форсируем выбор и уже есть сохраненный релиз - отдаем его
    if (!forcePicker && mapping != null && mapping.releaseId.isNotEmpty) {
      return loadEpisodesDirect(provider, mapping.releaseId);
    }

    // 2. Ищем кандидатов сразу по двум названиям
    final candidates = await _searchCandidates(provider, searchNameRu, searchNameEn);
    if (candidates.isEmpty) throw Exception('Релиз не найден в базе $provider');

    // 3. Отбираем ВСЕХ кандидатов с высоким шансом совпадения
    final highScorers = candidates.where((c) => (c['matchScore'] as int) >= 90).toList();

    // 🔥 ГЛАВНЫЙ ФИКС: Авто-выбор работает ТОЛЬКО если есть РОВНО ОДИН идеальный кандидат.
    // Если их несколько (например, 1, 2 и 3 сезоны "Магической битвы") — не угадываем, а просим выбрать.
    if (!forcePicker && highScorers.length == 1) {
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
      return loadEpisodesDirect(provider, best['id'].toString());
    }

    // 4. Если точного совпадения нет, их слишком много, ИЛИ юзер нажал "Сбросить" -> ручной выбор
    return {
      'needsPicker': true,
      'candidates': candidates,
      'shikimoriId': shikimoriId,
      'provider': provider,
    };
  }

  Future<void> saveMapping(WatchMapping mapping) => _repo.save(mapping);

  Future<List<Map<String, dynamic>>> loadEpisodesDirect(String provider, String releaseId) async {
    if (provider == 'anilibria') {
      final res = await _dio.get('https://anilibria.top/api/v1/anime/releases/$releaseId');
      final playlist = res.data['episodes'] as List<dynamic>? ?? [];

      return playlist.map((ep) {
        final videoUrl = ep['hls_1080'] ?? ep['hls_720'] ?? ep['hls_480'];
        final number = ep['ordinal'] ?? ep['episode'] ?? 0;
        return {
          'number': number,
          'title': ep['name']?.toString() ?? 'Серия $number',
          'videoUrl': videoUrl,
        };
      }).where((e) => e['number'] != 0).toList();
    }
    throw Exception('Неизвестный провайдер видео: $provider');
  }

  // ==================== УМНЫЙ ПОИСК И АЛГОРИТМ СОВПАДЕНИЙ ====================

  Future<List<Map<String, dynamic>>> _searchCandidates(String provider, String nameRu, String nameEn) async {
    if (provider != 'anilibria') return [];

    final cleanRu = _cleanSearchQuery(nameRu);
    final cleanEn = _cleanSearchQuery(nameEn);

    final Map<int, dynamic> uniqueReleases = {};

    try {
      final results = await Future.wait([
        if (cleanRu.isNotEmpty) _dio.get('https://anilibria.top/api/v1/app/search/releases', queryParameters: {'query': cleanRu}),
        if (cleanEn.isNotEmpty) _dio.get('https://anilibria.top/api/v1/app/search/releases', queryParameters: {'query': cleanEn}),
      ]);

      for (var res in results) {
        final releases = res.data is List ? res.data : (res.data['releases'] as List? ?? []);
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

  String _cleanSearchQuery(String query) {
    return query.replaceAll(RegExp(r'[\(\[\{]?\d{4}[\)\]\}]?'), '').trim();
  }

  int _calculateMatchScore(dynamic release, String queryRu, String queryEn) {
    final mainName = (release['name']?['main'] ?? '').toString().toLowerCase();
    final engName = (release['name']?['english'] ?? '').toString().toLowerCase();
    final altName = (release['name']?['alternative'] ?? '').toString().toLowerCase();

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

    int matches = 0;
    bool missingVitalNumber = false;

    final numsQ = wordsQ.where((w) => RegExp(r'^\d+$').hasMatch(w) || w == 'ii' || w == 'iii' || w == 'iv').toList();
    final numsT = wordsT.where((w) => RegExp(r'^\d+$').hasMatch(w) || w == 'ii' || w == 'iii' || w == 'iv').toList();

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

    double score = (matches / wordsQ.length) * 100;

    if (missingVitalNumber) {
      score -= 60; 
    }

    if (normT.contains(normQ) && normQ.length > 4 && !missingVitalNumber) {
      score += 15;
    }

    return score.clamp(0, 100).round();
  }

  String _normalizeForComparison(String s) {
    String n = s.replaceAll(RegExp(r'[\(\[\{]?\d{4}[\)\]\}]?'), ' '); 
    n = n.replaceAll(RegExp(r'[^\w\sа-яА-ЯёЁ]'), ' '); 
    return n.replaceAll(RegExp(r'\s+'), ' ').trim();
  }
}