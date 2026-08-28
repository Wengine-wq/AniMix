import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/catalog/catalog_screen.dart';
import 'package:animix/features/profile/profile_screen.dart';
import 'package:animix/features/profile/settings_screen.dart';
import 'package:animix/features/home/home_screen.dart';
import 'package:animix/main.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:animix/providers/user_provider.dart';
import 'package:animix/widgets/animix_surface.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  Future<void> pumpAt(WidgetTester tester, Widget root, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(root);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  }

  testWidgets('settings stays finite on a narrow iPhone viewport', (
    tester,
  ) async {
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [isLoggedInProvider.overrideWith((ref) async => false)],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const SettingsScreen(),
        ),
      ),
      const Size(390, 844),
    );
    expect(find.text('Настройки'), findsOneWidget);
    expect(find.text('Умное соединение'), findsOneWidget);
    await tester.tap(find.text('Оформление'));
    await tester.pumpAndSettle();
    expect(find.text('Предпросмотр'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('custom_accent_button')));
    await tester.pumpAndSettle();
    expect(find.text('Свой акцентный цвет'), findsOneWidget);
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -500));
    await tester.pumpAndSettle();
    expect(find.text('ТЕМА ИНТЕРФЕЙСА'), findsOneWidget);
    expect(find.text('Воздух'), findsOneWidget);
  });

  testWidgets('translucent theme blurs only explicitly floating surfaces', (
    tester,
  ) async {
    final theme = AniMixTheme.material(
      const Color(0xFF64D2FF),
      AniMixThemeStyle.translucent,
    );
    await pumpAt(
      tester,
      MaterialApp(
        theme: theme,
        home: const AniMixPage(
          title: 'Воздух',
          child: Center(
            child: AniMixSurface(
              elevated: true,
              blurred: true,
              child: Text('Карточка'),
            ),
          ),
        ),
      ),
      const Size(390, 844),
    );

    expect(theme.extension<AniMixVisualStyle>()?.translucent, isTrue);
    expect(find.byType(BackdropFilter), findsWidgets);
    expect(find.text('Карточка'), findsOneWidget);
  });

  testWidgets('bookmarks auth state does not overflow on mobile', (
    tester,
  ) async {
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [isLoggedInProvider.overrideWith((ref) async => false)],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const CatalogScreen(),
        ),
      ),
      const Size(390, 844),
    );
    expect(find.text('Закладки'), findsOneWidget);
    expect(find.text('Нужен аккаунт AniMix'), findsOneWidget);
  });

  testWidgets('profile signed-out state remains usable on Windows size', (
    tester,
  ) async {
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [currentUserProvider.overrideWith((ref) async => null)],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const ProfileScreen(),
        ),
      ),
      const Size(1280, 720),
    );
    expect(find.text('Профиль Shikimori'), findsOneWidget);
    expect(find.text('Войти'), findsOneWidget);
  });

  testWidgets('mobile root uses the floating AniMix tab bar', (tester) async {
    const emptyHome = HomeData(
      hero: [],
      popular: [],
      ongoing: [],
      topRated: [],
      announced: [],
    );
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          homeDataProvider.overrideWith((ref) async => emptyHome),
          currentUserProvider.overrideWith((ref) async => null),
          isLoggedInProvider.overrideWith((ref) async => false),
        ],
        child: MaterialApp(
          theme: AniMixTheme.material(
            const Color(0xFF8B5CF6),
            AniMixThemeStyle.graphite,
          ),
          home: const MainWrapper(),
        ),
      ),
      const Size(390, 844),
    );
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.text('Главная'), findsOneWidget);
    expect(find.text('Для вас'), findsWidgets);
    expect(find.text('Закладки'), findsOneWidget);
  });
}
