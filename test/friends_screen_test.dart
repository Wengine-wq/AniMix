import 'dart:async';
import 'package:animix/core/animix_auth_service.dart';
import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/profile/friends_screen.dart';
import 'package:animix/features/profile/profile_components.dart';
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

class _PublicService extends _FriendService {
  final library = Completer<List<Map<String, dynamic>>>();

  @override
  Future<Map<String, dynamic>> getPublicProfile(String userId) async => {
    'display_name': 'Друг AniMix',
    'created_at': '2026-08-24T10:00:00Z',
    'stats': {'completed': 12, 'planned': 3, 'episodes_watched': 144},
  };

  @override
  Future<String> getFriendStatus(String userId) async => 'incoming';

  @override
  Future<List<Map<String, dynamic>>> getPublicLibrary(String userId) =>
      library.future;
}

void main() {
  for (final brightness in Brightness.values) {
    testWidgets(
      'public profile remains usable while library loads: $brightness',
      (tester) async {
        tester.view.physicalSize = const Size(390, 844);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final service = _PublicService();
        await tester.pumpWidget(
          ProviderScope(
            overrides: [animixAuthServiceProvider.overrideWithValue(service)],
            child: MaterialApp(
              theme: AniMixTheme.material(
                const Color(0xFF8B5CF6),
                AniMixThemeStyle.graphite,
                brightness: brightness,
              ),
              home: const PublicProfileScreen(userId: 'friend'),
            ),
          ),
        );
        await tester.pump();
        expect(find.byType(ProfileHeader), findsOneWidget);
        expect(find.byType(ProfileLibraryOverview), findsOneWidget);
        await tester.tap(find.text('Принять заявку'));
        await tester.pump();
        expect(service.acceptCalls, 1);
        expect(find.text('Вы друзья в AniMix'), findsOneWidget);
        service.library.complete([]);
        await tester.pumpAndSettle();
        expect(find.text('Вы друзья в AniMix'), findsOneWidget);
        await tester.drag(find.byType(ListView), const Offset(0, -700));
        await tester.pumpAndSettle();
        expect(find.byType(ProfileInfoCard), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
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
