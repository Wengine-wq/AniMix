import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; // <-- Добавили для работы Colors.transparent
import 'package:cached_network_image/cached_network_image.dart';

import 'watch_player_screen.dart';
import 'models/watch_mapping.dart';           
import 'services/watch_resolver_service.dart'; 
import 'repositories/watch_mapping_repository.dart'; 

class EpisodeSelectionScreen extends StatefulWidget {
  final int animeId;
  final String provider;
  final String translationName;
  final String animeNameRu;
  final String animeNameEn;

  const EpisodeSelectionScreen({
    required this.animeId,
    required this.provider,
    required this.translationName,
    required this.animeNameRu,
    required this.animeNameEn,
    super.key,
  });

  @override
  State<EpisodeSelectionScreen> createState() => _EpisodeSelectionScreenState();
}

class _EpisodeSelectionScreenState extends State<EpisodeSelectionScreen> {
  List<Map<String, dynamic>> episodes = [];
  List<Map<String, dynamic>>? candidates;
  bool isLoading = true;
  String? errorMessage;

  final _resolver = WatchResolverService();
  final _repo = WatchMappingRepository();

  @override
  void initState() {
    super.initState();
    _loadWithResolver();
  }

  Future<void> _loadWithResolver() async {
    if (!mounted) return;
    setState(() {
      isLoading = true;
      errorMessage = null;
    });

    try {
      final result = await _resolver.resolve(
        shikimoriId: widget.animeId,
        provider: widget.provider,
        searchNameRu: widget.animeNameRu,
        searchNameEn: widget.animeNameEn,
      );

      if (!mounted) return;

      if (result is Map<String, dynamic> && result['needsPicker'] == true) {
        setState(() => candidates = result['candidates']);
      } else {
        setState(() => episodes = result as List<Map<String, dynamic>>);
      }
    } catch (e) {
      if (mounted) setState(() => errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  Future<void> _selectCandidate(Map<String, dynamic> candidate) async {
    setState(() => isLoading = true);
    try {
      // 🔥 Сохраняем правильный URL постера в базу
      final String rawPoster = candidate['poster']?.toString() ?? '';
      final String validPoster = rawPoster.isNotEmpty 
          ? (rawPoster.startsWith('http') ? rawPoster : 'https://anilibria.top$rawPoster')
          : '';

      final mapping = WatchMapping(
        shikimoriId: widget.animeId,
        provider: widget.provider,
        releaseId: candidate['id'].toString(),
        releaseTitle: candidate['title'],
        posterUrl: validPoster,
        savedAt: DateTime.now(),
      );

      await _resolver.saveMapping(mapping);
      final direct = await _resolver.loadEpisodesDirect(widget.provider, mapping.releaseId);

      if (mounted) {
        setState(() {
          candidates = null;
          episodes = direct;
        });
      }
    } catch (e) {
      if (mounted) setState(() => errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  Future<void> _resetMapping() async {
    await _repo.save(WatchMapping(
      shikimoriId: widget.animeId,
      provider: widget.provider,
      releaseId: '',
      releaseTitle: '',
      savedAt: DateTime.now(),
    ));
    if (mounted) {
      setState(() {
        episodes = [];
        candidates = null;
      });
      _loadWithResolver();
    }
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: Text(widget.translationName),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: _resetMapping,
          child: const Text('Сбросить', style: TextStyle(color: Color(0xFFFF5722))),
        ),
      ),
      child: SafeArea(
        child: isLoading
            ? const Center(child: CupertinoActivityIndicator(radius: 28))
            : errorMessage != null
                ? _buildErrorState()
                : candidates != null
                    ? _buildPickerState()
                    : _buildEpisodesList(),
      ),
    );
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(30),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(CupertinoIcons.exclamationmark_triangle, size: 80, color: Color(0xFFFF9800)),
            const SizedBox(height: 20),
            const Text('Ошибка загрузки', style: TextStyle(fontSize: 20, color: CupertinoColors.white, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            Text(errorMessage!, style: const TextStyle(color: CupertinoColors.systemGrey), textAlign: TextAlign.center),
            const SizedBox(height: 30),
            CupertinoButton.filled(onPressed: _loadWithResolver, child: const Text('Повторить')),
          ],
        ),
      ),
    );
  }

  Widget _buildPickerState() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.fromLTRB(20, 20, 20, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Упс! Автопоиск не уверен на 100%', style: TextStyle(color: Color(0xFFFF9800), fontSize: 16, fontWeight: FontWeight.bold)),
              SizedBox(height: 6),
              Text('Мы нашли несколько похожих аниме. Пожалуйста, выбери правильное из списка ниже:', style: TextStyle(color: CupertinoColors.systemGrey, fontSize: 15)),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            itemCount: candidates!.length,
            itemBuilder: (context, index) {
              final c = candidates![index];
              final score = c['matchScore'] as int;
              
              // 🔥 ИСПРАВЛЕНИЕ КРАША: Безопасная обработка ссылок на постер
              final String rawPoster = c['poster']?.toString() ?? '';
              final String? validPoster = rawPoster.isNotEmpty 
                  ? (rawPoster.startsWith('http') ? rawPoster : 'https://anilibria.top$rawPoster')
                  : null;

              return GestureDetector(
                onTap: () => _selectCandidate(c),
                child: Container(
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E1E),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: score >= 80 ? const Color(0xFFFF5722).withValues(alpha: 0.3) : Colors.transparent),
                  ),
                  child: Row(
                    children: [
                      if (validPoster != null) ...[
                        ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: CachedNetworkImage(
                            imageUrl: validPoster, 
                            width: 64, 
                            height: 86, 
                            fit: BoxFit.cover,
                            // Дополнительная защита на случай битой картинки
                            errorWidget: (context, url, error) => Container(
                              width: 64,
                              height: 86,
                              color: const Color(0xFF2A2A2A),
                              child: const Icon(CupertinoIcons.photo, color: CupertinoColors.systemGrey),
                            ),
                          ),
                        ),
                        const SizedBox(width: 16),
                      ],
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(c['title'], style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: CupertinoColors.white)),
                            const SizedBox(height: 4),
                            Text('${c['year']} • ${c['episodes']} эп.', style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 14)),
                          ],
                        ),
                      ),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                        decoration: BoxDecoration(
                          color: score >= 80 ? const Color(0xFF4CAF50).withValues(alpha: 0.2) : const Color(0xFFFF9800).withValues(alpha: 0.2),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Text('$score%', style: TextStyle(fontWeight: FontWeight.bold, color: score >= 80 ? const Color(0xFF4CAF50) : const Color(0xFFFF9800))),
                      )
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildEpisodesList() {
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: episodes.length,
      itemBuilder: (context, index) {
        final ep = episodes[index];
        final number = ep['number'] as int;
        final videoUrl = ep['videoUrl'] as String?;
        final hasVideo = videoUrl != null && videoUrl.isNotEmpty;

        return GestureDetector(
          onTap: () {
            if (hasVideo) {
              Navigator.push(context, CupertinoPageRoute(builder: (_) => WatchPlayerScreen(animeId: widget.animeId, episodeNumber: number, videoUrl: videoUrl, episodeTitle: ep['title'] ?? 'Серия $number')));
            } else {
              showCupertinoDialog(context: context, builder: (ctx) => CupertinoAlertDialog(title: const Text('Видео не найдено'), content: Text('Нет ссылки на серию $number'), actions: [CupertinoDialogAction(child: const Text('OK'), onPressed: () => Navigator.pop(ctx))]));
            }
          },
          child: Container(
            margin: const EdgeInsets.only(bottom: 10),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(18)),
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: const Color(0xFFFF5722).withValues(alpha: 0.15), shape: BoxShape.circle),
                  child: Text('$number', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Color(0xFFFF5722))),
                ),
                const SizedBox(width: 16),
                Expanded(child: Text(ep['title'] ?? 'Серия $number', style: const TextStyle(fontSize: 17, color: CupertinoColors.white))),
                if (!hasVideo) const Icon(CupertinoIcons.exclamationmark_triangle, color: CupertinoColors.systemGrey, size: 20),
              ],
            ),
          ),
        );
      },
    );
  }
}