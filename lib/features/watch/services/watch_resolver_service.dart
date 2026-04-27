import 'dart:math';
import 'package:dio/dio.dart';
import '../models/watch_mapping.dart';
import '../repositories/watch_mapping_repository.dart';
import 'package:flutter/foundation.dart';

class WatchResolverService {
  final _dio = Dio(BaseOptions(
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
      'Accept': 'application/json',
    },
    connectTimeout: const Duration(seconds: 15),
    receiveTimeout: const Duration(seconds: 15),
  ));

  final _repo = WatchMappingRepository();

  Future<dynamic> resolve({
    required int shikimoriId,
    required String provider,
    required String searchNameRu,
    required String searchNameEn,
    bool forcePicker = false,
  }) async {
    final mapping = await _repo.get('${shikimoriId}_$provider');
    
    if (!forcePicker && mapping != null && mapping.releaseId.isNotEmpty) {
      return provider == 'yummyanime' 
          ? loadYummyStudios(mapping.releaseId)
          : loadEpisodesDirect(provider, mapping.releaseId);
    }

    final candidates = provider == 'yummyanime'
        ? await _searchYummyCandidates(searchNameRu, searchNameEn, shikimoriId)
        : await _searchCandidates(provider, searchNameRu, searchNameEn);
        
    if (candidates.isEmpty) throw Exception('Релиз не найден в базе $provider');

    final highScorers = candidates.where((c) => (c['matchScore'] as int) >= 90).toList();

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
      
      return provider == 'yummyanime' 
          ? loadYummyStudios(best['id'].toString())
          : loadEpisodesDirect(provider, best['id'].toString());
    }

    return {
      'needsPicker': true,
      'candidates': candidates,
      'shikimoriId': shikimoriId,
      'provider': provider,
    };
  }

  Future<List<Map<String, dynamic>>> searchManual(String provider, String query) async {
    if (provider == 'yummyanime') {
       return await _searchYummyCandidates(query, '', null);
    } else {
       return await _searchCandidates(provider, query, '');
    }
  }

  Future<void> saveMapping(WatchMapping mapping) => _repo.save(mapping);

  // =====================================================================
  // 🟢 БЛОК YUMMY ANIME (С УМНОЙ ГРУППИРОВКОЙ)
  // =====================================================================

  Future<List<Map<String, dynamic>>> loadYummyStudios(String yummyId) async {
    debugPrint('🌐 [YUMMY API] Получение студий для релиза: $yummyId');
    try {
      final url = 'https://api.yani.tv/anime/$yummyId/videos';
      final res = await _dio.get(url);
      
      final data = res.data;
      List<dynamic> videos = [];
      if (data is List) {
        videos = data;
      } else if (data is Map && data['response'] != null) {
        videos = data['response'];
      } else if (data is Map && data['data'] != null) {
        videos = data['data'];
      }
      
      debugPrint('📦 [YUMMY API] Найдено сырых элементов (серий): ${videos.length}');
      
      // 🔥 ГРУППИРУЕМ ПЛОСКИЙ СПИСОК СЕРИЙ ПО НАЗВАНИЮ СТУДИИ
      Map<String, Map<String, dynamic>> groupedStudios = {};

      for (var v in videos) {
        if (v is! Map) continue;

        String trName = 'Неизвестная озвучка';

        // 🔥 ИЩЕМ ИМЯ СТУДИИ В ОБЪЕКТЕ DATA (Прямо как в твоем JSON)
        if (v['data'] is Map) {
          final dataMap = v['data'] as Map;
          if (dataMap['dubbing'] != null && dataMap['dubbing'].toString().isNotEmpty) {
            trName = dataMap['dubbing'].toString(); // "Озвучка AniLibria"
          } else if (dataMap['player'] != null && dataMap['player'].toString().isNotEmpty) {
            trName = dataMap['player'].toString(); // "Плеер Alloha"
          }
        } 
        // Фоллбэки на случай других форматов ответа
        else {
          if (v['translation'] is Map && v['translation']['name'] != null) trName = v['translation']['name'];
          else if (v['translation'] is Map && v['translation']['title'] != null) trName = v['translation']['title'];
          else if (v['translation'] is String) trName = v['translation'];
          else if (v['translation_name'] != null) trName = v['translation_name'];
          else if (v['author'] != null) trName = v['author'];
          else if (v['studio'] is Map && v['studio']['name'] != null) trName = v['studio']['name'];
          else if (v['player_name'] != null) trName = v['player_name'];
          else if (v['name'] != null) trName = v['name'];
        }

        final String nameStr = trName.toString().trim();
        final epNumber = v['number'] ?? v['episode'] ?? v['episode_number'];
        final url = v['iframe_url'] ?? v['player_url'] ?? v['url'] ?? v['link'] ?? '';

        if (!groupedStudios.containsKey(nameStr)) {
          groupedStudios[nameStr] = {
            'name': nameStr,
            'episodes': <Map<String, dynamic>>[],
            'url': url, // Дефолтный URL
          };
        }

        if (epNumber != null) {
          final epList = groupedStudios[nameStr]!['episodes'] as List;
          // Защита от дублей серий
          if (!epList.any((e) => e['number'].toString() == epNumber.toString())) {
            epList.add({
              'number': epNumber.toString(),
              'url': url,
            });
          }
        } else if ((groupedStudios[nameStr]!['episodes'] as List).isEmpty) {
           // Если это фильм (нет номера серии), добавляем как 1-ю серию
           (groupedStudios[nameStr]!['episodes'] as List).add({
              'number': '1',
              'url': url,
           });
        }
      }

      final result = groupedStudios.values.toList();
      
      // Сортируем: сначала те, у кого больше серий, затем сортируем серии внутри
      result.sort((a, b) => (b['episodes'] as List).length.compareTo((a['episodes'] as List).length));
      for (var map in result) {
        (map['episodes'] as List).sort((a, b) => (int.tryParse(a['number'].toString()) ?? 0).compareTo(int.tryParse(b['number'].toString()) ?? 0));
      }

      debugPrint('📦 [YUMMY API] Сгруппировано уникальных студий: ${result.length}');
      return result;
    } catch (e) {
      debugPrint('🛑 Ошибка загрузки студий YummyAnime: $e');
      throw Exception('Не удалось загрузить плееры YummyAnime');
    }
  }

  Future<List<Map<String, dynamic>>> _searchYummyCandidates(String nameRu, String nameEn, int? shikimoriId) async {
    final cleanRu = _cleanSearchQuery(nameRu);
    final cleanEn = _cleanSearchQuery(nameEn);

    final Map<int, dynamic> uniqueReleases = {};

    Future<void> fetchUrl(String q) async {
      try {
        final url = 'https://api.yani.tv/search';
        final res = await _dio.get(url, queryParameters: {'q': q, 'limit': 15});
        
        final data = res.data;
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

    final queries = [if (cleanRu.isNotEmpty) cleanRu, if (cleanEn.isNotEmpty) cleanEn];
    
    for (final q in queries) {
      await fetchUrl(q);
      if (uniqueReleases.isNotEmpty) break;
    }

    if (uniqueReleases.isEmpty) return [];

    List<dynamic> combinedReleases = uniqueReleases.values.toList();

    for (var r in combinedReleases) {
      int score = 0;
      final remoteShikiId = r['remote_ids']?['shikimori_id'];
      
      if (shikimoriId != null && remoteShikiId != null && remoteShikiId.toString() == shikimoriId.toString()) {
        score = 100; 
      } else {
        final titleRu = (r['title'] ?? r['name_ru'] ?? r['russian'] ?? r['name'] ?? '').toString();
        final titleEn = (r['title_en'] ?? r['name_en'] ?? r['english'] ?? r['original'] ?? '').toString();
        
        final adapter = {
          'name': {'main': titleRu, 'english': titleEn, 'alternative': ''}
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
      final titleRu = (r['title'] ?? r['name_ru'] ?? r['russian'] ?? r['name'] ?? '').toString();
      final titleEn = (r['title_en'] ?? r['name_en'] ?? r['english'] ?? r['original'] ?? '').toString();
      final title = titleEn.isNotEmpty && titleRu != titleEn ? '$titleRu / $titleEn' : titleRu;
      
      final posterObj = r['poster'];
      String poster = '';
      if (posterObj is Map) {
        poster = posterObj['fullsize'] ?? posterObj['medium'] ?? posterObj['small'] ?? '';
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

  // =====================================================================
  // ⚙️ ОБЩАЯ ЛОГИКА УМНОГО ПОИСКА И СОВПАДЕНИЙ
  // =====================================================================

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