import 'dart:async';
import 'dart:math' as math;

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/app_settings.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../profile/profile_screen.dart';
import '../recommendation/recommendation_screen.dart';

class HomeData {
  const HomeData({
    required this.hero,
    required this.popular,
    required this.ongoing,
    required this.topRated,
    required this.announced,
  });

  final List<ShikimoriAnime> hero;
  final List<ShikimoriAnime> popular;
  final List<ShikimoriAnime> ongoing;
  final List<ShikimoriAnime> topRated;
  final List<ShikimoriAnime> announced;
}

final homeDataProvider = FutureProvider.autoDispose<HomeData>((ref) async {
  final api = ref.read(apiClientProvider);
  final results = await Future.wait<List<ShikimoriAnime>>([
    api.getAnimes(
      limit: 6,
      filters: const {'order': 'ranked', 'status': 'ongoing'},
    ),
    api.getAnimes(limit: 30, filters: const {'order': 'popularity'}),
    api.getAnimes(
      limit: 16,
      filters: const {'status': 'ongoing', 'order': 'popularity'},
    ),
    api.getAnimes(limit: 16, filters: const {'order': 'ranked'}),
    api.getAnimes(
      limit: 16,
      filters: const {'status': 'anons', 'order': 'popularity'},
    ),
  ]);
  return HomeData(
    hero: results[0],
    popular: results[1],
    ongoing: results[2],
    topRated: results[3],
    announced: results[4],
  );
});

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  final _scrollController = ScrollController();
  final List<ShikimoriAnime> _allAnime = [];
  var _catalogPage = 0;
  var _loadingMore = false;
  var _catalogHasMore = true;
  Object? _catalogError;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) => _initializeCatalog());
  }

  @override
  void dispose() {
    _scrollController
      ..removeListener(_onScroll)
      ..dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients ||
        _scrollController.position.extentAfter > 900) {
      return;
    }
    _loadMore();
  }

  Future<void> _initializeCatalog() async {
    try {
      final home = await ref.read(homeDataProvider.future);
      if (!mounted) return;
      setState(() {
        _allAnime
          ..clear()
          ..addAll(home.popular);
        _catalogPage = 1;
        _catalogHasMore = home.popular.length == 30;
        _catalogError = null;
      });
    } catch (error) {
      if (mounted) {
        setState(() => _catalogError = error);
      }
    }
  }

  Future<void> _loadMore({bool reset = false}) async {
    if (_loadingMore || (!reset && !_catalogHasMore)) return;
    if (reset) {
      setState(() {
        _catalogPage = 0;
        _catalogHasMore = true;
        _catalogError = null;
        _allAnime.clear();
      });
    }
    setState(() {
      _loadingMore = true;
      _catalogError = null;
    });
    final nextPage = _catalogPage + 1;
    try {
      final items = await ref
          .read(apiClientProvider)
          .getAnimes(
            page: nextPage,
            limit: 30,
            filters: const {'order': 'popularity'},
          );
      if (!mounted) return;
      setState(() {
        _catalogPage = nextPage;
        _catalogHasMore = items.length == 30;
        for (final anime in items) {
          if (!_allAnime.any((existing) => existing.id == anime.id)) {
            _allAnime.add(anime);
          }
        }
      });
    } catch (error) {
      if (mounted) setState(() => _catalogError = error);
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _refresh() async {
    ref.invalidate(homeDataProvider);
    ref.invalidate(currentUserProvider);
    await _initializeCatalog();
  }

  void _retryHome() {
    ref.invalidate(homeDataProvider);
    _initializeCatalog();
  }

  @override
  Widget build(BuildContext context) {
    final data = ref.watch(homeDataProvider);
    final user = ref.watch(currentUserProvider).value;
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        bottom: false,
        child: RefreshIndicator.adaptive(
          onRefresh: _refresh,
          child: CustomScrollView(
            controller: _scrollController,
            physics: const BouncingScrollPhysics(
              parent: AlwaysScrollableScrollPhysics(),
            ),
            slivers: [
              SliverToBoxAdapter(child: _DashboardHeader(user: user)),
              ...data.when(
                loading: () => const [
                  SliverFillRemaining(
                    child: Center(
                      child: CupertinoActivityIndicator(radius: 15),
                    ),
                  ),
                ],
                error: (_, _) => [
                  SliverFillRemaining(
                    child: AniMixEmptyState(
                      icon: CupertinoIcons.wifi_exclamationmark,
                      title: 'Не удалось загрузить главную',
                      message: 'Проверьте подключение и обновите страницу.',
                      actionLabel: 'Повторить',
                      onAction: _retryHome,
                    ),
                  ),
                ],
                data: (home) => _content(context, home),
              ),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _content(BuildContext context, HomeData data) => [
    if (data.hero.isNotEmpty)
      SliverToBoxAdapter(child: _HeroCarousel(items: data.hero)),
    SliverToBoxAdapter(
      child: _DiscoveryStrip(
        best: data.topRated.firstOrNull ?? data.popular.firstOrNull,
        random: data.popular.isEmpty
            ? null
            : data.popular[math.Random().nextInt(data.popular.length)],
      ),
    ),
    if (data.ongoing.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Сейчас на экранах',
          subtitle: 'Онгоинги',
          icon: CupertinoIcons.tv_fill,
          tint: CupertinoColors.systemOrange,
          items: data.ongoing,
        ),
      ),
    if (data.announced.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Новинки сезона',
          subtitle: 'Свежее',
          icon: CupertinoIcons.sparkles,
          tint: CupertinoColors.systemGreen,
          items: data.announced,
        ),
      ),
    if (data.popular.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Популярное',
          subtitle: 'Топ на Shikimori',
          icon: CupertinoIcons.flame_fill,
          tint: CupertinoColors.systemPink,
          items: data.popular,
        ),
      ),
    if (data.topRated.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Топ по рейтингу',
          subtitle: 'Лучшие оценки',
          icon: CupertinoIcons.star_fill,
          tint: const Color(0xFFFFC638),
          items: data.topRated,
        ),
      ),
    if (_allAnime.isNotEmpty || _loadingMore || _catalogError != null)
      _AllAnimeGrid(
        items: _allAnime,
        loadingMore: _loadingMore,
        error: _catalogError,
        hasMore: _catalogHasMore,
        onRetry: _loadMore,
      ),
    const SliverToBoxAdapter(child: SizedBox(height: 42)),
  ];
}

class _DashboardHeader extends StatelessWidget {
  const _DashboardHeader({required this.user});

  final dynamic user;

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 1180),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 18),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Добро пожаловать',
                    style: TextStyle(
                      color: AniMixTheme.subtleText,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    user?.nickname?.toString().isNotEmpty == true
                        ? user.nickname
                        : 'в AniMix',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 31,
                      height: 1.05,
                      letterSpacing: -.9,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
            AniMixIconButton(
              icon: CupertinoIcons.search,
              tooltip: 'Поиск',
              onPressed: () => showModalBottomSheet<void>(
                context: context,
                useSafeArea: true,
                isScrollControlled: true,
                backgroundColor: Theme.of(context).scaffoldBackgroundColor,
                builder: (_) => const FractionallySizedBox(
                  heightFactor: .94,
                  child: _HomeSearchSheet(),
                ),
              ),
            ),
            const SizedBox(width: 10),
            GestureDetector(
              onTap: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(builder: (_) => const ProfileScreen()),
              ),
              child: Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surface,
                  shape: BoxShape.circle,
                  border: Border.all(color: const Color(0x66FFFFFF)),
                ),
                clipBehavior: Clip.antiAlias,
                child: user?.imageUrl?.toString().isNotEmpty == true
                    ? CachedNetworkImage(
                        imageUrl: user.imageUrl,
                        fit: BoxFit.cover,
                        errorWidget: (_, _, _) => const Icon(
                          CupertinoIcons.person_crop_circle_fill,
                          color: Colors.white54,
                        ),
                      )
                    : const Icon(
                        CupertinoIcons.person_crop_circle_fill,
                        color: Colors.white54,
                      ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _HeroCarousel extends StatefulWidget {
  const _HeroCarousel({required this.items});
  final List<ShikimoriAnime> items;

  @override
  State<_HeroCarousel> createState() => _HeroCarouselState();
}

class _HeroCarouselState extends State<_HeroCarousel> {
  final _controller = PageController(viewportFraction: .92);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final desktop = constraints.maxWidth >= 760;
      return SizedBox(
        height: desktop ? 350 : 228,
        child: PageView.builder(
          controller: _controller,
          physics: const BouncingScrollPhysics(),
          itemCount: widget.items.length,
          itemBuilder: (context, index) => Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6),
                child: _HeroCard(anime: widget.items[index]),
              ),
            ),
          ),
        ),
      );
    },
  );
}

