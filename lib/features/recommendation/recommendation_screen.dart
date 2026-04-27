import 'dart:ui';
import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; 
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';

import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../anime_detail/anime_detail_screen.dart';

class RecommendationScreen extends StatefulHookConsumerWidget {
  const RecommendationScreen({super.key});

  @override
  ConsumerState<RecommendationScreen> createState() => _RecommendationScreenState();
}

class _RecommendationScreenState extends ConsumerState<RecommendationScreen> {
  final List<ShikimoriAnime> _queue = [];
  bool _isLoading = false;
  
  // Прогресс свайпа (от -1.0 до 1.0) для отображения градиентов
  double _swipeProgress = 0.0;
  
  // Настройки подборки
  String _currentKind = 'tv'; // По умолчанию ищем сериалы
  
  @override
  void initState() {
    super.initState();
    _loadMoreAnime();
  }

  // 🔥 УМНАЯ ОЧЕРЕДЬ С ОБХОДОМ КЭША
  Future<void> _loadMoreAnime({bool reset = false}) async {
    if (_isLoading) return;
    setState(() => _isLoading = true);

    try {
      final api = ref.read(apiClientProvider);
      
      // Оценка строго целое число, иначе API выдает 422!
      final filters = <String, dynamic>{
        'order': 'random',
        'score': 6, 
        if (_currentKind != 'all') 'kind': _currentKind,
      };

      // Обход кэша Shikimori
      final randomPage = math.Random().nextInt(20) + 1;

      final animes = await api.getAnimes(page: randomPage, limit: 15, filters: filters);
      
      if (mounted) {
        setState(() {
          if (reset) _queue.clear();
          for (var a in animes) {
            if (!_queue.any((existing) => existing.id == a.id)) {
              _queue.add(a);
            }
          }
        });
        
        if (_queue.length > 1) {
          final nextUrl = _queue[1].imageUrl ?? '';
          if (nextUrl.isNotEmpty) {
            precacheImage(CachedNetworkImageProvider(nextUrl), context).catchError((_) {});
          }
        }
      }
    } catch (e) {
      if (mounted) {
        showCupertinoDialog(
          context: context,
          builder: (ctx) => CupertinoAlertDialog(
            title: const Text('Ошибка'),
            content: Text('Не удалось загрузить рекомендации.\n$e'),
            actions: [CupertinoDialogAction(child: const Text('OK'), onPressed: () => Navigator.pop(ctx))],
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  // 🔥 ЛОГИКА ДОБАВЛЕНИЯ В СПИСОК (СВАЙП ВПРАВО)
  Future<void> _addToWatching(ShikimoriAnime anime) async {
    try {
      final api = ref.read(apiClientProvider);
      final user = await ref.read(currentUserProvider.future);
      
      if (user != null) {
        await api.setUserRate(anime.id, 'watching', userId: user.id);
        ref.invalidate(currentUserProvider); // Обновляем профиль в фоне
      }
    } catch (e) {
      debugPrint('Ошибка при добавлении в список: $e');
    }
  }

  // Внутренняя функция продвижения очереди
  void _advanceQueue() {
    if (_queue.isEmpty) return;
    
    setState(() {
      _queue.removeAt(0); 
      _swipeProgress = 0.0; // Сбрасываем оверлей для новой карточки
    });

    if (_queue.length <= 3) {
      _loadMoreAnime();
    }

    if (_queue.length > 1) {
      final nextUrl = _queue[1].imageUrl ?? '';
      if (nextUrl.isNotEmpty) {
        precacheImage(CachedNetworkImageProvider(nextUrl), context).catchError((_) {});
      }
    }
  }

  void _onSwipeRight(ShikimoriAnime anime) {
    _addToWatching(anime);
    _advanceQueue();
  }

  void _onSwipeLeft(ShikimoriAnime anime) {
    _advanceQueue();
  }

  void _showFilters() {
    showCupertinoModalPopup(
      context: context,
      builder: (ctx) => CupertinoActionSheet(
        title: const Text('Что будем искать?'),
        message: const Text('Алгоритм подберет случайные аниме с хорошим рейтингом.'),
        actions: [
          _buildFilterAction(ctx, 'Любой формат', 'all'),
          _buildFilterAction(ctx, 'TV Сериалы', 'tv'),
          _buildFilterAction(ctx, 'Полнометражные фильмы', 'movie'),
        ],
        cancelButton: CupertinoActionSheetAction(
          isDestructiveAction: true,
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Отмена'),
        ),
      ),
    );
  }

  CupertinoActionSheetAction _buildFilterAction(BuildContext ctx, String title, String kind) {
    final isActive = _currentKind == kind;
    return CupertinoActionSheetAction(
      onPressed: () {
        Navigator.pop(ctx);
        if (!isActive) {
          setState(() => _currentKind = kind);
          _loadMoreAnime(reset: true);
        }
      },
      child: Text(
        title,
        style: TextStyle(
          color: isActive ? const Color(0xFFFF5722) : CupertinoColors.activeBlue,
          fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_queue.isEmpty && _isLoading) {
      return const CupertinoPageScaffold(
        backgroundColor: Color(0xFF0F0F0F),
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CupertinoActivityIndicator(radius: 20),
              SizedBox(height: 20),
              Text('Подбираем шедевры...', style: TextStyle(color: CupertinoColors.systemGrey, fontSize: 16)),
            ],
          ),
        ),
      );
    }

    if (_queue.isEmpty) {
      return CupertinoPageScaffold(
        backgroundColor: const Color(0xFF0F0F0F),
        child: Center(
          child: CupertinoButton(
            color: const Color(0xFF1E1E1E),
            onPressed: () => _loadMoreAnime(reset: true),
            child: const Text('Попробовать снова', style: TextStyle(color: Color(0xFFFF5722))),
          ),
        ),
      );
    }

    final topAnime = _queue.first;

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      // Нативная прозрачная навигационная панель с кнопкой фильтров
      navigationBar: CupertinoNavigationBar(
        middle: const Text('Что посмотреть?', style: TextStyle(color: Colors.white)),
        backgroundColor: Colors.transparent,
        border: null,
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: _showFilters,
          child: Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: const Icon(CupertinoIcons.slider_horizontal_3, color: Colors.white, size: 18),
          ),
        ),
      ),
      child: Stack(
        fit: StackFit.expand,
        children: [
          // ==================== 1. ИММЕРСИВНЫЙ БЛЮР-ФОН ====================
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 600),
            child: CachedNetworkImage(
              key: ValueKey(topAnime.id),
              imageUrl: topAnime.imageUrl ?? '',
              fit: BoxFit.cover,
              width: double.infinity,
              height: double.infinity,
              memCacheWidth: 100, 
              errorWidget: (_, __, ___) => Container(color: const Color(0xFF0F0F0F)),
            ),
          ),
          Positioned.fill(
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 50, sigmaY: 50),
              child: Container(color: Colors.black.withValues(alpha: 0.6)),
            ),
          ),

          // ==================== 2. АДАПТИВНАЯ ВЕРСТКА ====================
          SafeArea(
            bottom: false, // Отключаем нижнюю SafeArea для своего отступа
            child: LayoutBuilder(
              builder: (context, constraints) {
                // Адаптация: Определяем, запущен ли апп на широком экране (Windows/iPad)
                final isDesktop = constraints.maxWidth > 600;
                // На ПК ограничиваем ширину карточки, чтобы она не была гигантской
                final cardMaxWidth = isDesktop ? 420.0 : constraints.maxWidth;

                return Column(
                  children: [
                    // --- ЗОНА КАРТОЧЕК (Занимает всё оставшееся свободное место) ---
                    Expanded(
                      child: Center(
                        child: ConstrainedBox(
                          constraints: BoxConstraints(maxWidth: cardMaxWidth),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                            child: Stack(
                              children: [
                                // ЗАДНЯЯ КАРТА (Индекс 1)
                                if (_queue.length > 1)
                                  Positioned.fill(
                                    child: AnimatedScale(
                                      scale: 0.92,
                                      duration: const Duration(milliseconds: 300),
                                      child: AnimatedOpacity(
                                        opacity: 0.7,
                                        duration: const Duration(milliseconds: 300),
                                        child: _buildAnimeCard(_queue[1], swipeProgress: 0.0),
                                      ),
                                    ),
                                  ),

                                // ПЕРЕДНЯЯ КАРТА (Интерактивная)
                                Positioned.fill(
                                  child: Dismissible(
                                    key: ValueKey(topAnime.id),
                                    direction: DismissDirection.horizontal,
                                    onUpdate: (details) {
                                      setState(() {
                                        _swipeProgress = details.direction == DismissDirection.startToEnd
                                            ? details.progress  // Свайп вправо (Смотрю)
                                            : -details.progress; // Свайп влево (Пропуск)
                                      });
                                    },
                                    onDismissed: (direction) {
                                      if (direction == DismissDirection.startToEnd) {
                                        _onSwipeRight(topAnime);
                                      } else {
                                        _onSwipeLeft(topAnime);
                                      }
                                    },
                                    child: AnimatedScale(
                                      scale: 1.0,
                                      duration: const Duration(milliseconds: 300),
                                      child: _buildAnimeCard(topAnime, swipeProgress: _swipeProgress),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),

                    // --- ПАНЕЛЬ УПРАВЛЕНИЯ (Всегда прибита вниз и не уезжает) ---
                    Container(
                      width: double.infinity,
                      // 🔥 КРИТИЧНЫЙ ФИКС: Отступ снизу для обхода LiquidGlassBar
                      padding: EdgeInsets.only(
                        bottom: MediaQuery.of(context).padding.bottom + 110,
                        top: 16,
                      ),
                      child: Center(
                        child: ConstrainedBox(
                          constraints: BoxConstraints(maxWidth: cardMaxWidth),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              // Кнопка СКИП (Крестик)
                              _buildActionButton(
                                icon: CupertinoIcons.xmark,
                                color: CupertinoColors.systemRed,
                                onTap: () => _onSwipeLeft(topAnime),
                              ),
                              const SizedBox(width: 24),
                              // Кнопка СМОТРЮ (Сердечко)
                              _buildActionButton(
                                icon: CupertinoIcons.heart_fill,
                                color: CupertinoColors.systemGreen,
                                onTap: () => _onSwipeRight(topAnime),
                                size: 76,
                                iconSize: 36,
                              ),
                              const SizedBox(width: 24),
                              // Кнопка ИНФО (Оранжевая)
                              _buildActionButton(
                                icon: CupertinoIcons.info,
                                color: const Color(0xFFFF5722),
                                onTap: () {
                                  Navigator.push(
                                    context,
                                    CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: topAnime.id)),
                                  );
                                },
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  // 🔥 Карточка с поддержкой Оверлеев (Градиенты + Штампы)
  Widget _buildAnimeCard(ShikimoriAnime anime, {required double swipeProgress}) {
    final score = anime.score ?? 0.0;
    
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(32),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: 0.4), blurRadius: 30, offset: const Offset(0, 15)),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(32),
        child: Stack(
          fit: StackFit.expand,
          children: [
            CachedNetworkImage(
              imageUrl: anime.imageUrl ?? '',
              fit: BoxFit.cover,
              memCacheWidth: 600,
              memCacheHeight: 900,
              placeholder: (_, __) => Container(color: const Color(0xFF1E1E1E)),
              errorWidget: (_, __, ___) => Container(color: const Color(0xFF1E1E1E), child: const Icon(CupertinoIcons.photo, color: Colors.grey, size: 50)),
            ),
            
            Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Colors.transparent, Colors.black87],
                  stops: [0.5, 1.0],
                ),
              ),
            ),
            
            Positioned(
              bottom: 24,
              left: 24,
              right: 24,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (score > 0)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: score >= 8.0 ? CupertinoColors.systemGreen : CupertinoColors.systemOrange,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        '★ ${score.toStringAsFixed(1)}',
                        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
                      ),
                    ),
                  const SizedBox(height: 12),
                  Text(
                    anime.russian ?? anime.name ?? 'Без названия',
                    style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900, height: 1.1),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    anime.status == 'released' ? 'Вышло' : (anime.status == 'ongoing' ? 'Онгоинг' : 'Анонс'),
                    style: const TextStyle(color: CupertinoColors.systemGrey2, fontSize: 16, fontWeight: FontWeight.w500),
                  ),
                ],
              ),
            ),

            // 🔥 ВИЗУАЛЬНЫЙ ОТКЛИК НА СВАЙП
            if (swipeProgress != 0.0)
              Positioned.fill(
                child: Container(
                  color: (swipeProgress > 0 ? CupertinoColors.systemGreen : CupertinoColors.systemRed)
                      .withValues(alpha: (swipeProgress.abs() * 0.4).clamp(0.0, 0.4)), // Легкая заливка карточки
                  child: Center(
                    child: Transform.rotate(
                      angle: swipeProgress > 0 ? -0.2 : 0.2, // Наклон штампа
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                        decoration: BoxDecoration(
                          border: Border.all(
                            color: (swipeProgress > 0 ? CupertinoColors.systemGreen : CupertinoColors.systemRed)
                                .withValues(alpha: swipeProgress.abs().clamp(0.0, 1.0)),
                            width: 5,
                          ),
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Text(
                          swipeProgress > 0 ? 'СМОТРЮ' : 'ПРОПУСК',
                          style: TextStyle(
                            color: (swipeProgress > 0 ? CupertinoColors.systemGreen : CupertinoColors.systemRed)
                                .withValues(alpha: swipeProgress.abs().clamp(0.0, 1.0)),
                            fontSize: 42,
                            fontWeight: FontWeight.w900,
                            letterSpacing: 2,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionButton({
    required IconData icon, 
    required Color color, 
    required VoidCallback onTap,
    double size = 64,
    double iconSize = 28,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: const Color(0xFF1E1E1E).withValues(alpha: 0.8),
          border: Border.all(color: color.withValues(alpha: 0.5), width: 2),
          boxShadow: [
            BoxShadow(color: color.withValues(alpha: 0.2), blurRadius: 20, spreadRadius: 2),
          ],
        ),
        child: Icon(icon, color: color, size: iconSize),
      ),
    );
  }
}