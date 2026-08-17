import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter/foundation.dart';

import '../models/shikimori_anime.dart';
import '../models/shikimori_anime_detail.dart';
import '../models/shikimori_comment.dart';
import '../models/shikimori_user.dart';
import '../models/shikimori_history.dart';
import '../providers/auth_provider.dart';
import 'app_logging.dart';
import 'secure_storage.dart';

class ShikimoriApiClient {
  late final Dio _dio;
  final Ref ref;
  final Map<String, _ShikimoriCacheEntry> _cache = {};
  final Map<String, Future<dynamic>> _inFlight = {};

  static Future<void>? _sessionExpiryTask;

  ShikimoriApiClient(this.ref) {
    _dio = Dio(
      BaseOptions(
        baseUrl: 'https://shikimori.io',
        connectTimeout: const Duration(seconds: 30),
        receiveTimeout: const Duration(seconds: 30),
        headers: {
          'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 AniMix/1.0',
          'Accept': 'application/json',
        },
      ),
    );

    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          final token = await SecureStorage.getAccessToken();
          if (token != null) {
            options.headers['Authorization'] = 'Bearer $token';
          }
          return handler.next(options);
        },
        onError: (error, handler) async {
          if (error.response?.statusCode == 401) {
            await _handleExpiredSession();
          } else {
            AppLogBuffer.instance.recordError(
              error,
              error.stackTrace,
              source: 'Shikimori API',
            );
          }
          return handler.next(error);
        },
      ),
    );
  }

  Future<void> _handleExpiredSession() async {
    if (ref.read(sessionNoticeProvider) != null) return;
    final pending = _sessionExpiryTask;
    if (pending != null) return pending;

    final task = () async {
      AppLogBuffer.instance.warning(
        'Shikimori rejected the current session (HTTP 401).',
        source: 'Authentication',
      );
      await SecureStorage.clear();
      _cache.clear();
      ref.read(sessionNoticeProvider.notifier).sessionExpired();
      ref.invalidate(isLoggedInProvider);
    }();
    _sessionExpiryTask = task;
    try {
      await task;
    } finally {
      if (identical(_sessionExpiryTask, task)) _sessionExpiryTask = null;
    }
  }

  void invalidateCurrentUserCache() => _cache.remove('current-user');

  // =====================================================================
  // СТРОГО ОРИГИНАЛЬНЫЕ МЕТОДЫ ИЗ ВАЛИДНОГО ФАЙЛА (БЕЗ ИЗМЕНЕНИЙ)
  // =====================================================================

  Future<ShikimoriUser> getCurrentUser() async {
    final data = await _cachedRequest('current-user', () async {
      final whoamiRes = await _dio.get('/api/users/whoami');
      final userId = int.tryParse(whoamiRes.data?['id']?.toString() ?? '');
      if (userId == null || userId <= 0) {
        throw const FormatException('Shikimori whoami returned no user id.');
      }
      final fullRes = await _dio.get('/api/users/$userId');
      return fullRes.data;
    }, freshFor: const Duration(seconds: 20));
    return ShikimoriUser.fromJson(Map<String, dynamic>.from(data as Map));
  }

  Future<List<ShikimoriAnime>> getAnimes({
    int page = 1,
    int limit = 30,
    Map<String, dynamic> filters = const {},
  }) async {
    final queryParams = {
      'page': page,
      'limit': limit,
      'order': 'popularity',
      ...filters,
    };
    final data = await _cachedRequest(
      'animes:${jsonEncode(queryParams)}',
      () async =>
          (await _dio.get('/api/animes', queryParameters: queryParams)).data,
      freshFor: const Duration(minutes: 2),
    );
    return (data as List).map((json) => ShikimoriAnime.fromJson(json)).toList();
  }

  Future<ShikimoriAnimeDetail> getAnimeDetail(int id) async {
    final data = await _cachedRequest(
      'anime-detail:$id',
      () async => (await _dio.get('/api/animes/$id')).data,
      freshFor: const Duration(minutes: 10),
    );
    return ShikimoriAnimeDetail.fromJson(
      Map<String, dynamic>.from(data as Map),
    );
  }

  Future<List<String>> getAnimeScreenshots(int animeId) async {
    final data = await _cachedRequest(
      'anime-screenshots:$animeId',
      () async => (await _dio.get('/api/animes/$animeId/screenshots')).data,
      freshFor: const Duration(hours: 1),
    );
    return (data as List? ?? [])
        .map((s) {
          final String path = s?['original'] ?? s?['preview'] ?? '';
          if (path.isEmpty) return '';
          return path.startsWith('http') ? path : 'https://shikimori.io$path';
        })
        .where((url) => url.isNotEmpty)
        .toList();
  }

  Future<List<ShikimoriAnime>> getSimilarAnimes(int animeId) async {
    try {
      final data = await _cachedRequest(
        'anime-similar:$animeId',
        () async => (await _dio.get('/api/animes/$animeId/similar')).data,
        freshFor: const Duration(hours: 1),
      );
      return (data as List? ?? const [])
          .map((json) => ShikimoriAnime.fromJson(json))
          .toList();
    } catch (_) {
      return const [];
    }
  }

  Future<Map<String, dynamic>?> getUserRate(
    int animeId, {
    required int userId,
  }) async {
    try {
      final res = await _dio.get(
        '/api/v2/user_rates',
        queryParameters: {
          'user_id': userId,
          'target_id': animeId,
          'target_type': 'Anime',
        },
      );
      final list = res.data as List;
      return list.isNotEmpty ? list.first as Map<String, dynamic> : null;
    } catch (e) {
      if (e is DioException && e.response?.statusCode == 422) return null;
      return null;
    }
  }

  Future<List<Map<String, dynamic>>> getUserAnimeRates(int userId) async {
    final response = await _dio.get(
      '/api/users/$userId/anime_rates',
      queryParameters: const {'limit': 5000},
    );
    return (response.data as List? ?? const [])
        .whereType<Map>()
        .map((value) => Map<String, dynamic>.from(value))
        .toList();
  }

  Future<void> setUserRate(
    int animeId,
    String status, {
    int? score,
    int? episodes,
    required int userId,
  }) async {
    final currentRate = await getUserRate(animeId, userId: userId);
    final rateId = currentRate?['id'];

    final body = {
      'user_rate': {
        'target_id': animeId,
        'target_type': 'Anime',
        'status': status,
        'score': ?score,
        'episodes': ?episodes,
        if (rateId == null) 'user_id': userId,
      },
    };

    if (rateId != null) {
      await _dio.patch('/api/v2/user_rates/$rateId', data: body);
    } else {
      await _dio.post('/api/v2/user_rates', data: body);
    }
    invalidateCurrentUserCache();
    ref.read(userDataRevisionProvider.notifier).bump();
  }

  Future<List<ShikimoriHistory>> getUserHistory(
    int userId, {
    int limit = 8,
  }) async {
    try {
      final res = await _dio.get(
        '/api/users/$userId/history',
        queryParameters: {'limit': limit},
      );
      return (res.data as List)
          .map((json) => ShikimoriHistory.fromJson(json))
          .toList();
    } catch (e) {
      return [];
    }
  }

  Future<List<Map<String, dynamic>>> getRelatedAnimes(int animeId) async {
    try {
      final data = await _cachedRequest(
        'anime-related:$animeId',
        () async => (await _dio.get('/api/animes/$animeId/related')).data,
        freshFor: const Duration(hours: 1),
      );
      return List<Map<String, dynamic>>.from(data as List);
    } catch (e) {
      return [];
    }
  }

  // =====================================================================
  // 💬 БЛОК КОММЕНТАРИЕВ (Работают строго с Topic)
  // =====================================================================

  Future<List<ShikimoriComment>> getComments(
    int topicId, {
    int page = 1,
    bool descending = true,
  }) async {
    try {
      final res = await _dio.get(
        '/api/comments',
        queryParameters: {
          'commentable_id': topicId,
          'commentable_type': 'Topic',
          'limit': 30,
          'page': page, // 🔥 Включаем поддержку пагинации
          'desc': descending ? 1 : 0,
        },
      );
      return (res.data as List)
          .map((c) => ShikimoriComment.fromJson(c))
          .toList();
    } catch (e) {
      debugPrint('Ошибка загрузки комментариев: $e');
      rethrow;
    }
  }

  Future<ShikimoriComment> postComment(int topicId, String text) async {
    final res = await _dio.post(
      '/api/comments',
      data: {
        'comment': {
          'body': text,
          'commentable_id': topicId,
          'commentable_type': 'Topic',
        },
      },
    );
    return ShikimoriComment.fromJson(res.data);
  }

  Future<dynamic> _cachedRequest(
    String key,
    Future<dynamic> Function() loader, {
    required Duration freshFor,
  }) async {
    final cached = _cache[key];
    if (cached != null &&
        DateTime.now().difference(cached.savedAt) <= freshFor) {
      return cached.data;
    }
    final pending = _inFlight[key];
    if (pending != null) return pending;
    final request = () async {
      try {
        final value = await loader();
        _cache[key] = _ShikimoriCacheEntry(DateTime.now(), value);
        return value;
      } finally {
        _inFlight.remove(key);
      }
    }();
    _inFlight[key] = request;
    return request;
  }
}

class _ShikimoriCacheEntry {
  const _ShikimoriCacheEntry(this.savedAt, this.data);

  final DateTime savedAt;
  final dynamic data;
}