class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.anime});
  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: () => _openAnime(context, anime.id),
    child: Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        boxShadow: const [
          BoxShadow(
            color: Color(0x47000000),
            blurRadius: 22,
            offset: Offset(0, 9),
          ),
        ],
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final imageHeight = constraints.maxHeight * .64;
          return Column(
            children: [
              SizedBox(
                width: double.infinity,
                height: imageHeight,
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: anime.imageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                  alignment: Alignment.topCenter,
                ),
              ),
              Expanded(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        const Color(0xF5000000),
                        Theme.of(
                          context,
                        ).colorScheme.primary.withValues(alpha: .52),
                      ],
                    ),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              '◉  СЕЙЧАС ВЫХОДИТ',
                              style: TextStyle(
                                color: Colors.white70,
                                fontSize: 10,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            const SizedBox(height: 5),
                            Text(
                              anime.russian ?? anime.name ?? 'Без названия',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 18,
                                height: 1.05,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ],
                        ),
                      ),
                      if ((anime.score ?? 0) > 0)
                        AniMixMetadataPill(
                          label: '★ ${anime.score!.toStringAsFixed(1)}',
                        ),
                      const SizedBox(width: 8),
                      const Icon(
                        CupertinoIcons.chevron_right,
                        color: Colors.white,
                        size: 16,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    ),
  );
}

