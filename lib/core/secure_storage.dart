import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureStorage {
  // Инициализируем хранилище с правильными настройками для всех платформ
  static const _storage = FlutterSecureStorage(
    // 🔥 ФИКС: Убрали aOptions с encryptedSharedPreferences, 
    // так как пакет теперь использует новые кастомные алгоритмы шифрования 
    // и автоматически мигрирует старые данные.
    
    // ФИКС ДЛЯ SIDELOADLY / ALTSTORE НА iOS
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.first_unlock,
    ),
  );

  static const _tokenKey = 'shikimori_access_token';
  static const _refreshKey = 'shikimori_refresh_token';

  static Future<void> saveTokens({required String accessToken, required String refreshToken}) async {
    await _storage.write(key: _tokenKey, value: accessToken);
    await _storage.write(key: _refreshKey, value: refreshToken);
  }

  static Future<String?> getAccessToken() async {
    return await _storage.read(key: _tokenKey);
  }

  static Future<String?> getRefreshToken() async {
    return await _storage.read(key: _refreshKey);
  }

  static Future<void> clear() async {
    await _storage.delete(key: _tokenKey);
    await _storage.delete(key: _refreshKey);
  }
}