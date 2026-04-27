import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../anime_detail/anime_detail_screen.dart';

// Цветовая палитра "Premium Violet"
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF09090B);

// =====================================================================
// УМНАЯ ОБЕРТКА ДЛЯ ЛИКВИДНОГО СТЕКЛА
// =====================================================================
class _GlassUI extends StatelessWidget {
  final Widget child;
  final BorderRadius? borderRadius;
  final BoxBorder? border;
  final GlassQuality quality;
  final EdgeInsetsGeometry? padding;
  final Color? tintColor;

  const _GlassUI({
    required this.child,
    this.borderRadius,
    this.border,
    this.quality = GlassQuality.standard,
    this.padding,
    this.tintColor,
  });

  @override
  Widget build(BuildContext context) {
    Widget content = Container(
      padding: padding ?? EdgeInsets.zero,
      decoration: BoxDecoration(color: tintColor ?? Colors.transparent),
      child: child,
    );

    content = GlassContainer(quality: quality, child: content);

    if (borderRadius != null) content = ClipRRect(borderRadius: borderRadius!, child: content);
    if (border != null) {
      content = Container(
        foregroundDecoration: BoxDecoration(borderRadius: borderRadius, border: border),
        child: content,
      );
    }
    return content;
  }
}

// =====================================================================
// ЭКРАН КАТАЛОГА
// =====================================================================
class CatalogScreen extends StatefulHookConsumerWidget {
  const CatalogScreen({super.key});

