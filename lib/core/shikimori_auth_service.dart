import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'config.dart';
import 'app_logging.dart';
import 'secure_storage.dart';

class ShikimoriAuthService {
  Future<bool> exchangeCodeManually(String authCode) async {
    return login(authCode);
  }

  // 🔥 ДОБАВЛЕН ПАРАМЕТР redirectUri
  Future<bool> login([String? authCode, String? redirectUri]) async {
    try {
      if (authCode == null || authCode.isEmpty) {
        return false;
      }

      final clientId = Config.shikimoriClientId;

      if (clientId.isEmpty) {
        AppLogBuffer.instance.warning(
          'SHIKIMORI_CLIENT_ID is missing.',
          source: 'Authentication',
        );
        debugPrint('Ошибка авторизации: SHIKIMORI_CLIENT_ID не найден.');
        return false;
      }

      // Выбираем правильный редирект в зависимости от того, откуда пришел запрос
      final actualRedirectUri = redirectUri?.trim().isNotEmpty == true
          ? redirectUri!.trim()
          : Config.shikimoriRedirectUri;

      final Response<dynamic> response;
      final proxyUrl = Config.shikimoriOAuthProxyUrl.trim();
      if (proxyUrl.isNotEmpty) {
        // Preferred path: the OAuth secret never enters the application.
        response = await Dio().post(
          proxyUrl,
          data: {
            'code': authCode,
            'redirect_uri': actualRedirectUri,
            'client_id': clientId,
          },
          options: Options(
            contentType: Headers.jsonContentType,
            responseType: ResponseType.json,
          ),
        );
      } else {
        AppLogBuffer.instance.warning(
          'SHIKIMORI_OAUTH_PROXY_URL is missing.',
          source: 'Authentication',
        );
        debugPrint(
          'Ошибка авторизации: настройте SHIKIMORI_OAUTH_PROXY_URL. '
          'Обмен кода выполняется только через защищённый прокси.',
        );
        return false;
      }

      if (response.statusCode == 200) {
        final data = response.data is Map
            ? Map<String, dynamic>.from(response.data as Map)
            : const <String, dynamic>{};
        final accessToken = data['access_token']?.toString();
        final refreshToken = data['refresh_token']?.toString() ?? '';
        if (accessToken == null || accessToken.isEmpty) return false;
        await SecureStorage.saveTokens(
          accessToken: accessToken,
          refreshToken: refreshToken,
        );
        return true;
      }
      return false;
    } catch (e, stackTrace) {
      AppLogBuffer.instance.recordError(
        e,
        stackTrace,
        source: 'Shikimori OAuth',
      );
      debugPrint('Ошибка авторизации Shikimori: $e');
      return false;
    }
  }

  Future<void> logout() async {
    await SecureStorage.clear();
  }
}
