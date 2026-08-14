import 'package:flutter_dotenv/flutter_dotenv.dart';

class Config {
  static String? _environment(String key) {
    try {
      final value = dotenv.env[key]?.trim();
      return value == null || value.isEmpty ? null : value;
    } catch (_) {
      // Services and unit tests may be created before dotenv.load completes.
      return _compileTimeValue(key);
    }
  }

  static String? _compileTimeValue(String key) {
    final value = switch (key) {
      'SHIKIMORI_CLIENT_ID' => const String.fromEnvironment(
        'SHIKIMORI_CLIENT_ID',
      ),
      'SHIKIMORI_REDIRECT_URI' => const String.fromEnvironment(
        'SHIKIMORI_REDIRECT_URI',
      ),
      'SHIKIMORI_OAUTH_PROXY_URL' => const String.fromEnvironment(
        'SHIKIMORI_OAUTH_PROXY_URL',
      ),
      'YUMMY_APPLICATION_TOKEN' => const String.fromEnvironment(
        'YUMMY_APPLICATION_TOKEN',
      ),
      'YUMMY_API_BASE' => const String.fromEnvironment('YUMMY_API_BASE'),
      'YUMMY_WEB_ORIGIN' => const String.fromEnvironment('YUMMY_WEB_ORIGIN'),
      'ANILIBERTY_API_BASE' => const String.fromEnvironment(
        'ANILIBERTY_API_BASE',
      ),
      _ => '',
    };
    return value.trim().isEmpty ? null : value.trim();
  }

  static String _value(String key, {String fallback = ''}) =>
      _environment(key) ?? _compileTimeValue(key) ?? fallback;

  static String get shikimoriClientId => _value('SHIKIMORI_CLIENT_ID');

  /// Optional HTTPS endpoint that exchanges the Shikimori authorization code
  /// on a trusted server. The server owns the OAuth client secret; the mobile
  /// and desktop binaries only receive the resulting tokens.
  static String get shikimoriOAuthProxyUrl =>
      _value('SHIKIMORI_OAUTH_PROXY_URL');
  static String get shikimoriRedirectUri => _value('SHIKIMORI_REDIRECT_URI');
  static const String shikimoriBaseUrl = 'https://shikimori.io';

  static String get yummyApplicationToken =>
      _value('YUMMY_APPLICATION_TOKEN', fallback: 'ze645twqfeql6l1u');
  static String get yummyApiBase =>
      _value('YUMMY_API_BASE', fallback: 'https://api.yani.tv');
  static String get yummyWebOrigin =>
      _value('YUMMY_WEB_ORIGIN', fallback: 'https://yani.tv');
  static String get aniLibertyApiBase =>
      _value('ANILIBERTY_API_BASE', fallback: 'https://anilibria.top/api/v1');

  static const browserUserAgent =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36';

  // WKWebView behaves more consistently when its declared engine matches the
  // actual WebKit stack. This is also the user agent used by the Swift resolver.
  static const appleBrowserUserAgent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
      'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Safari/605.1.15';

  static Map<String, String> get providerMediaHeaders => {
    'User-Agent': browserUserAgent,
    'Referer': '$yummyWebOrigin/',
    'Origin': yummyWebOrigin,
  };

  static Map<String, String> get yummyApiHeaders => {
    'User-Agent': browserUserAgent,
    'Accept': 'application/json',
    if (yummyApplicationToken.isNotEmpty)
      'X-Application': yummyApplicationToken,
  };

  // Добавляем проверку
  static bool get isInitialized => shikimoriClientId.isNotEmpty;
}