class _DiscoveryStrip extends StatelessWidget {
  const _DiscoveryStrip({required this.best, required this.random});
  final ShikimoriAnime? best;
  final ShikimoriAnime? random;

  @override
  Widget build(BuildContext context) {
    final items = [
      _Shortcut(
        title: 'Каталог',
        subtitle: 'Фильтры и поиск',
        icon: CupertinoIcons.slider_horizontal_3,
        colors: const [Color(0x3D3B82F6), Color(0x1F06B6D4)],
        onTap: () => showModalBottomSheet<void>(
          context: context,
          useSafeArea: true,
          isScrollControlled: true,
          backgroundColor: Theme.of(context).scaffoldBackgroundColor,
          builder: (_) => const FractionallySizedBox(
            heightFactor: .94,
            child: _HomeSearchSheet(),
          ),
        ),
      ),
      _Shortcut(
        title: 'Для вас',
        subtitle: 'Умная подборка',
        icon: CupertinoIcons.sparkles,
        colors: const [Color(0x3DF43F5E), Color(0x1F8B5CF6)],
        onTap: () => Navigator.push(
          context,
          CupertinoPageRoute<void>(
            builder: (_) => const RecommendationScreen(),
          ),
        ),
      ),
      if (best != null)
        _Shortcut(
          title: 'Лучшее',
          subtitle: 'Высокий рейтинг',
          icon: CupertinoIcons.rosette,
          colors: const [Color(0x40FBBF24), Color(0x1FF97316)],
          onTap: () => _openAnime(context, best!.id),
        ),
      if (random != null)
        _Shortcut(
          title: 'Мне повезёт',
          subtitle: 'Случайный тайтл',
          icon: CupertinoIcons.shuffle,
          colors: const [Color(0x380ABF78), Color(0x1F06B6D4)],
          onTap: () => _openAnime(context, random!.id),
        ),
    ];
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1180),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 22, 20, 2),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 760 ? 4 : 2;
              final width =
                  (constraints.maxWidth - (columns - 1) * 10) / columns;
              return Wrap(
                spacing: 10,
                runSpacing: 10,
                children: items
                    .map((item) => SizedBox(width: width, child: item))
                    .toList(),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _Shortcut extends StatelessWidget {
  const _Shortcut({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.colors,
    required this.onTap,
  });
  final String title;
  final String subtitle;
  final IconData icon;
  final List<Color> colors;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: Colors.transparent,
    child: InkWell(
      borderRadius: BorderRadius.circular(20),
      onTap: onTap,
      child: Ink(
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(
          gradient: LinearGradient(colors: colors),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: AniMixTheme.divider),
        ),
        child: Row(
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: const BoxDecoration(
                color: Color(0x24FFFFFF),
                shape: BoxShape.circle,
              ),
              child: Icon(icon, size: 18),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AniMixTheme.subtleText,
                      fontSize: 11,
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

class _AnimeSection extends StatelessWidget {
  const _AnimeSection({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.tint,
    required this.items,
  });
  final String title;
  final String subtitle;
  final IconData icon;
  final Color tint;
  final List<ShikimoriAnime> items;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 28),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1180),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: AniMixSectionHeader(
                title: title,
                subtitle: subtitle,
                icon: icon,
              ),
            ),
          ),
        ),
        const SizedBox(height: 13),
        SizedBox(
          height: 282,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 5),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: (context, index) => _PosterCard(anime: items[index]),
          ),
        ),
      ],
    ),
  );
}

