import 'dart:async';
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:dio/dio.dart';

import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart'; 
import '../../providers/auth_provider.dart'; 
import '../../core/secure_storage.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../auth/login_screen.dart';

// =====================================================================
// ЦВЕТОВАЯ ПАЛИТРА И СТИЛИ
// =====================================================================
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF050507); 
const Color _errorColor = Color(0xFFFF3B30);

// Настройки премиального стекла для карточек
const _cardGlassSettings = LiquidGlassSettings(
  glassColor: Color(0x26000000), 
  blur: 25.0,
  chromaticAberration: 0.15,
  specularSharpness: GlassSpecularSharpness.sharp,
  refractiveIndex: 1.2,
);

// =====================================================================
// КАТЕГОРИИ (ИСКЛЮЧИТЕЛЬНО ПОЛЬЗОВАТЕЛЬСКИЕ)
// =====================================================================
enum CatalogCategory { 
  myAll, 
  watching, 
  planned, 
  completed, 
  onHold, 
  dropped,
  rated 
}

extension CatalogCategoryExt on CatalogCategory {
  String get label {
    switch (this) {
      case CatalogCategory.myAll: return 'Все';
      case CatalogCategory.watching: return 'Смотрю';
      case CatalogCategory.planned: return 'В планах';
      case CatalogCategory.completed: return 'Просмотрено';
      case CatalogCategory.onHold: return 'Отложено';
      case CatalogCategory.dropped: return 'Брошено';
      case CatalogCategory.rated: return 'Оценено';
    }
  }

  IconData get icon {
    switch (this) {
      case CatalogCategory.myAll: return CupertinoIcons.square_grid_2x2_fill;
      case CatalogCategory.watching: return CupertinoIcons.play_circle_fill;
      case CatalogCategory.planned: return CupertinoIcons.calendar;
      case CatalogCategory.completed: return CupertinoIcons.checkmark_circle_fill; // 🔥 Исправлено
      case CatalogCategory.onHold: return CupertinoIcons.pause_circle_fill;
      case CatalogCategory.dropped: return CupertinoIcons.xmark_circle_fill;
      case CatalogCategory.rated: return CupertinoIcons.star_fill;
    }
  }
}

// Обертка для данных элемента списка
class CatalogItem {
  final ShikimoriAnime anime;
  final int? userScore;
  final String? status; 
  
  CatalogItem({required this.anime, this.userScore, this.status});
}

// =====================================================================
// СОСТОЯНИЕ И ЛОГИКА (Riverpod 2.0 Notifier)
// =====================================================================

class CatalogCategoryNotifier extends Notifier<CatalogCategory> {
  @override
  CatalogCategory build() => CatalogCategory.myAll;

  void setCategory(CatalogCategory newCategory) {
    state = newCategory;
  }
}

final catalogCategoryProvider = NotifierProvider<CatalogCategoryNotifier, CatalogCategory>(
  CatalogCategoryNotifier.new,
);

// Провайдер загрузки данных из Шикимори
final catalogDataProvider = FutureProvider.autoDispose<List<CatalogItem>>((ref) async {
  final category = ref.watch(catalogCategoryProvider);
  final api = ref.read(apiClientProvider);

  // Безопасная проверка авторизации
  final dynamic authValue = ref.watch(isLoggedInProvider);
  final bool isLoggedIn = authValue is bool ? authValue : (authValue?.value == true);
  
  if (!isLoggedIn) throw Exception('auth_required');

  // Получаем текущего юзера для доступа к его ID
  final currentUser = await api.getCurrentUser();
  
  final dio = Dio(BaseOptions(
    baseUrl: 'https://shikimori.io',
    headers: {
      'User-Agent': 'AniMix App',
      'Accept': 'application/json',
    },
  ));
  
  final token = await SecureStorage.getAccessToken();
  if (token != null) dio.options.headers['Authorization'] = 'Bearer $token';

  // Запрашиваем весь список аниме пользователя
  final res = await dio.get('/api/users/${currentUser.id}/anime_rates', queryParameters: {'limit': 5000});
  final List data = res.data as List;
  
  final items = <CatalogItem>[];
  for (var item in data) {
    final status = item['status'] as String?;
    final score = int.tryParse(item['score']?.toString() ?? '0') ?? 0;
    bool matches = false;
    
    // Фильтрация по выбранной вкладке
    if (category == CatalogCategory.myAll) matches = true;
    else if (category == CatalogCategory.watching && (status == 'watching' || status == 'rewatching')) matches = true;
    else if (category == CatalogCategory.planned && status == 'planned') matches = true;
    else if (category == CatalogCategory.completed && status == 'completed') matches = true;
    else if (category == CatalogCategory.onHold && status == 'on_hold') matches = true;
    else if (category == CatalogCategory.dropped && status == 'dropped') matches = true;
    else if (category == CatalogCategory.rated && score > 0) matches = true;

    if (matches && item['anime'] != null) {
      try {
        final anime = ShikimoriAnime.fromJson(item['anime']);
        items.add(CatalogItem(anime: anime, userScore: score, status: status));
      } catch (_) {}
    }
  }
  
  // Сортировка для категории "Оценено"
  if (category == CatalogCategory.rated) {
    items.sort((a, b) => (b.userScore ?? 0).compareTo(a.userScore ?? 0));
  }
  
  return items;
});

