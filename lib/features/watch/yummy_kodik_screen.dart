import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';

import 'models/watch_mapping.dart';           
import 'services/watch_resolver_service.dart'; 
import 'repositories/watch_mapping_repository.dart'; 
import 'kodik_webview_screen.dart';
import 'watch_storage.dart'; 

void launchKodikPlayer(BuildContext context, String urlRaw, String title, int animeId, String episodeNumber, VoidCallback onReturn) {
  if (urlRaw.isEmpty) {
    showCupertinoDialog(
      context: context, 
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Ошибка'),
        content: const Text('Ссылка на плеер не найдена'),
        actions: [CupertinoDialogAction(child: const Text('ОК'), onPressed: () => Navigator.pop(ctx))],
      )
    );
    return;
  }
  
  String finalUrl = urlRaw;
  if (finalUrl.startsWith('//')) finalUrl = 'https:$finalUrl';

  Navigator.push(context, CupertinoPageRoute(builder: (_) => KodikWebViewScreen(
    kodikEmbedUrl: finalUrl,
    episodeTitle: title,
    animeId: animeId,
    episodeNumber: episodeNumber,
  ))).then((_) => onReturn());
}

// =========================================================================================
// 1. ЭКРАН ВЫБОРА ОЗВУЧКИ
// =========================================================================================
class YummyAnimeScreen extends StatefulWidget {
  final int animeId;
  final String animeNameRu;
  final String animeNameEn;

  const YummyAnimeScreen({
    required this.animeId,
    required this.animeNameRu,
    required this.animeNameEn,
    super.key,
  });

  @override
  State<YummyAnimeScreen> createState() => _YummyAnimeScreenState();
}

class _YummyAnimeScreenState extends State<YummyAnimeScreen> {
  List<Map<String, dynamic>> studios = [];
  List<Map<String, dynamic>>? candidates;
  bool isLoading = true;
  String? errorMessage;
  
  final TextEditingController _searchController = TextEditingController();
  final _resolver = WatchResolverService();
  final _repo = WatchMappingRepository();

  @override
  void initState() {
    super.initState();
    _loadWithResolver();
  }

  Future<void> _loadWithResolver() async {
    if (!mounted) return;
    setState(() { isLoading = true; errorMessage = null; });

    try {
      final result = await _resolver.resolve(
        shikimoriId: widget.animeId,
        provider: 'yummyanime',
        searchNameRu: widget.animeNameRu,
        searchNameEn: widget.animeNameEn,
      );

      if (!mounted) return;

      if (result is Map<String, dynamic> && result['needsPicker'] == true) {
        final cands = (result['candidates'] as List).cast<Map<String, dynamic>>();
        final exactMatch = cands.firstWhere((c) => c['shikimori_id']?.toString() == widget.animeId.toString(), orElse: () => <String, dynamic>{});
        if (exactMatch.isNotEmpty) {
          _selectCandidate(exactMatch);
        } else {
          setState(() => candidates = cands);
        }
      } else {
        setState(() => studios = result as List<Map<String, dynamic>>);
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
      final cands = await _resolver.searchManual('yummyanime', query);
      if (cands.isEmpty) throw Exception('По вашему запросу ничего не найдено.');
      final exactMatch = cands.firstWhere((c) => c['shikimori_id']?.toString() == widget.animeId.toString(), orElse: () => <String, dynamic>{});
      if (exactMatch.isNotEmpty) {
        _selectCandidate(exactMatch);
        return;
      }
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
        provider: 'yummyanime',
        releaseId: candidate['id'].toString(),
        releaseTitle: candidate['title'],
        posterUrl: candidate['poster']?.toString(),
        savedAt: DateTime.now(),
      );
      await _resolver.saveMapping(mapping);
      final direct = await _resolver.loadYummyStudios(mapping.releaseId);
      if (mounted) setState(() { candidates = null; studios = direct; });
    } catch (e) {
      if (mounted) setState(() => errorMessage = e.toString());
    } finally {
      if (mounted) setState(() => isLoading = false);
    }
  }

  Future<void> _resetMapping() async {
    await _repo.delete('${widget.animeId}_yummyanime');
    _searchController.clear();
    if (mounted) {
      // 🔥 Убрали ScaffoldMessenger, который вызывал краш
      setState(() { studios = []; candidates = null; errorMessage = null; });
      _loadWithResolver();
    }
  }