class _PosterCard extends StatelessWidget {
  const _PosterCard({required this.anime, this.flexible = false});
  final ShikimoriAnime anime;
  final bool flexible;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: () => _openAnime(context, anime.id),
    child: SizedBox(
      width: flexible ? null : 150,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(22),
                    child: SmartAnimePoster(
                      animeId: anime.id,
                      imageUrl: anime.imageUrl,
                      title: anime.name ?? '',
                      russianTitle: anime.russian,
                    ),
                  ),
                ),
                if ((anime.score ?? 0) > 0)
                  Positioned(
                    top: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 7,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: const Color(0xB8000000),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        '★ ${anime.score!.toStringAsFixed(1)}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Text(
            anime.russian ?? anime.name ?? 'Без названия',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 14,
              height: 1.15,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            anime.status == 'ongoing' ? 'TV · выходит' : 'TV',
            style: const TextStyle(color: AniMixTheme.subtleText, fontSize: 10),
          ),
        ],
      ),
    ),
  );
}

class _AllAnimeGrid extends StatelessWidget {
  const _AllAnimeGrid({
    required this.items,
    required this.loadingMore,
    required this.error,
    required this.hasMore,
    required this.onRetry,
  });
  final List<ShikimoriAnime> items;
  final bool loadingMore;
  final Object? error;
  final bool hasMore;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final settings = AppSettingsController.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => SliverLayoutBuilder(
        builder: (context, constraints) {
          final list =
              settings.contentLayout == AniMixContentLayout.list ||
              (settings.contentLayout == AniMixContentLayout.automatic &&
                  constraints.crossAxisExtent < 620);
          return SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 28, 20, 0),
            sliver: SliverMainAxisGroup(
              slivers: [
                SliverToBoxAdapter(
                  child: Row(
                    children: [
                      Expanded(
                        child: AniMixSectionHeader(
                          title: 'Все аниме',
                          subtitle: '${items.length} загружено',
                          icon: CupertinoIcons.rectangle_stack_fill,
                        ),
                      ),
                      _LayoutSwitch(
                        list: list,
                        onChanged: settings.setContentLayout,
                      ),
                    ],
                  ),
                ),
                const SliverToBoxAdapter(child: SizedBox(height: 14)),
                if (list)
                  SliverList.separated(
                    itemCount: items.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 9),
                    itemBuilder: (context, index) =>
                        _AnimeListRow(items[index]),
                  )
                else
                  SliverGrid.builder(
                    itemCount: items.length,
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 205,
                          childAspectRatio: .58,
                          crossAxisSpacing: 14,
                          mainAxisSpacing: 20,
                        ),
                    itemBuilder: (context, index) =>
                        _PosterCard(anime: items[index], flexible: true),
                  ),
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    child: error != null
                        ? Center(
                            child: OutlinedButton.icon(
                              onPressed: onRetry,
                              icon: const Icon(CupertinoIcons.refresh),
                              label: const Text('Повторить загрузку'),
                            ),
                          )
                        : loadingMore
                        ? const Center(child: CupertinoActivityIndicator())
                        : hasMore
                        ? const SizedBox(height: 36)
                        : const Center(
                            child: Text(
                              'Вы дошли до конца каталога',
                              style: TextStyle(
                                color: AniMixTheme.subtleText,
                                fontSize: 12,
                              ),
                            ),
                          ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _LayoutSwitch extends StatelessWidget {
  const _LayoutSwitch({required this.list, required this.onChanged});
  final bool list;
  final ValueChanged<AniMixContentLayout> onChanged;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(4),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: AniMixTheme.divider),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _LayoutButton(
          icon: CupertinoIcons.square_grid_2x2,
          selected: !list,
          tooltip: 'Карточки',
          onTap: () => onChanged(AniMixContentLayout.cards),
        ),
        _LayoutButton(
          icon: CupertinoIcons.list_bullet,
          selected: list,
          tooltip: 'Список',
          onTap: () => onChanged(AniMixContentLayout.list),
        ),
      ],
    ),
  );
}

