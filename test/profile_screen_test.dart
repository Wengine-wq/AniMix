import 'dart:async';

import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/profile/profile_screen.dart';
import 'package:animix/models/shikimori_history.dart';
import 'package:animix/models/shikimori_user.dart';
import 'package:animix/providers/user_provider.dart';
import 'package:animix/widgets/animix_skeletons.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

void main() {
  testWidgets('loaded profile can show activity skeleton independently', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1280, 720);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final user = ShikimoriUser.fromJson({
      'id': '42',
      'nickname': 'Tester',
      'image': '/system/users/x160/42.png',
      'stats': {
        'activity': [
          {'value': '12'},
        ],
        'statuses': {
          'anime': [
            {'grouped_id': 'completed', 'size': '87'},
          ],
        },
      },
    });
    final history = Completer<List<ShikimoriHistory>>();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          currentUserProvider.overrideWith((ref) async => user),
          userHistoryProvider(user.id).overrideWith((ref) => history.future),
        ],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const ProfileScreen(),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Tester'), findsOneWidget);
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -900));
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.byType(AniMixProfileActivitySkeleton), findsOneWidget);
    expect(tester.takeException(), isNull);

    history.complete(const []);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('profile dashboard stays readable on a narrow phone', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final user = ShikimoriUser.fromJson({
      'id': 9,
      'nickname': 'RhythmTester',
      'stats': {
        'scores': {
          'anime': [
            {'value': 14},
          ],
        },
        'statuses': {
          'anime': [
            {'grouped_id': 'watching', 'size': 4},
            {'grouped_id': 'planned', 'size': 22},
            {'grouped_id': 'completed', 'size': 87},
            {'grouped_id': 'on_hold', 'size': 3},
          ],
        },
      },
    });
    final history = [
      ShikimoriHistory.fromJson({
        'id': 1,
        'created_at': DateTime.now().toIso8601String(),
        'description': 'просмотрен 1 эпизод',
        'target': {
          'id': 1,
          'name': 'Example',
          'russian': 'Пример',
          'image': {'original': ''},
        },
      }),
    ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          currentUserProvider.overrideWith((ref) async => user),
          userHistoryProvider(user.id).overrideWith((ref) async => history),
        ],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const ProfileScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Моя медиатека'), findsOneWidget);
    expect(find.text('Ритм просмотра'), findsOneWidget);
    expect(find.text('Последние штрихи'), findsOneWidget);
    expect(find.text('Выйти из аккаунта'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