  void _openTranslation(Map<String, dynamic> tr) {
    final trName = tr['name'] ?? 'Неизвестная озвучка';
    final urlRaw = tr['url'] ?? '';
    final episodesRaw = tr['episodes'] as List?;

    if (episodesRaw == null || episodesRaw.isEmpty) {
      launchKodikPlayer(context, urlRaw.toString(), trName, widget.animeId, '1', () {});
      return;
    }

    if (episodesRaw.length == 1) {
      launchKodikPlayer(context, episodesRaw.first['url']?.toString() ?? urlRaw.toString(), '$trName • Серия 1', widget.animeId, '1', () {});
      return;
    }

    Navigator.push(context, CupertinoPageRoute(builder: (_) => _YummyEpisodesScreen(
      animeId: widget.animeId,
      translationName: trName,
      episodes: episodesRaw.cast<Map<String, dynamic>>(),
    )));
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: const Text('YummyAnime'),
        backgroundColor: const Color(0xFF1E1E1E),
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: _resetMapping,
          child: const Text('Сбросить', style: TextStyle(color: Color(0xFF4CAF50))),
        ),
      ),
      child: SafeArea(
        child: isLoading
            ? const Center(child: CupertinoActivityIndicator(radius: 28))
            : errorMessage != null
                ? _buildErrorState()
                : candidates != null
                    ? _buildPickerState()
                    : _buildStudiosList(),
      ),
    );
  }

  Widget _buildErrorState() { return Center(child: Text(errorMessage!, style: const TextStyle(color: Colors.red))); }
  Widget _buildPickerState() { return Container(); }

  Widget _buildStudiosList() {
    if (studios.isEmpty) return const Center(child: Text('Озвучки не найдены', style: TextStyle(color: CupertinoColors.systemGrey)));
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: studios.length,
      itemBuilder: (context, index) {
        final tr = studios[index];
        final trName = tr['name'];
        final epsCount = (tr['episodes'] as List?)?.length ?? 0;

        return GestureDetector(
          onTap: () => _openTranslation(tr),
          child: Container(
            margin: const EdgeInsets.only(bottom: 12),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(20)),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: const Color(0xFF4CAF50).withOpacity(0.15), shape: BoxShape.circle),
                  child: const Icon(CupertinoIcons.mic_fill, color: Color(0xFF4CAF50), size: 20),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(trName, style: const TextStyle(fontSize: 17, color: Colors.white, fontWeight: FontWeight.bold)),
                      if (epsCount > 0) ...[
                        const SizedBox(height: 4),
                        Text('$epsCount эпизодов', style: const TextStyle(fontSize: 13, color: CupertinoColors.systemGrey)),
                      ]
                    ],
                  ),
                ),
                const Icon(CupertinoIcons.chevron_right, color: CupertinoColors.systemGrey3),
              ],
            ),
          ),
        );
      },
    );
  }
}

// =========================================================================================
// 2. ЭКРАН ВЫБОРА СЕРИЙ С СОХРАНЕНИЕМ ИСТОРИИ
// =========================================================================================
class _YummyEpisodesScreen extends StatefulWidget {
  final int animeId;
  final String translationName;
  final List<Map<String, dynamic>> episodes;

  const _YummyEpisodesScreen({
    required this.animeId,
    required this.translationName,
    required this.episodes,
  });

  @override
  State<_YummyEpisodesScreen> createState() => _YummyEpisodesScreenState();
}

class _YummyEpisodesScreenState extends State<_YummyEpisodesScreen> {
  List<String> _watchedEpisodes = [];

  @override
  void initState() {
    super.initState();
    _loadWatched();
  }

  Future<void> _loadWatched() async {
    final w = await WatchStorage.getWatchedEpisodes(widget.animeId);
    if (mounted) setState(() => _watchedEpisodes = w);
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: Text(widget.translationName),
        backgroundColor: const Color(0xFF1E1E1E),
      ),
      child: SafeArea(
        child: ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: widget.episodes.length,
          itemBuilder: (context, index) {
            final ep = widget.episodes[index];
            final String epNumber = ep['number'].toString();
            final String title = 'Серия $epNumber';
            final String urlRaw = ep['url'] as String;
            final bool isValidUrl = urlRaw.isNotEmpty;
            final bool isWatched = _watchedEpisodes.contains(epNumber); 
            
            return GestureDetector(
              onTap: () {
                if (isValidUrl) {
                  launchKodikPlayer(context, urlRaw, '${widget.translationName} • $title', widget.animeId, epNumber, _loadWatched);
                } else {
                  showCupertinoDialog(
                    context: context, 
                    builder: (ctx) => CupertinoAlertDialog(
                      title: const Text('Ошибка'),
                      content: const Text('Ссылка на эпизод не найдена'),
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
                      decoration: BoxDecoration(color: const Color(0xFF4CAF50).withOpacity(0.15), shape: BoxShape.circle),
                      child: Text(
                        epNumber, 
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Color(0xFF4CAF50))
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(child: Text(title, style: const TextStyle(fontSize: 17, color: CupertinoColors.white))),
                    
                    if (isWatched)
                      const Icon(CupertinoIcons.eye_solid, color: Color(0xFF4CAF50), size: 22)
                    else if (!isValidUrl) 
                      const Icon(CupertinoIcons.exclamationmark_triangle, color: CupertinoColors.systemGrey, size: 20)
                    else
                      const Icon(CupertinoIcons.play_circle_fill, color: CupertinoColors.systemGrey, size: 20),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}