import 'package:hooks_riverpod/hooks_riverpod.dart';
import '../core/shikimori_api_client.dart';
import '../models/shikimori_user.dart';
import 'package:flutter/foundation.dart';

// 🔥 Передаем ref внутрь клиента, чтобы он мог управлять глобальным состоянием (например, при 401)
final apiClientProvider = Provider<ShikimoriApiClient>((ref) => ShikimoriApiClient(ref));

final currentUserProvider = FutureProvider.autoDispose<ShikimoriUser?>((ref) async {
  try {
    debugPrint('📡 ЗАПРОС К SHIKIMORI: /api/users/whoami'); 
    final api = ref.watch(apiClientProvider);
    final user = await api.getCurrentUser();
    debugPrint('✅ ПОЛУЧЕН ПОЛЬЗОВАТЕЛЬ: ${user.nickname} | watched: ${user.watched}');
    return user;
  } catch (e) {
    debugPrint('❌ ОШИБКА ЗАГРУЗКИ ПРОФИЛЯ: $e');
    return null;
  }
});