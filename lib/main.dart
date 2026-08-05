import 'dart:io';
import 'dart:ui';
import 'package:fvp/fvp.dart' as fvp;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';
import 'features/profile/profile_screen.dart';
import 'features/recommendation/recommendation_screen.dart';
import 'features/catalog/catalog_screen.dart';
import 'providers/auth_provider.dart';
import 'core/animix_theme.dart';
import 'core/app_settings.dart';

// 🔥 ГЛОБАЛЬНЫЙ КЛЮЧ НАВИГАЦИИ (Нужен для вызова диалогов из перехватчиков API)
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  if (Platform.isWindows) {
    fvp.registerWith(
      options: const {
        'platforms': ['windows'],
      },
    );
  }

  debugPaintSizeEnabled = false;
  debugPaintBaselinesEnabled = false;
  debugRepaintRainbowEnabled = false;
  debugProfilePaintsEnabled = false;

  // Стандартная загрузка .env
  await dotenv.load(fileName: ".env");
  await AppSettingsController.instance.initialize();
  await LiquidGlassWidgets.initialize();

  runApp(
    LiquidGlassWidgets.wrap(
      adaptiveQuality: true,
      theme: GlassThemeData.simple(
        blur: 10,
        thickness: 30,
        quality: GlassQuality.standard,
      ),
      child: const ProviderScope(child: MyApp()),
    ),
  );
}

class MyApp extends ConsumerWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Слушаем состояние авторизации для выбора стартового экрана.
    // Используем .maybeWhen, так как он поддерживается во всех версиях Riverpod (и 1.x, и 2.x)
    final isLoggedIn = ref
        .watch(isLoggedInProvider)
        .maybeWhen(data: (bool data) => data, orElse: () => false);

    final settings = AppSettingsController.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => MaterialApp(
        navigatorKey: appNavigatorKey,
        title: 'AniMix',
        debugShowCheckedModeBanner: false,
        theme: AniMixTheme.material(settings.accentColor),
        builder: (context, child) => CupertinoTheme(
          data: AniMixTheme.cupertino(settings.accentColor),
          child: DefaultTextStyle(
            style: Theme.of(context).textTheme.bodyMedium!,
            child: child ?? const SizedBox.shrink(),
          ),
        ),
        home: isLoggedIn ? const MainWrapper() : const LoginScreen(),
      ),
    );
  }
}

