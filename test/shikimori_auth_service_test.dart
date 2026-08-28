import 'dart:convert';
import 'dart:typed_data';

import 'package:animix/core/config.dart';
import 'package:animix/core/secure_storage.dart';
import 'package:animix/core/shikimori_auth_service.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  test('authorization code exchange uses the canonical redirect', () async {
    final adapter = _OAuthAdapter({
      'access_token': 'access-1',
      'refresh_token': 'refresh-1',
    });
    final stored = <String, String>{};
    final service = ShikimoriAuthService(
      dio: Dio()..httpClientAdapter = adapter,
      saveTokens: ({required accessToken, required refreshToken}) async {
        stored['access'] = accessToken;
        stored['refresh'] = refreshToken;
      },
    );

    expect(await service.login(' code-1 '), isTrue);
    expect(adapter.payload['grant_type'], 'authorization_code');
    expect(adapter.payload['redirect_uri'], Config.shikimoriRedirectUri);
    expect(adapter.payload['code'], 'code-1');
    expect(stored, {'access': 'access-1', 'refresh': 'refresh-1'});
  });

  test(
    'refresh grant keeps rotated credentials out of the client binary',
    () async {
      final adapter = _OAuthAdapter({
        'access_token': 'access-2',
        'refresh_token': 'refresh-2',
      });
      final stored = <String, String>{};
      final service = ShikimoriAuthService(
        dio: Dio()..httpClientAdapter = adapter,
        readRefreshToken: () async => 'refresh-1',
        saveTokens: ({required accessToken, required refreshToken}) async {
          stored['access'] = accessToken;
          stored['refresh'] = refreshToken;
        },
      );

      expect(await service.refreshSession(), isTrue);
      expect(adapter.payload['grant_type'], 'refresh_token');
      expect(adapter.payload['refresh_token'], 'refresh-1');
      expect(adapter.payload, isNot(contains('redirect_uri')));
      expect(stored, {'access': 'access-2', 'refresh': 'refresh-2'});
    },
  );

  test('disconnecting Shikimori preserves the AniMix session', () async {
    FlutterSecureStorage.setMockInitialValues({
      'shikimori_access_token': 'shiki-access',
      'shikimori_refresh_token': 'shiki-refresh',
      'animix_access_token': 'animix-access',
      'animix_refresh_token': 'animix-refresh',
    });

    await ShikimoriAuthService().logout();

    expect(await SecureStorage.getAccessToken(), isNull);
    expect(await SecureStorage.getRefreshToken(), isNull);
    expect(await SecureStorage.getAniMixAccessToken(), 'animix-access');
    expect(await SecureStorage.getAniMixRefreshToken(), 'animix-refresh');
  });
}

class _OAuthAdapter implements HttpClientAdapter {
  _OAuthAdapter(this.response);

  final Map<String, dynamic> response;
  Map<String, dynamic> payload = {};

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    payload = Map<String, dynamic>.from(options.data as Map);
    return ResponseBody.fromString(
      jsonEncode(response),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
