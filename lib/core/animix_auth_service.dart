import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';

import 'app_logging.dart';
import 'animix_local_cache.dart';
import 'config.dart';
import 'secure_storage.dart';

class AniMixAuthService {
  AniMixAuthService({Dio? dio, FutureOr<void> Function()? onSessionInvalidated})
    : _dio = dio ?? _createDio(),
      _onSessionInvalidated = onSessionInvalidated;

  final Dio _dio;
  final FutureOr<void> Function()? _onSessionInvalidated;

  // Access tokens are short-lived. Without a single-flight guard, several
  // parallel 401 responses rotate the same refresh token at once: the first
  // request succeeds and the second one clears the brand-new session. That
  // was the source of the seemingly random "login only after restart" bug.
  static Future<bool>? _refreshInFlight;
  static Future<Map<String, dynamic>?>? _profileRequestInFlight;
  static int _sessionGeneration = 0;

  static void _advanceSessionGeneration() {
    _sessionGeneration++;
    _profileRequestInFlight = null;
  }

  static Dio _createDio() => Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(seconds: 20),
      headers: const {'Accept': 'application/json'},
    ),
  );

  Future<AniMixAuthResult> loginWithGoogle() async {
    try {
      final startUri = Uri.parse(
        '${Config.animixApiBaseUrl}/v1/auth/google/start',
      ).replace(queryParameters: {'return_uri': Config.animixOAuthCallbackUri});
      final callback = await FlutterWebAuth2.authenticate(
        url: startUri.toString(),
        callbackUrlScheme: Config.animixOAuthCallbackScheme,
        options: const FlutterWebAuth2Options(
          useWebview: true,
          preferEphemeral: false,
          timeout: 120,
          httpsHost: 'oauth',
          httpsPath: '/callback',
        ),
      );
      final callbackUri = Uri.tryParse(callback);
      final ticket = callbackUri?.queryParameters['ticket']?.trim();
      final state = callbackUri?.queryParameters['state']?.trim();
      final error = callbackUri?.queryParameters['error'];
      if (error != null ||
          ticket == null ||
          ticket.isEmpty ||
          state == null ||
          state.isEmpty) {
        AppLogBuffer.instance.warning(
          'Google OAuth callback did not contain a valid one-time ticket${error == null ? '' : ': $error'}.',
          source: 'AniMix authentication',
        );
        return AniMixAuthResult.failure(_googleErrorMessage(error));
      }
      final response = await _dio.post<dynamic>(
        '${Config.animixApiBaseUrl}/v1/auth/google/exchange',
        data: {'ticket': ticket, 'state': state},
        options: Options(
          validateStatus: (status) => status != null && status < 600,
        ),
      );
      if (response.statusCode != 200 || response.data is! Map) {
        final errorCode = response.data is Map
            ? response.data['error']?.toString()
            : null;
        AppLogBuffer.instance.warning(
          'Google OAuth exchange failed: HTTP ${response.statusCode}, '
          'code=${errorCode ?? 'invalid_response'}.',
          source: 'AniMix authentication',
        );
        return AniMixAuthResult.failure(_googleErrorMessage(errorCode));
      }
      final data = Map<String, dynamic>.from(response.data as Map);
      final accessToken = data['access_token']?.toString().trim();
      final refreshToken = data['refresh_token']?.toString().trim();
      if (accessToken == null ||
          accessToken.isEmpty ||
          refreshToken == null ||
          refreshToken.isEmpty) {
        return const AniMixAuthResult.failure(
          'Сервер вернул неполную AniMix-сессию. Повторите вход.',
        );
      }
      _advanceSessionGeneration();
      await SecureStorage.saveAniMixTokens(
        accessToken: accessToken,
        refreshToken: refreshToken,
      );
      await AniMixLocalCache.clear();
      final profile = data['user'];
      if (profile is Map) {
        await AniMixLocalCache.writeProfile(Map<String, dynamic>.from(profile));
      }
      return const AniMixAuthResult.success();
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix Google OAuth',
      );
      if (error is PlatformException && error.code == 'CANCELED') {
        return const AniMixAuthResult.failure('Вход через Google был отменён.');
      }
      return const AniMixAuthResult.failure(
        'Не удалось завершить Google OAuth. Подробности сохранены в диагностике.',
      );
    }
  }

  String _googleErrorMessage(String? error) => switch (error) {
    'access_denied' => 'Вход был отменён в окне Google.',
    'google_auth_not_configured' =>
      'Google OAuth не настроен на сервере. Проверь Client ID и Client Secret.',
    'google_token_exchange_failed' =>
      'Google отверг обмен кода. Проверь, что Client Secret от того же Web OAuth Client и redirect URI совпадает символ в символ.',
    'google_token_invalid_client' =>
      'Google не принял Client Secret. Задай заново Client secret от Web OAuth Client с этим же Client ID.',
    'google_token_invalid_grant' =>
      'Google отклонил одноразовый код входа. Запусти новый вход и не открывай callback повторно.',
    'google_token_unauthorized_client' =>
      'Этот Google OAuth Client не разрешён для серверного обмена. Проверь тип клиента: Web application.',
    'google_profile_failed' =>
      'Google не отдал профиль после входа. Повтори попытку.',
    'google_email_not_verified' =>
      'У выбранного Google-аккаунта не подтверждён email.',
    'invalid_google_ticket' => 'Сессия входа истекла. Запусти вход ещё раз.',
    'google_callback_failed' =>
      'Сервер AniMix не смог завершить Google callback. Открой диагностику и скопируй лог.',
    'auth_unavailable' =>
      'Сервер AniMix временно не завершил вход. Повтори попытку через несколько секунд.',
    _ => 'Не удалось войти через Google. Повтори попытку.',
  };

  Future<Map<String, dynamic>?> getCachedCurrentUser() =>
      AniMixLocalCache.readProfile();

  Future<Map<String, dynamic>?> getCurrentUser({
    bool allowCachedFallback = true,
  }) {
    final active = _profileRequestInFlight;
    if (active != null) return active;
    final request = _getCurrentUser(
      allowCachedFallback: allowCachedFallback,
      generation: _sessionGeneration,
    );
    _profileRequestInFlight = request;
    return request.whenComplete(() {
      if (identical(_profileRequestInFlight, request)) {
        _profileRequestInFlight = null;
      }
    });
  }

  Future<Map<String, dynamic>?> _getCurrentUser({
    required bool allowCachedFallback,
    required int generation,
  }) async {
    final token = await SecureStorage.getAniMixAccessToken();
    if (token == null || token.isEmpty) {
      return allowCachedFallback ? await AniMixLocalCache.readProfile() : null;
    }
    try {
      var requestToken = token;
      var response = await _meResponseWithRetry(requestToken);
      if (response.statusCode == 401 && await refreshSession()) {
        final refreshedToken = await SecureStorage.getAniMixAccessToken();
        if (refreshedToken != null && refreshedToken.isNotEmpty) {
          requestToken = refreshedToken;
          response = await _meResponseWithRetry(requestToken);
        }
      }
      if (generation != _sessionGeneration ||
          await SecureStorage.getAniMixAccessToken() != requestToken) {
        return null;
      }
      if (response.statusCode != 200 || response.data is! Map) {
        final errorCode = response.data is Map
            ? response.data['error']?.toString()
            : null;
        AppLogBuffer.instance.warning(
          'Profile request failed: HTTP ${response.statusCode}, '
          'code=${errorCode ?? 'invalid_response'}.',
          source: 'AniMix profile',
        );
        return allowCachedFallback
            ? await AniMixLocalCache.readProfile()
            : null;
      }
      final data = Map<String, dynamic>.from(response.data as Map);
      final profile = data['user'] is Map
          ? Map<String, dynamic>.from(data['user'] as Map)
          : null;
      if (profile != null) await AniMixLocalCache.writeProfile(profile);
      return profile;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix profile',
      );
      return allowCachedFallback ? await AniMixLocalCache.readProfile() : null;
    }
  }

  Future<Map<String, dynamic>?> updateProfile({required String displayName}) =>
      _profileResponse(
        method: 'PATCH',
        path: '/v1/me/profile',
        data: {'display_name': displayName},
      );

  Future<Map<String, dynamic>?> uploadProfileMedia({
    required AniMixProfileMediaKind kind,
    required Uint8List bytes,
    required String contentType,
  }) => _profileResponse(
    method: 'PUT',
    path: '/v1/me/media/${kind.name}',
    data: bytes,
    contentType: contentType,
  );

  Future<Map<String, dynamic>?> deleteProfileMedia(
    AniMixProfileMediaKind kind,
  ) => _profileResponse(method: 'DELETE', path: '/v1/me/media/${kind.name}');

  Future<Map<String, dynamic>?> getLibraryEntry(int animeId) async {
    final response = await _authorizedRequest(
      (token) => _dio.get<dynamic>(
        '${Config.animixApiBaseUrl}/v1/library/$animeId',
        options: _authorizedOptions(token),
      ),
      retryTransient: true,
    );
    if (response == null ||
        response.statusCode != 200 ||
        response.data is! Map) {
      return null;
    }
    final data = Map<String, dynamic>.from(response.data as Map);
    return data['entry'] is Map
        ? Map<String, dynamic>.from(data['entry'] as Map)
        : null;
  }

  Future<List<Map<String, dynamic>>?> getLibraryEntries() async {
    try {
      final response = await _authorizedRequest(
        (token) => _dio.get<dynamic>(
          '${Config.animixApiBaseUrl}/v1/library',
          options: _authorizedOptions(token),
        ),
        retryTransient: true,
      );
      if (response?.statusCode != 200 || response?.data is! Map) {
        return await AniMixLocalCache.readLibrary();
      }
      final entries = (response!.data as Map)['entries'];
      if (entries is! List) return await AniMixLocalCache.readLibrary();
      final normalized = entries
          .whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList(growable: false);
      await AniMixLocalCache.writeLibrary(normalized);
      return normalized;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix library list',
      );
      return await AniMixLocalCache.readLibrary();
    }
  }

  Future<bool> saveLibraryEntry({
    required int animeId,
    required String status,
    required int score,
    required int episodesWatched,
  }) async {
    try {
      final response = await _authorizedRequest(
        (token) => _dio.put<dynamic>(
          '${Config.animixApiBaseUrl}/v1/library/$animeId',
          data: {
            'status': status,
            'score': score,
            'episodes_watched': episodesWatched,
          },
          options: _authorizedOptions(token),
        ),
      );
      if (response?.statusCode != 200) return false;
      final responseData = response?.data;
      final entry = responseData is Map ? responseData['entry'] : null;
      await AniMixLocalCache.upsertLibraryEntry(
        entry is Map
            ? Map<String, dynamic>.from(entry)
            : {
                'shikimori_id': animeId,
                'status': status,
                'score': score,
                'episodes_watched': episodesWatched,
                'updated_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
              },
      );
      if (responseData is Map && responseData['user'] is Map) {
        await AniMixLocalCache.writeProfile(
          Map<String, dynamic>.from(responseData['user'] as Map),
        );
      }
      return true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix library',
      );
      return false;
    }
  }

  Future<AniMixLibraryImportResult> importShikimoriLibrary({
    required int shikimoriUserId,
    required List<Map<String, dynamic>> entries,
  }) async {
    final response = await _authorizedRequest(
      (token) => _dio.post<dynamic>(
        '${Config.animixApiBaseUrl}/v1/library/import/shikimori',
        data: {'shikimori_user_id': shikimoriUserId, 'entries': entries},
        options: _authorizedOptions(token),
      ),
    );
    if (response == null) {
      throw const AniMixApiException(
        operation: 'Shikimori library import',
        errorCode: 'missing_animix_session',
      );
    }
    if (response.statusCode != 200 || response.data is! Map) {
      final payload = response.data is Map ? response.data as Map : null;
      throw AniMixApiException(
        operation: 'Shikimori library import',
        statusCode: response.statusCode,
        errorCode: payload?['error']?.toString() ?? 'invalid_response',
        details: payload?['reason']?.toString(),
      );
    }
    final payload = response.data as Map;
    final profile = payload['user'];
    if (profile is Map) {
      await AniMixLocalCache.writeProfile(Map<String, dynamic>.from(profile));
    }
    final imported = _intFromPayload(payload['imported']);
    if (imported == null) {
      throw const AniMixApiException(
        operation: 'Shikimori library import',
        errorCode: 'missing_import_count',
      );
    }
    final importedAt = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    await AniMixLocalCache.writeLibrary(
      entries
          .map(
            (entry) => <String, dynamic>{
              ...entry,
              'updated_at': entry['updated_at'] ?? importedAt,
            },
          )
          .toList(growable: false),
    );
    return AniMixLibraryImportResult(
      imported: imported,
      skipped: _intFromPayload(payload['skipped']) ?? 0,
    );
  }

  int? _intFromPayload(Object? value) =>
      value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');

  Future<Map<String, dynamic>?> _profileResponse({
    required String method,
    required String path,
    Object? data,
    String? contentType,
  }) async {
    final response = await _authorizedRequest(
      (token) => _dio.request<dynamic>(
        '${Config.animixApiBaseUrl}$path',
        data: data,
        options: _authorizedOptions(
          token,
          method: method,
          contentType: contentType,
        ),
      ),
      retryTransient: true,
    );
    if (response?.statusCode != 200 || response?.data is! Map) {
      final errorCode = response?.data is Map
          ? (response!.data as Map)['error']?.toString()
          : null;
      AppLogBuffer.instance.warning(
        'Profile mutation failed: $method $path, HTTP '
        '${response?.statusCode ?? 0}, code=${errorCode ?? 'invalid_response'}.',
        source: 'AniMix profile mutation',
      );
      return null;
    }
    final payload = Map<String, dynamic>.from(response!.data as Map);
    final profile = payload['user'] is Map
        ? Map<String, dynamic>.from(payload['user'] as Map)
        : null;
    if (profile != null) await AniMixLocalCache.writeProfile(profile);
    return profile;
  }

  Future<Response<dynamic>?> _authorizedRequest(
    Future<Response<dynamic>> Function(String token) request, {
    bool retryTransient = false,
  }) async {
    final token = await SecureStorage.getAniMixAccessToken();
    if (token == null || token.isEmpty) return null;
    var response = await _runAuthorizedRequest(
      () => request(token),
      retryTransient: retryTransient,
    );
    if (response.statusCode == 401 && await refreshSession()) {
      final refreshed = await SecureStorage.getAniMixAccessToken();
      if (refreshed != null && refreshed.isNotEmpty) {
        response = await _runAuthorizedRequest(
          () => request(refreshed),
          retryTransient: retryTransient,
        );
      }
    } else if (response.statusCode == 401) {
      await _invalidateLocalSession();
    }
    return response;
  }

  Future<Response<dynamic>> _runAuthorizedRequest(
    Future<Response<dynamic>> Function() request, {
    required bool retryTransient,
  }) async {
    for (var attempt = 0; ; attempt++) {
      try {
        return await request();
      } on DioException catch (error) {
        if (!retryTransient ||
            attempt >= 1 ||
            !_isTransientNetworkError(error)) {
          rethrow;
        }
        await Future<void>.delayed(const Duration(milliseconds: 350));
      }
    }
  }

  Options _authorizedOptions(
    String token, {
    String? method,
    String? contentType,
  }) => Options(
    method: method,
    contentType: contentType,
    headers: {'Authorization': 'Bearer $token'},
    validateStatus: (status) => status != null && status < 600,
  );

  Future<Response<dynamic>> _meResponse(String token) => _dio.get<dynamic>(
    '${Config.animixApiBaseUrl}/v1/me',
    options: Options(
      headers: {'Authorization': 'Bearer $token'},
      validateStatus: (status) => status != null && status < 600,
    ),
  );

  Future<Response<dynamic>> _meResponseWithRetry(String token) async {
    for (var attempt = 0; ; attempt++) {
      try {
        return await _meResponse(token);
      } on DioException catch (error) {
        if (attempt >= 1 || !_isTransientNetworkError(error)) rethrow;
        await Future<void>.delayed(const Duration(milliseconds: 350));
      }
    }
  }

  static bool _isTransientNetworkError(DioException error) =>
      switch (error.type) {
        DioExceptionType.connectionTimeout ||
        DioExceptionType.sendTimeout ||
        DioExceptionType.receiveTimeout ||
        DioExceptionType.connectionError => true,
        _ => false,
      };

  Future<bool> refreshSession() {
    final active = _refreshInFlight;
    if (active != null) return active;
    final request = _refreshSessionOnce();
    _refreshInFlight = request;
    return request.whenComplete(() {
      if (identical(_refreshInFlight, request)) _refreshInFlight = null;
    });
  }

  Future<bool> _refreshSessionOnce() async {
    final generation = _sessionGeneration;
    final refreshToken = await SecureStorage.getAniMixRefreshToken();
    if (refreshToken == null || refreshToken.isEmpty) {
      await _invalidateLocalSession();
      return false;
    }
    try {
      final response = await _dio.post<dynamic>(
        '${Config.animixApiBaseUrl}/v1/auth/refresh',
        data: {'refresh_token': refreshToken},
        options: Options(
          validateStatus: (status) => status != null && status < 600,
        ),
      );
      if (response.statusCode == 401) {
        await _invalidateLocalSession();
        AppLogBuffer.instance.warning(
          'AniMix refresh token was rejected; the local session was cleared.',
          source: 'AniMix auth refresh',
        );
        return false;
      }
      if (response.statusCode != 200 || response.data is! Map) {
        final errorCode = response.data is Map
            ? response.data['error']?.toString()
            : null;
        AppLogBuffer.instance.warning(
          'Session refresh failed: HTTP ${response.statusCode}, '
          'code=${errorCode ?? 'invalid_response'}.',
          source: 'AniMix auth refresh',
        );
        return false;
      }
      final data = Map<String, dynamic>.from(response.data as Map);
      final accessToken = data['access_token']?.toString().trim();
      final nextRefreshToken = data['refresh_token']?.toString().trim();
      if (accessToken == null ||
          accessToken.isEmpty ||
          nextRefreshToken == null ||
          nextRefreshToken.isEmpty) {
        return false;
      }
      if (generation != _sessionGeneration ||
          await SecureStorage.getAniMixRefreshToken() != refreshToken) {
        return false;
      }
      await SecureStorage.saveAniMixTokens(
        accessToken: accessToken,
        refreshToken: nextRefreshToken,
      );
      final profile = data['user'];
      if (profile is Map) {
        await AniMixLocalCache.writeProfile(Map<String, dynamic>.from(profile));
      }
      return true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix auth refresh',
      );
      return false;
    }
  }

  Future<void> _invalidateLocalSession() async {
    _advanceSessionGeneration();
    await SecureStorage.clearAniMix();
    await AniMixLocalCache.clear();
    final callback = _onSessionInvalidated;
    if (callback != null) await Future<void>.sync(callback);
  }

  Future<void> logout() async {
    final token = await SecureStorage.getAniMixAccessToken();
    // The device session is authoritative for the UI. Clear it before making
    // a best-effort network request so a blocked gateway can never trap the
    // user on the authenticated screen.
    await _invalidateLocalSession();
    if (token != null && token.isNotEmpty) {
      unawaited(_revokeRemoteSession(token));
    }
  }

  Future<void> _revokeRemoteSession(String token) async {
    try {
      await _dio.post<dynamic>(
        '${Config.animixApiBaseUrl}/v1/auth/logout',
        options: Options(
          headers: {'Authorization': 'Bearer $token'},
          sendTimeout: const Duration(seconds: 4),
          receiveTimeout: const Duration(seconds: 4),
        ),
      );
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix remote logout',
        context: 'The local session was cleared successfully.',
      );
    }
  }
}

enum AniMixProfileMediaKind { avatar, banner }

class AniMixAuthResult {
  const AniMixAuthResult._({required this.success, this.errorMessage});

  const AniMixAuthResult.success() : this._(success: true);

  const AniMixAuthResult.failure(String message)
    : this._(success: false, errorMessage: message);

  final bool success;
  final String? errorMessage;
}

class AniMixApiException implements Exception {
  const AniMixApiException({
    required this.operation,
    required this.errorCode,
    this.statusCode,
    this.details,
  });

  final String operation;
  final String errorCode;
  final int? statusCode;
  final String? details;

  @override
  String toString() =>
      '$operation failed'
      '${statusCode == null ? '' : ' (HTTP $statusCode)'}: $errorCode'
      '${details == null ? '' : ' ($details)'}';
}

class AniMixLibraryImportResult {
  const AniMixLibraryImportResult({
    required this.imported,
    required this.skipped,
  });

  final int imported;
  final int skipped;
}
