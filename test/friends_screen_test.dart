import 'package:animix/core/animix_auth_service.dart';
import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/profile/friends_screen.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class _FriendService extends AniMixAuthService {
  int acceptCalls = 0;

  @override
  Future<List<Map<String, dynamic>>> getFriends() async => [
    {
      'id': '12345678-1234-1234-1234-123456789abc',
      'display_name': 'Сосед по тайтлам',
      'status': 'incoming',
    },
  ];

  @override
  Future<String> addFriend(String userId) async {
    acceptCalls++;
    return 'friends';
  }
}

void main() {
  testWidgets('incoming request can be accepted from the friends list', (
    tester,
  ) async {
    final service = _FriendService();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [animixAuthServiceProvider.overrideWithValue(service)],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const FriendsScreen(),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('Заявки вам'), findsOneWidget);

    await tester.tap(find.text('Принять'));
    await tester.pump();

    expect(service.acceptCalls, 1);
    expect(find.text('Заявки вам'), findsNothing);
    expect(find.text('Сосед по тайтлам'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
