import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';

import 'watch_player_screen.dart';
import 'models/watch_mapping.dart';           
import 'services/watch_resolver_service.dart'; 
import 'repositories/watch_mapping_repository.dart'; 
import 'watch_storage.dart';

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
  List<String> _watchedEpisodes = []; 
  bool isLoading = true;
  String? errorMessage;

  final TextEditingController _searchController = TextEditingController();
  final _resolver = WatchResolverService();
  final _repo = WatchMappingRepository();

  @override
  void initState() {
    super.initState();
    _loadWatched();
    _loadWithResolver();
  }

  Future<void> _loadWatched() async {
    final w = await WatchStorage.getWatchedEpisodes(widget.animeId);
    if (mounted) setState(() => _watchedEpisodes = w);
  }

  Future<void> _loadWithResolver() async {
    setState(() { isLoading = true; errorMessage = null; });
    try {
      final result = await _resolver.resolve(
        shikimoriId: widget.animeId,
        provider: widget.provider,
        searchNameRu: widget.animeNameRu,
        searchNameEn: widget.animeNameEn,
      );
      if (mounted) {
        if (result is Map<String, dynamic> && result['needsPicker'] == true) {
          setState(() => candidates = (result['candidates'] as List).cast<Map<String, dynamic>>());
        } else {
          setState(() => episodes = result as List<Map<String, dynamic>>);
        }
      }
    } catch (e) {
      if (mounted) setState(() => errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  void _manualSearch() async {
    final query = _searchController.text.trim();
    if (query.isEmpty) return;
    
    FocusScope.of(context).unfocus();
    setState(() { isLoading = true; errorMessage = null; });
    
    try {
      final cands = await _resolver.searchManual(widget.provider, query);
      if (cands.isEmpty) throw Exception('По вашему запросу "$query" ничего не найдено.');

      setState(() { candidates = cands; isLoading = false; });
    } catch (e) {
      setState(() { errorMessage = e.toString(); isLoading = false; });
    }
  }

  Future<void> _selectCandidate(Map<String, dynamic> candidate) async {
    setState(() => isLoading = true);
    try {
      final mapping = WatchMapping(
        shikimoriId: widget.animeId,
        provider: widget.provider,
        releaseId: candidate['id'].toString(),
        releaseTitle: candidate['title'],
        posterUrl: candidate['poster']?.toString(),
        savedAt: DateTime.now(),
      );
      await _resolver.saveMapping(mapping);
      final direct = await _resolver.loadEpisodesDirect(widget.provider, mapping.releaseId);
      if (mounted) {
        setState(() { candidates = null; episodes = direct; });
      }
    } catch (e) {
      if (mounted) setState(() => errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  Future<void> _resetMapping() async {
    await _repo.delete('${widget.animeId}_${widget.provider}');
    _searchController.clear();
    
    if (mounted) {
      // 🔥 Убрали ScaffoldMessenger, который вызывал краш
      setState(() { episodes = []; candidates = null; errorMessage = null; });
      _loadWithResolver();
    }
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: Text(widget.translationName),
        backgroundColor: const Color(0xFF1E1E1E),
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

  Widget _buildEpisodesList() {
    if (episodes.isEmpty) {
      return const Center(child: Text('Эпизоды не найдены', style: TextStyle(color: CupertinoColors.systemGrey)));
    }

    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: episodes.length,
      itemBuilder: (context, index) {
        final ep = episodes[index];
        final String epNumber = ep['number'].toString();
        final bool isWatched = _watchedEpisodes.contains(epNumber); 
        final hasVideo = ep['videoUrl'] != null;

        return GestureDetector(
          onTap: () {
            if (hasVideo) {
              Navigator.push(context, CupertinoPageRoute(builder: (_) => WatchPlayerScreen(
                animeId: widget.animeId,
                episodeNumber: epNumber,
                videoUrl: ep['videoUrl'],
                episodeTitle: ep['title'] ?? 'Серия $epNumber',
              ))).then((_) => _loadWatched());
            } else {
              // Заменили SnackBar на красивый Cupertino Dialog
              showCupertinoDialog(
                context: context, 
                builder: (ctx) => CupertinoAlertDialog(
                  title: const Text('Ошибка'),
                  content: const Text('Ссылка на серию не найдена'),
                  actions: [CupertinoDialogAction(child: const Text('ОК'), onPressed: () => Navigator.pop(ctx))],
                )
              );
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
                  decoration: BoxDecoration(color: const Color(0xFFFF5722).withOpacity(0.15), shape: BoxShape.circle),
                  child: Text(epNumber, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Color(0xFFFF5722))),
                ),
                const SizedBox(width: 16),
                Expanded(child: Text(ep['title'] ?? 'Серия $epNumber', style: const TextStyle(fontSize: 17, color: CupertinoColors.white))),
                
                if (isWatched)
                  const Icon(CupertinoIcons.eye_solid, color: Color(0xFF4CAF50), size: 22)
                else if (!hasVideo) 
                  const Icon(CupertinoIcons.exclamationmark_triangle, color: CupertinoColors.systemGrey, size: 20)
                else
                  const Icon(CupertinoIcons.play_circle_fill, color: CupertinoColors.systemGrey, size: 20),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildErrorState() {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(30),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(CupertinoIcons.search, size: 70, color: Color(0xFFFF5722)),
            const SizedBox(height: 20),
            const Text('Аниме не найдено', style: TextStyle(fontSize: 20, color: CupertinoColors.white, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            const Text(
              'Автопоиск не нашел этот тайтл в базе AniLibria. Введите название (например, на английском), чтобы найти вручную:', 
              style: TextStyle(color: CupertinoColors.systemGrey), 
              textAlign: TextAlign.center
            ),
            const SizedBox(height: 30),
            
            CupertinoTextField(
              controller: _searchController,
              placeholder: 'Введите название...',
              style: const TextStyle(color: Colors.white),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              decoration: BoxDecoration(
                color: const Color(0xFF1E1E1E),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.white.withOpacity(0.1)),
              ),
              suffix: CupertinoButton(
                padding: EdgeInsets.zero,
                onPressed: _manualSearch,
                child: const Icon(CupertinoIcons.search, color: Color(0xFFFF5722)),
              ),
              onSubmitted: (_) => _manualSearch(),
            ),
            
            const SizedBox(height: 24),
            CupertinoButton.filled(
              onPressed: _loadWithResolver,
              child: const Text('Повторить автопоиск'),
            ),
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
              Text('Найдено несколько совпадений', style: TextStyle(color: Color(0xFFFF5722), fontSize: 16, fontWeight: FontWeight.bold)),
              SizedBox(height: 6),
              Text('Мы нашли похожие релизы в базе AniLibria. Выбери правильный из списка ниже:', style: TextStyle(color: CupertinoColors.systemGrey, fontSize: 15)),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            itemCount: candidates!.length,
            itemBuilder: (context, index) {
              final c = candidates![index];
              final score = c['matchScore'] as int? ?? 0;
              final String rawPoster = c['poster']?.toString() ?? '';

              return GestureDetector(
                onTap: () => _selectCandidate(c),
                child: Container(
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E1E),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: score >= 80 ? const Color(0xFFFF5722).withOpacity(0.3) : Colors.transparent),
                  ),
                  child: Row(
                    children: [
                      if (rawPoster.isNotEmpty) ...[
                        ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: CachedNetworkImage(
                            imageUrl: rawPoster, 
                            width: 64, height: 86, fit: BoxFit.cover,
                            errorWidget: (_, __, ___) => Container(width: 64, height: 86, color: const Color(0xFF2A2A2A)),
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
                        decoration: BoxDecoration(color: score >= 80 ? const Color(0xFFFF5722).withOpacity(0.2) : const Color(0xFFFF9800).withOpacity(0.2), borderRadius: BorderRadius.circular(12)),
                        child: Text('$score%', style: TextStyle(fontWeight: FontWeight.bold, color: score >= 80 ? const Color(0xFFFF5722) : const Color(0xFFFF9800))),
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
}