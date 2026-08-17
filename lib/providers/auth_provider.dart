import 'package:hooks_riverpod/hooks_riverpod.dart';
import '../core/secure_storage.dart';
import '../core/shikimori_auth_service.dart';

final authServiceProvider = Provider<ShikimoriAuthService>(
  (ref) => ShikimoriAuthService(),
);

final isLoggedInProvider = FutureProvider<bool>((ref) async {
  final token = await SecureStorage.getAccessToken();
  return token != null && token.isNotEmpty;
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
