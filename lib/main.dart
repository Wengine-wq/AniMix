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

// Цветовая палитра AniMix
const Color _accentColor = Color(0xFF8B5CF6);

// 🔥 ГЛОБАЛЬНЫЙ КЛЮЧ НАВИГАЦИИ
// Необходим для показа диалога Liquid Glass об истекшей сессии прямо из API клиента
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();

// 🔥 ФУНКЦИЯ ДЛЯ ВЫЗОВА ДИАЛОГА (вызывается из shikimori_api_client.dart при 401 ошибке)
void showSessionExpiredDialog(WidgetRef ref) {
  final context = appNavigatorKey.currentContext;
  if (context == null) return;
  
  showGeneralDialog(
    context: context,
    barrierDismissible: false, // Запрещаем закрывать тапом по фону
    barrierColor: Colors.black.withOpacity(0.8), // Глубокое затемнение окружения
    transitionDuration: const Duration(milliseconds: 400),
    pageBuilder: (context, animation, secondaryAnimation) {
      return FadeTransition(
        opacity: animation,
        child: _LiquidGlassExpiredDialog(ref: ref),
      );
    },
  );
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: ".env");

  // Инициализация шейдеров Liquid Glass
  await LiquidGlassWidgets.initialize();

  runApp(ProviderScope(
    child: LiquidGlassWidgets.wrap(
      const MyApp(),
      adaptiveQuality: true,
    ),
  ));
}

class MyApp extends HookConsumerWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Используем isLoggedInProvider из вашего auth_provider.dart
    final authState = ref.watch(isLoggedInProvider);

    return MaterialApp(
      title: 'AniMix',
      debugShowCheckedModeBanner: false,
      // Встраиваем наш глобальный ключ для перехвата сессии
      navigatorKey: appNavigatorKey,
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: _accentColor,
        scaffoldBackgroundColor: const Color(0xFF09090B),
        fontFamily: 'SF Pro Display',
      ),
      home: authState.when(
        data: (isLoggedIn) => isLoggedIn ? const MainTabs() : const LoginScreen(),
        loading: () => const Scaffold(body: Center(child: CupertinoActivityIndicator())),
        error: (e, _) => Scaffold(body: Center(child: Text('Ошибка: $e'))),
      ),
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [Locale('ru', 'RU')],
    );
  }
}

class MainTabs extends StatefulWidget {
  const MainTabs({super.key});

  @override
  State<MainTabs> createState() => _MainTabsState();
}

class _MainTabsState extends State<MainTabs> {
  int _currentIndex = 0;
  