  @override
  ConsumerState<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends ConsumerState<CatalogScreen> with SingleTickerProviderStateMixin {
  final ScrollController _scrollController = ScrollController();
  late AnimationController _bgAnimController;

  final List<ShikimoriAnime> _animes = [];
  String _currentStatus = 'watching';
  int _page = 1;
  bool _isLoading = false;
  bool _hasMore = true;

  final Map<String, String> _statuses = {
    'watching': 'Смотрю',
    'planned': 'В планах',
    'completed': 'Просмотрено',
    'on_hold': 'Отложено',
    'dropped': 'Брошено',
    'rewatching': 'Пересмотр',
  };

  @override
  void initState() {
    super.initState();
    _loadAnimes(reset: true);
    _scrollController.addListener(_onScroll);
    
    _bgAnimController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 12),
    )..repeat(reverse: true);
  }

  void _onScroll() {
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 400) {
      _loadAnimes();
    }
  }

  Future<void> _loadAnimes({bool reset = false}) async {
    if (_isLoading || (!_hasMore && !reset)) return;
    setState(() => _isLoading = true);

    try {
      final user = await ref.read(currentUserProvider.future);
      if (user == null) return;
      final api = ref.read(apiClientProvider);

      // 🔥 ИСПРАВЛЕНИЕ: Используем оригинальный метод getAnimes
      // Передаем фильтр mylist, который Shikimori API автоматически 
      // применяет к текущему авторизованному пользователю.
      final newAnimes = await api.getAnimes(
        page: _page,
        limit: 30,
        filters: {'mylist': _currentStatus},
      );

      setState(() {
        if (reset) _animes.clear();
        _animes.addAll(newAnimes);
        _page++;
        _hasMore = newAnimes.length == 30;
      });
    } catch (e) {
      debugPrint('Ошибка загрузки каталога: $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _changeStatus(String status) {
    if (_currentStatus == status) return;
    setState(() {
      _currentStatus = status;
      _page = 1;
      _hasMore = true;
      _animes.clear();
    });
    _loadAnimes(reset: true);
  }

  @override
  void dispose() {
    _bgAnimController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  int _getCrossAxisCount(double width) {
    if (width > 1200) return 5;
    if (width > 900) return 4;
    if (width > 600) return 3;
    return 2;
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: _bgColor,
      body: Stack(
        children: [
          // Анимированный фон (Сферы)
          Positioned.fill(
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 90, sigmaY: 90),
              child: AnimatedBuilder(
                animation: _bgAnimController,
                builder: (context, child) {
                  final value = _bgAnimController.value;
                  return Stack(
                    children: [
                      Positioned(
                        top: -100 + (40 * value), left: -50 - (20 * value),
                        child: Container(width: 400, height: 400, decoration: BoxDecoration(color: _accentColor.withOpacity(0.12), shape: BoxShape.circle)),
                      ),
                      Positioned(
                        bottom: 100 - (30 * value), right: -100 + (40 * value),
                        child: Container(width: 450, height: 450, decoration: BoxDecoration(color: const Color(0xFF3B82F6).withOpacity(0.1), shape: BoxShape.circle)),
                      ),
                    ],
                  );
                }
              ),
            ),
          ),

          CustomScrollView(
            controller: _scrollController,
            physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
            slivers: [
              // Нативный Pull-to-Refresh
              CupertinoSliverRefreshControl(
                onRefresh: () async => _loadAnimes(reset: true),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(20, MediaQuery.of(context).padding.top + 20, 20, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Каталог', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: -1.2)),
                      const SizedBox(height: 4),
                      Text('Ваша личная медиатека', style: TextStyle(fontSize: 15, color: Colors.white.withOpacity(0.6), fontWeight: FontWeight.w500)),
                      const SizedBox(height: 24),

                      // Фильтры по статусу (Горизонтальный скролл)
                      SizedBox(
                        height: 44,
                        child: ListView.builder(
                          scrollDirection: Axis.horizontal,
                          physics: const BouncingScrollPhysics(),
                          itemCount: _statuses.length,
                          itemBuilder: (context, index) {
                            final key = _statuses.keys.elementAt(index);
                            final title = _statuses.values.elementAt(index);
                            final isActive = _currentStatus == key;

                            return GestureDetector(
                              onTap: () => _changeStatus(key),
                              child: Container(
                                margin: const EdgeInsets.only(right: 12),
                                child: _GlassUI(
                                  quality: GlassQuality.minimal,
                                  tintColor: isActive ? _accentColor.withOpacity(0.2) : Colors.black.withOpacity(0.3),
                                  borderRadius: BorderRadius.circular(22),
                                  border: Border.all(color: isActive ? _accentColor : Colors.white.withOpacity(0.08)),
                                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                                  child: Text(
                                    title,
                                    style: TextStyle(
                                      color: isActive ? Colors.white : Colors.white.withOpacity(0.7),
                                      fontSize: 15,
                                      fontWeight: isActive ? FontWeight.bold : FontWeight.w600,
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
              ),

              // Сетка аниме
              _animes.isEmpty && !_isLoading
                  ? SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.only(top: 100),
                        child: Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(CupertinoIcons.square_stack_3d_up_slash, size: 64, color: Colors.white.withOpacity(0.2)),
                              const SizedBox(height: 16),
                              Text('В этом списке пока пусто', style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 16)),
                            ],
                          ),
                        ),
                      ),
                    )
                  : SliverPadding(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                      sliver: SliverGrid(
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: _getCrossAxisCount(screenWidth),
                          childAspectRatio: screenWidth > 900 ? 0.60 : 0.62,
                          crossAxisSpacing: 16,
                          mainAxisSpacing: 20,
                        ),
                        delegate: SliverChildBuilderDelegate(
                          (context, index) {
                            if (index == _animes.length) {
                              return const Center(child: CupertinoActivityIndicator());
                            }
                            return _CatalogAnimeCard(anime: _animes[index]);
                          },
                          childCount: _animes.length + (_isLoading && _animes.isNotEmpty ? 1 : 0),
                        ),
                      ),
                    ),

              const SliverToBoxAdapter(child: SizedBox(height: 120)), // Под бар
            ],
          ),
        ],
      ),
    );
  }
}

// =====================================================================
// ПРЕМИАЛЬНАЯ КАРТОЧКА КАТАЛОГА
// =====================================================================
class _CatalogAnimeCard extends StatefulWidget {
  final ShikimoriAnime anime;
  const _CatalogAnimeCard({required this.anime});

  @override
  State<_CatalogAnimeCard> createState() => _CatalogAnimeCardState();
}

class _CatalogAnimeCardState extends State<_CatalogAnimeCard> with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _scaleAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(duration: const Duration(milliseconds: 200), vsync: this);
    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.03).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onHover(bool hovered) {
    if (hovered) _controller.forward();
    else _controller.reverse();
  }

  @override
  Widget build(BuildContext context) {
    final score = widget.anime.score ?? 0.0;

    return MouseRegion(
      onEnter: (_) => _onHover(true),
      onExit: (_) => _onHover(false),
      child: GestureDetector(
        onTapDown: (_) => _controller.forward(),
        onTapUp: (_) => _controller.reverse(),
        onTapCancel: () => _controller.reverse(),
        onTap: () {
          Navigator.of(context).push(
            CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: widget.anime.id)),
          );
        },
        child: AnimatedBuilder(
          animation: _scaleAnimation,
          builder: (context, child) => Transform.scale(scale: _scaleAnimation.value, child: child),
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              boxShadow: [
                BoxShadow(color: Colors.black.withOpacity(0.5), blurRadius: 20, offset: const Offset(0, 10)),
              ],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(24),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  CachedNetworkImage(
                    imageUrl: widget.anime.imageUrl ?? '',
                    fit: BoxFit.cover,
                    memCacheWidth: 600,
                    memCacheHeight: 900,
                    placeholder: (_, __) => Container(color: const Color(0xFF1C1C1E)),
                    errorWidget: (_, __, ___) => Container(color: const Color(0xFF1C1C1E), child: const Icon(CupertinoIcons.photo, color: Colors.grey)),
                  ),
                  
                  // Градиентная подложка снизу для читаемости текста
                  Positioned(
                    bottom: 0, left: 0, right: 0,
                    child: Container(
                      height: 120,
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [Colors.transparent, Colors.black87, Colors.black],
                        ),
                      ),
                    ),
                  ),
                    
                  Positioned(
                    bottom: 16, left: 16, right: 16,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.anime.russian ?? widget.anime.name ?? 'Без названия',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 16, height: 1.1, letterSpacing: -0.3),
                        ),
                        if (score > 0) ...[
                          const SizedBox(height: 10),
                          _GlassUI(
                            quality: GlassQuality.minimal, 
                            tintColor: Colors.black.withOpacity(0.5),
                            borderRadius: BorderRadius.circular(12),
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(CupertinoIcons.star_fill, color: Color(0xFFFBBF24), size: 12),
                                const SizedBox(width: 4),
                                Text(
                                  score.toStringAsFixed(1),
                                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 12),
                                ),
                              ],
                            ),
                          ),
                        ]
                      ],
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