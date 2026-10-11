import 'package:shared_preferences/shared_preferences.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import '../core/secure_storage.dart';
import '../core/shikimori_auth_service.dart';
import '../core/animix_auth_service.dart';

final authServiceProvider = Provider<ShikimoriAuthService>(
  (ref) => ShikimoriAuthService(),
);

final animixAuthServiceProvider = Provider<AniMixAuthService>(
  (ref) => AniMixAuthService(
    onSessionInvalidated: () =>
        ref.read(authSessionSignalProvider.notifier).signedOut(),
  ),
);

class AuthSessionSignalNotifier extends Notifier<bool?> {
  @override
  bool? build() => null;

  /// OAuth already persisted the tokens. Do not start a second asynchronous
  /// storage read while the login screen is still mounted: Windows could
  /// rebuild the root route with the old `false` value until the next launch.
  void signedIn() => state = true;

  void signedOut() => state = false;
}

final authSessionSignalProvider =
    NotifierProvider<AuthSessionSignalNotifier, bool?>(
      AuthSessionSignalNotifier.new,
    );

final isLoggedInProvider = FutureProvider<bool>((ref) async {
  final signal = ref.watch(authSessionSignalProvider);
  if (signal != null) return signal;
  final animixToken = await SecureStorage.getAniMixAccessToken();
  return animixToken != null && animixToken.isNotEmpty;
});

class SessionNoticeNotifier extends Notifier<String?> {
  @override
  String? build() => null;

  void sessionExpired() {
    state = 'Сессия Shikimori истекла. Войдите снова.';
  }

  void clear() => state = null;
}

final sessionNoticeProvider = NotifierProvider<SessionNoticeNotifier, String?>(
  SessionNoticeNotifier.new,
);

class UserDataRevisionNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

final userDataRevisionProvider =
    NotifierProvider<UserDataRevisionNotifier, int>(
      UserDataRevisionNotifier.new,
    );

/// «Продолжить без входа»: the app works without an AniMix account (catalog
/// from Shikimori, watching, local progress and downloads). Loaded before the
/// first frame so the start screen does not flicker.
class GuestMode {
  GuestMode._();

  static const _key = 'guest_mode_v1';
  static bool enabled = false;

  static Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    enabled = prefs.getBool(_key) ?? false;
  }

  static Future<void> save(bool value) async {
    enabled = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_key, value);
  }
}

class GuestModeNotifier extends Notifier<bool> {
  @override
  bool build() => GuestMode.enabled;

  Future<void> set(bool value) async {
    state = value;
    await GuestMode.save(value);
  }
}

final guestModeProvider = NotifierProvider<GuestModeNotifier, bool>(
  GuestModeNotifier.new,
);