class _LayoutButton extends StatelessWidget {
  const _LayoutButton({
    required this.icon,
    required this.selected,
    required this.tooltip,
    required this.onTap,
  });
  final IconData icon;
  final bool selected;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: tooltip,
    child: InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        width: 38,
        height: 34,
        decoration: BoxDecoration(
          color: selected
              ? Theme.of(context).colorScheme.primary.withValues(alpha: .22)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Icon(
          icon,
          size: 17,
          color: selected
              ? Theme.of(context).colorScheme.primary
              : AniMixTheme.subtleText,
        ),
      ),
    ),
  );
}

class _AnimeListRow extends StatelessWidget {
  const _AnimeListRow(this.anime);
  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) => AniMixSurface(
    radius: 20,
    onTap: () => _openAnime(context, anime.id),
    padding: const EdgeInsets.all(10),
    child: Row(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: SizedBox(
            width: 64,
            height: 94,
            child: SmartAnimePoster(
              animeId: anime.id,
              imageUrl: anime.imageUrl,
              title: anime.name ?? '',
              russianTitle: anime.russian,
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
                style: const TextStyle(fontWeight: FontWeight.w800),
              ),
              if (anime.name?.isNotEmpty == true) ...[
                const SizedBox(height: 5),
                Text(
                  anime.name!,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AniMixTheme.subtleText,
                    fontSize: 12,
                  ),
                ),
              ],
              const SizedBox(height: 8),
              Wrap(
                spacing: 7,
                children: [
                  if ((anime.score ?? 0) > 0)
                    AniMixMetadataPill(
                      label: '★ ${anime.score!.toStringAsFixed(1)}',
                    ),
                  AniMixMetadataPill(
                    label: anime.status == 'ongoing' ? 'Выходит' : 'Вышло',
                  ),
                ],
              ),
            ],
          ),
        ),
        const Icon(
          CupertinoIcons.chevron_right,
          color: Colors.white30,
          size: 15,
        ),
      ],
    ),
  );
}

