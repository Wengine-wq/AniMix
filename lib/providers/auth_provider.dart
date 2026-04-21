import 'package:hooks_riverpod/hooks_riverpod.dart';
import '../core/secure_storage.dart';           // ← Добавили этот импорт!
import '../core/shikimori_auth_service.dart';

final authServiceProvider = Provider<ShikimoriAuthService>(
  (ref) => ShikimoriAuthService(),
);

final isLoggedInProvider = FutureProvider<bool>((ref) async {
  final token = await SecureStorage.getAccessToken();   // теперь будет видно
  return token != null && token.isNotEmpty;
});