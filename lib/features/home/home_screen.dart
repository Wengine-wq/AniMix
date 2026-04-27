import 'dart:async';
import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../anime_detail/anime_detail_screen.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';

// Цветовая палитра "Premium Violet"
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF09090B); // Глубокий темный фон

// =====================================================================
// УМНАЯ ОБЕРТКА ДЛЯ ЛИКВИДНОГО СТЕКЛА С ЗАТЕМНЕНИЕМ (TINT)
// =====================================================================
class _GlassUI extends StatelessWidget {
  final Widget child;
  final BorderRadius? borderRadius;
  final BoxBorder? border;
  final GlassQuality quality;
  final EdgeInsetsGeometry? padding;
  final Color? tintColor; // 🔥 Новый параметр для затемнения светлых участков

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
    // Внутренний слой заливки поверх блюра (для читаемости текста)
    Widget content = Container(
      padding: padding ?? EdgeInsets.zero,
      decoration: BoxDecoration(
        color: tintColor ?? Colors.transparent,
      ),
      child: child,
    );

    // Само стекло
    content = GlassContainer(
      quality: quality,
      child: content,
    );

    // Отрезаем углы
    if (borderRadius != null) {
      content = ClipRRect(
        borderRadius: borderRadius!,
        child: content,
      );
    }

    // Рамка ПОВЕРХ стекла
    if (border != null) {
      content = Container(
        foregroundDecoration: BoxDecoration(
          borderRadius: borderRadius,
          border: border,
        ),
        child: content,
      );
    }

    return content;
  }
}

// =====================================================================
// ГЛАВНЫЙ ЭКРАН
// =====================================================================
class HomeScreen extends StatefulHookConsumerWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> with SingleTickerProviderStateMixin {
  final ScrollController _scrollController = ScrollController();
  Timer? _debounce;
  late AnimationController _bgAnimController;

  final List<ShikimoriAnime> _animes = [];
  int _page = 1;
  bool _isLoading = false;
  bool _hasMore = true;

  String _searchQuery = '';
  String _kind = '';        
  String _status = '';      
  String _order = 'popularity'; 

  @override
  void initState() {
    super.initState();
    _loadAnimes(reset: true);
    _scrollController.addListener(_onScroll);
    
    // Анимация для "дышащего" неонового фона
    _bgAnimController = AnimationController(
      vsync: this, 
      duration: const Duration(seconds: 12),
    )..repeat(reverse: true);
  }

  void _applyFilters() {
    setState(() {
      _animes.clear();
      _page = 1;
      _hasMore = true;
    });
    _loadAnimes(reset: true);
  }

