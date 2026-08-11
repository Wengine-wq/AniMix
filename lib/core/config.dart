import 'package:flutter_dotenv/flutter_dotenv.dart';

class Config {
  static String? _environment(String key) {
    try {
      return dotenv.env[key];
    } catch (_) {
      // Services and unit tests may be created before dotenv.load completes.
      return null;
    }
  }

  static String get shikimoriClientId =>
      _environment('SHIKIMORI_CLIENT_ID') ?? '';

  /// Optional HTTPS endpoint that exchanges the Shikimori authorization code
  /// on a trusted server. The server owns the OAuth client secret; the mobile
  /// and desktop binaries only receive the resulting tokens.
  static String get shikimoriOAuthProxyUrl =>
      _environment('SHIKIMORI_OAUTH_PROXY_URL') ?? '';

  /// Kept only as a backwards-compatible escape hatch for existing local
  /// installations. Never put this value in a distributed `.env` or CI build.
  static String get shikimoriClientSecret =>
      _environment('SHIKIMORI_CLIENT_SECRET') ?? '';
  static String get shikimoriRedirectUri =>
      _environment('SHIKIMORI_REDIRECT_URI') ?? '';
  static const String shikimoriBaseUrl = 'https://shikimori.io';

  static String get yummyApplicationToken =>
      _environment('YUMMY_APPLICATION_TOKEN') ?? 'ze645twqfeql6l1u';
  static String get yummyApiBase =>
      _environment('YUMMY_API_BASE') ?? 'https://api.yani.tv';
  static String get yummyWebOrigin =>
      _environment('YUMMY_WEB_ORIGIN') ?? 'https://yani.tv';
  static String get aniLibertyApiBase =>
      _environment('ANILIBERTY_API_BASE') ?? 'https://anilibria.top/api/v1';

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
