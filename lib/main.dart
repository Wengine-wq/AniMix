import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';
import 'features/profile/profile_screen.dart';
import 'features/recommendation/recommendation_screen.dart';
import 'features/catalog/catalog_screen.dart';
import 'providers/auth_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: ".env");

  // 🔥 ОБЯЗАТЕЛЬНАЯ ИНИЦИАЛИЗАЦИАЯ ДЛЯ liquid_glass_widgets (предкэширование шейдеров)
  await LiquidGlassWidgets.initialize();

  // Обертка LiquidGlassWidgets.wrap() для правильного рендеринга шейдеров
  // adaptiveQuality позволяет автоматически понижать качество на слабых устройствах
  runApp(ProviderScope(
    child: LiquidGlassWidgets.wrap(
      const MyApp(),
      adaptiveQuality: true,
    ),
  ));
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const CupertinoApp(
      title: 'AniMix',
      debugShowCheckedModeBanner: false,
      theme: CupertinoThemeData(
        primaryColor: Color(0xFF8B5CF6), // 🔥 Новый фиолетовый акцент
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Color(0xFF09090B),
      ),
      // 🔥 ДЕЛЕГАТЫ НУЖНЫ ДЛЯ КОРРЕКТНОЙ РАБОТЫ ВИДЕОПЛЕЕРОВ И НАТИВНЫХ ДИАЛОГОВ НА WINDOWS/ANDROID
      localizationsDelegates: [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: [
        Locale('ru', 'RU'),
        Locale('en', 'US'),
      ],
      home: AuthChecker(),
    );
  }
}

// =====================================================================
// CHECKER АВТОРИЗАЦИИ (Перехватывает состояние токена)
// =====================================================================
class AuthChecker extends ConsumerWidget {
  const AuthChecker({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(isLoggedInProvider);

    return authState.when(
      data: (isLoggedIn) => isLoggedIn ? const MainTabs() : const LoginScreen(),
      loading: () => const CupertinoPageScaffold(
        backgroundColor: Color(0xFF09090B),
        child: Center(child: CupertinoActivityIndicator(radius: 16)),
      ),
      error: (_, __) => const LoginScreen(),
    );
  }
}

// =====================================================================
// ГЛАВНЫЙ ЭКРАН С ВКЛАДКАМИ И НЕЗАВИСИМОЙ НАВИГАЦИЕЙ
// =====================================================================
class MainTabs extends StatefulWidget {
  const MainTabs({super.key});

  @override
  State<MainTabs> createState() => _MainTabsState();
}

class _MainTabsState extends State<MainTabs> {
  int _currentIndex = 0;

  // 🔥 НЕЗАВИСИМЫЕ НАВИГАТОРЫ ДЛЯ КАЖДОЙ ВКЛАДКИ
  // Предотвращает Hero-краши при переключении табов
  final List<GlobalKey<NavigatorState>> _tabNavKeys = [
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
  ];

  final List<Widget> _pages = const [
    HomeScreen(),
    RecommendationScreen(),
    CatalogScreen(),
    ProfileScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    // Используем Scaffold из Material для правильного позиционирования GlassBottomBar
    return Scaffold(
      backgroundColor: const Color(0xFF09090B),
      // 🔥 КРИТИЧЕСКИ ВАЖНО: позволяет контенту (включая неоновые сферы) 
      // "затекать" под бар, чтобы стекло могло его преломлять
      extendBody: true, 
      body: IndexedStack(
        index: _currentIndex,
        children: List.generate(_pages.length, (index) {
          return CupertinoTabView(
            navigatorKey: _tabNavKeys[index],
            builder: (context) => _pages[index],
          );
        }),
      ),
      bottomNavigationBar: GlassBottomBar(
        selectedIndex: _currentIndex,
        quality: GlassQuality.premium, // 🔥 Премиальные блики на стекле
        onTabSelected: (index) {
          if (_currentIndex == index) {
            // Возвращаем в корень, если тапнули по активной вкладке (как в iOS)
            _tabNavKeys[index].currentState?.popUntil((route) => route.isFirst);
          } else {
            setState(() {
              _currentIndex = index;
            });
          }
        },
        tabs: const [
          GlassBottomBarTab(
            icon: Icon(CupertinoIcons.house_fill, color: CupertinoColors.systemGrey),
            activeIcon: Icon(CupertinoIcons.house_fill, color: Color(0xFF8B5CF6)), // Фиолетовый акцент
            label: 'Главная',
          ),
          GlassBottomBarTab(
            icon: Icon(CupertinoIcons.sparkles, color: CupertinoColors.systemGrey),
            activeIcon: Icon(CupertinoIcons.sparkles, color: Color(0xFF8B5CF6)),
            label: 'Подбор',
          ),
          GlassBottomBarTab(
            icon: Icon(CupertinoIcons.list_bullet, color: CupertinoColors.systemGrey),
            activeIcon: Icon(CupertinoIcons.list_bullet, color: Color(0xFF8B5CF6)),
            label: 'Каталог',
          ),
          GlassBottomBarTab(
            icon: Icon(CupertinoIcons.person_fill, color: CupertinoColors.systemGrey),
            activeIcon: Icon(CupertinoIcons.person_fill, color: Color(0xFF8B5CF6)),
            label: 'Профиль',
          ),
        ],
      ),
    );
  }
}