import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../anime_detail/anime_detail_screen.dart';
import '../../models/shikimori_anime.dart';
import '../../widgets/smart_anime_poster.dart';
import '../../providers/user_provider.dart';
import '../../core/app_settings.dart';
import '../../widgets/animix_surface.dart';

// =====================================================================
// ЦВЕТОВАЯ ПАЛИТРА И СТИЛИ
// =====================================================================
Color get _accentColor => AppSettingsController.instance.accentColor;
Color get _accentLight =>
    Color.lerp(_accentColor, Colors.white, 0.24) ?? _accentColor;
const Color _bgColor = Color(0xFF050507); // Максимально глубокий темный фон
const Color _errorColor = Color(0xFFFF3B30);

// =====================================================================
// СОСТОЯНИЕ ФИЛЬТРОВ И ПОИСКА (Riverpod 2.0+ Notifier)
// =====================================================================

class HomeSearchQueryNotifier extends Notifier<String> {
  @override
  String build() => '';

  void updateState(String newState) {
    state = newState;
  }
}

final homeSearchQueryProvider =
    NotifierProvider<HomeSearchQueryNotifier, String>(
      HomeSearchQueryNotifier.new,
    );

class HomeSearchFiltersNotifier extends Notifier<Map<String, String>> {
  @override
  Map<String, String> build() => {
    'status': 'all',
    'kind': 'all',
    'order': 'popularity',
  };

  void updateState(Map<String, String> newState) {
    state = newState;
  }
}

final homeSearchFiltersProvider =
    NotifierProvider<HomeSearchFiltersNotifier, Map<String, String>>(
      HomeSearchFiltersNotifier.new,
    );

// Провайдер, который активируется, когда пользователь применяет фильтры или поиск
final homeSearchResultsProvider =
    FutureProvider.autoDispose<List<ShikimoriAnime>>((ref) async {
      final api = ref.read(apiClientProvider);
      final query = ref.watch(homeSearchQueryProvider);
      final filters = ref.watch(homeSearchFiltersProvider);

      final Map<String, dynamic> apiFilters = {};
      if (query.isNotEmpty) apiFilters['search'] = query;
      if (filters['status'] != 'all') apiFilters['status'] = filters['status'];
      if (filters['kind'] != 'all') apiFilters['kind'] = filters['kind'];
      if (filters['order'] != 'popularity') {
        apiFilters['order'] = filters['order'];
      }

      if (apiFilters['order'] == null && query.isEmpty) {
        apiFilters['order'] = 'popularity';
      }

      return api.getAnimes(limit: 30, filters: apiFilters);
    });

// =====================================================================
// ЕДИНЫЙ ПРОВАЙДЕР ДАННЫХ ДОМАШНЕГО ЭКРАНА (ЗАЩИТА ОТ 429)
// =====================================================================
class HomeData {
  final List<ShikimoriAnime> hero;
  final List<ShikimoriAnime> popular;
  final List<ShikimoriAnime> ongoing;
  final List<ShikimoriAnime> topRated;
  final List<ShikimoriAnime> announced;

  HomeData({
    required this.hero,
    required this.popular,
    required this.ongoing,
    required this.topRated,
    required this.announced,
  });
}

final homeDataProvider = FutureProvider.autoDispose<HomeData>((ref) async {
  final api = ref.read(apiClientProvider);

  // Строго последовательные запросы для обхода защиты Cloudflare/Shikimori 429 Too Many Requests
  final hero = await api.getAnimes(
    limit: 5,
    filters: {'order': 'ranked', 'status': 'ongoing'},
  );
  await Future.delayed(const Duration(milliseconds: 300));

  final popular = await api.getAnimes(
    limit: 15,
    filters: {'order': 'popularity'},
  );
  await Future.delayed(const Duration(milliseconds: 300));

  final ongoing = await api.getAnimes(
    limit: 15,
    filters: {'status': 'ongoing', 'order': 'popularity'},
  );
  await Future.delayed(const Duration(milliseconds: 300));

  final topRated = await api.getAnimes(limit: 15, filters: {'order': 'ranked'});
  await Future.delayed(const Duration(milliseconds: 300));

  final announced = await api.getAnimes(
    limit: 15,
    filters: {'status': 'anons', 'order': 'popularity'},
  );

  return HomeData(
    hero: hero,
    popular: popular,
    ongoing: ongoing,
    topRated: topRated,
    announced: announced,
  );
});

