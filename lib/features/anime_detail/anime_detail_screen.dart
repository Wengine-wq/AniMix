import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:dio/dio.dart';


import '../../models/shikimori_anime_detail.dart';
import '../../models/shikimori_anime.dart';
import '../../models/shikimori_comment.dart'; // ← ИМПОРТ КОММЕНТАРИЕВ
import '../../providers/user_provider.dart';
import '../watch/watch_provider_selection_screen.dart';

class AnimeDetailScreen extends StatefulHookConsumerWidget {
  final int animeId;
  const AnimeDetailScreen({required this.animeId, super.key});

  @override
  ConsumerState<AnimeDetailScreen> createState() => _AnimeDetailScreenState();
}

class _AnimeDetailScreenState extends ConsumerState<AnimeDetailScreen> {
  ShikimoriAnimeDetail? anime;
  List<String> screenshots = [];
  List<Map<String, dynamic>> relatedList = [];
  List<ShikimoriComment> commentsList = [];
  
  bool isLoading = true;
  String? error;
  String? currentUserStatus;

  @override
  void initState() {
    super.initState();
    _loadAnime();
  }

  void _showWatchOptions() {
    if (anime == null) return;
    Navigator.push(
      context,
      CupertinoPageRoute(
        builder: (_) => WatchProviderSelectionScreen(
          animeId: widget.animeId,
          // 🔥 ИСПРАВЛЕНО: Передаем оба названия для умного поиска
          animeNameRu: anime!.russian ?? '',
          animeNameEn: anime!.name ?? '',
        ),
      ),
    );
  }

