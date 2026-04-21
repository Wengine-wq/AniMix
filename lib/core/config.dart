import 'package:flutter_dotenv/flutter_dotenv.dart';

class Config {
  static String get shikimoriClientId => dotenv.env['SHIKIMORI_CLIENT_ID'] ?? '';
  static String get shikimoriClientSecret => dotenv.env['SHIKIMORI_CLIENT_SECRET'] ?? '';
  static String get shikimoriRedirectUri => dotenv.env['SHIKIMORI_REDIRECT_URI'] ?? '';
  static const String shikimoriBaseUrl = 'https://shikimori.io';

  // Добавляем проверку
  static bool get isInitialized => 
      dotenv.env['SHIKIMORI_CLIENT_ID'] != null && 
      dotenv.env['SHIKIMORI_CLIENT_ID']!.isNotEmpty;
}