// =====================================================================
// ГЛАВНЫЙ ЭКРАН (HOME)
// =====================================================================
class HomeScreen extends StatefulHookConsumerWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  final ScrollController _scrollController = ScrollController();
  final PageController _heroPageController = PageController(
    viewportFraction: 0.92,
  );

  @override
  void dispose() {
    _scrollController.dispose();
    _heroPageController.dispose();
    super.dispose();
  }

  Future<void> _onRefresh() async {
    ref.invalidate(homeDataProvider);
    ref.invalidate(homeSearchResultsProvider);
    await ref.read(homeDataProvider.future);
  }

  void _showFilterDialog(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.7),
      isScrollControlled: true,
      useRootNavigator: true,
      builder: (ctx) => const _FilterSheet(),
    );
  }

  @override
  Widget build(BuildContext context) {
    final homeDataAsync = ref.watch(homeDataProvider);

    // 🔥 Если поиск или фильтры активны - переводим экран в режим "Каталог/Поиск"
    final searchQuery = ref.watch(homeSearchQueryProvider);
    final searchFilters = ref.watch(homeSearchFiltersProvider);
    final isSearching =
        searchQuery.isNotEmpty ||
        searchFilters.values.any((v) => v != 'all' && v != 'popularity');

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        children: [
          // One static backdrop keeps scrolling cheap on Windows and iOS.
          _buildAmbientBackground(),

          CustomScrollView(
            controller: _scrollController,
            physics: const BouncingScrollPhysics(
              parent: AlwaysScrollableScrollPhysics(),
            ),
            slivers: [
              CupertinoSliverRefreshControl(
                onRefresh: _onRefresh,
                builder: _buildPremiumRefreshIndicator,
              ),

              // ШАПКА ПРИЛОЖЕНИЯ (App Bar)
              SliverToBoxAdapter(
                child: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 12,
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              isSearching ? 'Поиск' : 'AniMix',
                              style: const TextStyle(
                                fontSize: 34,
                                fontWeight: FontWeight.w900,
                                color: Colors.white,
                                letterSpacing: -1.2,
                                height: 1.1,
                              ),
                            ),
                            Text(
                              isSearching
                                  ? 'Результаты запроса'
                                  : 'Открой новые миры',
                              style: TextStyle(
                                fontSize: 14,
                                color: _accentLight.withValues(alpha: 0.9),
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                        Row(
                          children: [
                            if (isSearching)
                              Padding(
                                padding: const EdgeInsets.only(right: 8),
                                child: AniMixSurface(
                                  onTap: () {
                                    // Сброс поиска через Notifier
                                    ref
                                        .read(homeSearchQueryProvider.notifier)
                                        .updateState('');
                                    ref
                                        .read(
                                          homeSearchFiltersProvider.notifier,
                                        )
                                        .updateState({
                                          'status': 'all',
                                          'kind': 'all',
                                          'order': 'popularity',
                                        });
                                  },
                                  radius: 16,
                                  padding: const EdgeInsets.all(11),
                                  child: const Icon(
                                    CupertinoIcons.clear,
                                    color: CupertinoColors.systemRed,
                                    size: 22,
                                  ),
                                ),
                              ),
                            AniMixSurface(
                              onTap: () => _showFilterDialog(context),
                              selected: isSearching,
                              radius: 16,
                              padding: const EdgeInsets.all(11),
                              child: const Icon(
                                CupertinoIcons.search,
                                color: Colors.white,
                                size: 22,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),

              // 🔥 РЕЖИМ ПОИСКА ИЛИ ГЛАВНЫЙ ЭКРАН
              if (isSearching)
                _buildSearchResultsGrid(ref.watch(homeSearchResultsProvider))
              else
                ...homeDataAsync.when<List<Widget>>(
                  data: (data) => [
                    SliverToBoxAdapter(child: _buildHeroSection(data.hero)),

                    SliverToBoxAdapter(
                      child: _buildSectionHeader(
                        'Популярное сейчас',
                        onSeeAll: () {
                          _applyFilterAndScroll(
                            status: 'all',
                            order: 'popularity',
                          );
                        },
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: _buildHorizontalList(data.popular, large: true),
                    ),

                    SliverToBoxAdapter(
                      child: _buildSectionHeader(
                        'Новые эпизоды',
                        onSeeAll: () {
                          _applyFilterAndScroll(
                            status: 'ongoing',
                            order: 'popularity',
                          );
                        },
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: _buildHorizontalList(data.ongoing, large: false),
                    ),

                    SliverToBoxAdapter(
                      child: _buildSectionHeader(
                        'Высокий рейтинг',
                        onSeeAll: () {
                          _applyFilterAndScroll(status: 'all', order: 'ranked');
                        },
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: _buildHorizontalList(data.topRated, large: false),
                    ),

                    SliverToBoxAdapter(
                      child: _buildSectionHeader(
                        'Скоро выйдут',
                        onSeeAll: () {
                          _applyFilterAndScroll(
                            status: 'anons',
                            order: 'popularity',
                          );
                        },
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: _buildHorizontalList(data.announced, large: false),
                    ),

                    const SliverToBoxAdapter(
                      child: SizedBox(height: 120),
                    ), // Отступ под GlassBottomBar
                  ],
                  loading: () => [
                    const SliverFillRemaining(
                      child: Center(
                        child: CupertinoActivityIndicator(radius: 16),
                      ),
                    ),
                  ],
                  error: (e, _) => [
                    SliverFillRemaining(
                      child: Center(child: _buildGlobalError(e.toString())),
                    ),
                  ],
                ),
            ],
          ),
        ],
      ),
    );
  }

  // Метод для превращения кнопок "Все" в рабочий инструмент
  void _applyFilterAndScroll({required String status, required String order}) {
    ref.read(homeSearchQueryProvider.notifier).updateState('');
    ref.read(homeSearchFiltersProvider.notifier).updateState({
      'status': status,
      'kind': 'all',
      'order': order,
    });
    _scrollController.animateTo(
      0,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
    );
  }

  // =====================================================================
  // КОМПОНЕНТЫ ГЛАВНОГО ЭКРАНА
  // =====================================================================

  Widget _buildAmbientBackground() {
    return Positioned.fill(
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              Color.alphaBlend(
                _accentColor.withValues(alpha: 0.10),
                const Color(0xFF111117),
              ),
              _bgColor,
              _bgColor,
            ],
            stops: const [0, 0.34, 1],
          ),
        ),
      ),
    );
  }

  Widget _buildPremiumRefreshIndicator(
    BuildContext context,
    RefreshIndicatorMode refreshState,
    double pulledExtent,
    double refreshTriggerPullDistance,
    double refreshIndicatorExtent,
  ) {
    return Center(
      child: const AniMixSurface(
        radius: 50,
        padding: EdgeInsets.all(10),
        child: CupertinoActivityIndicator(color: Colors.white),
      ),
    );
  }

  Widget _buildSectionHeader(String title, {required VoidCallback onSeeAll}) {
    return Padding(
      padding: const EdgeInsets.only(left: 20, right: 16, top: 32, bottom: 16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.bold,
              color: Colors.white,
              letterSpacing: -0.5,
            ),
          ),
          GestureDetector(
            onTap: onSeeAll,
            child: Text(
              'Все',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: _accentLight.withValues(alpha: 0.8),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeroSection(List<ShikimoriAnime> animes) {
    if (animes.isEmpty) return const SizedBox.shrink();

    return SizedBox(
      height: 240,
      child: PageView.builder(
        controller: _heroPageController,
        physics: const BouncingScrollPhysics(),
        itemCount: animes.length,
        itemBuilder: (context, index) {
          return _buildHeroCard(animes[index]);
        },
      ),
    );
  }

  // Умный парсер URL для обложек (защита от битых ссылок)
  String _getValidImageUrl(String? rawPath) {
    if (rawPath == null || rawPath.isEmpty) return '';
    if (rawPath.startsWith('http')) return rawPath;
    return 'https://shikimori.io$rawPath';
  }

  Widget _buildHeroCard(ShikimoriAnime anime) {
    final shape = const LiquidRoundedSuperellipse(borderRadius: 32.0);
    final String validImageUrl = _getValidImageUrl(anime.imageUrl);

    return GestureDetector(
      onTap: () => Navigator.push(
        context,
        CupertinoPageRoute(
          builder: (_) => AnimeDetailScreen(animeId: anime.id),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 8.0),
        child: ClipPath(
          clipper: ShapeBorderClipper(shape: shape),
          child: Stack(
            fit: StackFit.expand,
            children: [
              ClipPath(
                clipper: ShapeBorderClipper(shape: shape),
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: validImageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                  fit: BoxFit.cover,
                  alignment: Alignment.topCenter,
                ),
              ),
              // Премиальный градиент
              Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Colors.transparent,
                      Colors.black.withValues(alpha: 0.4),
                      Colors.black.withValues(alpha: 0.9),
                    ],
                    stops: const [0.3, 0.6, 1.0],
                  ),
                ),
              ),
              Positioned(
                bottom: 20,
                left: 20,
                right: 20,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: _accentColor.withValues(alpha: 0.8),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        anime.status == 'ongoing'
                            ? 'НОВЫЕ ЭПИЗОДЫ'
                            : 'ТОП НЕДЕЛИ',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 0.5,
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      anime.russian ?? anime.name ?? '',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.w900,
                        height: 1.1,
                        letterSpacing: -0.5,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Text(
                          'АНИМЕ',
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.7),
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (anime.score != null &&
                            anime.score.toString() != '0.0' &&
                            anime.score.toString() != '0') ...[
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            child: Container(
                              width: 4,
                              height: 4,
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: 0.5),
                                shape: BoxShape.circle,
                              ),
                            ),
                          ),
                          const Icon(
                            CupertinoIcons.star_fill,
                            color: Color(0xFFFBBF24),
                            size: 14,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            anime.score!.toString(),
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHorizontalList(
    List<ShikimoriAnime> animes, {
    required bool large,
  }) {
    if (animes.isEmpty) {
      return SizedBox(
        height: large ? 260 : 200,
        child: Center(
          child: Text(
            'Нет данных',
            style: TextStyle(color: Colors.white.withValues(alpha: 0.5)),
          ),
        ),
      );
    }

    return SizedBox(
      height: large ? 260 : 200,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: animes.length,
        itemBuilder: (context, index) =>
            _buildAnimeCard(animes[index], large: large),
      ),
    );
  }

  // 🔥 СЕТКА ДЛЯ РЕЗУЛЬТАТОВ ПОИСКА И ФИЛЬТРОВ
  Widget _buildSearchResultsGrid(AsyncValue<List<ShikimoriAnime>> asyncData) {
    return asyncData.when(
      data: (animes) {
        if (animes.isEmpty) {
          return SliverFillRemaining(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    CupertinoIcons.search,
                    size: 48,
                    color: Colors.white.withValues(alpha: 0.3),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Ничего не найдено',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.7),
                      fontSize: 16,
                    ),
                  ),
                ],
              ),
            ),
          );
        }
        return SliverLayoutBuilder(
          builder: (context, constraints) {
            final preference = AppSettingsController.instance.contentLayout;
            final useCards =
                preference == AniMixContentLayout.cards ||
                (preference == AniMixContentLayout.automatic &&
                    constraints.crossAxisExtent >= 560);
            return SliverPadding(
              padding: const EdgeInsets.symmetric(
                horizontal: 20,
                vertical: 16,
              ).copyWith(bottom: 120),
              sliver: useCards
                  ? SliverGrid(
                      gridDelegate:
                          const SliverGridDelegateWithMaxCrossAxisExtent(
                            maxCrossAxisExtent: 230,
                            childAspectRatio: 0.62,
                            crossAxisSpacing: 16,
                            mainAxisSpacing: 16,
                          ),
                      delegate: SliverChildBuilderDelegate(
                        (context, index) => _buildAnimeCard(
                          animes[index],
                          large: true,
                          isGrid: true,
                        ),
                        childCount: animes.length,
                      ),
                    )
                  : SliverList.separated(
                      itemCount: animes.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 12),
                      itemBuilder: (_, index) =>
                          _buildAnimeSearchListTile(animes[index]),
                    ),
            );
          },
        );
      },
      loading: () => const SliverFillRemaining(
        child: Center(child: CupertinoActivityIndicator(radius: 16)),
      ),
      error: (e, _) => SliverFillRemaining(
        child: Center(child: _buildGlobalError(e.toString())),
      ),
    );
  }

  Widget _buildAnimeCard(
    ShikimoriAnime anime, {
    required bool large,
    bool isGrid = false,
  }) {
    final score = double.tryParse(anime.score?.toString() ?? '0') ?? 0.0;
    final displayStatus = anime.status == 'ongoing'
        ? 'Выходит'
        : (anime.status == 'released' ? 'Вышло' : 'Анонс');
    final width = large ? 160.0 : 130.0;
    final shape = const LiquidRoundedSuperellipse(borderRadius: 24.0);
    final validImageUrl = _getValidImageUrl(anime.imageUrl);

    return GestureDetector(
      onTap: () => Navigator.push(
        context,
        CupertinoPageRoute(
          builder: (_) => AnimeDetailScreen(animeId: anime.id),
        ),
      ),
      child: Container(
        width: isGrid
            ? null
            : width, // В режиме сетки ширина определяется GridDelegate
        margin: isGrid ? null : const EdgeInsets.only(right: 16),
        child: ClipPath(
          clipper: ShapeBorderClipper(shape: shape),
          child: Stack(
            fit: StackFit.expand,
            children: [
              ClipPath(
                clipper: ShapeBorderClipper(shape: shape),
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: validImageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                  fit: BoxFit.cover,
                ),
              ),

              Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Colors.transparent,
                      Colors.black.withValues(alpha: 0.3),
                      Colors.black.withValues(alpha: 0.9),
                    ],
                    stops: const [0.4, 0.7, 1.0],
                  ),
                ),
              ),

              Positioned(
                bottom: 16,
                left: 12,
                right: 12,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (score > 0)
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.6),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 3,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              CupertinoIcons.star_fill,
                              color: Color(0xFFFBBF24),
                              size: 10,
                            ),
                            const SizedBox(width: 4),
                            Text(
                              score.toStringAsFixed(1),
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.bold,
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),

                    const SizedBox(height: 6),
                    Text(
                      anime.russian ?? anime.name ?? '',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        height: 1.1,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      displayStatus,
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.7),
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAnimeSearchListTile(ShikimoriAnime anime) {
    final score = double.tryParse(anime.score?.toString() ?? '0') ?? 0;
    return AniMixSurface(
      onTap: () => Navigator.push(
        context,
        CupertinoPageRoute(
          builder: (_) => AnimeDetailScreen(animeId: anime.id),
        ),
      ),
      padding: const EdgeInsets.all(10),
      radius: 20,
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(13),
            child: SizedBox(
              width: 62,
              height: 88,
              child: SmartAnimePoster(
                animeId: anime.id,
                imageUrl: _getValidImageUrl(anime.imageUrl),
                title: anime.name ?? '',
                russianTitle: anime.russian,
                fit: BoxFit.cover,
              ),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  anime.russian ?? anime.name ?? 'Без названия',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 7),
                Text(
                  anime.status == 'ongoing' ? 'Выходит' : 'Вышло',
                  style: TextStyle(color: _accentLight),
                ),
              ],
            ),
          ),
          if (score > 0) ...[
            const Icon(CupertinoIcons.star_fill, size: 13, color: Colors.amber),
            const SizedBox(width: 5),
            Text(score.toStringAsFixed(1)),
          ],
          const SizedBox(width: 8),
          const Icon(CupertinoIcons.chevron_right, color: Colors.white30),
        ],
      ),
    );
  }

  Widget _buildGlobalError(String err) {
    return Padding(
      padding: const EdgeInsets.all(24.0),
      child: AniMixSurface(
        radius: 24,
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              CupertinoIcons.exclamationmark_triangle_fill,
              color: _errorColor.withValues(alpha: 0.8),
              size: 48,
            ),
            const SizedBox(height: 16),
            const Text(
              'Ошибка загрузки данных',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              err,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.6),
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _onRefresh,
              icon: const Icon(CupertinoIcons.refresh),
              label: const Text('Повторить'),
            ),
          ],
        ),
      ),
    );
  }
}

