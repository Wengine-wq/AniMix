import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';

import 'models/watch_mapping.dart';
import 'services/watch_resolver_service.dart';
import 'repositories/watch_mapping_repository.dart';
import 'kodik_webview_screen.dart';
import 'watch_player_screen.dart';
import 'watch_storage.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
import 'services/episode_action_service.dart';
import 'widgets/episode_collection_view.dart';

Future<void> launchKodikPlayer(
  BuildContext context,
  String urlRaw,
  String title,
  String animeTitle,
  int animeId,
  String episodeNumber,
  VoidCallback onReturn, {
  Future<void> Function()? onPlayNext,
  String? nextEpisodeTitle,
}) async {
  if (urlRaw.isEmpty) {
    showCupertinoDialog(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Ошибка'),
        content: const Text('Ссылка на плеер не найдена'),
        actions: [
          CupertinoDialogAction(
            child: const Text('ОК'),
            onPressed: () => Navigator.pop(ctx),
          ),
        ],
      ),
    );
    return;
  }

  String finalUrl = urlRaw;
  if (finalUrl.startsWith('//')) finalUrl = 'https:$finalUrl';

  final sources = await Navigator.push<Map<String, String>>(
    context,
    PageRouteBuilder<Map<String, String>>(
      transitionDuration: Duration.zero,
      reverseTransitionDuration: Duration.zero,
      pageBuilder: (_, _, _) => KodikWebViewScreen(
        kodikEmbedUrl: finalUrl,
        episodeTitle: title,
        animeTitle: animeTitle,
        animeId: animeId,
        episodeNumber: episodeNumber,
      ),
    ),
  );
  if (!context.mounted || sources == null || sources.isEmpty) {
    onReturn();
    return;
  }

  // The resolver route must be gone before the video backend owns a native
  // texture. This keeps resolving and playback as separate lifecycles.
  await WidgetsBinding.instance.endOfFrame;
  if (Platform.isWindows) {
    await Future<void>.delayed(const Duration(milliseconds: 180));
  }
  if (!context.mounted) return;
  await Navigator.push<void>(
    context,
    MaterialPageRoute<void>(
      builder: (_) => WatchPlayerScreen(
        animeId: animeId,
        episodeNumber: episodeNumber,
        episodeTitle: title,
        animeTitle: animeTitle,
        videoUrl: sources['Авто'] ?? sources.values.first,
        sources: sources,
        onPlayNext: onPlayNext,
        nextEpisodeTitle: nextEpisodeTitle,
      ),
    ),
  );
  onReturn();
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

  Future<void> _loadWithResolver({bool forceRefresh = false}) async {
    if (!mounted) return;
    setState(() {
      isLoading = true;
      errorMessage = null;
    });

    try {
      final result = await _resolver.resolve(
        shikimoriId: widget.animeId,
        provider: 'yummyanime',
        searchNameRu: widget.animeNameRu,
        searchNameEn: widget.animeNameEn,
        forceRefresh: forceRefresh,
      );

      if (!mounted) return;

      if (result is Map<String, dynamic> && result['needsPicker'] == true) {
        final cands = (result['candidates'] as List)
            .cast<Map<String, dynamic>>();
        final exactMatch = cands.firstWhere(
          (c) => c['shikimori_id']?.toString() == widget.animeId.toString(),
          orElse: () => <String, dynamic>{},
        );
        if (exactMatch.isNotEmpty) {
          await _selectCandidate(exactMatch);
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
    setState(() {
      isLoading = true;
      errorMessage = null;
    });
    try {
      final cands = await _resolver.searchManual('yummyanime', query);
      if (cands.isEmpty) {
        throw Exception('По вашему запросу ничего не найдено.');
      }
      final exactMatch = cands.firstWhere(
        (c) => c['shikimori_id']?.toString() == widget.animeId.toString(),
        orElse: () => <String, dynamic>{},
      );
      if (exactMatch.isNotEmpty) {
        await _selectCandidate(exactMatch);
        return;
      }
      setState(() {
        candidates = cands;
        isLoading = false;
      });
    } catch (e) {
      setState(() {
        errorMessage = e.toString();
        isLoading = false;
      });
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
      final direct = await _resolver.loadYummyStudios(
        mapping.releaseId,
        forceRefresh: true,
      );
      if (mounted) {
        setState(() {
          candidates = null;
          studios = direct;
        });
      }
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
      setState(() {
        studios = [];
        candidates = null;
        errorMessage = null;
      });
      _loadWithResolver();
    }
  }

  void _openTranslation(Map<String, dynamic> tr) {
    final trName =
        tr['displayName']?.toString() ??
        tr['name']?.toString() ??
        'Неизвестная озвучка';
    final urlRaw = tr['url'] ?? '';
    final episodesRaw = tr['episodes'] as List?;

    if (episodesRaw == null || episodesRaw.isEmpty) {
      launchKodikPlayer(
        context,
        urlRaw.toString(),
        trName,
        widget.animeNameRu,
        widget.animeId,
        '1',
        () {},
      );
      return;
    }

    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => _YummyEpisodesScreen(
          animeId: widget.animeId,
          animeTitle: widget.animeNameRu.isNotEmpty
              ? widget.animeNameRu
              : widget.animeNameEn,
          translationName: trName,
          episodes: episodesRaw.cast<Map<String, dynamic>>(),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AniMixPage(
      title: 'Озвучки YummyAnime',
      actions: [
        IconButton(
          tooltip: 'Обновить источники',
          onPressed: isLoading
              ? null
              : () => _loadWithResolver(forceRefresh: true),
          icon: const Icon(Icons.refresh_rounded),
        ),
        IconButton(
          tooltip: 'Сбросить найденное соответствие',
          onPressed: _resetMapping,
          icon: const Icon(Icons.link_off_rounded),
        ),
      ],
      child: isLoading
          ? const AniMixEpisodeListSkeleton()
          : errorMessage != null
          ? _buildErrorState()
          : candidates != null
          ? _buildPickerState()
          : _buildStudiosList(),
    );
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              CupertinoIcons.exclamationmark_triangle_fill,
              color: CupertinoColors.systemOrange,
              size: 44,
            ),
            const SizedBox(height: 14),
            Text(errorMessage!, textAlign: TextAlign.center),
            const SizedBox(height: 18),
            CupertinoButton.filled(
              onPressed: _loadWithResolver,
              child: const Text('Повторить'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPickerState() {
    final results = candidates ?? const <Map<String, dynamic>>[];
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 10),
          child: SearchBar(
            controller: _searchController,
            hintText: 'Уточнить название релиза',
            leading: const Icon(Icons.search_rounded),
            onSubmitted: (_) => _manualSearch(),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
          child: Text(
            'Автоматическое совпадение неоднозначно. Выберите правильный релиз:',
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              height: 1.35,
            ),
          ),
        ),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            itemCount: results.length,
            separatorBuilder: (_, _) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              final candidate = results[index];
              final poster = candidate['poster']?.toString() ?? '';
              final score = candidate['matchScore'] as int? ?? 0;
              return AniMixSurface(
                onTap: () => _selectCandidate(candidate),
                padding: const EdgeInsets.all(10),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: SizedBox(
                        width: 54,
                        height: 76,
                        child: poster.isEmpty
                            ? const ColoredBox(color: Color(0xFF27272A))
                            : CachedNetworkImage(
                                imageUrl: poster,
                                memCacheWidth: 200,
                                fit: BoxFit.cover,
                                errorWidget: (_, _, _) =>
                                    const ColoredBox(color: Color(0xFF27272A)),
                              ),
                      ),
                    ),
                    const SizedBox(width: 13),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            candidate['title']?.toString() ?? 'Без названия',
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.onSurface,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '${candidate['year'] ?? '—'} • ${candidate['episodes'] ?? '—'} эп. • совпадение $score%',
                            style: TextStyle(
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurfaceVariant,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Icon(
                      Icons.chevron_right_rounded,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      size: 20,
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildStudiosList() {
    if (studios.isEmpty) {
      return const Center(
        child: Text(
          'Озвучки не найдены',
          style: TextStyle(color: CupertinoColors.systemGrey),
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 920
            ? 3
            : constraints.maxWidth >= 620
            ? 2
            : 1;
        return GridView.builder(
          padding: const EdgeInsets.all(20),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
            mainAxisExtent: 92,
          ),
          itemCount: studios.length,
          itemBuilder: (context, index) {
            final translation = studios[index];
            final name =
                translation['displayName']?.toString() ??
                translation['name']?.toString() ??
                'Неизвестная озвучка';
            final count = (translation['episodes'] as List?)?.length ?? 0;
            final accent = Theme.of(context).colorScheme.primary;
            final isKodik = translation['isKodik'] == true;
            return AniMixSurface(
              onTap: () => _openTranslation(translation),
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: accent.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Icon(
                      CupertinoIcons.mic_fill,
                      color: accent,
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 17,
                            color: Theme.of(context).colorScheme.onSurface,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (count > 0) ...[
                          const SizedBox(height: 4),
                          Text(
                            '$count эпизодов${isKodik ? ' • прямой поток' : ' • резерв'}',
                            style: TextStyle(
                              fontSize: 13,
                              color: CupertinoColors.systemGrey,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  Icon(
                    Icons.chevron_right_rounded,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ],
              ),
            );
          },
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
  final String animeTitle;
  final String translationName;
  final List<Map<String, dynamic>> episodes;

  const _YummyEpisodesScreen({
    required this.animeId,
    required this.animeTitle,
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
    final items = widget.episodes.map((episode) {
      final number = episode['number']?.toString() ?? '1';
      return EpisodeViewData(
        number: number,
        title: 'Серия $number',
        available:
            episode['videoUrl']?.toString().isNotEmpty == true ||
            episode['url']?.toString().isNotEmpty == true,
        watched: _watchedEpisodes.contains(number),
        downloadId: EpisodeActionService.downloadId(
          animeId: widget.animeId,
          provider: 'yummyanime-kodik',
          episodeNumber: number,
          translation: widget.translationName,
        ),
      );
    }).toList();
    return AniMixPage(
      title: widget.translationName,
      child: EpisodeCollectionView(
        episodes: items,
        onPlay: _playEpisode,
        onDownload: _downloadEpisode,
      ),
    );
  }

  Map<String, dynamic>? _episodeFor(String number) {
    for (final episode in widget.episodes) {
      if (episode['number']?.toString() == number) return episode;
    }
    return null;
  }

  Map<String, dynamic>? _nextEpisodeFor(String currentEpisode) {
    final current = double.tryParse(currentEpisode);
    if (current == null) return null;
    final candidates =
        widget.episodes.where((episode) {
          final number = double.tryParse(episode['number']?.toString() ?? '');
          final playable =
              episode['videoUrl']?.toString().isNotEmpty == true ||
              episode['url']?.toString().isNotEmpty == true;
          return playable && number != null && number > current;
        }).toList()..sort((a, b) {
          final aNumber = double.parse(a['number'].toString());
          final bNumber = double.parse(b['number'].toString());
          return aNumber.compareTo(bNumber);
        });
    return candidates.isEmpty ? null : candidates.first;
  }

  Future<void> _playEpisode(EpisodeViewData item) async {
    final episode = _episodeFor(item.number);
    if (episode == null) return;
    await _openEpisode(episode);
  }

  Future<void> _openEpisode(
    Map<String, dynamic> episode, {
    bool replaceCurrent = false,
  }) async {
    final number = episode['number']?.toString() ?? '1';
    final title = 'Серия $number';
    final next = _nextEpisodeFor(number);
    final directUrl = episode['videoUrl']?.toString() ?? '';
    final qualities = episode['qualities'] is Map
        ? Map<String, String>.from(episode['qualities'] as Map)
        : <String, String>{};
    if (directUrl.isNotEmpty) {
      final route = MaterialPageRoute<void>(
        builder: (_) => WatchPlayerScreen(
          animeId: widget.animeId,
          episodeNumber: number,
          episodeTitle: '${widget.translationName} • $title',
          animeTitle: widget.animeTitle,
          videoUrl: directUrl,
          sources: qualities,
          nextEpisodeTitle: next == null ? null : 'Серия ${next['number']}',
          onPlayNext: next == null
              ? null
              : () => _openEpisode(next, replaceCurrent: true),
        ),
      );
      if (replaceCurrent) {
        await Navigator.of(context).pushReplacement<void, void>(route);
      } else {
        await Navigator.of(context).push<void>(route);
      }
      await _loadWatched();
      return;
    }
    final url = episode['url']?.toString() ?? '';
    if (url.isEmpty) return;
    await launchKodikPlayer(
      context,
      url,
      '${widget.translationName} • $title',
      widget.animeTitle,
      widget.animeId,
      number,
      _loadWatched,
      onPlayNext: next == null
          ? null
          : () => _openEpisode(next, replaceCurrent: true),
      nextEpisodeTitle: next == null ? null : 'Серия ${next['number']}',
    );
  }

  Future<void> _downloadEpisode(EpisodeViewData item) async {
    final episode = _episodeFor(item.number);
    if (episode == null) return;
    final directSources = episode['qualities'] is Map
        ? Map<String, String>.from(episode['qualities'] as Map)
        : <String, String>{};
    if (directSources.isNotEmpty) {
      await EpisodeActionService.chooseAndDownload(
        context,
        sources: directSources,
        episodeId: item.downloadId,
        animeId: widget.animeId,
        animeTitle: widget.animeTitle,
        episodeName: '${widget.translationName} • ${item.title}',
      );
      return;
    }
    final url = episode['url']?.toString() ?? '';
    if (url.isEmpty) return;
    final sources = await EpisodeActionService.resolveKodik(
      context,
      embedUrl: url.startsWith('//') ? 'https:$url' : url,
      episodeTitle: '${widget.translationName} • ${item.title}',
      animeTitle: widget.animeTitle,
      animeId: widget.animeId,
      episodeNumber: item.number,
    );
    if (sources == null || !mounted) return;
    await EpisodeActionService.chooseAndDownload(
      context,
      sources: sources,
      episodeId: item.downloadId,
      animeId: widget.animeId,
      animeTitle: widget.animeTitle,
      episodeName: '${widget.translationName} • ${item.title}',
    );
  }
}