// =====================================================================
// 🚨 ДИАЛОГ ИСТЕКШЕЙ СЕССИИ (Полностью нативное стекло)
// Вызывается из shikimori_api_client.dart при ошибке 401
// =====================================================================
void showSessionExpiredDialog(dynamic ref) {
  final context = appNavigatorKey.currentContext;
  if (context == null) return;
  final accent = Theme.of(context).colorScheme.primary;

  showDialog(
    context: context,
    barrierDismissible: false,
    barrierColor: Colors.black.withValues(alpha: 0.6),
    builder: (context) {
      return Center(
        child: Material(
          color: Colors.transparent,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(28),
            child: BackdropFilter(
              filter: ImageFilter.blur(
                sigmaX: 25,
                sigmaY: 25,
              ), // Нативное размытие
              child: Container(
                width: MediaQuery.of(context).size.width * 0.85,
                padding: const EdgeInsets.all(28),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.05), // Тинт стекла
                  borderRadius: BorderRadius.circular(28),
                  border: Border.all(
                    color: Colors.white.withValues(
                      alpha: 0.15,
                    ), // Эффект граней (Fresnel)
                    width: 1.5,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.2),
                      blurRadius: 30,
                      spreadRadius: -5,
                    ),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.redAccent.withValues(alpha: 0.2),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(
                        CupertinoIcons.exclamationmark_triangle_fill,
                        color: Colors.redAccent,
                        size: 36,
                      ),
                    ),
                    const SizedBox(height: 20),
                    const Text(
                      'Сессия истекла',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 0.5,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      'Токен авторизации был отозван или его срок действия истек. Пожалуйста, войдите в аккаунт заново.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.7),
                        fontSize: 15,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 28),
                    GestureDetector(
                      onTap: () {
                        Navigator.of(context).pop();
                        // Так как в apiClient мы уже вызвали ref.invalidate(),
                        // приложение автоматически вернет пользователя на LoginScreen
                      },
                      child: Container(
                        width: double.infinity,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              accent,
                              Color.lerp(accent, Colors.black, 0.25)!,
                            ],
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                          ),
                          borderRadius: BorderRadius.circular(20),
                          boxShadow: [
                            BoxShadow(
                              color: accent.withValues(alpha: 0.4),
                              blurRadius: 12,
                              offset: const Offset(0, 4),
                            ),
                          ],
                        ),
                        child: const Text(
                          'Войти',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

// =====================================================================
// 🪞 ГЛОБАЛЬНЫЙ ВИДЖЕТ НАТИВНОГО СТЕКЛА (AniMixGlass)
// Используется в LoginScreen и других экранах вместо сторонних библиотек
// =====================================================================
class AniMixGlass extends StatelessWidget {
  final Widget child;
  final double borderRadius;
  final EdgeInsetsGeometry padding;
  final double blur; // 🔥 Добавили параметр для интенсивности размытия

  const AniMixGlass({
    super.key,
    required this.child,
    this.borderRadius = 24.0,
    this.padding = EdgeInsets.zero,
    this.blur = 25.0, // Значение по умолчанию
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: AniMixTheme.surface,
        borderRadius: BorderRadius.circular(borderRadius),
        border: Border.all(color: AniMixTheme.divider),
      ),
      child: child,
    );
  }
}

// =====================================================================
// 🏠 ГЛАВНЫЙ КОНТЕЙНЕР С НАТИВНЫМ СТЕКЛЯННЫМ NAV BAR
// =====================================================================
class MainWrapper extends StatefulWidget {
  const MainWrapper({super.key});

  @override
  State<MainWrapper> createState() => _MainWrapperState();
}

class _MainWrapperState extends State<MainWrapper> {
  int _currentIndex = 0;

  final List<Widget> _screens = [
    const HomeScreen(),
    const CatalogScreen(),
    const RecommendationScreen(),
    const ProfileScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    const destinations = [
      NavigationDestination(
        icon: Icon(CupertinoIcons.house),
        selectedIcon: Icon(CupertinoIcons.house_fill),
        label: 'Главная',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.square_grid_2x2),
        selectedIcon: Icon(CupertinoIcons.square_grid_2x2_fill),
        label: 'Каталог',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.sparkles),
        selectedIcon: Icon(CupertinoIcons.sparkles),
        label: 'Подборка',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.person),
        selectedIcon: Icon(CupertinoIcons.person_fill),
        label: 'Профиль',
      ),
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 1000;
        final content = IndexedStack(index: _currentIndex, children: _screens);
        if (wide) {
          return Scaffold(
            body: Row(
              children: [
                SafeArea(
                  right: false,
                  child: NavigationRail(
                    selectedIndex: _currentIndex,
                    onDestinationSelected: (index) =>
                        setState(() => _currentIndex = index),
                    labelType: NavigationRailLabelType.all,
                    leading: Padding(
                      padding: const EdgeInsets.only(top: 14, bottom: 22),
                      child: Text(
                        'A',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          fontSize: 26,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                    ),
                    destinations: const [
                      NavigationRailDestination(
                        icon: Icon(CupertinoIcons.house),
                        selectedIcon: Icon(CupertinoIcons.house_fill),
                        label: Text('Главная'),
                      ),
                      NavigationRailDestination(
                        icon: Icon(CupertinoIcons.square_grid_2x2),
                        selectedIcon: Icon(CupertinoIcons.square_grid_2x2_fill),
                        label: Text('Каталог'),
                      ),
                      NavigationRailDestination(
                        icon: Icon(CupertinoIcons.sparkles),
                        label: Text('Подборка'),
                      ),
                      NavigationRailDestination(
                        icon: Icon(CupertinoIcons.person),
                        selectedIcon: Icon(CupertinoIcons.person_fill),
                        label: Text('Профиль'),
                      ),
                    ],
                  ),
                ),
                const VerticalDivider(width: 1),
                Expanded(child: content),
              ],
            ),
          );
        }
        return Scaffold(
          body: content,
          bottomNavigationBar: NavigationBar(
            selectedIndex: _currentIndex,
            onDestinationSelected: (index) =>
                setState(() => _currentIndex = index),
            destinations: destinations,
          ),
        );
      },
    );
  }
}