// =====================================================================
// ДИАЛОГ ФИЛЬТРОВ И ПОИСКА
// =====================================================================
class _FilterSheet extends StatefulHookConsumerWidget {
  const _FilterSheet();

  @override
  ConsumerState<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends ConsumerState<_FilterSheet> {
  late String _selectedStatus;
  late String _selectedKind;
  late String _selectedOrder;
  late TextEditingController _searchController;

  @override
  void initState() {
    super.initState();
    // Инициализируем локальное состояние из глобального провайдера
    final currentFilters = ref.read(homeSearchFiltersProvider);
    _selectedStatus = currentFilters['status'] ?? 'all';
    _selectedKind = currentFilters['kind'] ?? 'all';
    _selectedOrder = currentFilters['order'] ?? 'popularity';
    _searchController = TextEditingController(
      text: ref.read(homeSearchQueryProvider),
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(
      context,
    ).viewInsets.bottom; // Для клавиатуры

    return Material(
      type: MaterialType.transparency,
      child: Container(
        height: MediaQuery.of(context).size.height * 0.85,
        margin: const EdgeInsets.only(top: 20),
        child: DecoratedBox(
          decoration: const BoxDecoration(
            color: Color(0xFF111116),
            borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
          ),
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 16, bottom: 8),
                child: Center(
                  child: Container(
                    width: 48,
                    height: 5,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                ),
              ),
              Expanded(
                child: ListView(
                  physics: const BouncingScrollPhysics(),
                  padding: EdgeInsets.fromLTRB(24, 16, 24, bottomInset + 32),
                  children: [
                    const Text(
                      'Поиск и фильтры',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 28,
                        fontWeight: FontWeight.bold,
                        letterSpacing: -1,
                      ),
                    ),
                    const SizedBox(height: 24),

                    AniMixSurface(
                      radius: 18,
                      child: CupertinoTextField(
                        controller: _searchController,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 16,
                        ),
                        placeholder: 'Название аниме...',
                        placeholderStyle: TextStyle(
                          color: Colors.white.withValues(alpha: 0.4),
                        ),
                        style: const TextStyle(color: Colors.white),
                        prefix: Padding(
                          padding: const EdgeInsets.only(left: 16),
                          child: Icon(
                            CupertinoIcons.search,
                            color: Colors.white.withValues(alpha: 0.5),
                          ),
                        ),
                        decoration: null,
                      ),
                    ),

                    const SizedBox(height: 32),
                    _buildSectionTitle('Статус'),
                    _buildChipGroup(
                      items: {
                        'all': 'Любой',
                        'ongoing': 'Онгоинг',
                        'released': 'Вышло',
                        'anons': 'Анонс',
                      },
                      selectedValue: _selectedStatus,
                      onChanged: (v) => setState(() => _selectedStatus = v),
                    ),

                    const SizedBox(height: 32),
                    _buildSectionTitle('Тип'),
                    _buildChipGroup(
                      items: {
                        'all': 'Все',
                        'tv': 'ТВ Сериал',
                        'movie': 'Фильм',
                        'ova': 'OVA',
                        'ona': 'ONA',
                      },
                      selectedValue: _selectedKind,
                      onChanged: (v) => setState(() => _selectedKind = v),
                    ),

                    const SizedBox(height: 32),
                    _buildSectionTitle('Сортировка'),
                    _buildChipGroup(
                      items: {
                        'popularity': 'Популярность',
                        'ranked': 'Рейтинг',
                        'aired_on': 'Дата выхода',
                        'name': 'Алфавит',
                      },
                      selectedValue: _selectedOrder,
                      onChanged: (v) => setState(() => _selectedOrder = v),
                    ),

                    const SizedBox(height: 48),

                    FilledButton(
                      onPressed: () {
                        // 🔥 ПРИМЕНЯЕМ ФИЛЬТРЫ ЧЕРЕЗ NOTIFIER
                        ref
                            .read(homeSearchQueryProvider.notifier)
                            .updateState(_searchController.text.trim());
                        ref
                            .read(homeSearchFiltersProvider.notifier)
                            .updateState({
                              'status': _selectedStatus,
                              'kind': _selectedKind,
                              'order': _selectedOrder,
                            });
                        Navigator.pop(context);
                      },
                      child: const Padding(
                        padding: EdgeInsets.symmetric(vertical: 16),
                        child: Center(
                          child: Text(
                            'Применить и показать',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.5,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Text(
        title,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 18,
          fontWeight: FontWeight.bold,
          letterSpacing: -0.5,
        ),
      ),
    );
  }

  Widget _buildChipGroup({
    required Map<String, String> items,
    required String selectedValue,
    required ValueChanged<String> onChanged,
  }) {
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: items.entries.map((entry) {
        final isSelected = entry.key == selectedValue;
        return GestureDetector(
          onTap: () => onChanged(entry.key),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            decoration: BoxDecoration(
              color: isSelected
                  ? _accentColor
                  : Colors.white.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: isSelected
                    ? _accentColor.withValues(alpha: 0.5)
                    : Colors.transparent,
                width: 1,
              ),
            ),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                entry.value,
                style: TextStyle(
                  color: isSelected
                      ? Colors.white
                      : Colors.white.withValues(alpha: 0.7),
                  fontSize: 14,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}
