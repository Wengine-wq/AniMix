import 'dart:convert';
import 'dart:async';
import 'dart:typed_data';

import 'package:animix/core/animix_auth_service.dart';
import 'package:animix/core/animix_local_cache.dart';
import 'package:animix/core/secure_storage.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
  });

  test('profile and library cache survive transient edge failures', () async {
    const profile = <String, dynamic>{
      'id': 'user-1',
      'display_name': 'AniMix user',
      'profile_version': 42,
    };
    const library = <Map<String, dynamic>>[
      {'shikimori_id': 1, 'status': 'watching'},
    ];

    expect(await AniMixLocalCache.writeProfile(profile), isTrue);
    expect(await AniMixLocalCache.writeProfile(profile), isFalse);
    await AniMixLocalCache.writeLibrary(library);

    expect(await AniMixLocalCache.readProfile(), profile);
    expect(await AniMixLocalCache.readLibrary(), library);
  });

  test('parallel refresh requests rotate the token only once', () async {
    FlutterSecureStorage.setMockInitialValues({
      'animix_access_token': 'expired-access',
      'animix_refresh_token': 'refresh-1',
    });
    final adapter = _AniMixAdapter();
    final service = AniMixAuthService(dio: Dio()..httpClientAdapter = adapter);

    final results = await Future.wait([
      service.refreshSession(),
      service.refreshSession(),
      service.refreshSession(),
    ]);

    expect(results, everyElement(isTrue));
    expect(adapter.refreshRequests, 1);
    expect(await SecureStorage.getAniMixAccessToken(), 'access-2');
    expect(await SecureStorage.getAniMixRefreshToken(), 'refresh-2');
  });

  test(
    'cached profile remains available when the API gateway is unreachable',
    () async {
      FlutterSecureStorage.setMockInitialValues({
        'animix_access_token': 'access-1',
        'animix_refresh_token': 'refresh-1',
      });
      const profile = <String, dynamic>{
        'id': 'user-1',
        'display_name': 'Offline user',
      };
      await AniMixLocalCache.writeProfile(profile);
      final service = AniMixAuthService(
        dio: Dio()..httpClientAdapter = _OfflineAdapter(),
      );

      expect(await service.getCurrentUser(), profile);
      expect(await SecureStorage.getAniMixAccessToken(), 'access-1');
    },
  );

  test('rejected refresh clears stale identity and signals sign out', () async {
    FlutterSecureStorage.setMockInitialValues({
      'animix_access_token': 'old-access',
      'animix_refresh_token': 'old-refresh',
    });
    await AniMixLocalCache.writeProfile({
      'id': 'old-user',
      'display_name': 'Stale profile',
    });
    var invalidated = false;
    final service = AniMixAuthService(
      dio: Dio()..httpClientAdapter = _RejectedRefreshAdapter(),
      onSessionInvalidated: () => invalidated = true,
    );

    expect(await service.refreshSession(), isFalse);
    expect(invalidated, isTrue);
    expect(await SecureStorage.getAniMixAccessToken(), isNull);
    expect(await SecureStorage.getAniMixRefreshToken(), isNull);
    expect(await AniMixLocalCache.readProfile(), isNull);
  });

  test('library import exposes a missing AniMix session', () async {
    final service = AniMixAuthService(dio: Dio());

    await expectLater(
      service.importShikimoriLibrary(
        shikimoriUserId: 42,
        entries: const [
          {
            'shikimori_id': 1,
            'status': 'planned',
            'score': 0,
            'episodes_watched': 0,
          },
        ],
      ),
      throwsA(
        isA<AniMixApiException>().having(
          (error) => error.errorCode,
          'errorCode',
          'missing_animix_session',
        ),
      ),
    );
  });

  test('library import reports imported and skipped entries', () async {
    FlutterSecureStorage.setMockInitialValues({
      'animix_access_token': 'access-1',
    });
    final service = AniMixAuthService(
      dio: Dio()
        ..httpClientAdapter = _ImportAdapter(
          statusCode: 200,
          payload: const {'imported': 3, 'skipped': 1},
        ),
    );

    final result = await service.importShikimoriLibrary(
      shikimoriUserId: 42,
      entries: const [
        {
          'shikimori_id': 1,
          'status': 'planned',
          'score': 0,
          'episodes_watched': 0,
        },
      ],
    );

    expect(result.imported, 3);
    expect(result.skipped, 1);
    final cached = await AniMixLocalCache.readLibrary();
    expect(cached, hasLength(1));
    expect(cached!.single['shikimori_id'], 1);
    expect(cached.single['updated_at'], isA<int>());
  });

  test('library import preserves the server rejection reason', () async {
    FlutterSecureStorage.setMockInitialValues({
      'animix_access_token': 'access-1',
    });
    final service = AniMixAuthService(
      dio: Dio()
        ..httpClientAdapter = _ImportAdapter(
          statusCode: 400,
          payload: const {
            'error': 'invalid_shikimori_import',
            'reason': 'no_valid_anime_entries',
          },
        ),
    );

    await expectLater(
      service.importShikimoriLibrary(
        shikimoriUserId: 42,
        entries: const [
          {'status': 'mystery'},
        ],
      ),
      throwsA(
        isA<AniMixApiException>()
            .having(
              (error) => error.errorCode,
              'errorCode',
              'invalid_shikimori_import',
            )
            .having(
              (error) => error.details,
              'details',
              'no_valid_anime_entries',
            ),
      ),
    );
  });

  test('a Shikimori token is not treated as an AniMix login', () async {
    FlutterSecureStorage.setMockInitialValues({
      'shikimori_access_token': 'integration-only-token',
    });
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(await container.read(isLoggedInProvider.future), isFalse);
  });

  test('AniMix logout is local-first even when the gateway hangs', () async {
    FlutterSecureStorage.setMockInitialValues({
      'animix_access_token': 'access-1',
      'animix_refresh_token': 'refresh-1',
    });
    var signedOut = false;
    final service = AniMixAuthService(
      dio: Dio()..httpClientAdapter = _HangingAdapter(),
      onSessionInvalidated: () => signedOut = true,
    );

    await service.logout().timeout(const Duration(milliseconds: 250));

    expect(await SecureStorage.getAniMixAccessToken(), isNull);
    expect(await SecureStorage.getAniMixRefreshToken(), isNull);
    expect(signedOut, isTrue);
  });

  test(
    'a profile response arriving after logout cannot restore stale cache',
    () async {
      FlutterSecureStorage.setMockInitialValues({
        'animix_access_token': 'access-1',
        'animix_refresh_token': 'refresh-1',
      });
      await AniMixLocalCache.writeProfile({
        'id': 'user-1',
        'display_name': 'Cached before logout',
      });
      final adapter = _DelayedSessionAdapter();
      final service = AniMixAuthService(
        dio: Dio()..httpClientAdapter = adapter,
      );

      final profileRequest = service.getCurrentUser(allowCachedFallback: false);
      await adapter.profileStarted.future;
      await service.logout();
      adapter.profileResponse.complete();

      expect(await profileRequest, isNull);
      expect(await AniMixLocalCache.readProfile(), isNull);
      expect(await SecureStorage.getAniMixAccessToken(), isNull);
    },
  );

  test(
    'a refresh response arriving after logout cannot resurrect session',
    () async {
      FlutterSecureStorage.setMockInitialValues({
        'animix_access_token': 'access-1',
        'animix_refresh_token': 'refresh-1',
      });
      final adapter = _DelayedSessionAdapter();
      final service = AniMixAuthService(
        dio: Dio()..httpClientAdapter = adapter,
      );

      final refresh = service.refreshSession();
      await adapter.refreshStarted.future;
      await service.logout();
      adapter.refreshResponse.complete();

      expect(await refresh, isFalse);
      expect(await SecureStorage.getAniMixAccessToken(), isNull);
      expect(await SecureStorage.getAniMixRefreshToken(), isNull);
    },
  );
}

