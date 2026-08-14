import 'dart:io';
import 'package:fvp/fvp.dart' as fvp;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';
import 'features/profile/profile_screen.dart';
import 'features/recommendation/recommendation_screen.dart';
import 'features/catalog/catalog_screen.dart';
import 'features/downloads/downloads_screen.dart';
import 'features/profile/settings_screen.dart';
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

  // Optional local override. Release builds receive public values through
  // --dart-define, so no .env asset or secret is shipped with the app.
  await dotenv.load(fileName: '.env', isOptional: true);
  await AppSettingsController.instance.initialize();
  runApp(const ProviderScope(child: MyApp()));
}

class MyApp extends ConsumerWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Слушаем состояние авторизации для выбора стартового экрана.
    // Используем .maybeWhen, так как он поддерживается во всех версиях Riverpod (и 1.x, и 2.x)
    final authState = ref.watch(isLoggedInProvider);

    final settings = AppSettingsController.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => MaterialApp(
        navigatorKey: appNavigatorKey,
        title: 'AniMix',
        debugShowCheckedModeBanner: false,
        theme: AniMixTheme.material(settings.accentColor, settings.themeStyle),
        builder: (context, child) => CupertinoTheme(
          data: AniMixTheme.cupertino(
            settings.accentColor,
            settings.themeStyle,
          ),
          child: DefaultTextStyle(
            style: Theme.of(context).textTheme.bodyMedium!,
            child: child ?? const SizedBox.shrink(),
          ),
        ),
        home: authState.when(
          data: (loggedIn) =>
              loggedIn ? const MainWrapper() : const LoginScreen(),
          loading: () => const _StartupLoading(),
          error: (_, _) =>
              _StartupError(onRetry: () => ref.invalidate(isLoggedInProvider)),
        ),
      ),
    );
  }
}

class _StartupLoading extends StatelessWidget {
  const _StartupLoading();

  @override
  Widget build(BuildContext context) => const Scaffold(
    body: Center(child: CupertinoActivityIndicator(radius: 15)),
  );
}

class _StartupError extends StatelessWidget {
  const _StartupError({required this.onRetry});
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(CupertinoIcons.exclamationmark_triangle, size: 42),
            const SizedBox(height: 16),
            const Text(
              'Не удалось проверить авторизацию',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onRetry,
              icon: const Icon(CupertinoIcons.refresh),
              label: const Text('Повторить'),
            ),
          ],
        ),
      ),
    ),
  );
}

// =====================================================================
// 🚨 ДИАЛОГ ИСТЕКШЕЙ СЕССИИ (Полностью нативное стекло)
// Вызывается из shikimori_api_client.dart при ошибке 401
// =====================================================================
void showSessionExpiredDialog(dynamic ref) {
  final context = appNavigatorKey.currentContext;
  if (context == null) return;
  showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      icon: const Icon(
        CupertinoIcons.exclamationmark_triangle_fill,
        color: Colors.redAccent,
        size: 38,
      ),
      title: const Text('Сессия истекла'),
      content: const Text(
        'Shikimori больше не принимает текущий токен. Войдите в аккаунт повторно.',
        textAlign: TextAlign.center,
      ),
      actionsAlignment: MainAxisAlignment.center,
      actions: [
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Войти повторно'),
        ),
      ],
    ),
  );
}

// =====================================================================
// Адаптивная корневая навигация: tab bar на телефоне и rail на desktop.
// =====================================================================
class MainWrapper extends StatefulWidget {
  const MainWrapper({super.key});

  @override
  State<MainWrapper> createState() => _MainWrapperState();
}

class _MainWrapperState extends State<MainWrapper> {
  int _currentIndex = 0;
  final Set<int> _visited = {0};

  final List<Widget> _screens = [
    const HomeScreen(),
    const RecommendationScreen(),
    const CatalogScreen(),
    const DownloadsScreen(),
    const ProfileScreen(),
    const SettingsScreen(),
  ];

  void _select(int index) {
    setState(() {
      _currentIndex = index;
      _visited.add(index);
    });
  }

  @override
  Widget build(BuildContext context) {
    const mobileDestinations = [
      NavigationDestination(
        icon: Icon(CupertinoIcons.house),
        selectedIcon: Icon(CupertinoIcons.house_fill),
        label: 'Главная',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.sparkles),
        selectedIcon: Icon(CupertinoIcons.sparkles),
        label: 'Для вас',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.bookmark),
        selectedIcon: Icon(CupertinoIcons.bookmark_fill),
        label: 'Закладки',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.arrow_down_circle),
        selectedIcon: Icon(CupertinoIcons.arrow_down_circle_fill),
        label: 'Загрузки',
      ),
      NavigationDestination(
        icon: Icon(CupertinoIcons.person_crop_circle),
        selectedIcon: Icon(CupertinoIcons.person_crop_circle_fill),
        label: 'Профиль',
      ),
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 900;
        final visibleIndex = !wide && _currentIndex > 4 ? 4 : _currentIndex;
        final content = IndexedStack(
          index: visibleIndex,
          children: [
            for (var index = 0; index < _screens.length; index++)
              (_visited.contains(index) || index == visibleIndex)
                  ? _screens[index]
                  : const SizedBox.shrink(),
          ],
        );
        if (wide) {
          return Scaffold(
            body: Row(
              children: [
                _DesktopSidebar(
                  selectedIndex: _currentIndex,
                  onSelected: _select,
                ),
                Expanded(child: content),
              ],
            ),
          );
        }
        return Scaffold(
          body: content,
          bottomNavigationBar: _FloatingTabBar(
            selectedIndex: visibleIndex,
            destinations: mobileDestinations,
            onSelected: _select,
          ),
        );
      },
    );
  }
}

