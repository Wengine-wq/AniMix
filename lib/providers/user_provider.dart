import 'dart:async';
import 'dart:convert';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import '../core/shikimori_api_client.dart';
import '../models/shikimori_user.dart';
import 'package:flutter/foundation.dart';

import '../core/app_logging.dart';
import '../core/secure_storage.dart';
import 'auth_provider.dart';

// 🔥 Передаем ref внутрь клиента, чтобы он мог управлять глобальным состоянием (например, при 401)
final apiClientProvider = Provider<ShikimoriApiClient>(
  (ref) => ShikimoriApiClient(ref),
);

Future<void>? _profileRefreshInFlight;
DateTime? _lastProfileRefresh;

void _refreshAniMixProfileInBackground(Ref ref, Map<String, dynamic> cached) {
  final lastRefresh = _lastProfileRefresh;
  if (_profileRefreshInFlight != null ||
      (lastRefresh != null &&
          DateTime.now().difference(lastRefresh) <
              const Duration(seconds: 20))) {
    return;
  }
  _lastProfileRefresh = DateTime.now();
  final service = ref.read(animixAuthServiceProvider);
  final request = () async {
    final fresh = await service.getCurrentUser(allowCachedFallback: false);
    if (!ref.mounted) return;
    if (fresh == null) {
      final token = await SecureStorage.getAniMixAccessToken();
      if (!ref.mounted) return;
      if (token == null || token.isEmpty) {
        ref.read(authSessionSignalProvider.notifier).signedOut();
      }
      return;
    }
    if (ref.mounted && jsonEncode(fresh) != jsonEncode(cached)) {
      ref.read(userDataRevisionProvider.notifier).bump();
    }
  }();
  _profileRefreshInFlight = request;
  unawaited(
    request.whenComplete(() {
      if (identical(_profileRefreshInFlight, request)) {
        _profileRefreshInFlight = null;
      }
    }),
  );
}

final currentUserProvider = FutureProvider<ShikimoriUser?>((ref) async {
  try {
    ref.watch(userDataRevisionProvider);
    final localToken = await SecureStorage.getAniMixAccessToken();
    if (localToken == null || localToken.isEmpty) return null;
    final service = ref.read(animixAuthServiceProvider);
    final cached = await service.getCachedCurrentUser();
    if (cached != null) {
      _refreshAniMixProfileInBackground(ref, cached);
      return ShikimoriUser.localFromAniMixJson(cached);
    }
    final localJson = await service.getCurrentUser();
    if (localJson == null) {
      final remainingToken = await SecureStorage.getAniMixAccessToken();
      if (remainingToken == null || remainingToken.isEmpty) {
        ref.read(authSessionSignalProvider.notifier).signedOut();
        return null;
      }
      throw const FormatException('AniMix profile is unavailable.');
    }
    return ShikimoriUser.localFromAniMixJson(localJson);
  } catch (e) {
    AppLogBuffer.instance.recordError(e, StackTrace.current, source: 'Profile');
    debugPrint('❌ ОШИБКА ЗАГРУЗКИ ПРОФИЛЯ: $e');
    rethrow;
  }
});