class _AniMixAdapter implements HttpClientAdapter {
  int refreshRequests = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/v1/auth/refresh')) {
      refreshRequests++;
      await Future<void>.delayed(const Duration(milliseconds: 30));
      return ResponseBody.fromString(
        jsonEncode({
          'access_token': 'access-2',
          'refresh_token': 'refresh-2',
          'user': {'id': 'user-1', 'display_name': 'User'},
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    return ResponseBody.fromString('{}', 404);
  }

  @override
  void close({bool force = false}) {}
}

class _OfflineAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => throw DioException.connectionError(
    requestOptions: options,
    reason: 'offline',
  );

  @override
  void close({bool force = false}) {}
}

class _RejectedRefreshAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    jsonEncode({'error': 'invalid_refresh_token'}),
    401,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );

  @override
  void close({bool force = false}) {}
}

class _ImportAdapter implements HttpClientAdapter {
  const _ImportAdapter({required this.statusCode, required this.payload});

  final int statusCode;
  final Map<String, dynamic> payload;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    jsonEncode(payload),
    statusCode,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );

  @override
  void close({bool force = false}) {}
}

class _HangingAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => Completer<ResponseBody>().future;

  @override
  void close({bool force = false}) {}
}

class _DelayedSessionAdapter implements HttpClientAdapter {
  final profileStarted = Completer<void>();
  final profileResponse = Completer<void>();
  final refreshStarted = Completer<void>();
  final refreshResponse = Completer<void>();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.endsWith('/v1/me')) {
      if (!profileStarted.isCompleted) profileStarted.complete();
      await profileResponse.future;
      return _jsonResponse({
        'user': {'id': 'user-1', 'display_name': 'Late profile'},
      });
    }
    if (options.path.endsWith('/v1/auth/refresh')) {
      if (!refreshStarted.isCompleted) refreshStarted.complete();
      await refreshResponse.future;
      return _jsonResponse({
        'access_token': 'late-access',
        'refresh_token': 'late-refresh',
        'user': {'id': 'user-1', 'display_name': 'Late refresh'},
      });
    }
    if (options.path.endsWith('/v1/auth/logout')) {
      return _jsonResponse(const {'ok': true});
    }
    return ResponseBody.fromString('{}', 404);
  }

  static ResponseBody _jsonResponse(Map<String, dynamic> payload) =>
      ResponseBody.fromString(
        jsonEncode(payload),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );

  @override
  void close({bool force = false}) {}
}
