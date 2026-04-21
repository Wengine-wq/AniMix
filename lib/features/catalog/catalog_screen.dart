import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';

import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../anime_detail/anime_detail_screen.dart';

class CatalogScreen extends StatefulHookConsumerWidget {
  const CatalogScreen({super.key});

  @override
  ConsumerState<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends ConsumerState<CatalogScreen> {
  final ScrollController _scrollController = ScrollController();
  final List<ShikimoriAnime> _animes = [];
  
  String _currentStatus = 'watching';
  int _page = 1;
  bool _isLoading = false;
  bool _hasMore = true;

  // Соответствие статусов Shikimori
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
      final api = ref.read(apiClientProvider);
      // Если по какой-то причине мы попытаемся загрузить каталог до авторизации
      final user = await ref.read(currentUserProvider.future);
      if (user == null) throw Exception('Пользователь не авторизован');

      final filters = <String, dynamic>{
        'mylist': _currentStatus, // 🔥 Магия Shikimori: фильтр по личному списку
        'order': 'ranked',
      };

      final newAnimes = await api.getAnimes(
        page: _page,
        limit: 30,
        filters: filters,
      );

      if (mounted) {
        setState(() {
          if (reset) _animes.clear();
          _animes.addAll(newAnimes);
          _page++;
          _hasMore = newAnimes.length == 30;
        });
      }
    } catch (e) {
      if (mounted) {
        showCupertinoDialog(
          context: context,
          builder: (ctx) => CupertinoAlertDialog(
            title: const Text('Ошибка загрузки'),
            content: Text(e.toString()),
            actions: [CupertinoDialogAction(child: const Text('OK'), onPressed: () => Navigator.pop(ctx))],
          ),
        );
      }
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

  int _getCrossAxisCount(double width) {
    if (width > 1200) return 5;
    if (width > 900) return 4;
    if (width > 600) return 3;
    return 2;
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Мой Каталог', style: TextStyle(fontWeight: FontWeight.w600)),
        backgroundColor: Color(0xFF1E1E1E),
      ),
      child: CustomScrollView(
        controller: _scrollController,
        slivers: [
          // Горизонтальный список статусов (табы)
          SliverToBoxAdapter(
            child: Container(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Row(
                  children: _statuses.entries.map((e) {
                    final isActive = _currentStatus == e.key;
                    return GestureDetector(
                      onTap: () => _changeStatus(e.key),
                      child: Container(
                        margin: const EdgeInsets.only(right: 10),
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                        decoration: BoxDecoration(
                          color: isActive ? const Color(0xFFFF5722).withValues(alpha: 0.2) : const Color(0xFF2A2A2A),
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(
                            color: isActive ? const Color(0xFFFF5722) : Colors.transparent,
                            width: 1.5,
                          ),
                        ),
                        child: Text(
                          e.value,
                          style: TextStyle(
                            color: isActive ? const Color(0xFFFF5722) : CupertinoColors.white,
                            fontSize: 15,
                            fontWeight: isActive ? FontWeight.w700 : FontWeight.w500,
                          ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ),
            ),
          ),

          // Сетка аниме или состояние загрузки/пустоты
          if (_animes.isEmpty && !_isLoading)
            SliverFillRemaining(
              child: Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(CupertinoIcons.folder_open, size: 80, color: CupertinoColors.systemGrey.withValues(alpha: 0.5)),
                    const SizedBox(height: 16),
                    Text(
                      'В списке «${_statuses[_currentStatus]}» пока пусто',
                      style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 16),
                    ),
                  ],
                ),
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverGrid(
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: _getCrossAxisCount(screenWidth),
                  childAspectRatio: screenWidth > 900 ? 0.62 : 0.68,
                  crossAxisSpacing: 14,
                  mainAxisSpacing: 14,
                ),
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    if (index == _animes.length) {
                      return const Center(child: CupertinoActivityIndicator());
                    }
                    return _CatalogAnimeCard(anime: _animes[index]);
                  },
                  childCount: _animes.length + (_isLoading ? 1 : 0),
                ),
              ),
            ),
            
            const SliverToBoxAdapter(child: SizedBox(height: 100)), // Отступ для нижнего бара
        ],
      ),
    );
  }
}

// Карточка для каталога, аналогичная HomeScreen, чтобы интерфейс был однородным
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
    _controller = AnimationController(duration: const Duration(milliseconds: 180), vsync: this);
    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.05).animate(CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final score = widget.anime.score ?? 0.0;
    final scoreColor = score >= 8.0
        ? CupertinoColors.systemGreen
        : score >= 6.0 ? CupertinoColors.systemOrange : CupertinoColors.systemRed;

    return MouseRegion(
      onEnter: (_) => _controller.forward(),
      onExit: (_) => _controller.reverse(),
      child: GestureDetector(
        onTapDown: (_) => _controller.forward(),
        onTapUp: (_) => _controller.reverse(),
        onTapCancel: () => _controller.reverse(),
        onTap: () => Navigator.of(context).push(CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: widget.anime.id))),
        child: AnimatedBuilder(
          animation: _scaleAnimation,
          builder: (context, child) => Transform.scale(scale: _scaleAnimation.value, child: child),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: Stack(
              fit: StackFit.expand,
              children: [
                CachedNetworkImage(
                  imageUrl: widget.anime.imageUrl ?? '',
                  fit: BoxFit.cover,
                  memCacheWidth: 400,
                  memCacheHeight: 600,
                  errorWidget: (_, __, ___) => Container(color: const Color(0xFF2A2A2A), child: const Icon(CupertinoIcons.photo, color: CupertinoColors.systemGrey)),
                ),
                if (score > 0)
                  Positioned(
                    top: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(color: scoreColor.withValues(alpha: 0.9), borderRadius: BorderRadius.circular(12)),
                      child: Text(score.toStringAsFixed(1), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 13)),
                    ),
                  ),
                Positioned(
                  bottom: 0, left: 0, right: 0,
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Colors.transparent, Colors.black87]),
                    ),
                    child: Text(
                      widget.anime.russian ?? widget.anime.name ?? 'Без названия',
                      maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600, fontSize: 14),
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