  Future<void> _loadAnime() async {
    try {
      final api = ref.read(apiClientProvider);
      final user = await ref.read(currentUserProvider.future);
      
      if (user == null) throw Exception('Пользователь не авторизован');

      final detailFuture = api.getAnimeDetail(widget.animeId);
      final screenshotsFuture = api.getAnimeScreenshots(widget.animeId);
      final rateFuture = api.getUserRate(widget.animeId, userId: user.id);
      
      final detail = await detailFuture;
      final allScreenshots = await screenshotsFuture;
      final rate = await rateFuture;

      // 🔥 Загружаем связи и комментарии в фоне, не давая им сломать основной экран в случае ошибки
      List<Map<String, dynamic>> related = [];
      List<ShikimoriComment> comms = [];
      try { related = await api.getRelatedAnimes(widget.animeId); } catch (_) {}
      try { comms = await api.getComments(widget.animeId); } catch (_) {}

      if (mounted) {
        setState(() {
          anime = detail;
          screenshots = allScreenshots;
          currentUserStatus = rate?['status'] as String?;
          relatedList = related;
          commentsList = comms;
          isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  void _showStatusPicker() {
    final statuses = {
      'planned': 'Запланировано',
      'watching': 'Смотрю',
      'rewatching': 'Пересматриваю',
      'completed': 'Просмотрено',
      'on_hold': 'Отложено',
      'dropped': 'Брошено',
    };

    showCupertinoModalPopup(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: const Text('Статус аниме'),
        actions: statuses.entries.map((e) {
          final isSelected = currentUserStatus == e.key;
          return CupertinoActionSheetAction(
            onPressed: () async {
              Navigator.pop(sheetContext);
              if (!mounted) return;

              try {
                final api = ref.read(apiClientProvider);
                final user = await ref.read(currentUserProvider.future);
                if (user == null) throw Exception('Пользователь не найден');

                await api.setUserRate(widget.animeId, e.key, userId: user.id);
                ref.invalidate(currentUserProvider);

                if (mounted) setState(() => currentUserStatus = e.key);
                if (mounted) {
                  showCupertinoDialog(
                    context: context,
                    builder: (ctx) => CupertinoAlertDialog(
                      title: const Text('Готово'),
                      content: Text('Статус обновлён: ${e.value}'),
                      actions: [CupertinoDialogAction(child: const Text('ОК'), onPressed: () => Navigator.pop(ctx))],
                    ),
                  );
                }
              } catch (e) {
                if (!mounted) return;
                String message = e.toString();
                if (e is DioException) {
                  message = 'Ошибка ${e.response?.statusCode ?? "неизвестно"}\n${e.response?.data ?? ""}';
                }
                if (mounted) {
                  showCupertinoDialog(
                    context: context,
                    builder: (ctx) => CupertinoAlertDialog(
                      title: const Text('Ошибка'),
                      content: Text(message),
                      actions: [CupertinoDialogAction(child: const Text('ОК'), onPressed: () => Navigator.pop(ctx))],
                    ),
                  );
                }
              }
            },
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(e.value, style: TextStyle(color: isSelected ? const Color(0xFFFF5722) : CupertinoColors.white)),
                if (isSelected) ...[
                  const SizedBox(width: 8),
                  const Icon(CupertinoIcons.check_mark_circled, color: Color(0xFFFF5722), size: 18),
                ],
              ],
            ),
          );
        }).toList(),
        cancelButton: CupertinoActionSheetAction(onPressed: () => Navigator.pop(sheetContext), child: const Text('Отмена')),
      ),
    );
  }

  // Очистка комментариев от мусорных тегов
  String _cleanCommentBody(String text) {
    String clean = text.replaceAll(RegExp(r'<[^>]*>'), ''); // Убираем HTML
    clean = clean.replaceAll(RegExp(r'\[/?\w+.*?\]'), ''); // Убираем BB-коды (например, [character=123])
    return clean.trim();
  }

  @override
  Widget build(BuildContext context) {
    if (isLoading) {
      return const CupertinoPageScaffold(
        backgroundColor: Color(0xFF0F0F0F),
        navigationBar: CupertinoNavigationBar(middle: Text('AniMix')),
        child: Center(child: CupertinoActivityIndicator(radius: 28)),
      );
    }

    if (error != null || anime == null) {
      return CupertinoPageScaffold(
        backgroundColor: const Color(0xFF0F0F0F),
        navigationBar: const CupertinoNavigationBar(middle: Text('AniMix')),
        child: Center(child: Text(error ?? 'Не удалось загрузить', style: const TextStyle(color: CupertinoColors.white))),
      );
    }

    final a = anime!;
    final screenHeight = MediaQuery.of(context).size.height;
    // Ограничиваем высоту хедера на ПК
    final heroHeight = math.min(screenHeight * 0.52, 550.0);

    String statusText = 'Не указано';
    Color statusColor = CupertinoColors.systemGrey;
    if (currentUserStatus != null) {
      switch (currentUserStatus) {
        case 'watching': statusText = 'Смотрю'; statusColor = CupertinoColors.systemGreen; break;
        case 'completed': statusText = 'Просмотрено'; statusColor = CupertinoColors.systemBlue; break;
        case 'planned': statusText = 'Запланировано'; statusColor = CupertinoColors.systemOrange; break;
        case 'on_hold': statusText = 'Отложено'; statusColor = CupertinoColors.systemYellow; break;
        case 'dropped': statusText = 'Брошено'; statusColor = CupertinoColors.systemRed; break;
        case 'rewatching': statusText = 'Пересматриваю'; statusColor = CupertinoColors.systemPurple; break;
      }
    }

    String episodesText = (a.episodesAired != null && a.episodes != null)
        ? '${a.episodesAired}/${a.episodes} эп.'
        : (a.episodes != null ? '${a.episodes} эп.' : '? эп.');
    String year = a.airedOn?.substring(0, 4) ?? '';

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: const Text('AniMix'),
        leading: CupertinoButton(padding: EdgeInsets.zero, onPressed: () => Navigator.pop(context), child: const Icon(CupertinoIcons.back)),
      ),
      // 🔥 Оборачиваем страницу в Stack, чтобы плавающая кнопка была независимой от скролла!
      child: Stack(
        children: [
          // Основной контент
          SingleChildScrollView(
            padding: const EdgeInsets.only(bottom: 120), // Отступ снизу, чтобы кнопка не перекрывала контент
            child: Column(
              children: [
                // ==================== HERO БЛОК ====================
                SizedBox(
                  height: heroHeight,
                  width: double.infinity,
                  child: Stack(
                    fit: StackFit.expand,
                    clipBehavior: Clip.hardEdge,
                    children: [
                      CachedNetworkImage(
                        imageUrl: a.imageUrl ?? '',
                        width: double.infinity,
                        fit: BoxFit.cover,
                        filterQuality: FilterQuality.high,
                      ),
                      Container(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Colors.transparent, Colors.black.withValues(alpha: 0.65), const Color(0xFF0F0F0F)],
                            stops: const [0.0, 0.6, 1.0],
                          ),
                        ),
                      ),
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 28),
                          child: Container(
                            width: 260,
                            decoration: BoxDecoration(
                              borderRadius: BorderRadius.circular(24),
                              boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.7), blurRadius: 40, spreadRadius: 12, offset: const Offset(0, 20))],
                            ),
                            child: ClipRRect(borderRadius: BorderRadius.circular(24), child: CachedNetworkImage(imageUrl: a.imageUrl ?? '', fit: BoxFit.cover)),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                // ==================== ОПИСАНИЕ И ИНФО ====================
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(a.russian ?? a.name ?? 'Без названия', style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800, color: CupertinoColors.white, height: 1.1)),
                      const SizedBox(height: 4),
                      if (a.english?.isNotEmpty == true) Text(a.english!, style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 17)),
                      const SizedBox(height: 18),
                      
                      Wrap(
                        spacing: 10,
                        runSpacing: 10,
                        children: [
                          GestureDetector(
                            onTap: _showStatusPicker,
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                              decoration: BoxDecoration(color: statusColor.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(999), border: Border.all(color: statusColor.withValues(alpha: 0.4), width: 1.5)),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(CupertinoIcons.person_fill, size: 14, color: statusColor),
                                  const SizedBox(width: 6),
                                  Text(statusText, style: TextStyle(color: statusColor, fontSize: 14, fontWeight: FontWeight.w600)),
                                  const SizedBox(width: 6),
                                  Icon(CupertinoIcons.pencil, size: 13, color: statusColor.withValues(alpha: 0.7)),
                                ],
                              ),
                            ),
                          ),
                          if (year.isNotEmpty) _buildTag(year),
                          _buildTag(episodesText),
                          if (a.score != null) _buildTag('★ ${a.score!.toStringAsFixed(1)}'),
                          _buildTag(a.kind?.toUpperCase() ?? ''),
                        ],
                      ),
                      
                      const SizedBox(height: 26),
                      Text(a.description ?? 'Описание отсутствует', style: const TextStyle(color: CupertinoColors.white, fontSize: 16.5, height: 1.6)),
                      
                      if (a.studios.isNotEmpty) ...[
                        const SizedBox(height: 36),
                        const Text('Студия', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: CupertinoColors.white)),
                        const SizedBox(height: 10),
                        Wrap(spacing: 8, children: a.studios.map((s) => _buildTag(s, color: const Color(0xFFFF5722))).toList()),
                      ],

                      if (a.genres.isNotEmpty) ...[
                        const SizedBox(height: 30),
                        const Text('Жанры', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: CupertinoColors.white)),
                        const SizedBox(height: 12),
                        Wrap(spacing: 8, runSpacing: 8, children: a.genres.map((g) => _buildTag(g, color: const Color(0xFFFF5722))).toList()),
                      ],

                      // ==================== СВЯЗАННЫЕ АНИМЕ (ФРАНШИЗА) ====================
                      if (relatedList.isNotEmpty) ...[
                        const SizedBox(height: 40),
                        const Text('Франшиза и связи', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: CupertinoColors.white)),
                        const SizedBox(height: 14),
                        SizedBox(
                          height: 220,
                          child: ListView.builder(
                            scrollDirection: Axis.horizontal,
                            itemCount: relatedList.length,
                            itemBuilder: (context, index) {
                              final item = relatedList[index];
                              // Проверяем, что связь именно с аниме (мангу пропускаем)
                              final animeData = item['anime'];
                              if (animeData == null) return const SizedBox();
                              
                              final relation = item['relation_russian'] ?? item['relation'] ?? '';
                              final relatedAnime = ShikimoriAnime.fromJson(animeData);

                              return GestureDetector(
                                onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: relatedAnime.id))),
                                child: Container(
                                  width: 140,
                                  margin: const EdgeInsets.only(right: 14),
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      ClipRRect(
                                        borderRadius: BorderRadius.circular(14),
                                        child: CachedNetworkImage(imageUrl: relatedAnime.imageUrl ?? '', height: 160, width: 140, fit: BoxFit.cover, memCacheWidth: 280),
                                      ),
                                      const SizedBox(height: 8),
                                      Text(relation.toUpperCase(), style: const TextStyle(color: Color(0xFFFF5722), fontSize: 10, fontWeight: FontWeight.w800, letterSpacing: 0.5)),
                                      const SizedBox(height: 4),
                                      Text(relatedAnime.russian ?? relatedAnime.name ?? '', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: CupertinoColors.white, fontSize: 13, height: 1.2)),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ],

                      // ==================== КАДРЫ ====================
                      if (screenshots.isNotEmpty) ...[
                        const SizedBox(height: 40),
                        const Text('Кадры из аниме', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: CupertinoColors.white)),
                        const SizedBox(height: 14),
                        SizedBox(
                          height: 200,
                          child: ListView.builder(
                            scrollDirection: Axis.horizontal,
                            itemCount: screenshots.length,
                            itemBuilder: (context, index) => GestureDetector(
                              onTap: () => _showFullscreenGallery(index),
                              child: Padding(
                                padding: const EdgeInsets.only(right: 14),
                                child: ClipRRect(borderRadius: BorderRadius.circular(16), child: CachedNetworkImage(imageUrl: screenshots[index], height: 200, width: 340, fit: BoxFit.cover)),
                              ),
                            ),
                          ),
                        ),
                      ],

                      // ==================== КОММЕНТАРИИ ====================
                      if (commentsList.isNotEmpty) ...[
                        const SizedBox(height: 40),
                        const Text('Отзывы', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: CupertinoColors.white)),
                        const SizedBox(height: 16),
                        ListView.builder(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: math.min(commentsList.length, 15), // Ограничиваем до 15, чтобы не перегружать страницу
                          itemBuilder: (context, index) {
                            final c = commentsList[index];
                            final cleanBody = _cleanCommentBody(c.body);
                            if (cleanBody.isEmpty) return const SizedBox();

                            return Container(
                              margin: const EdgeInsets.only(bottom: 16),
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(20)),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  CircleAvatar(
                                    radius: 20,
                                    backgroundColor: const Color(0xFF2A2A2A),
                                    backgroundImage: c.userAvatar != null ? CachedNetworkImageProvider(c.userAvatar!) : null,
                                    child: c.userAvatar == null ? const Icon(CupertinoIcons.person_fill, color: Colors.grey) : null,
                                  ),
                                  const SizedBox(width: 16),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(c.userNickname ?? 'Пользователь', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15)),
                                        const SizedBox(height: 6),
                                        Text(cleanBody, style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 14.5, height: 1.4)),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),

          // 🔥 ПЛАВАЮЩАЯ КНОПКА (FAB)
          Positioned(
            bottom: MediaQuery.of(context).padding.bottom + 24, // Учитываем шторку iPhone
            right: 24,
            child: GestureDetector(
              onTap: _showWatchOptions,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 16),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFFFF5722), Color(0xFFFF8A65)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(30),
                  boxShadow: [
                    BoxShadow(color: const Color(0xFFFF5722).withValues(alpha: 0.5), blurRadius: 20, spreadRadius: 2, offset: const Offset(0, 8)),
                  ],
                ),
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(CupertinoIcons.play_fill, color: Colors.white, size: 24),
                    SizedBox(width: 8),
                    Text('Смотреть', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w800, letterSpacing: -0.5)),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTag(String text, {Color color = CupertinoColors.systemGrey}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(999)),
      child: Text(text, style: TextStyle(color: color, fontSize: 14, fontWeight: FontWeight.w500)),
    );
  }

  void _showFullscreenGallery(int startIndex) {
    if (screenshots.isEmpty) return;
    showCupertinoDialog(
      context: context,
      barrierDismissible: true,
      builder: (ctx) => _FullscreenGallery(screenshots: screenshots, initialIndex: startIndex),
    );
  }
}

class _FullscreenGallery extends StatefulWidget {
  final List<String> screenshots;
  final int initialIndex;
  const _FullscreenGallery({required this.screenshots, required this.initialIndex});

  @override
  State<_FullscreenGallery> createState() => _FullscreenGalleryState();
}

class _FullscreenGalleryState extends State<_FullscreenGallery> {
  late PageController _pageController;
  int _currentIndex = 0;

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.initialIndex;
    _pageController = PageController(initialPage: widget.initialIndex);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        middle: Text('${_currentIndex + 1} / ${widget.screenshots.length}'),
        leading: CupertinoButton(padding: EdgeInsets.zero, onPressed: () => Navigator.pop(context), child: const Icon(CupertinoIcons.xmark)),
      ),
      child: Stack(
        children: [
          PageView.builder(
            controller: _pageController,
            onPageChanged: (index) => setState(() => _currentIndex = index),
            itemCount: widget.screenshots.length,
            itemBuilder: (context, index) => Center(
              child: InteractiveViewer(
                minScale: 0.5,
                maxScale: 4.0,
                child: CachedNetworkImage(imageUrl: widget.screenshots[index], fit: BoxFit.contain),
              ),
            ),
          ),
        ],
      ),
    );
  }
}