  final List<GlobalKey<NavigatorState>> _navigatorKeys = [
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
    GlobalKey<NavigatorState>(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF09090B),
      // Позволяет контенту затекать под навбар для видимой радуги и преломления
      extendBody: true, 
      body: IndexedStack(
        index: _currentIndex,
        children: [
          _buildTab(0, const HomeScreen()),
          _buildTab(1, const RecommendationScreen()),
          _buildTab(2, const CatalogScreen()),
          _buildTab(3, const ProfileScreen()),
        ],
      ),
      // ЭКСТРЕМАЛЬНАЯ ОПТИКА LIQUID GLASS: Идеальная прозрачность для эффекта "Бензина"
      bottomNavigationBar: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(
          glassColor: Color(0x60050507),    // ~38% непрозрачности: идеально пропускает цвет для "растекания"
          blur: 45.0,                       // Высокий блюр для плавного смешивания фоновых цветов
          thickness: 80.0,                  // Огромная толщина для эффекта линзы и сильного преломления
          chromaticAberration: 0.95,        // Максимальная бензиновая радужка на гранях
          refractiveIndex: 1.8,             // Сверх-сильное искажение контента за стеклом
          lightIntensity: 0.15,             // Приглушенный свет на гранях для глубокого премиального вида
          specularSharpness: GlassSpecularSharpness.soft, // Мягкие, "жидкие" блики
        ),
        child: GlassBottomBar(
          selectedIndex: _currentIndex,
          onTabSelected: (index) {
            if (_currentIndex == index) {
              _navigatorKeys[index].currentState?.popUntil((route) => route.isFirst);
            } else {
              setState(() => _currentIndex = index);
            }
          },
          selectedIconColor: Colors.white, 
          unselectedIconColor: Colors.white.withOpacity(0.35),
          // То самое "стеклышко поверх" - активный индикатор, который вбирает в себя оптику
          indicatorColor: _accentColor.withOpacity(0.4), 
          maskingQuality: MaskingQuality.high,
          tabs: const [
            GlassBottomBarTab(
              icon: Icon(CupertinoIcons.house),
              activeIcon: Icon(CupertinoIcons.house_fill),
              label: 'Главная',
            ),
            GlassBottomBarTab(
              icon: Icon(CupertinoIcons.sparkles),
              activeIcon: Icon(CupertinoIcons.sparkles),
              label: 'Подбор',
            ),
            GlassBottomBarTab(
              icon: Icon(CupertinoIcons.list_bullet),
              activeIcon: Icon(CupertinoIcons.list_bullet_indent),
              label: 'Каталог',
            ),
            GlassBottomBarTab(
              icon: Icon(CupertinoIcons.person),
              activeIcon: Icon(CupertinoIcons.person_fill),
              label: 'Профиль',
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTab(int index, Widget child) {
    return Navigator(
      key: _navigatorKeys[index],
      onGenerateRoute: (settings) {
        return MaterialPageRoute(builder: (_) => child);
      },
    );
  }
}

// =====================================================================
// ПРЕМИАЛЬНЫЙ ДИАЛОГ ОБ ИСТЕКШЕЙ СЕССИИ (LIQUID GLASS)
// =====================================================================
class _LiquidGlassExpiredDialog extends StatelessWidget {
  final WidgetRef ref;
  const _LiquidGlassExpiredDialog({required this.ref});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Center(
        child: AdaptiveLiquidGlassLayer(
          settings: const LiquidGlassSettings(
            glassColor: Color(0xD9050507), // Темная подложка (85%), чтобы текст читался идеально
            blur: 60.0,
            thickness: 40.0,
            chromaticAberration: 0.25, // Мощная дисперсия (угрожающая для алертов)
            refractiveIndex: 1.5,
            specularSharpness: GlassSpecularSharpness.sharp,
          ),
          child: GlassContainer(
            shape: const LiquidRoundedSuperellipse(borderRadius: 36.0),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 40.0, horizontal: 32.0),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Иконка замка в красном стекле
                  GlassContainer(
                    shape: const LiquidRoundedSuperellipse(borderRadius: 100.0),
                    settings: const LiquidGlassSettings(
                      glassColor: Color(0x33FF3B30), // Красная подложка
                      blur: 10.0,
                    ),
                    child: const Padding(
                      padding: EdgeInsets.all(16.0),
                      child: Icon(CupertinoIcons.lock_slash_fill, color: CupertinoColors.systemRed, size: 42),
                    ),
                  ),
                  const SizedBox(height: 24),
                  const Text(
                    'Сессия истекла',
                    style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w800, letterSpacing: -0.5),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Ваш токен авторизации устарел.\nПожалуйста, войдите в аккаунт заново.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white70, fontSize: 15, height: 1.3, fontWeight: FontWeight.w500),
                  ),
                  const SizedBox(height: 36),
                  // Кнопка с физикой желе
                  GlassButton(
                    onTap: () {
                      Navigator.pop(context); // Закрываем сам диалог
                      ref.invalidate(isLoggedInProvider); // Перекидываем на LoginScreen в main.dart
                    },
                    shape: const LiquidRoundedSuperellipse(borderRadius: 20.0),
                    settings: const LiquidGlassSettings(
                      glassColor: Color(0x66FF3B30), // Красный премиальный тинт
                      blur: 20.0,
                    ),
                    icon: const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 48, vertical: 16),
                      child: Text('Войти заново', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 17, letterSpacing: -0.5)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}