class _FloatingTabBar extends StatelessWidget {
  const _FloatingTabBar({
    required this.selectedIndex,
    required this.destinations,
    required this.onSelected,
  });

  final int selectedIndex;
  final List<NavigationDestination> destinations;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return ColoredBox(
      color: Colors.transparent,
      child: SafeArea(
        top: false,
        minimum: const EdgeInsets.fromLTRB(12, 6, 12, 10),
        child: Container(
          height: 66,
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainer,
            borderRadius: BorderRadius.circular(25),
            border: Border.all(color: const Color(0x1FFFFFFF)),
            boxShadow: const [
              BoxShadow(
                color: Color(0x66000000),
                blurRadius: 24,
                offset: Offset(0, 10),
              ),
            ],
          ),
          child: Row(
            children: [
              for (var index = 0; index < destinations.length; index++)
                Expanded(
                  child: Semantics(
                    button: true,
                    selected: selectedIndex == index,
                    label: destinations[index].label,
                    child: InkWell(
                      borderRadius: BorderRadius.circular(19),
                      onTap: () => onSelected(index),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 190),
                        curve: Curves.easeOutCubic,
                        decoration: BoxDecoration(
                          color: selectedIndex == index
                              ? accent.withValues(alpha: .17)
                              : Colors.transparent,
                          borderRadius: BorderRadius.circular(19),
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            IconTheme(
                              data: IconThemeData(
                                size: 20,
                                color: selectedIndex == index
                                    ? accent
                                    : AniMixTheme.subtleText,
                              ),
                              child: selectedIndex == index
                                  ? (destinations[index].selectedIcon ??
                                        destinations[index].icon)
                                  : destinations[index].icon,
                            ),
                            const SizedBox(height: 4),
                            FittedBox(
                              fit: BoxFit.scaleDown,
                              child: Text(
                                destinations[index].label,
                                style: TextStyle(
                                  color: selectedIndex == index
                                      ? Colors.white
                                      : AniMixTheme.subtleText,
                                  fontSize: 9,
                                  fontWeight: selectedIndex == index
                                      ? FontWeight.w800
                                      : FontWeight.w600,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DesktopSidebar extends StatelessWidget {
  const _DesktopSidebar({
    required this.selectedIndex,
    required this.onSelected,
  });

  final int selectedIndex;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    const items = [
      ('Главная', CupertinoIcons.house_fill),
      ('Для вас', CupertinoIcons.sparkles),
      ('Закладки', CupertinoIcons.bookmark_fill),
      ('Загрузки', CupertinoIcons.arrow_down_circle_fill),
      ('Профиль', CupertinoIcons.person_crop_circle_fill),
      ('Настройки', CupertinoIcons.gear_alt_fill),
    ];
    return Container(
      width: 220,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        border: const Border(right: BorderSide(color: AniMixTheme.divider)),
      ),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 20, 18, 26),
              child: Row(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(11),
                    child: Image.asset(
                      'assets/icon/app_icon.png',
                      width: 38,
                      height: 38,
                      fit: BoxFit.cover,
                    ),
                  ),
                  const SizedBox(width: 11),
                  const Text(
                    'AniMix',
                    style: TextStyle(
                      fontSize: 21,
                      letterSpacing: -.5,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                ],
              ),
            ),
            for (var index = 0; index < items.length; index++)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 2,
                ),
                child: Material(
                  color: selectedIndex == index
                      ? Theme.of(
                          context,
                        ).colorScheme.primary.withValues(alpha: .16)
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(13),
                  child: ListTile(
                    dense: true,
                    minTileHeight: 48,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(13),
                    ),
                    leading: Icon(
                      items[index].$2,
                      size: 20,
                      color: selectedIndex == index
                          ? Theme.of(context).colorScheme.primary
                          : AniMixTheme.subtleText,
                    ),
                    title: Text(
                      items[index].$1,
                      style: TextStyle(
                        color: selectedIndex == index
                            ? Colors.white
                            : AniMixTheme.subtleText,
                        fontWeight: selectedIndex == index
                            ? FontWeight.w800
                            : FontWeight.w600,
                      ),
                    ),
                    onTap: () => onSelected(index),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
