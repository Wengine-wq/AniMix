import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'config.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PosterFallbackService {
  PosterFallbackService._();

  static final PosterFallbackService instance = PosterFallbackService._();
  static const _cacheKey = 'animix_provider_poster_cache_v1';

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
      headers: const {
        'Accept': 'application/json',
        'User-Agent': 'AniMix/1.0 Flutter',
      },
    ),
  );
  final Map<int, String> _cache = {};
  final Map<int, Future<String?>> _inFlight = {};
  Future<void>? _initializing;

  Future<void> initialize() => _initializing ??= _loadCache();

  Future<String?> resolve({
    required int shikimoriId,
    required String title,
    String? russianTitle,
  }) async {
    await initialize();
    final cached = _cache[shikimoriId];
    if (cached != null) return cached;

    return _inFlight.putIfAbsent(
      shikimoriId,
      () => _resolveAndCache(
        shikimoriId: shikimoriId,
        title: title,
        russianTitle: russianTitle,
      ),
    );
  }

  String? cached(int shikimoriId) => _cache[shikimoriId];

  Future<void> clear() async {
    _cache.clear();
    _inFlight.clear();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_cacheKey);
  }

  Future<String?> _resolveAndCache({
    required int shikimoriId,
    required String title,
    String? russianTitle,
  }) async {
    try {
      final queries = <String>{
        if (russianTitle?.trim().isNotEmpty == true) russianTitle!.trim(),
        if (title.trim().isNotEmpty) title.trim(),
      };
      for (final query in queries) {
        final yummy = await _fromYummy(shikimoriId, query, queries);
        if (yummy != null) return await _remember(shikimoriId, yummy);
      }
      for (final query in queries) {
        final liberty = await _fromAniLiberty(query, queries);
        if (liberty != null) return await _remember(shikimoriId, liberty);
      }
      return null;
    } finally {
      _inFlight.remove(shikimoriId);
    }
  }

  Future<String?> _fromYummy(
    int shikimoriId,
    String query,
    Set<String> expectedTitles,
  ) async {
    for (final host in [
      Uri.parse(Config.yummyApiBase).host,
      'api.yummyani.me',
    ]) {
      try {
        final response = await _dio.get<dynamic>(
          'https://$host/anime',
          queryParameters: {'q': query, 'limit': 10},
          options: Options(headers: Config.yummyApiHeaders),
        );
        final items = _listFrom(response.data);
        Map<dynamic, dynamic>? match;
        for (final raw in items) {
          if (raw is! Map) continue;
          final remoteId =
              raw['remote_ids']?['shikimori_id'] ?? raw['shikimori_id'];
          if (remoteId?.toString() == shikimoriId.toString()) {
            match = raw;
            break;
          }
        }
        match ??= _bestTitleMatch(items, expectedTitles);
        final poster = _posterFrom(match);
        if (poster != null) return _absoluteYummyPoster(poster);
      } catch (_) {
        // Try the next mirror.
      }
    }
    return null;
  }

  Future<String?> _fromAniLiberty(
    String query,
    Set<String> expectedTitles,
  ) async {
    for (final host in const [
      'aniliberty.top',
      'api.aniliberty.top',
      'aniliberty.ru',
    ]) {
      try {
        final response = await _dio.get<dynamic>(
          'https://$host/api/v1/app/search/releases',
          queryParameters: {'query': query},
        );
        final items = _listFrom(response.data, nestedKey: 'releases');
        final match = _bestTitleMatch(items, expectedTitles);
        final poster = _posterFrom(match);
        if (poster != null) {
          if (poster.startsWith('http')) return poster;
          final path = poster.startsWith('/') ? poster : '/$poster';
          return 'https://${host.replaceFirst('api.', '')}$path';
        }
      } catch (_) {
        // Try the next mirror.
      }
    }
    return null;
  }

  static List<dynamic> _listFrom(dynamic data, {String? nestedKey}) {
    if (data is List) return data;
    if (data is! Map) return const [];
    if (nestedKey != null && data[nestedKey] is List) {
      return data[nestedKey] as List;
    }
    if (data['response'] is List) return data['response'] as List;
    if (data['data'] is List) return data['data'] as List;
    return const [];
  }

  static Map<dynamic, dynamic>? _bestTitleMatch(
    List<dynamic> items,
    Set<String> expectedTitles,
  ) {
    final expected = expectedTitles
        .map(_normalizeTitle)
        .where((e) => e.length >= 5);
    for (final raw in items) {
      if (raw is! Map) continue;
      final candidates = <String>{
        raw['title']?.toString() ?? '',
        raw['name'] is Map
            ? raw['name']['main']?.toString() ?? ''
            : raw['name']?.toString() ?? '',
        raw['name'] is Map ? raw['name']['english']?.toString() ?? '' : '',
        raw['name_ru']?.toString() ?? '',
        raw['name_en']?.toString() ?? '',
      }.map(_normalizeTitle).where((e) => e.length >= 5);
      for (final candidate in candidates) {
        if (expected.any(
          (value) =>
              candidate == value ||
              candidate.contains(value) ||
              value.contains(candidate),
        )) {
          return raw;
        }
      }
    }
    return null;
  }

  static String? _posterFrom(Map<dynamic, dynamic>? item) {
    if (item == null) return null;
    final poster = item['poster'];
    if (poster is String && poster.isNotEmpty) return poster;
    if (poster is Map) {
      for (final key in const [
        'big',
        'original',
        'fullsize',
        'medium',
        'preview',
        'small',
        'src',
      ]) {
        final value = poster[key]?.toString();
        if (value != null && value.isNotEmpty) return value;
      }
    }
    return null;
  }

  static String _absoluteYummyPoster(String raw) {
    if (raw.startsWith('http')) return raw;
    if (raw.startsWith('//')) return 'https:$raw';
    return '${Config.yummyWebOrigin}/${raw.replaceFirst(RegExp(r'^/+'), '')}';
  }

  static String _normalizeTitle(String value) => value
      .toLowerCase()
      .replaceAll('ё', 'е')
      .replaceAll(RegExp(r'[^a-zа-я0-9]+', caseSensitive: false), ' ')
      .trim()
      .replaceAll(RegExp(r'\s+'), ' ');

  Future<String> _remember(int id, String url) async {
    _cache[id] = url;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _cacheKey,
      jsonEncode(_cache.map((key, value) => MapEntry(key.toString(), value))),
    );
    return url;
  }

  Future<void> _loadCache() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_cacheKey);
    if (raw == null) return;
    try {
      final values = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      for (final entry in values.entries) {
        final id = int.tryParse(entry.key);
        final url = entry.value?.toString();
        if (id != null && url != null && Uri.tryParse(url)?.hasScheme == true) {
          _cache[id] = url;
        }
      }
    } catch (_) {
      // Ignore malformed cache left by a development build.
    }
  }
}
