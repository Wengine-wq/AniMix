import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';

import '../anime_detail/anime_detail_screen.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';

class HomeScreen extends StatefulHookConsumerWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  final ScrollController _scrollController = ScrollController();
  Timer? _debounce; // Умная задержка для поиска

  final List<ShikimoriAnime> _animes = [];
  int _page = 1;
  bool _isLoading = false;
  bool _hasMore = true;

  // ==================== СОСТОЯНИЕ ФИЛЬТРОВ ====================
  String _searchQuery = '';
  String _kind = '';        // '' = все, 'tv', 'movie', 'ova', 'ona', 'special', 'music'
  String _status = '';      // '' = все, 'released', 'ongoing', 'anons'
  
  // 🔥 ФИКС ОШИБКИ 422: В Shikimori нет 'score', правильный ключ для оценки — это 'ranked'
  String _order = 'popularity'; // popularity, ranked, name, aired_on

  @override
  void initState() {
    super.initState();
    _loadAnimes(reset: true);
    _scrollController.addListener(_onScroll);
  }

  // Полная перезагрузка при смене фильтров
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
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 400) {
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
    return width > 900 ? 0.62 : 0.68;
  }

  void _openFilters() {
    showCupertinoModalPopup(
      context: context,
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

  String _getOrderName(String order) {
    switch (order) {
      case 'popularity': return 'По популярности';
      case 'ranked': return 'По оценке';
      case 'name': return 'По алфавиту';
      case 'aired_on': return 'Новинки';
      default: return 'Сортировка';
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    final bool hasActiveFilters = _kind.isNotEmpty || _status.isNotEmpty;

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(
        middle: Text('AniMix', style: TextStyle(fontWeight: FontWeight.w600)),
        backgroundColor: Color(0xFF1E1E1E),
      ),
      child: CustomScrollView(
        controller: _scrollController,
        slivers: [
          // ==================== ПОИСК И ФИЛЬТРЫ ====================
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Column(
                children: [
                  // 🔥 Умный поиск с Debouncer (не спамит API при каждом нажатии клавиши)
                  CupertinoSearchTextField(
                    placeholder: 'Поиск аниме...',
                    onChanged: (value) {
                      if (_debounce?.isActive ?? false) _debounce!.cancel();
                      _debounce = Timer(const Duration(milliseconds: 600), () {
                        _searchQuery = value.trim();
                        _applyFilters();
                      });
                    },
                    style: const TextStyle(color: CupertinoColors.white),
                  ),

                  const SizedBox(height: 12),

                  // Кнопки открытия шторки фильтров
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        _buildFilterChip(
                          label: hasActiveFilters ? 'Фильтры (Активны)' : 'Фильтры',
                          icon: CupertinoIcons.slider_horizontal_3,
                          isActive: hasActiveFilters,
                          onTap: _openFilters,
                        ),
                        const SizedBox(width: 8),
                        _buildFilterChip(
                          label: _getOrderName(_order),
                          icon: CupertinoIcons.sort_down,
                          isActive: true,
                          onTap: _openFilters,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),

          // ==================== ГРИД С АНИМЕ ====================
          SliverPadding(
            padding: const EdgeInsets.all(12),
            sliver: SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: _getCrossAxisCount(screenWidth),
                childAspectRatio: _getChildAspectRatio(screenWidth),
                crossAxisSpacing: 14,
                mainAxisSpacing: 14,
              ),
              delegate: SliverChildBuilderDelegate(
                (context, index) {
                  if (index == _animes.length) {
                    return const Center(child: CupertinoActivityIndicator());
                  }
                  final anime = _animes[index];
                  return _AnimeCard(anime: anime);
                },
                childCount: _animes.length + (_isLoading ? 1 : 0),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterChip({
    required String label,
    required IconData icon,
    required bool isActive,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: isActive
              ? const Color(0xFFFF5722).withValues(alpha: 0.2)
              : const Color(0xFF2A2A2A),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: isActive ? const Color(0xFFFF5722) : Colors.transparent,
            width: 1.5,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 16, color: isActive ? const Color(0xFFFF5722) : CupertinoColors.white),
            const SizedBox(width: 8),
            Text(
              label,
              style: TextStyle(
                color: isActive ? const Color(0xFFFF5722) : CupertinoColors.white,
                fontSize: 15,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ==================== УМНЫЕ ФИЛЬТРЫ (В СТИЛЕ ANIXART) ====================
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
    return Container(
      height: MediaQuery.of(context).size.height * 0.75,
      margin: EdgeInsets.only(top: MediaQuery.of(context).padding.top),
      decoration: const BoxDecoration(
        color: Color(0xFF1E1E1E),
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 12),
          // Ползунок (ручка)
          Center(
            child: Container(
              width: 40,
              height: 5,
              decoration: BoxDecoration(
                color: CupertinoColors.systemGrey.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(10),
              ),
            ),
          ),
          const SizedBox(height: 16),
          // Заголовок
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('Фильтры', style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold)),
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
              padding: const EdgeInsets.symmetric(horizontal: 20),
              children: [
                _buildSectionTitle('Сортировка'),
                _buildWrap({
                  'popularity': 'По популярности',
                  'ranked': 'По оценке (Рейтингу)', // ← Исправлено 422
                  'name': 'По алфавиту',
                  'aired_on': 'Сначала новые',
                }, _order, (v) => setState(() => _order = v)),
                
                const SizedBox(height: 30),
                _buildSectionTitle('Статус'),
                _buildWrap({
                  '': 'Любой',
                  'released': 'Вышло',
                  'ongoing': 'Онгоинг',
                  'anons': 'Анонс',
                }, _status, (v) => setState(() => _status = v)),

                const SizedBox(height: 30),
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
          // Кнопка применить
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: SizedBox(
                width: double.infinity,
                child: CupertinoButton.filled(
                  onPressed: () {
                    widget.onApply(_kind, _status, _order);
                    Navigator.pop(context);
                  },
                  child: const Text('Применить фильтры', style: TextStyle(fontWeight: FontWeight.bold)),
                ),
              ),
            ),
          )
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        title.toUpperCase(),
        style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 13, fontWeight: FontWeight.w600, letterSpacing: 0.5),
      ),
    );
  }

  Widget _buildWrap(Map<String, String> items, String currentValue, Function(String) onSelect) {
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: items.entries.map((e) {
        final isActive = e.key == currentValue;
        return GestureDetector(
          onTap: () => onSelect(e.key),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            decoration: BoxDecoration(
              color: isActive ? const Color(0xFFFF5722).withValues(alpha: 0.2) : const Color(0xFF2A2A2A),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: isActive ? const Color(0xFFFF5722) : Colors.transparent, width: 1.5),
            ),
            child: Text(
              e.value,
              style: TextStyle(
                color: isActive ? const Color(0xFFFF5722) : CupertinoColors.white,
                fontSize: 14,
                fontWeight: isActive ? FontWeight.w600 : FontWeight.w500,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}

// ==================== КАРТОЧКА АНИМЕ ====================
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
    _controller = AnimationController(
      duration: const Duration(milliseconds: 180),
      vsync: this,
    );
    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.08).animate(
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
    final scoreColor = score >= 8.0
        ? CupertinoColors.systemGreen
        : score >= 6.0
            ? CupertinoColors.systemOrange
            : CupertinoColors.systemRed;

    return MouseRegion(
      onEnter: (_) => _onHover(true),
      onExit: (_) => _onHover(false),
      child: GestureDetector(
        onTapDown: (_) => _controller.forward(),
        onTapUp: (_) => _controller.reverse(),
        onTapCancel: () => _controller.reverse(),
        onTap: () {
          Navigator.of(context).push(
            CupertinoPageRoute(
              builder: (_) => AnimeDetailScreen(animeId: widget.anime.id),
            ),
          );
        },
        child: AnimatedBuilder(
          animation: _scaleAnimation,
          builder: (context, child) {
            return Transform.scale(
              scale: _scaleAnimation.value,
              child: child,
            );
          },
          child: ClipRRect(
            borderRadius: BorderRadius.circular(18),
            child: Stack(
              children: [
                CachedNetworkImage(
                  imageUrl: widget.anime.imageUrl ?? '',
                  fit: BoxFit.cover,
                  height: double.infinity,
                  width: double.infinity,
                  httpHeaders: const {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
                  },
                  memCacheWidth: 600,
                  memCacheHeight: 900,
                  filterQuality: FilterQuality.high,
                  placeholder: (context, url) => Container(
                    color: CupertinoColors.systemGrey6,
                    child: const Center(child: CupertinoActivityIndicator()),
                  ),
                  errorWidget: (context, url, error) => Container(
                    color: CupertinoColors.systemGrey6,
                    child: const Icon(CupertinoIcons.photo, size: 48, color: CupertinoColors.systemGrey),
                  ),
                ),
                Positioned(
                  top: 12,
                  right: 12,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: scoreColor.withValues(alpha: 0.95),
                      borderRadius: BorderRadius.circular(20),
                      boxShadow: [BoxShadow(color: scoreColor.withValues(alpha: 0.4), blurRadius: 10)],
                    ),
                    child: Text(
                      score.toStringAsFixed(1),
                      style: const TextStyle(color: CupertinoColors.white, fontWeight: FontWeight.bold, fontSize: 15),
                    ),
                  ),
                ),
                Positioned(
                  bottom: 0,
                  left: 0,
                  right: 0,
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [Color(0x00000000), Color(0xDD000000)],
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.anime.russian ?? widget.anime.name ?? 'Без названия',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: CupertinoColors.white, fontWeight: FontWeight.w600, fontSize: 15.5),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          widget.anime.status ?? '',
                          style: const TextStyle(color: CupertinoColors.systemGrey2, fontSize: 12.5),
                        ),
                      ],
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