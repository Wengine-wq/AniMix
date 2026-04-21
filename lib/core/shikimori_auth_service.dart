import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

import 'secure_storage.dart';

class ShikimoriAuthService {
  final Dio _dio = Dio(
    BaseOptions(
      baseUrl: 'https://shikimori.io',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 AniMix/1.0',
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

      final clientId = dotenv.env['SHIKIMORI_CLIENT_ID'];
      final clientSecret = dotenv.env['SHIKIMORI_CLIENT_SECRET'];

      if (clientId == null || clientSecret == null) {
        debugPrint('ОШИБКА: Ключи не найдены в .env');
        return false;
      }

      // Выбираем правильный редирект в зависимости от того, откуда пришел запрос
      final actualRedirectUri = redirectUri ?? 'https://animix.app/callback';

      final response = await _dio.post(
        '/oauth/token',
        data: {
          'grant_type': 'authorization_code',
          'client_id': clientId,
          'client_secret': clientSecret,
          'code': authCode,
          'redirect_uri': actualRedirectUri, // 🔥 Отправляем нужный URI
        },
      );

      if (response.statusCode == 200) {
        final data = response.data;
        await SecureStorage.saveTokens(
          accessToken: data['access_token'],
          refreshToken: data['refresh_token'],
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