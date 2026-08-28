import 'dart:convert';
import 'dart:math';

import 'config.dart';

class ShikimoriOAuthCallback {
  const ShikimoriOAuthCallback({this.code, this.error});

  final String? code;
  final String? error;

  bool get isSuccess => code?.isNotEmpty == true && error == null;
}

abstract final class ShikimoriOAuthFlow {
  static String createState() {
    final random = Random.secure();
    final bytes = List<int>.generate(32, (_) => random.nextInt(256));
    return base64UrlEncode(bytes).replaceAll('=', '');
  }

  static Uri authorizationUri({
    required String clientId,
    required String state,
  }) => Uri.https('shikimori.io', '/oauth/authorize', {
    'client_id': clientId,
    'redirect_uri': Config.shikimoriRedirectUri,
    'response_type': 'code',
    'scope': 'user_rates comments topics',
    'state': state,
  });

  /// Returns null for ordinary navigation and a result once the configured
  /// callback is reached. Scheme, host, port and path must match exactly.
  static ShikimoriOAuthCallback? parseCallback(
    String rawUrl, {
    required String expectedState,
  }) {
    final candidate = Uri.tryParse(rawUrl);
    final expected = Uri.tryParse(Config.shikimoriRedirectUri);
    if (candidate == null || expected == null) return null;
    if (!_sameEndpoint(candidate, expected)) return null;

    final returnedState = candidate.queryParameters['state'];
    if (returnedState == null || returnedState != expectedState) {
      return const ShikimoriOAuthCallback(
        error: 'Shikimori вернул callback с неверным state.',
      );
    }
    final upstreamError = candidate.queryParameters['error'];
    if (upstreamError?.isNotEmpty == true) {
      return ShikimoriOAuthCallback(
        error: candidate.queryParameters['error_description'] ?? upstreamError,
      );
    }
    final code = candidate.queryParameters['code']?.trim();
    if (code == null || code.isEmpty) {
      return const ShikimoriOAuthCallback(
        error: 'Shikimori не вернул код авторизации.',
      );
    }
    return ShikimoriOAuthCallback(code: code);
  }

  static bool _sameEndpoint(Uri left, Uri right) =>
      left.scheme.toLowerCase() == right.scheme.toLowerCase() &&
      left.host.toLowerCase() == right.host.toLowerCase() &&
      left.port == right.port &&
      _normalizedPath(left.path) == _normalizedPath(right.path);

  static String _normalizedPath(String value) {
    if (value.isEmpty) return '/';
    return value.length > 1 && value.endsWith('/')
        ? value.substring(0, value.length - 1)
        : value;
  }
}