class _HomeSearchSheet extends ConsumerStatefulWidget {
  const _HomeSearchSheet();

  @override
  ConsumerState<_HomeSearchSheet> createState() => _HomeSearchSheetState();
}

class _HomeSearchSheetState extends ConsumerState<_HomeSearchSheet> {
  final _controller = TextEditingController();
  Timer? _debounce;
  List<ShikimoriAnime> _items = const [];
  bool _loading = true;
  String _kind = 'all';
  String _status = 'all';

  @override
  void initState() {
    super.initState();
    _search();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _schedule(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 320), _search);
  }

  Future<void> _search() async {
    setState(() => _loading = true);
    try {
      final result = await ref
          .read(apiClientProvider)
          .getAnimes(
            limit: 40,
            filters: {
              if (_controller.text.trim().isNotEmpty)
                'search': _controller.text.trim(),
              if (_kind != 'all') 'kind': _kind,
              if (_status != 'all') 'status': _status,
              'order': _controller.text.trim().isEmpty
                  ? 'popularity'
                  : 'ranked',
            },
          );
      if (mounted) setState(() => _items = result);
    } catch (_) {
      if (mounted) setState(() => _items = const []);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 10, 12, 10),
        child: Row(
          children: [
            const Expanded(
              child: Text(
                'Поиск и каталог',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900),
              ),
            ),
            IconButton(
              onPressed: () => Navigator.pop(context),
              icon: const Icon(CupertinoIcons.xmark_circle_fill),
            ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: CupertinoSearchTextField(
          controller: _controller,
          autofocus: true,
          placeholder: 'Название аниме',
          backgroundColor: Theme.of(context).colorScheme.surface,
          style: const TextStyle(color: Colors.white),
          onChanged: _schedule,
          onSubmitted: (_) => _search(),
        ),
      ),
      const SizedBox(height: 12),
      SizedBox(
        height: 36,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 20),
          children: [
            _FilterChip(
              label: 'Все форматы',
              selected: _kind == 'all',
              onTap: () => _setKind('all'),
            ),
            _FilterChip(
              label: 'TV',
              selected: _kind == 'tv',
              onTap: () => _setKind('tv'),
            ),
            _FilterChip(
              label: 'Фильмы',
              selected: _kind == 'movie',
              onTap: () => _setKind('movie'),
            ),
            _FilterChip(
              label: 'Выходит',
              selected: _status == 'ongoing',
              onTap: () => _setStatus(_status == 'ongoing' ? 'all' : 'ongoing'),
            ),
          ],
        ),
      ),
      const SizedBox(height: 10),
      Expanded(
        child: _loading
            ? const Center(child: CupertinoActivityIndicator())
            : _items.isEmpty
            ? const AniMixEmptyState(
                icon: CupertinoIcons.search,
                title: 'Ничего не найдено',
                message: 'Попробуйте изменить запрос или фильтры.',
              )
            : ListView.separated(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
                itemCount: _items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 9),
                itemBuilder: (context, index) => _AnimeListRow(_items[index]),
              ),
      ),
    ],
  );

  void _setKind(String value) {
    setState(() => _kind = value);
    _search();
  }

  void _setStatus(String value) {
    setState(() => _status = value);
    _search();
  }
}

class _FilterChip extends StatelessWidget {
  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(right: 8),
    child: ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onTap(),
    ),
  );
}

void _openAnime(BuildContext context, int id) => Navigator.push(
  context,
  CupertinoPageRoute<void>(builder: (_) => AnimeDetailScreen(animeId: id)),
);
