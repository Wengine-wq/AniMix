import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'app_logging.dart';
import 'config.dart';
import 'secure_storage.dart';

typedef RefreshTokenReader = Future<String?> Function();
typedef TokenWriter =
    Future<void> Function({
      required String accessToken,
      required String refreshToken,
    });

class ShikimoriAuthService {
  ShikimoriAuthService({
    Dio? dio,
    RefreshTokenReader? readRefreshToken,
    TokenWriter? saveTokens,
  }) : _dio = dio ?? Dio(),
       _readRefreshToken = readRefreshToken ?? SecureStorage.getRefreshToken,
       _saveTokens = saveTokens ?? SecureStorage.saveTokens;

  final Dio _dio;
  final RefreshTokenReader _readRefreshToken;
  final TokenWriter _saveTokens;

  Future<bool> exchangeCodeManually(String authCode) => login(authCode);

  Future<bool> login(String authCode) async {
    final code = authCode.trim();
    if (code.isEmpty) return false;
    return _exchange({
      'grant_type': 'authorization_code',
      'code': code,
      'redirect_uri': Config.shikimoriRedirectUri,
    });
  }

  /// Uses the refresh token without exposing the OAuth client secret to the
  /// application. The secret remains in the protected server environment.
  Future<bool> refreshSession() async {
    final refreshToken = (await _readRefreshToken())?.trim();
    if (refreshToken == null || refreshToken.isEmpty) return false;
    return _exchange({
      'grant_type': 'refresh_token',
      'refresh_token': refreshToken,
    }, previousRefreshToken: refreshToken);
  }

  Future<bool> _exchange(
    Map<String, String> grant, {
    String? previousRefreshToken,
  }) async {
    final clientId = Config.shikimoriClientId.trim();
    final proxyUrl = Config.shikimoriOAuthProxyUrl.trim();
    if (clientId.isEmpty || proxyUrl.isEmpty) {
      AppLogBuffer.instance.warning(
        'Public Shikimori OAuth configuration is incomplete.',
        source: 'Authentication',
      );
      return false;
    }

    try {
      final response = await _dio.post<dynamic>(
        proxyUrl,
        data: {...grant, 'client_id': clientId},
        options: Options(
          contentType: Headers.jsonContentType,
          responseType: ResponseType.json,
          validateStatus: (status) => status != null && status < 500,
        ),
      );
      if (response.statusCode != 200 || response.data is! Map) {
        final data = response.data;
        final upstreamError = data is Map ? data['error']?.toString() : null;
        AppLogBuffer.instance.warning(
          'OAuth proxy rejected ${grant['grant_type']} '
          '(HTTP ${response.statusCode}${upstreamError == null ? '' : ', $upstreamError'}).',
          source: 'Authentication',
        );
        return false;
      }

      final data = Map<String, dynamic>.from(response.data as Map);
      final accessToken = data['access_token']?.toString().trim();
      final refreshToken = data['refresh_token']?.toString().trim();
      if (accessToken == null || accessToken.isEmpty) return false;
      final tokenToStore = refreshToken?.isNotEmpty == true
          ? refreshToken!
          : previousRefreshToken;
      if (tokenToStore == null || tokenToStore.isEmpty) return false;
      await _saveTokens(accessToken: accessToken, refreshToken: tokenToStore);
      return true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Shikimori OAuth',
        context: 'Grant: ${grant['grant_type']}',
      );
      debugPrint('Ошибка авторизации Shikimori: $error');
      return false;
    }
  }

  /// Disconnecting the optional Shikimori integration must not destroy the
  /// primary AniMix/Google session.
  Future<void> logout() => SecureStorage.clearShikimori();
}
