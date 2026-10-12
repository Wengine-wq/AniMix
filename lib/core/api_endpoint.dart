import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Lets the AniMix server move (for example off Yandex Cloud to a VM)
/// without shipping a new app build.
///
/// `endpoints.json` in the GitHub repository names the current server. The
/// app switches only after that server answers `/health` itself, so a typo
/// or a dead address never breaks sign-in; the last good address is kept on
/// the device and used from the first request of the next launch.
class ApiEndpoint {
  ApiEndpoint._();

  static const _prefsKey = 'api_endpoint_override_v1';
  static const _manifestUrl =
      'https://raw.githubusercontent.com/Wengine-wq/AniMix/main/endpoints.json';

  /// Base URL to use instead of the built-in one, if the server has moved.
  static String? override;

  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final saved = prefs.getString(_prefsKey);
      override = saved != null && _isValid(saved) ? saved : null;
    } catch (_) {
      override = null;
    }
  }

  static bool _isValid(String url) {
    final uri = Uri.tryParse(url);
    return uri != null && uri.scheme == 'https' && uri.host.isNotEmpty;
  }

  static String _clean(String url) => url.trim().replaceAll(RegExp(r'/+$'), '');

  /// Checks the manifest and adopts a new address once it proves healthy.
  /// [builtIn] is the address compiled into the app.
  static Future<void> refresh({required String builtIn}) async {
    if (Platform.environment.containsKey('FLUTTER_TEST')) return;
    final dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 8),
        receiveTimeout: const Duration(seconds: 8),
        responseType: ResponseType.plain,
      ),
    );
    try {
      final response = await dio.get<String>(_manifestUrl);
      final data = jsonDecode(response.data ?? '');
      final api = data is Map ? _clean('${data['api'] ?? ''}') : '';
      if (!_isValid(api)) return;
      final current = override ?? _clean(builtIn);
      if (api == current) return;
      final health = await dio.get<String>(
        '$api/health',
        options: Options(validateStatus: (status) => status != null),
      );
      if (health.statusCode != 200) return;
      final prefs = await SharedPreferences.getInstance();
      if (api == _clean(builtIn)) {
        override = null;
        await prefs.remove(_prefsKey);
      } else {
        override = api;
        await prefs.setString(_prefsKey, api);
      }
    } catch (_) {
      // Keep the current address; the next launch tries again.
    }
  }
}
