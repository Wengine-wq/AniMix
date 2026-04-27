import 'dart:ui';
import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:dio/dio.dart';

import '../../models/shikimori_anime_detail.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../watch/watch_provider_selection_screen.dart';
import '../data/comments_screen.dart';

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
  List<ShikimoriAnime> similarList = [];
  
  // Статистика с Шикимори
  int _statsWatching = 0;
  int _statsPlanned = 0;
  int _statsCompleted = 0;
  
  // Данные пользователя
  String? currentUserStatus;
  int currentUserScore = 0;
  int currentUserEpisodes = 0;
  int? currentUserId;

  bool isLoading = true;
  String? error;

  @override
  void initState() {
    super.initState();
    _loadAnime();
  }

  Future<void> _loadAnime() async {
    try {
      final api = ref.read(apiClientProvider);
      final user = await ref.read(currentUserProvider.future);
      currentUserId = user?.id;

      if (user == null) throw Exception('Пользователь не авторизован');

      // Безопасные параллельные запросы (если что-то не загрузится, экран все равно откроется)
      final futures = await Future.wait([
        api.getAnimeDetail(widget.animeId),
        api.getAnimeScreenshots(widget.animeId),
        api.getUserRate(widget.animeId, userId: user.id),
        Dio().get('https://shikimori.io/api/animes/${widget.animeId}').catchError((_) => Response(requestOptions: RequestOptions(path: ''), data: {})),
        Dio().get('https://shikimori.io/api/animes/${widget.animeId}/similar').catchError((_) => Response(requestOptions: RequestOptions(path: ''), data: [])),
        api.getRelatedAnimes(widget.animeId).catchError((_) => <Map<String, dynamic>>[]),
      ]);

      final detail = futures[0] as ShikimoriAnimeDetail;
      final allScreenshots = futures[1] as List<String>;
      final rate = futures[2] as Map<String, dynamic>?;
      final rawData = (futures[3] as Response).data;
      final similarData = (futures[4] as Response).data;
      final related = futures[5] as List<Map<String, dynamic>>;

      // Парсим настоящую статистику (Шикимори отдает русские названия статусов в rates_statuses_stats)
      int watching = 0, planned = 0, completed = 0;
      if (rawData is Map<String, dynamic>) {
        final statuses = rawData['rates_statuses_stats'] as List?;
        if (statuses != null) {
          for (var s in statuses) {
            final name = s['name'];
            if (name == 'watching' || name == 'Смотрю') watching = s['value'];
            if (name == 'planned' || name == 'В планах' || name == 'Запланировано') planned = s['value'];
            if (name == 'completed' || name == 'Просмотрено') completed = s['value'];
          }
        }
      }

      List<ShikimoriAnime> similar = [];
      if (similarData is List) {
        similar = similarData.map((e) => ShikimoriAnime.fromJson(e)).toList();
      }
      
      if (mounted) {
        setState(() {
          anime = detail;
          screenshots = allScreenshots;
          relatedList = related;
          similarList = similar;
          
          currentUserStatus = rate?['status'] as String?;
          currentUserScore = rate?['score'] as int? ?? 0;
          currentUserEpisodes = rate?['episodes'] as int? ?? 0;
          
          _statsWatching = watching;
          _statsPlanned = planned;
          _statsCompleted = completed;
          
          isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  // Красивое форматирование цифр для статистики
  String _formatStat(int value) {
    if (value == 0) return '0';
    if (value >= 1000) {
      return '${(value / 1000).toStringAsFixed(1).replaceAll('.0', '')}k';
    }
    return value.toString();
  }

  void _showWatchOptions() {
    if (anime == null) return;
    Navigator.of(context, rootNavigator: true).push(
      CupertinoPageRoute(
        builder: (_) => WatchProviderSelectionScreen(
          animeId: widget.animeId,
          animeNameRu: anime!.russian ?? '',
          animeNameEn: anime!.name ?? '',
        ),
      ),
    );
  }

  // =======================================================================
  // 🔥 МОДАЛЬНОЕ ОКНО ОЦЕНКИ И СТАТУСА (С МГНОВЕННОЙ СИНХРОНИЗАЦИЕЙ)
  // =======================================================================
  void _showRatingModal() {
    if (currentUserId == null || anime == null) return;
    
    HapticFeedback.mediumImpact();
    
    String tempStatus = currentUserStatus ?? 'planned';
    int tempScore = currentUserScore;
    int tempEps = currentUserEpisodes;
    bool isSaving = false;
    final int maxEps = anime!.episodes ?? anime!.episodesAired ?? 0;

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setModalState) {
          return Container(
            margin: const EdgeInsets.all(16),
            padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(36),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 40, sigmaY: 40),
                child: Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1A1A1A).withOpacity(0.6),
                    borderRadius: BorderRadius.circular(36),
                    border: Border.all(color: Colors.white.withOpacity(0.15), width: 1.5),
                    boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.3), blurRadius: 30)],
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(width: 40, height: 5, decoration: BoxDecoration(color: Colors.white30, borderRadius: BorderRadius.circular(10))),
                      const SizedBox(height: 24),
                      
                      const Text('Мой список', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 24),

                      // Статусы
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            _StatusChip(title: 'Смотрю', value: 'watching', groupValue: tempStatus, onTap: (v) => setModalState(() => tempStatus = v)),
                            _StatusChip(title: 'В планах', value: 'planned', groupValue: tempStatus, onTap: (v) => setModalState(() => tempStatus = v)),
                            _StatusChip(title: 'Просмотрено', value: 'completed', groupValue: tempStatus, onTap: (v) {
                               setModalState(() {
                                 tempStatus = v;
                                 if (maxEps > 0) tempEps = maxEps; // Автоматически ставим макс. эпизоды
                               });
                            }),
                            _StatusChip(title: 'Отложено', value: 'on_hold', groupValue: tempStatus, onTap: (v) => setModalState(() => tempStatus = v)),
                            _StatusChip(title: 'Брошено', value: 'dropped', groupValue: tempStatus, onTap: (v) => setModalState(() => tempStatus = v)),
                          ],
                        ),
                      ),
                      
                      const SizedBox(height: 32),
                      
                      // Оценка
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text('Оценка', style: TextStyle(color: Colors.white70, fontSize: 16)),
                          Text(tempScore == 0 ? 'Без оценки' : '★ $tempScore', style: const TextStyle(color: Color(0xFFFF5722), fontSize: 20, fontWeight: FontWeight.bold)),
                        ],
                      ),
                      const SizedBox(height: 8),
                      CupertinoSlider(
                        value: tempScore.toDouble(),
                        min: 0,
                        max: 10,
                        divisions: 10,
                        activeColor: const Color(0xFFFF5722),
                        onChanged: (v) {
                          HapticFeedback.selectionClick();
                          setModalState(() => tempScore = v.toInt());
                        },
                      ),
                      
                      const SizedBox(height: 32),
                      
                      // Эпизоды
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text('Эпизоды', style: TextStyle(color: Colors.white70, fontSize: 16)),
                          Container(
                            decoration: BoxDecoration(color: Colors.white.withOpacity(0.1), borderRadius: BorderRadius.circular(16)),
                            child: Row(
                              children: [
                                CupertinoButton(
                                  padding: const EdgeInsets.symmetric(horizontal: 16),
                                  child: const Icon(CupertinoIcons.minus, color: Colors.white),
                                  onPressed: () {
                                    if (tempEps > 0) {
                                      HapticFeedback.lightImpact();
                                      setModalState(() => tempEps--);
                                    }
                                  },
                                ),
                                Text('$tempEps ${maxEps > 0 ? '/ $maxEps' : ''}', style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
                                CupertinoButton(
                                  padding: const EdgeInsets.symmetric(horizontal: 16),
                                  child: const Icon(CupertinoIcons.plus, color: Colors.white),
                                  onPressed: () {
                                    if (maxEps == 0 || tempEps < maxEps) {
                                      HapticFeedback.lightImpact();
                                      setModalState(() => tempEps++);
                                    }
                                  },
                                ),
                              ],
                            ),
                          )
                        ],
                      ),
                      
                      const SizedBox(height: 40),
                      
                      // Кнопка сохранения (Синхронизация с сервером)
                      SizedBox(
                        width: double.infinity,
                        child: CupertinoButton(
                          color: const Color(0xFFFF5722),
                          borderRadius: BorderRadius.circular(20),
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          onPressed: isSaving ? null : () async {
                            setModalState(() => isSaving = true);
                            try {
                              await ref.read(apiClientProvider).setUserRate(
                                widget.animeId, 
                                tempStatus,
                                score: tempScore,
                                episodes: tempEps,
                                userId: currentUserId!
                              );
                              ref.invalidate(currentUserProvider); // Обновляем профиль глобально
                              
                              if (mounted) {
                                setState(() {
                                  currentUserStatus = tempStatus;
                                  currentUserScore = tempScore;
                                  currentUserEpisodes = tempEps;
                                });
                                Navigator.pop(context);
                              }
                            } catch (_) {
                              setModalState(() => isSaving = false);
                            }
                          },
                          child: isSaving 
                            ? const CupertinoActivityIndicator(color: Colors.white)
                            : const Text('Сохранить', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
                        ),
                      )
                    ],
                  ),
                ),
              ),
            ),
          );
        }
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (isLoading) {
      return const CupertinoPageScaffold(
        backgroundColor: Colors.black,
        child: Center(child: CupertinoActivityIndicator(radius: 20)),
      );
    }

    if (error != null || anime == null) {
      return CupertinoPageScaffold(
        backgroundColor: Colors.black,
        navigationBar: const CupertinoNavigationBar(backgroundColor: Colors.black, middle: Text('Ошибка')),
        child: Center(child: Text(error ?? 'Не удалось загрузить', style: const TextStyle(color: CupertinoColors.white))),
      );
    }

    final a = anime!;
    
    // Статус юзера для отображения
    String userStatusText = 'Добавить';
    Color userStatusColor = Colors.white;
    IconData userStatusIcon = CupertinoIcons.add;
    
    if (currentUserStatus != null) {
      userStatusIcon = CupertinoIcons.pencil; // Иконка редактирования, если статус уже есть
      switch (currentUserStatus) {
        case 'watching': userStatusText = 'Смотрю'; userStatusColor = const Color(0xFF4CAF50); break;
        case 'completed': userStatusText = 'Просмотрено'; userStatusColor = const Color(0xFF2196F3); break;
        case 'planned': userStatusText = 'В планах'; userStatusColor = const Color(0xFFFF9800); break;
        case 'on_hold': userStatusText = 'Отложено'; userStatusColor = const Color(0xFFFFC107); break;
        case 'dropped': userStatusText = 'Брошено'; userStatusColor = const Color(0xFFF44336); break;
        case 'rewatching': userStatusText = 'Пересматриваю'; userStatusColor = const Color(0xFF9C27B0); break;
      }
    }

    return Scaffold(
      backgroundColor: Colors.black,
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: CupertinoButton(
          padding: EdgeInsets.zero, 
          onPressed: () => Navigator.pop(context), 
          child: Container(
            margin: const EdgeInsets.only(left: 8),
            decoration: BoxDecoration(color: Colors.black.withOpacity(0.4), shape: BoxShape.circle),
            child: const Center(child: Icon(CupertinoIcons.back, color: Colors.white)),
          )
        ),
      ),
      body: Stack(
        children: [
          // 1. АБСОЛЮТНЫЙ ФОН LIQUID GLASS
          Positioned.fill(
            child: CachedNetworkImage(imageUrl: a.imageUrl ?? '', fit: BoxFit.cover),
          ),
          Positioned.fill(
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 80, sigmaY: 80),
              child: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [Colors.black.withOpacity(0.3), Colors.black.withOpacity(0.85)],
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                  )
                ),
              ),
            ),
          ),

          // 2. КОНТЕНТ (Поверх стекла)
          CustomScrollView(
            physics: const BouncingScrollPhysics(),
            slivers: [
              SliverToBoxAdapter(
                child: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        // Постер с Glass границами
                        Container(
                          width: MediaQuery.of(context).size.width * 0.6,
                          height: MediaQuery.of(context).size.width * 0.85,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(32),
                            border: Border.all(color: Colors.white.withOpacity(0.2), width: 1),
                            boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.5), blurRadius: 40, offset: const Offset(0, 20))],
                          ),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(32),
                            child: CachedNetworkImage(imageUrl: a.imageUrl ?? '', fit: BoxFit.cover),
                          ),
                        ),
                        
                        const SizedBox(height: 30),
                        
                        // Заголовки
                        Text(a.russian ?? a.name ?? 'Без названия', textAlign: TextAlign.center, style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: Colors.white, height: 1.1, letterSpacing: -0.5)),
                        const SizedBox(height: 8),
                        if (a.english?.isNotEmpty == true) Text(a.english!, textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 16)),
                        
                        const SizedBox(height: 24),
                        
                        // Glass Stats Панель (Теперь работает правильно!)
                        _GlassContainer(
                          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                            children: [
                              _StatItem(icon: CupertinoIcons.star_fill, value: (a.score != null && a.score! > 0) ? a.score.toString() : '?', label: 'Оценка', color: const Color(0xFFFFC107)),
                              _StatItem(icon: CupertinoIcons.eye_solid, value: _formatStat(_statsWatching), label: 'Смотрят', color: const Color(0xFF4CAF50)),
                              _StatItem(icon: CupertinoIcons.bookmark_fill, value: _formatStat(_statsPlanned), label: 'В планах', color: const Color(0xFF2196F3)),
                            ],
                          ),
                        ),
                        
                        const SizedBox(height: 24),
                        
                        // Кнопки действий
                        Row(
                          children: [
                            Expanded(
                              flex: 3,
                              child: GestureDetector(
                                onTap: _showWatchOptions,
                                child: Container(
                                  height: 58,
                                  decoration: BoxDecoration(
                                    gradient: const LinearGradient(colors: [Color(0xFFFF5722), Color(0xFFFF8A65)]),
                                    borderRadius: BorderRadius.circular(20),
                                    boxShadow: [BoxShadow(color: const Color(0xFFFF5722).withOpacity(0.4), blurRadius: 15, offset: const Offset(0, 5))],
                                  ),
                                  child: const Row(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      Icon(CupertinoIcons.play_fill, color: Colors.white, size: 20),
                                      SizedBox(width: 8),
                                      Text('Смотреть', style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w700)),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              flex: 2,
                              child: GestureDetector(
                                onTap: _showRatingModal,
                                child: _GlassContainer(
                                  padding: EdgeInsets.zero,
                                  child: SizedBox(
                                    height: 58,
                                    child: Center(
                                      child: Column(
                                        mainAxisAlignment: MainAxisAlignment.center,
                                        children: [
                                          Row(
                                            mainAxisAlignment: MainAxisAlignment.center,
                                            children: [
                                              Icon(userStatusIcon, color: userStatusColor, size: 14),
                                              const SizedBox(width: 6),
                                              Text(userStatusText, style: TextStyle(color: userStatusColor, fontSize: 13, fontWeight: FontWeight.w800)),
                                            ],
                                          ),
                                          if (currentUserScore > 0) ...[
                                            const SizedBox(height: 2),
                                            Text('★ $currentUserScore из 10', style: TextStyle(color: Colors.white.withOpacity(0.7), fontSize: 10, fontWeight: FontWeight.w600)),
                                          ]
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                        
                        const SizedBox(height: 32),
                        
                        // Инфо Тэги
                        Wrap(
                          spacing: 10,
                          runSpacing: 10,
                          alignment: WrapAlignment.center,
                          children: [
                            if (a.airedOn?.isNotEmpty == true) _GlassTag(text: a.airedOn!.substring(0, 4)),
                            _GlassTag(text: a.kind?.toUpperCase() ?? 'TV'),
                            _GlassTag(text: '${a.episodesAired ?? a.episodes ?? '?'} / ${a.episodes ?? '?'} эп.'),
                            if (a.status == 'released') const _GlassTag(text: 'Вышло', color: Color(0xFF4CAF50))
                            else if (a.status == 'ongoing') const _GlassTag(text: 'Онгоинг', color: Color(0xFFFF5722)),
                          ],
                        ),

                        // Студии
                        if (a.studios.isNotEmpty) ...[
                          const SizedBox(height: 32),
                          const Align(alignment: Alignment.centerLeft, child: Text('Студия', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Wrap(
                              spacing: 8, 
                              runSpacing: 8, 
                              children: a.studios.map((s) => _GlassTag(text: s, color: const Color(0xFFE0E0E0))).toList()
                            ),
                          )
                        ],

                        const SizedBox(height: 32),
                        
                        // Описание
                        if (a.description?.isNotEmpty == true) ...[
                          const Align(alignment: Alignment.centerLeft, child: Text('Сюжет', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          _GlassContainer(
                            padding: const EdgeInsets.all(20),
                            child: Text(a.description!, style: TextStyle(color: Colors.white.withOpacity(0.85), fontSize: 15.5, height: 1.5)),
                          ),
                        ],
                        
                        if (a.genres.isNotEmpty) ...[
                          const SizedBox(height: 32),
                          const Align(alignment: Alignment.centerLeft, child: Text('Жанры', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Wrap(spacing: 8, runSpacing: 8, children: a.genres.map((g) => _GlassTag(text: g, color: const Color(0xFFFF5722))).toList()),
                          )
                        ],

                        // Франшиза (Поднята выше)
                        if (relatedList.isNotEmpty) ...[
                          const SizedBox(height: 32),
                          const Align(alignment: Alignment.centerLeft, child: Text('Франшиза', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          SizedBox(
                            height: 220, 
                            child: ListView.builder(
                              scrollDirection: Axis.horizontal,
                              physics: const BouncingScrollPhysics(),
                              itemCount: relatedList.length,
                              itemBuilder: (context, index) {
                                final item = relatedList[index];
                                final animeData = item['anime'];
                                if (animeData == null) return const SizedBox();
                                
                                final relation = item['relation_russian'] ?? item['relation'] ?? '';
                                final relatedAnime = ShikimoriAnime.fromJson(animeData);

                                return GestureDetector(
                                  onTap: () => Navigator.of(context, rootNavigator: true).push(CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: relatedAnime.id))),
                                  child: Container(
                                    width: 130,
                                    margin: const EdgeInsets.only(right: 14),
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        _GlassContainer(
                                          padding: EdgeInsets.zero,
                                          borderRadius: BorderRadius.circular(20),
                                          child: CachedNetworkImage(imageUrl: relatedAnime.imageUrl ?? '', height: 160, width: 130, fit: BoxFit.cover, memCacheWidth: 260),
                                        ),
                                        const SizedBox(height: 8),
                                        Text(relation.toUpperCase(), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFFFF5722), fontSize: 10, fontWeight: FontWeight.w800, letterSpacing: 0.5)),
                                        const SizedBox(height: 2),
                                        Text(relatedAnime.russian ?? relatedAnime.name ?? '', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 13, height: 1.2)),
                                      ],
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                        ],

                        // Похожие Аниме (Спущено ниже)
                        if (similarList.isNotEmpty) ...[
                          const SizedBox(height: 32),
                          const Align(alignment: Alignment.centerLeft, child: Text('Похожее', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          SizedBox(
                            height: 220, 
                            child: ListView.builder(
                              scrollDirection: Axis.horizontal,
                              physics: const BouncingScrollPhysics(),
                              itemCount: similarList.length,
                              itemBuilder: (context, index) {
                                final similarAnime = similarList[index];
                                return GestureDetector(
                                  onTap: () => Navigator.of(context, rootNavigator: true).push(CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: similarAnime.id))),
                                  child: Container(
                                    width: 130,
                                    margin: const EdgeInsets.only(right: 14),
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        _GlassContainer(
                                          padding: EdgeInsets.zero,
                                          borderRadius: BorderRadius.circular(20),
                                          child: CachedNetworkImage(imageUrl: similarAnime.imageUrl ?? '', height: 160, width: 130, fit: BoxFit.cover, memCacheWidth: 260),
                                        ),
                                        const SizedBox(height: 8),
                                        Text(similarAnime.russian ?? similarAnime.name ?? '', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 13, height: 1.2)),
                                      ],
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                        ],

                        if (screenshots.isNotEmpty) ...[
                          const SizedBox(height: 32),
                          const Align(alignment: Alignment.centerLeft, child: Text('Кадры', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          const SizedBox(height: 12),
                          SizedBox(
                            height: 180,
                            child: ListView.builder(
                              scrollDirection: Axis.horizontal,
                              physics: const BouncingScrollPhysics(),
                              itemCount: screenshots.length,
                              itemBuilder: (context, index) => GestureDetector(
                                onTap: () => _showFullscreenGallery(index),
                                child: Padding(
                                  padding: const EdgeInsets.only(right: 14),
                                  child: _GlassContainer(
                                    padding: EdgeInsets.zero,
                                    borderRadius: BorderRadius.circular(20),
                                    child: CachedNetworkImage(imageUrl: screenshots[index], height: 180, width: 300, fit: BoxFit.cover),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],

                        const SizedBox(height: 40),
                        
                        // Обсуждение
                        GestureDetector(
                          onTap: () {
                            if (anime?.topicId != null) {
                              Navigator.of(context, rootNavigator: true).push(
                                CupertinoPageRoute(builder: (_) => CommentsScreen(topicId: anime!.topicId!)),
                              );
                            }
                          },
                          child: _GlassContainer(
                            padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 24),
                            child: const Row(
                              children: [
                                Icon(CupertinoIcons.chat_bubble_2_fill, color: Color(0xFFFF5722), size: 28),
                                SizedBox(width: 16),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text('Обсуждение', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w700)),
                                      SizedBox(height: 4),
                                      Text('Читать и писать комментарии', style: TextStyle(color: Colors.white54, fontSize: 14)),
                                    ],
                                  ),
                                ),
                                Icon(CupertinoIcons.chevron_right, color: Colors.white54),
                              ],
                            ),
                          ),
                        ),
                        
                        const SizedBox(height: 100), // Отступ внизу
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
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

// =======================================================================
// УНИВЕРСАЛЬНЫЙ LIQUID GLASS КОНТЕЙНЕР
// =======================================================================
class _GlassContainer extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final BorderRadius? borderRadius;

  const _GlassContainer({required this.child, required this.padding, this.borderRadius});

  @override
  Widget build(BuildContext context) {
    final br = borderRadius ?? BorderRadius.circular(28);
    return ClipRRect(
      borderRadius: br,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
        child: Container(
          padding: padding,
          decoration: BoxDecoration(
            color: Colors.white.withOpacity(0.06),
            borderRadius: br,
            border: Border.all(color: Colors.white.withOpacity(0.12), width: 1.2),
          ),
          child: child,
        ),
      ),
    );
  }
}

// =======================================================================
// СТЕКЛЯННЫЙ ТЕГ (Для жанров, года и тд)
// =======================================================================
class _GlassTag extends StatelessWidget {
  final String text;
  final Color? color;

  const _GlassTag({required this.text, this.color});

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: (color ?? Colors.white).withOpacity(0.1),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: (color ?? Colors.white).withOpacity(0.2), width: 1),
          ),
          child: Text(text, style: TextStyle(color: color ?? Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ),
    );
  }
}

// =======================================================================
// ЭЛЕМЕНТ СТАТИСТИКИ
// =======================================================================
class _StatItem extends StatelessWidget {
  final IconData icon;
  final String value;
  final String label;
  final Color color;

  const _StatItem({required this.icon, required this.value, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Icon(icon, color: color, size: 24),
        const SizedBox(height: 8),
        Text(value, style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold)),
        const SizedBox(height: 2),
        Text(label, style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 11, fontWeight: FontWeight.w500)),
      ],
    );
  }
}

// =======================================================================
// КНОПКА СТАТУСА В ШТОРКЕ
// =======================================================================
class _StatusChip extends StatelessWidget {
  final String title;
  final String value;
  final String groupValue;
  final Function(String) onTap;

  const _StatusChip({required this.title, required this.value, required this.groupValue, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final isSelected = value == groupValue;
    return GestureDetector(
      onTap: () {
        HapticFeedback.lightImpact();
        onTap(value);
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        margin: const EdgeInsets.only(right: 12),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
        decoration: BoxDecoration(
          color: isSelected ? const Color(0xFFFF5722) : Colors.white.withOpacity(0.08),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: isSelected ? const Color(0xFFFF5722) : Colors.white.withOpacity(0.1), width: 1),
        ),
        child: Text(
          title, 
          style: TextStyle(
            color: isSelected ? Colors.white : Colors.white70, 
            fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
            fontSize: 15
          )
        ),
      ),
    );
  }
}

// =======================================================================
// ГАЛЕРЕЯ КАДРОВ
// =======================================================================
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
        backgroundColor: Colors.black.withOpacity(0.5),
        middle: Text('${_currentIndex + 1} / ${widget.screenshots.length}', style: const TextStyle(color: Colors.white)),
        leading: CupertinoButton(padding: EdgeInsets.zero, onPressed: () => Navigator.pop(context), child: const Icon(CupertinoIcons.xmark, color: Colors.white)),
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