  Future<void> _loadAnimes({bool reset = false}) async {
    if (_isLoading || (!_hasMore && !reset)) return;

    setState(() => _isLoading = true);

    try {
      final api = ref.read(apiClientProvider);

      final filters = <String, dynamic>{
        if (_searchQuery.isNotEmpty) 'search': _searchQuery,
        if (_kind.isNotEmpty) 'kind': _kind,
        if (_status.isNotEmpty) 'status': _status,
        'order': _order,
      };

      final newAnimes = await api.getAnimes(
        page: _page,
        limit: 30,
        filters: filters,
      );

      setState(() {
        if (reset) _animes.clear();
        _animes.addAll(newAnimes);
        _page++;
        _hasMore = newAnimes.length == 30;
      });
    } catch (e) {
      if (mounted) {
        showCupertinoDialog(
          context: context,
          builder: (ctx) => CupertinoAlertDialog(
            title: const Text('Ошибка загрузки'),
            content: Text(e.toString()),
            actions: [
              CupertinoDialogAction(
                child: const Text('OK'),
                onPressed: () => Navigator.pop(ctx),
              ),
            ],
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _onScroll() {
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 400) {
      _loadAnimes();
    }
  }

  int _getCrossAxisCount(double width) {
    if (width > 1200) return 5;
    if (width > 900) return 4;
    if (width > 600) return 3;
    return 2;
  }

  double _getChildAspectRatio(double width) {
    return width > 900 ? 0.60 : 0.62; // Сделали карточки чуть выше для эстетики
  }

  void _openFilters() {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => _FilterBottomSheet(
        initialKind: _kind,
        initialStatus: _status,
        initialOrder: _order,
        onApply: (kind, status, order) {
          setState(() {
            _kind = kind;
            _status = status;
            _order = order;
          });
          _applyFilters();
        },
      ),
    );
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _bgAnimController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    final bool hasActiveFilters = _kind.isNotEmpty || _status.isNotEmpty || _order != 'popularity';

    return GlassBackdropScope(
      child: Scaffold(
        backgroundColor: _bgColor,
        body: Stack(
          children: [
            // Анимированный Ambient-фон (Неоновые фиолетово-синие сферы)
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
                          top: 300 - (30 * value), right: -100 + (40 * value),
                          child: Container(width: 450, height: 450, decoration: BoxDecoration(color: const Color(0xFF3B82F6).withOpacity(0.1), shape: BoxShape.circle)),
                        ),
                        Positioned(
                          bottom: 0, left: 50 + (60 * value),
                          child: Container(width: 350, height: 350, decoration: BoxDecoration(color: const Color(0xFF6366F1).withOpacity(0.12), shape: BoxShape.circle)),
                        ),
                      ],
                    );
                  }
                ),
              ),
            ),

            CustomScrollView(
              controller: _scrollController,
              physics: const BouncingScrollPhysics(),
              slivers: [
                SliverToBoxAdapter(
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(20, MediaQuery.of(context).padding.top + 20, 20, 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Эстетичный Header в стиле референса
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('AniMix', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: -1.2)),
                                SizedBox(height: 2),
                                Text('Твоя аниме-коллекция', style: TextStyle(fontSize: 15, color: Colors.grey, fontWeight: FontWeight.w500)),
                              ],
                            ),
                            Container(
                              padding: const EdgeInsets.all(12),
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: Colors.white.withOpacity(0.05),
                                border: Border.all(color: Colors.white.withOpacity(0.1)),
                              ),
                              child: const Icon(CupertinoIcons.person_solid, color: Colors.white, size: 22),
                            )
                          ],
                        ),
                        const SizedBox(height: 28),

                        // Поиск и кнопка фильтров (Рядом, как в референсе)
                        Row(
                          children: [
                            Expanded(
                              child: _GlassUI(
                                quality: GlassQuality.standard,
                                tintColor: Colors.black.withOpacity(0.4), // Темное стекло
                                borderRadius: BorderRadius.circular(24),
                                border: Border.all(color: Colors.white.withOpacity(0.08)),
                                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                                child: CupertinoSearchTextField(
                                  placeholder: 'Поиск тайтлов...',
                                  backgroundColor: Colors.transparent,
                                  onChanged: (value) {
                                    if (_debounce?.isActive ?? false) _debounce!.cancel();
                                    _debounce = Timer(const Duration(milliseconds: 600), () {
                                      _searchQuery = value.trim();
                                      _applyFilters();
                                    });
                                  },
                                  style: const TextStyle(color: Colors.white, fontSize: 16),
                                  placeholderStyle: TextStyle(color: Colors.white.withOpacity(0.4), fontSize: 16),
                                  prefixIcon: Icon(CupertinoIcons.search, color: Colors.white.withOpacity(0.6), size: 20),
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            GestureDetector(
                              onTap: _openFilters,
                              child: _GlassUI(
                                quality: GlassQuality.standard,
                                tintColor: hasActiveFilters ? _accentColor.withOpacity(0.2) : Colors.black.withOpacity(0.4),
                                borderRadius: BorderRadius.circular(24),
                                border: Border.all(color: hasActiveFilters ? _accentColor.withOpacity(0.8) : Colors.white.withOpacity(0.08)),
                                padding: const EdgeInsets.all(14),
                                child: Stack(
                                  children: [
                                    Icon(CupertinoIcons.slider_horizontal_3, color: hasActiveFilters ? Colors.white : Colors.white.withOpacity(0.8), size: 22),
                                    if (hasActiveFilters)
                                      Positioned(
                                        right: 0, top: 0,
                                        child: Container(width: 8, height: 8, decoration: const BoxDecoration(color: _accentColor, shape: BoxShape.circle)),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),

                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                  sliver: SliverGrid(
                    gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: _getCrossAxisCount(screenWidth),
                      childAspectRatio: _getChildAspectRatio(screenWidth),
                      crossAxisSpacing: 16,
                      mainAxisSpacing: 20,
                    ),
                    delegate: SliverChildBuilderDelegate(
                      (context, index) {
                        if (index == _animes.length) {
                          return const Center(child: CupertinoActivityIndicator());
                        }
                        return _AnimeCard(anime: _animes[index]);
                      },
                      childCount: _animes.length + (_isLoading ? 1 : 0),
                    ),
                  ),
                ),
                
                const SliverToBoxAdapter(child: SizedBox(height: 120)), 
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// =====================================================================
// ШТОРКА ФИЛЬТРОВ
// =====================================================================
class _FilterBottomSheet extends StatefulWidget {
  final String initialKind;
  final String initialStatus;
  final String initialOrder;
  final Function(String kind, String status, String order) onApply;

  const _FilterBottomSheet({
    required this.initialKind,
    required this.initialStatus,
    required this.initialOrder,
    required this.onApply,
  });

  @override
  State<_FilterBottomSheet> createState() => _FilterBottomSheetState();
}

class _FilterBottomSheetState extends State<_FilterBottomSheet> {
  late String _kind;
  late String _status;
  late String _order;

  @override
  void initState() {
    super.initState();
    _kind = widget.initialKind;
    _status = widget.initialStatus;
    _order = widget.initialOrder;
  }

  @override
  Widget build(BuildContext context) {
    return _GlassUI(
      quality: GlassQuality.premium,
      tintColor: _bgColor.withOpacity(0.7), // Темная заливка шторки
      borderRadius: const BorderRadius.vertical(top: Radius.circular(36)),
      border: Border(top: BorderSide(color: Colors.white.withOpacity(0.1))),
      child: Container(
        height: MediaQuery.of(context).size.height * 0.75,
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).padding.bottom),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 14),
            Center(
              child: Container(
                width: 48,
                height: 5,
                decoration: BoxDecoration(color: Colors.white.withOpacity(0.2), borderRadius: BorderRadius.circular(10)),
              ),
            ),
            const SizedBox(height: 24),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Фильтры', style: TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                  CupertinoButton(
                    padding: EdgeInsets.zero,
                    onPressed: () {
                      setState(() {
                        _kind = '';
                        _status = '';
                        _order = 'popularity';
                      });
                    },
                    child: const Text('Сбросить', style: TextStyle(color: CupertinoColors.systemRed, fontSize: 16)),
                  )
                ],
              ),
            ),
            const SizedBox(height: 16),
            Expanded(
              child: ListView(
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.symmetric(horizontal: 24),
                children: [
                  _buildSectionTitle('Сортировка'),
                  _buildWrap({
                    'popularity': 'По популярности',
                    'ranked': 'По оценке (Рейтингу)', 
                    'name': 'По алфавиту',
                    'aired_on': 'Сначала новые',
                  }, _order, (v) => setState(() => _order = v)),
                  
                  const SizedBox(height: 32),
                  _buildSectionTitle('Статус'),
                  _buildWrap({
                    '': 'Любой',
                    'released': 'Вышло',
                    'ongoing': 'Онгоинг',
                    'anons': 'Анонс',
                  }, _status, (v) => setState(() => _status = v)),

                  const SizedBox(height: 32),
                  _buildSectionTitle('Тип'),
                  _buildWrap({
                    '': 'Все',
                    'tv': 'TV Сериал',
                    'movie': 'Фильм',
                    'ova': 'OVA',
                    'ona': 'ONA',
                    'special': 'Спешл',
                    'music': 'Клип',
                  }, _kind, (v) => setState(() => _kind = v)),
                  
                  const SizedBox(height: 40),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(24),
              child: GestureDetector(
                onTap: () {
                  widget.onApply(_kind, _status, _order);
                  Navigator.pop(context);
                },
                child: Container(
                  decoration: BoxDecoration(
                    color: _accentColor,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [BoxShadow(color: _accentColor.withOpacity(0.4), blurRadius: 20, offset: const Offset(0, 5))],
                  ),
                  padding: const EdgeInsets.symmetric(vertical: 18),
                  child: const Center(
                    child: Text('Применить фильтры', style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold)),
                  ),
                ),
              ),
            )
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Text(
        title.toUpperCase(),
        style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 13, fontWeight: FontWeight.w700, letterSpacing: 1.0),
      ),
    );
  }

  Widget _buildWrap(Map<String, String> items, String currentValue, Function(String) onSelect) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: items.entries.map((e) {
        final isActive = e.key == currentValue;
        return GestureDetector(
          onTap: () => onSelect(e.key),
          child: _GlassUI(
            quality: GlassQuality.minimal, // Оптимизация для множества чипов
            tintColor: isActive ? _accentColor.withOpacity(0.15) : Colors.white.withOpacity(0.05),
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: isActive ? _accentColor : Colors.white.withOpacity(0.1), 
              width: 1.5
            ),
            child: Text(
              e.value,
              style: TextStyle(
                color: isActive ? _accentLight : Colors.white.withOpacity(0.9),
                fontSize: 15,
                fontWeight: isActive ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}

// =====================================================================
// ПРЕМИАЛЬНАЯ КАРТОЧКА АНИМЕ
// =====================================================================
class _AnimeCard extends StatefulWidget {
  final ShikimoriAnime anime;
  const _AnimeCard({required this.anime});

  @override
  State<_AnimeCard> createState() => _AnimeCardState();
}

class _AnimeCardState extends State<_AnimeCard> with SingleTickerProviderStateMixin {
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
    
    // Форматирование статуса (эмуляция "Цены" как в референсе)
    String displayStatus = 'Анонс';
    if (widget.anime.status == 'released') displayStatus = 'Вышло';
    if (widget.anime.status == 'ongoing') displayStatus = 'Онгоинг';

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
              borderRadius: BorderRadius.circular(24), // Больше радиус скругления
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
                  
                  // 🔥 Градиентная подложка снизу, чтобы текст ВСЕГДА читался даже на белых картинках
                  Positioned(
                    bottom: 0, left: 0, right: 0,
                    child: Container(
                      height: 140,
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [Colors.transparent, Colors.black87, Colors.black],
                        ),
                      ),
                    ),
                  ),
                    
                  // Инфо-блок поверх градиента (в стиле Wanderlust)
                  Positioned(
                    bottom: 16, left: 16, right: 16,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.anime.russian ?? widget.anime.name ?? 'Без названия',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 17, height: 1.1, letterSpacing: -0.3),
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            // Рейтинг в темном стекле (читается идеально)
                            if (score > 0)
                              _GlassUI(
                                quality: GlassQuality.minimal, 
                                tintColor: Colors.black.withOpacity(0.5), // Темный слой внутри стекла
                                borderRadius: BorderRadius.circular(12),
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(CupertinoIcons.star_fill, color: Color(0xFFFBBF24), size: 12), // Золотая звезда
                                    const SizedBox(width: 4),
                                    Text(
                                      score.toStringAsFixed(1),
                                      style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 12),
                                    ),
                                  ],
                                ),
                              ),
                            
                            const Spacer(),
                            
                            // Статус
                            Text(
                              displayStatus,
                              style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w800),
                            ),
                          ],
                        ),
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