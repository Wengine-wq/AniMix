import 'package:dio/dio.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter/foundation.dart';

import '../models/shikimori_anime.dart';
import '../models/shikimori_anime_detail.dart';
import '../models/shikimori_comment.dart';
import '../models/shikimori_user.dart';
import '../models/shikimori_history.dart';
import '../providers/auth_provider.dart';
import 'secure_storage.dart';

// Глобальный navigator используется только для системного диалога сессии.
import '../main.dart';

class ShikimoriApiClient {
  late final Dio _dio;
  final Ref ref;

  // 🔥 ЗАЩИТА ОТ КРАША: Флаг для предотвращения спама диалогами при множественных 401 ошибках
  static bool _isSessionExpiredHandled = false;

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
          // 🔥 ПЕРЕХВАТ ИСТЕКШЕГО ТОКЕНА (401 Unauthorized)
          if (error.response?.statusCode == 401) {
            // Проверяем, не обрабатываем ли мы уже выход из аккаунта.
            // Это спасет от краша, если 5 запросов одновременно вернут 401.
            if (!_isSessionExpiredHandled) {
              _isSessionExpiredHandled = true; // Блокируем остальные ошибки

              debugPrint(
                '❌ Ошибка 401: Токен истек или отозван. Сбрасываем сессию.',
              );
              await SecureStorage.clear();

              // Инвалидируем провайдер авторизации (перекинет на экран входа)
              ref.invalidate(isLoggedInProvider);

              // Показываем единый диалог истёкшей сессии.
              // ignore: argument_type_not_assignable
              showSessionExpiredDialog(ref as dynamic);

              // Снимаем блокировку через 3 секунды, когда интерфейс уже перейдет на экран логина
              Future.delayed(const Duration(seconds: 3), () {
                _isSessionExpiredHandled = false;
              });
            }
          }
          return handler.next(error);
        },
      ),
    );
  }

  // =====================================================================
  // СТРОГО ОРИГИНАЛЬНЫЕ МЕТОДЫ ИЗ ВАЛИДНОГО ФАЙЛА (БЕЗ ИЗМЕНЕНИЙ)
  // =====================================================================

  Future<ShikimoriUser> getCurrentUser() async {
    final whoamiRes = await _dio.get('/api/users/whoami');
    final userId = whoamiRes.data['id'] as int;
    final fullRes = await _dio.get('/api/users/$userId');
    return ShikimoriUser.fromJson(fullRes.data);
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
    final res = await _dio.get('/api/animes', queryParameters: queryParams);
    return (res.data as List)
        .map((json) => ShikimoriAnime.fromJson(json))
        .toList();
  }

  Future<ShikimoriAnimeDetail> getAnimeDetail(int id) async {
    final res = await _dio.get('/api/animes/$id');
    return ShikimoriAnimeDetail.fromJson(res.data);
  }

  Future<List<String>> getAnimeScreenshots(int animeId) async {
    final res = await _dio.get('/api/animes/$animeId/screenshots');
    return (res.data as List? ?? [])
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
      final res = await _dio.get('/api/animes/$animeId/similar');
      return (res.data as List? ?? const [])
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
      final res = await _dio.get('/api/animes/$animeId/related');
      return List<Map<String, dynamic>>.from(res.data);
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
  }) async {
    try {
      final res = await _dio.get(
        '/api/comments',
        queryParameters: {
          'commentable_id': topicId,
          'commentable_type': 'Topic',
          'limit': 30,
          'page': page, // 🔥 Включаем поддержку пагинации
          'desc': 1, // 1 = сначала новые
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
}
