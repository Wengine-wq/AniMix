import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';
import 'features/profile/profile_screen.dart';
import 'features/recommendation/recommendation_screen.dart';
import 'features/catalog/catalog_screen.dart'; // ← ДОБАВЛЕН ИМПОРТ КАТАЛОГА
import 'providers/auth_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: ".env");
  runApp(const ProviderScope(child: MyApp()));
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoApp(
      title: 'AniMix',
      debugShowCheckedModeBanner: false,
      theme: const CupertinoThemeData(
        primaryColor: Color(0xFFFF5722),
        scaffoldBackgroundColor: Color(0xFF0F0F0F),
        barBackgroundColor: Color(0xFF1E1E1E),
      ),
      // 🔥 ФИКС КРАША ПЛЕЕРА (ШЕСТЕРЕНКА) НА WINDOWS 🔥
      // Добавляем глобальные локализации Material, так как меню плеера Chewie
      // использует Material-компоненты на десктопе, которые падают без переводов.
      localizationsDelegates: const [
        DefaultMaterialLocalizations.delegate,
        DefaultCupertinoLocalizations.delegate,
        DefaultWidgetsLocalizations.delegate,
      ],
      // 🔥 Оборачиваем весь навигатор в Theme и Material, 
      // чтобы глобальные модальные окна из плагинов (вроде showModalBottomSheet)
      // имели доступ к контексту и не вызывали "тихий" вылет приложения.
      builder: (context, child) {
        return Theme(
          data: ThemeData.dark().copyWith(
            primaryColor: const Color(0xFFFF5722),
            colorScheme: const ColorScheme.dark(primary: Color(0xFFFF5722)),
          ),
          child: Material(
            type: MaterialType.transparency,
            child: child!,
          ),
        );
      },
      home: const AuthChecker(),
    );
  }
}

class AuthChecker extends ConsumerWidget {
  const AuthChecker({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isLoggedInAsync = ref.watch(isLoggedInProvider);

    return isLoggedInAsync.when(
      data: (isLoggedIn) => isLoggedIn ? const MainTabs() : const LoginScreen(),
      loading: () => const CupertinoPageScaffold(
        child: Center(child: CupertinoActivityIndicator(radius: 16)),
      ),
      error: (err, stack) => const LoginScreen(),
    );
  }
}

class MainTabs extends StatelessWidget {
  const MainTabs({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoTabScaffold(
      // ====================== LIQUID GLASS BOTTOM NAV (iOS 18/26 style) ======================
      tabBar: CupertinoTabBar(
        backgroundColor: const Color(0xFF1E1E1E).withValues(alpha: 0.85),
        activeColor: const Color(0xFFFF5722),
        inactiveColor: CupertinoColors.systemGrey,
        height: 68,
        iconSize: 28,
        border: Border(
          top: BorderSide(
            color: Colors.white.withValues(alpha: 0.12),
            width: 0.5,
          ),
        ),
        items: const [
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.house_fill),
            label: 'Главная',
          ),
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.sparkles),
            label: 'Подбор',
          ),
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.list_bullet),
            label: 'Каталог',
          ),
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.person_fill),
            label: 'Профиль',
          ),
        ],
      ),

      tabBuilder: (context, index) {
        switch (index) {
          case 0:
            return const HomeScreen();
          case 1:
            return const RecommendationScreen();
          case 2:
            return const CatalogScreen(); // ← ПОДКЛЮЧЕН НОВЫЙ ЭКРАН КАТАЛОГА
          case 3:
            return const ProfileScreen();
          default:
            return const HomeScreen();
        }
      },
    );
  }
}