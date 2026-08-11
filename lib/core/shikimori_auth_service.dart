import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'config.dart';
import 'secure_storage.dart';

class ShikimoriAuthService {
  final Dio _dio = Dio(
    BaseOptions(
      baseUrl: 'https://shikimori.io',
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 AniMix/1.0',
        'Accept': 'application/json',
      },
    ),
  );

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
      final clientSecret = Config.shikimoriClientSecret;

      if (clientId.isEmpty) {
        debugPrint('Ошибка авторизации: SHIKIMORI_CLIENT_ID не найден.');
        return false;
      }

      // Выбираем правильный редирект в зависимости от того, откуда пришел запрос
      final actualRedirectUri = redirectUri?.trim().isNotEmpty == true
          ? redirectUri!.trim()
          : (Config.shikimoriRedirectUri.isNotEmpty
                ? Config.shikimoriRedirectUri
                : 'https://animix.app/callback');

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
      } else if (clientSecret.isNotEmpty) {
        // Compatibility path for existing local development `.env` files.
        // It is intentionally not used by the checked-in release config.
        debugPrint(
          'Предупреждение: используется legacy OAuth с client_secret. '
          'Настройте SHIKIMORI_OAUTH_PROXY_URL для безопасной сборки.',
        );
        response = await _dio.post(
          '/oauth/token',
          data: {
            'grant_type': 'authorization_code',
            'client_id': clientId,
            'client_secret': clientSecret,
            'code': authCode,
            'redirect_uri': actualRedirectUri,
          },
        );
      } else {
        debugPrint(
          'Ошибка авторизации: настройте SHIKIMORI_OAUTH_PROXY_URL. '
          'Прямой client_secret в приложении небезопасен.',
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
    } catch (e) {
      debugPrint('Ошибка авторизации Shikimori: $e');
      return false;
    }
  }

  Future<void> logout() async {
    await SecureStorage.clear();
  }
}