// =====================================================================
// ГЛАВНЫЙ ЭКРАН КАТАЛОГА
// =====================================================================
class CatalogScreen extends StatefulHookConsumerWidget {
  const CatalogScreen({super.key});

  @override
  ConsumerState<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends ConsumerState<CatalogScreen> with SingleTickerProviderStateMixin {
  late AnimationController _bgAnimController;

  @override
  void initState() {
    super.initState();
    _bgAnimController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 30),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _bgAnimController.dispose();
    super.dispose();
  }

  Future<void> _onRefresh() async {
    ref.invalidate(catalogDataProvider);
    await ref.read(catalogDataProvider.future);
  }

  @override
  Widget build(BuildContext context) {
    final dataAsync = ref.watch(catalogDataProvider);
    final currentCat = ref.watch(catalogCategoryProvider);

    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 30.0, thickness: 15.0),
        child: Stack(
          children: [
            // 1. АНИМИРОВАННЫЙ ФОН
            _buildAmbientBackground(),

            // 2. ОСНОВНОЙ СКРОЛЛ
            CustomScrollView(
              physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
              slivers: [
                
                // 🔥 ВЕРХНИЙ НАВИГАЦИОННЫЙ БАР (НАСТОЯЩИЙ LIQUID GLASS)
                SliverAppBar(
                  expandedHeight: 160,
                  backgroundColor: _bgColor.withValues(alpha: 0.8),
                  pinned: true,
                  stretch: true,
                  flexibleSpace: FlexibleSpaceBar(
                    titlePadding: const EdgeInsets.only(left: 20, bottom: 84),
                    title: const Text(
                      'Мои Списки',
                      style: TextStyle(
                        color: Colors.white, 
                        fontWeight: FontWeight.w900, 
                        letterSpacing: -1.5,
                        fontSize: 28,
                      ),
                    ),
                    background: Container(color: Colors.transparent),
                  ),
                  bottom: PreferredSize(
                    preferredSize: const Size.fromHeight(70),
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 16, left: 16, right: 16),
                      child: SizedBox(
                        height: 56,
                        // Настоящий Liquid Glass pill-дизайн из источников библиотеки
                        child: GlassContainer(
                          quality: GlassQuality.premium,
                          shape: const LiquidRoundedSuperellipse(borderRadius: 28),
                          settings: const LiquidGlassSettings(
                            glassColor: Color(0x33FFFFFF), 
                            blur: 30.0,
                            chromaticAberration: 0.12,
                            specularSharpness: GlassSpecularSharpness.sharp,
                            refractiveIndex: 1.1,
                          ),
                          child: ListView.builder(
                            scrollDirection: Axis.horizontal,
                            physics: const BouncingScrollPhysics(),
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            itemCount: CatalogCategory.values.length,
                            itemBuilder: (context, index) {
                              final cat = CatalogCategory.values[index];
                              final isSelected = currentCat == cat;

                              return GestureDetector(
                                onTap: () => ref.read(catalogCategoryProvider.notifier).setCategory(cat),
                                child: AnimatedContainer(
                                  duration: const Duration(milliseconds: 400),
                                  curve: Curves.easeOutQuart,
                                  margin: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
                                  padding: const EdgeInsets.symmetric(horizontal: 18),
                                  decoration: BoxDecoration(
                                    color: isSelected ? _accentColor : Colors.transparent,
                                    borderRadius: BorderRadius.circular(22),
                                    boxShadow: isSelected ? [
                                      BoxShadow(
                                        color: _accentColor.withValues(alpha: 0.5),
                                        blurRadius: 20,
                                        spreadRadius: -4,
                                      )
                                    ] : [],
                                  ),
                                  alignment: Alignment.center,
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Icon(cat.icon, size: 14, color: isSelected ? Colors.white : Colors.white.withValues(alpha: 0.4)),
                                      const SizedBox(width: 8),
                                      Text(
                                        cat.label,
                                        style: TextStyle(
                                          color: isSelected ? Colors.white : Colors.white.withValues(alpha: 0.6),
                                          fontSize: 14,
                                          fontWeight: isSelected ? FontWeight.bold : FontWeight.w600,
                                          letterSpacing: 0.1,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                CupertinoSliverRefreshControl(
                  onRefresh: _onRefresh,
                  builder: _buildPremiumRefreshIndicator,
                ),

                // КОНТЕНТ (СЕТКА)
                dataAsync.when(
                  data: (items) {
                    if (items.isEmpty) {
                      return SliverFillRemaining(
                        child: Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(CupertinoIcons.square_stack_3d_up_fill, size: 64, color: Colors.white.withValues(alpha: 0.1)),
                              const SizedBox(height: 16),
                              Text(
                                'В категории "${currentCat.label}" пусто', 
                                style: TextStyle(color: Colors.white.withValues(alpha: 0.4), fontSize: 16, fontWeight: FontWeight.w500),
                              ),
                            ],
                          ),
                        ),
                      );
                    }
                    return SliverPadding(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20).copyWith(bottom: 140),
                      sliver: SliverGrid(
                        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: 2,
                          childAspectRatio: 0.58, 
                          crossAxisSpacing: 18,
                          mainAxisSpacing: 18,
                        ),
                        delegate: SliverChildBuilderDelegate(
                          (context, index) => _buildAnimeCard(items[index]),
                          childCount: items.length,
                        ),
                      ),
                    );
                  },
                  loading: () => const SliverFillRemaining(
                    child: Center(child: CupertinoActivityIndicator(radius: 16)),
                  ),
                  error: (e, _) {
                    if (e.toString().contains('auth_required')) {
                      return SliverFillRemaining(child: _buildAuthRequiredState());
                    }
                    return SliverFillRemaining(child: _buildGlobalError(e.toString()));
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  // =====================================================================
  // UI ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
  // =====================================================================

  Widget _buildAmbientBackground() {
    return Positioned.fill(
      child: ImageFiltered(
        imageFilter: ImageFilter.blur(sigmaX: 120, sigmaY: 120),
        child: AnimatedBuilder(
          animation: _bgAnimController,
          builder: (context, child) {
            final value = _bgAnimController.value;
            return Stack(
              children: [
                Positioned(
                  top: 150 + (40 * value), left: -50 + (20 * value),
                  child: Container(width: 400, height: 400, decoration: BoxDecoration(color: _accentColor.withValues(alpha: 0.15), shape: BoxShape.circle)),
                ),
                Positioned(
                  bottom: 100 - (50 * value), right: -80 + (30 * value),
                  child: Container(width: 450, height: 450, decoration: BoxDecoration(color: const Color(0xFF3B82F6).withValues(alpha: 0.12), shape: BoxShape.circle)),
                ),
              ],
            );
          }
        ),
      ),
    );
  }

  Widget _buildPremiumRefreshIndicator(BuildContext context, RefreshIndicatorMode refreshState, double pulledExtent, double refreshTriggerPullDistance, double refreshIndicatorExtent) {
    return Center(
      child: GlassContainer(
        // Исправлено: вместо LiquidCircle используем superellipse с радиусом 100
        shape: const LiquidRoundedSuperellipse(borderRadius: 100.0),
        settings: const LiquidGlassSettings(blur: 15.0, glassColor: Color(0x33FFFFFF)),
        child: const Padding(padding: EdgeInsets.all(12.0), child: CupertinoActivityIndicator(color: Colors.white)),
      ),
    );
  }

  String _getValidImageUrl(String? rawPath) {
    if (rawPath == null || rawPath.isEmpty) return '';
    if (rawPath.startsWith('http')) return rawPath;
    return 'https://shikimori.io$rawPath';
  }

  Widget _buildAnimeCard(CatalogItem item) {
    final anime = item.anime;
    final shape = const LiquidRoundedSuperellipse(borderRadius: 28.0);
    final validImageUrl = _getValidImageUrl(anime.imageUrl);
    
    String displayStatus;
    if (item.status != null) {
      switch (item.status) {
        case 'watching': displayStatus = 'Смотрю'; break;
        case 'rewatching': displayStatus = 'Пересматриваю'; break;
        case 'planned': displayStatus = 'В планах'; break;
        case 'completed': displayStatus = 'Просмотрено'; break;
        case 'on_hold': displayStatus = 'Отложено'; break;
        case 'dropped': displayStatus = 'Брошено'; break;
        default: displayStatus = 'В списке';
      }
    } else {
      displayStatus = anime.status == 'ongoing' ? 'Выходит' : 'Завершено';
    }

    return GestureDetector(
      onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: anime.id))),
      child: GlassContainer(
        quality: GlassQuality.premium,
        shape: shape,
        settings: _cardGlassSettings,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (validImageUrl.isNotEmpty)
              ClipPath(
                clipper: ShapeBorderClipper(shape: shape),
                child: CachedNetworkImage(
                  imageUrl: validImageUrl,
                  fit: BoxFit.cover,
                  errorWidget: (_, __, ___) => Container(color: const Color(0xFF1C1C1E), child: const Icon(CupertinoIcons.photo, color: Colors.grey)),
                ),
              ),
            
            Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter, end: Alignment.bottomCenter,
                  colors: [Colors.transparent, Colors.black.withValues(alpha: 0.4), Colors.black.withValues(alpha: 0.98)],
                  stops: const [0.4, 0.7, 1.0],
                ),
              ),
            ),

            Positioned(
              bottom: 16, left: 14, right: 14,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (item.userScore != null && item.userScore! > 0)
                    Container(
                      decoration: BoxDecoration(color: _accentColor, borderRadius: BorderRadius.circular(10)),
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(CupertinoIcons.star_fill, color: Colors.white, size: 10),
                          const SizedBox(width: 4),
                          Text('${item.userScore}', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 11)),
                        ],
                      ),
                    ),
                  
                  const SizedBox(height: 10),
                  Text(
                    anime.russian ?? anime.name ?? '',
                    maxLines: 2, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w800, height: 1.1),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    displayStatus,
                    style: TextStyle(color: _accentLight.withValues(alpha: 0.9), fontSize: 12, fontWeight: FontWeight.w700),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAuthRequiredState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32.0),
        child: GlassContainer(
          shape: const LiquidRoundedSuperellipse(borderRadius: 36),
          settings: const LiquidGlassSettings(glassColor: Color(0x4D000000), blur: 25.0),
          child: Padding(
            padding: const EdgeInsets.all(32.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(CupertinoIcons.lock_shield_fill, size: 80, color: _accentColor.withValues(alpha: 0.9)),
                const SizedBox(height: 24),
                const Text('Вход не выполнен', style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900, letterSpacing: -0.5)),
                const SizedBox(height: 12),
                Text('Авторизуйтесь, чтобы синхронизировать свои списки и оценки с Shikimori.', textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: 0.6), fontSize: 16, height: 1.4)),
                const SizedBox(height: 32),
                SizedBox(
                  width: double.infinity,
                  child: GlassButton(
                    onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const LoginScreen())),
                    quality: GlassQuality.premium,
                    shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                    settings: const LiquidGlassSettings(glassColor: _accentColor, blur: 15),
                    icon: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 20),
                      child: Center(child: Text('Войти в аккаунт', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold))),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildGlobalError(String err) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: GlassContainer(
          shape: const LiquidRoundedSuperellipse(borderRadius: 32),
          settings: const LiquidGlassSettings(glassColor: Color(0x33FF3B30), blur: 20.0),
          child: Padding(
            padding: const EdgeInsets.all(32.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Исправлено: вместо cloud_offline_fill используем cloud_bolt_fill
                Icon(CupertinoIcons.cloud_bolt_fill, color: _errorColor, size: 64),
                const SizedBox(height: 24),
                const Text('Ошибка соединения', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                Text(err, textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 15)),
                const SizedBox(height: 32),
                SizedBox(
                  width: double.infinity,
                  child: GlassButton(
                    onTap: _onRefresh,
                    shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                    settings: const LiquidGlassSettings(glassColor: Color(0x33FFFFFF), blur: 15),
                    icon: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 18),
                      child: Center(child: Text('Обновить', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold))),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}