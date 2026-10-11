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
  final Map<int, Set<String>> _rejectedUrls = {};
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

  void markFailed(int shikimoriId, String url) {
    _rejectedUrls.putIfAbsent(shikimoriId, () => <String>{}).add(url);
    if (_cache[shikimoriId] == url) {
      _cache.remove(shikimoriId);
      unawaited(_persistCache());
    }
  }

  Future<void> invalidate(int shikimoriId) async {
    _cache.remove(shikimoriId);
    _inFlight.remove(shikimoriId);
    _rejectedUrls.remove(shikimoriId);
    await _persistCache();
  }

  Future<void> clear() async {
    _cache.clear();
    _inFlight.clear();
    _rejectedUrls.clear();
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
      // Shikimori moved posters to /uploads/…; the REST API still answers
      // "missing" for many titles, the GraphQL API has the real file.
      final shikimori = await _fromShikimoriGraphql(shikimoriId);
      if (shikimori != null && !_isRejected(shikimoriId, shikimori)) {
        return await _remember(shikimoriId, shikimori);
      }
      // All sources and titles at once: YummyAnime alone can take several
      // seconds per search, and doing them one by one blew the poster's
      // time budget, leaving titles without a cover.
      Future<String?> safe(Future<String?> lookup) =>
          lookup.catchError((_) => null);
      final yummy = [
        for (final query in queries)
          safe(_fromYummy(shikimoriId, query, queries)),
      ];
      final liberty = [
        for (final query in queries) safe(_fromAniLiberty(query, queries)),
      ];
      final results = await Future.wait([...yummy, ...liberty]);
      // YummyAnime matches by Shikimori id, so it wins when it has a cover.
      for (final url in results) {
        if (url != null && !_isRejected(shikimoriId, url)) {
          return await _remember(shikimoriId, url);
        }
      }
      return null;
    } finally {
      _inFlight.remove(shikimoriId);
    }
  }

  // Lookups that arrive together (a shelf of cards) share one request.
  final Map<int, Completer<String?>> _graphqlPending = {};
  Timer? _graphqlTimer;

  Future<String?> _fromShikimoriGraphql(int shikimoriId) {
    final existing = _graphqlPending[shikimoriId];
    if (existing != null) return existing.future;
    final completer = Completer<String?>();
    _graphqlPending[shikimoriId] = completer;
    _graphqlTimer ??= Timer(const Duration(milliseconds: 60), _flushGraphql);
    return completer.future;
  }

  Future<void> _flushGraphql() async {
    _graphqlTimer = null;
    final batch = Map<int, Completer<String?>>.of(_graphqlPending);
    _graphqlPending.clear();
    final ids = batch.keys.toList();
    final found = <int, String>{};
    for (var start = 0; start < ids.length; start += 50) {
      final chunk = ids.skip(start).take(50).join(',');
      try {
        final response = await _dio.post<dynamic>(
          '${Config.shikimoriBaseUrl}/api/graphql',
          data: {
            'query':
                '{ animes(ids: "$chunk", limit: 50, censored: false) '
                '{ id poster { originalUrl } } }',
          },
          options: Options(contentType: Headers.jsonContentType),
        );
        final data = response.data is String
            ? jsonDecode(response.data as String)
            : response.data;
        final payload = data is Map ? data['data'] : null;
        final animes = payload is Map ? payload['animes'] : null;
        if (animes is! List) continue;
        for (final item in animes.whereType<Map>()) {
          final id = int.tryParse('${item['id']}') ?? 0;
          final url = item['poster']?['originalUrl']?.toString() ?? '';
          if (id > 0 && url.startsWith('http') && !url.contains('missing_')) {
            found[id] = url;
          }
        }
      } catch (_) {
        // Other sources still get a chance.
      }
    }
    batch.forEach((id, completer) => completer.complete(found[id]));
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
    await _persistCache();
    return url;
  }

  bool _isRejected(int id, String url) =>
      _rejectedUrls[id]?.contains(url) ?? false;

  Future<void> _persistCache() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _cacheKey,
      jsonEncode(_cache.map((key, value) => MapEntry(key.toString(), value))),
    );
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
