import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/app_settings.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../recommendation/recommendation_screen.dart';
import 'anime_search_sheet.dart';

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
                  SliverToBoxAdapter(child: AniMixHomeSkeleton()),
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
          items: data.ongoing,
        ),
      ),
    if (data.announced.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Новинки сезона',
          subtitle: 'Свежее',
          items: data.announced,
        ),
      ),
    if (data.popular.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Популярное',
          subtitle: 'Топ на Shikimori',
          items: data.popular,
        ),
      ),
    if (data.topRated.isNotEmpty)
      SliverToBoxAdapter(
        child: _AnimeSection(
          title: 'Топ по рейтингу',
          subtitle: 'Лучшие оценки',
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
    const SliverToBoxAdapter(child: SizedBox(height: 56)),
  ];
}

class _DashboardHeader extends StatelessWidget {
  const _DashboardHeader({required this.user});

  final dynamic user;

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: AniMixLayout.contentMaxWidth),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          AniMixLayout.pageInset,
          AniMixSpacing.md,
          AniMixLayout.pageInset,
          AniMixSpacing.lg,
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Добро пожаловать',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: AniMixSpacing.xs),
                  Text(
                    user?.nickname?.toString().isNotEmpty == true
                        ? user.nickname
                        : 'в AniMix',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurface,
                      fontSize: 28,
                      height: 1.05,
                      letterSpacing: -.8,
                      fontWeight: FontWeight.w700,
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
                  child: AnimeSearchSheet(),
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
      return Column(
        children: [
          SizedBox(
            height: desktop ? 380 : 300,
            child: Stack(
              children: [
                PageView.builder(
                  controller: _controller,
                  physics: const BouncingScrollPhysics(),
                  itemCount: widget.items.length,
                  itemBuilder: (context, index) => AnimatedBuilder(
                    animation: _controller,
                    child: _HeroCard(anime: widget.items[index]),
                    builder: (context, child) {
                      final page = _controller.hasClients
                          ? (_controller.page ??
                                _controller.initialPage.toDouble())
                          : _controller.initialPage.toDouble();
                      final distance = (page - index).clamp(-1.0, 1.0);
                      return Transform.scale(
                        scale: 1 - distance.abs() * .025,
                        child: Center(
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 760),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                              ),
                              child: _HeroMotionScope(
                                pageOffset: distance,
                                child: child!,
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
          if (widget.items.length > 1) ...[
            const SizedBox(height: AniMixSpacing.sm),
            _CarouselSignal(
              controller: _controller,
              itemCount: widget.items.length,
            ),
          ],
        ],
      );
    },
  );
}

class _HeroMotionScope extends InheritedWidget {
  const _HeroMotionScope({required this.pageOffset, required super.child});

  final double pageOffset;

  static double of(BuildContext context) =>
      context
          .dependOnInheritedWidgetOfExactType<_HeroMotionScope>()
          ?.pageOffset ??
      0;

  @override
  bool updateShouldNotify(_HeroMotionScope oldWidget) =>
      pageOffset != oldWidget.pageOffset;
}

class _CarouselSignal extends StatelessWidget {
  const _CarouselSignal({required this.controller, required this.itemCount});

  final PageController controller;
  final int itemCount;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Позиция в подборке',
    child: IgnorePointer(
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          final page = controller.hasClients
              ? (controller.page ?? controller.initialPage.toDouble())
              : controller.initialPage.toDouble();
          final color = Theme.of(context).colorScheme.onSurface;
          return Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < itemCount; i++)
                Container(
                  width: 6 + 10 * (1 - (page - i).abs()).clamp(0.0, 1.0),
                  height: 6,
                  margin: const EdgeInsets.symmetric(horizontal: 3),
                  decoration: BoxDecoration(
                    color: color.withValues(
                      alpha: .22 + .58 * (1 - (page - i).abs()).clamp(0.0, 1.0),
                    ),
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
            ],
          );
        },
      ),
    ),
  );
}

class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.anime});
  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) {
    final pageOffset = _HeroMotionScope.of(context);
    final score = anime.score ?? 0;
    final meta = [
      if (anime.status == 'ongoing') 'Выходит',
      if (anime.year != null) '${anime.year}',
      if (anime.genres.isNotEmpty) anime.genres.take(2).join(', '),
    ].join(' · ');
    return Semantics(
      button: true,
      label: anime.russian ?? anime.name,
      child: GestureDetector(
        onTap: () => _openAnime(context, anime.id),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(AniMixRadius.xl),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Transform.translate(
                offset: Offset(-pageOffset * 14, 0),
                child: Transform.scale(
                  scale: 1.035,
                  child: SmartAnimePoster(
                    animeId: anime.id,
                    imageUrl: anime.imageUrl,
                    title: anime.name ?? '',
                    russianTitle: anime.russian,
                    alignment: const Alignment(0, -.35),
                  ),
                ),
              ),
              // A single soft scrim keeps the title legible over any poster.
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x00000000), Color(0xD9000000)],
                    stops: [.35, 1],
                  ),
                ),
              ),
              Positioned(
                left: 20,
                right: 20,
                bottom: 18,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            anime.russian ?? anime.name ?? 'Без названия',
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 22,
                              height: 1.1,
                              letterSpacing: -.4,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          if (meta.isNotEmpty) ...[
                            const SizedBox(height: 6),
                            Text(
                              meta,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: Color(0xCCFFFFFF),
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                    if (score > 0) ...[
                      const SizedBox(width: 12),
                      _ScoreBadge(score: score, large: true),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Compact rating mark used on posters: no fill colour beyond a dark veil.
class _ScoreBadge extends StatelessWidget {
  const _ScoreBadge({required this.score, this.large = false});
  final double score;
  final bool large;

  @override
  Widget build(BuildContext context) => Container(
    padding: EdgeInsets.symmetric(
      horizontal: large ? 9 : 6,
      vertical: large ? 5 : 3,
    ),
    decoration: BoxDecoration(
      color: const Color(0x99000000),
      borderRadius: BorderRadius.circular(999),
    ),
    child: Text(
      '★ ${score.toStringAsFixed(1)}',
      style: TextStyle(
        color: Colors.white,
        fontSize: large ? 13 : 10.5,
        fontWeight: FontWeight.w600,
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
        tooltip: 'Фильтры и поиск',
        icon: CupertinoIcons.slider_horizontal_3,
        onTap: () => showModalBottomSheet<void>(
          context: context,
          useSafeArea: true,
          isScrollControlled: true,
          backgroundColor: Theme.of(context).scaffoldBackgroundColor,
          builder: (_) => const FractionallySizedBox(
            heightFactor: .94,
            child: AnimeSearchSheet(),
          ),
        ),
      ),
      _Shortcut(
        title: 'Для вас',
        tooltip: 'Умная подборка',
        icon: CupertinoIcons.sparkles,
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
          tooltip: 'Высокий рейтинг',
          icon: CupertinoIcons.rosette,
          onTap: () => _openAnime(context, best!.id),
        ),
      if (random != null)
        _Shortcut(
          title: 'Мне повезёт',
          tooltip: 'Случайный тайтл',
          icon: CupertinoIcons.shuffle,
          onTap: () => _openAnime(context, random!.id),
        ),
    ];
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(
          maxWidth: AniMixLayout.contentMaxWidth,
        ),
        child: SizedBox(
          width: double.infinity,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(
              AniMixLayout.pageInset,
              AniMixSpacing.lg,
              AniMixLayout.pageInset,
              0,
            ),
            child: Row(
              children: [
                for (var i = 0; i < items.length; i++) ...[
                  if (i > 0) const SizedBox(width: AniMixSpacing.xs),
                  items[i],
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Shortcut extends StatelessWidget {
  const _Shortcut({
    required this.title,
    required this.tooltip,
    required this.icon,
    required this.onTap,
  });
  final String title;
  final String tooltip;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: tooltip,
      child: Material(
        color: scheme.surfaceContainerHigh,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 16, 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 16, color: scheme.onSurfaceVariant),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
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

class _AnimeSection extends StatelessWidget {
  const _AnimeSection({
    required this.title,
    required this.subtitle,
    required this.items,
  });
  final String title;
  final String subtitle;
  final List<ShikimoriAnime> items;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: AniMixSpacing.xl),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              maxWidth: AniMixLayout.contentMaxWidth,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: AniMixLayout.pageInset,
              ),
              child: AniMixSectionHeader(title: title, subtitle: subtitle),
            ),
          ),
        ),
        const SizedBox(height: AniMixSpacing.sm),
        SizedBox(
          height: 268,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.symmetric(
              horizontal: AniMixLayout.pageInset,
              vertical: 4,
            ),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(width: AniMixSpacing.sm),
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
  Widget build(BuildContext context) {
    final meta = [
      if (anime.year != null) '${anime.year}',
      anime.status == 'ongoing'
          ? 'выходит'
          : (anime.kind ?? 'tv').toUpperCase(),
    ].join(' · ');
    return GestureDetector(
      onTap: () => _openAnime(context, anime.id),
      child: SizedBox(
        width: flexible ? null : 144,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Stack(
                children: [
                  Positioned.fill(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(AniMixRadius.md),
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
                      top: 6,
                      right: 6,
                      child: _ScoreBadge(score: anime.score!),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              // Reserve two title lines so posters in a row stay aligned.
              height:
                  MediaQuery.textScalerOf(context).scale(13.5) * 1.2 * 2 + 1,
              child: Text(
                anime.russian ?? anime.name ?? 'Без названия',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 13.5,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            const SizedBox(height: 2),
            Text(
              meta,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 11.5,
              ),
            ),
          ],
        ),
      ),
    );
  }
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
                    separatorBuilder: (_, _) => const SizedBox(height: 4),
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
                        : Center(
                            child: Text(
                              'Вы дошли до конца каталога',
                              style: TextStyle(
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurfaceVariant,
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
    padding: const EdgeInsets.all(3),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(12),
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
              ? Theme.of(context).colorScheme.surface
              : Colors.transparent,
          borderRadius: BorderRadius.circular(9),
        ),
        child: Icon(
          icon,
          size: 16,
          color: selected
              ? Theme.of(context).colorScheme.onSurface
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ),
  );
}

class _AnimeListRow extends StatelessWidget {
  const _AnimeListRow(this.anime);
  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final meta = [
      if ((anime.score ?? 0) > 0) '★ ${anime.score!.toStringAsFixed(1)}',
      anime.status == 'ongoing' ? 'Выходит' : 'Вышло',
      if (anime.year != null) '${anime.year}',
    ].join('  ·  ');
    return InkWell(
      borderRadius: BorderRadius.circular(AniMixRadius.md),
      onTap: () => _openAnime(context, anime.id),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(AniMixRadius.sm + 2),
              child: SizedBox(
                width: 60,
                height: 88,
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
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (anime.name?.isNotEmpty == true) ...[
                    const SizedBox(height: 3),
                    Text(
                      anime.name!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                  const SizedBox(height: 6),
                  Text(
                    meta,
                    style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

void _openAnime(BuildContext context, int id) => Navigator.push(
  context,
  CupertinoPageRoute<void>(builder: (_) => AnimeDetailScreen(animeId: id)),
);
