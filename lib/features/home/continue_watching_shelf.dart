import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../watch/watch_storage.dart';

/// "Продолжить просмотр": titles with a saved position, most recent first.
/// Tapping opens the title; long-press removes it from the shelf.
class ContinueWatchingShelf extends ConsumerStatefulWidget {
  const ContinueWatchingShelf({super.key});

  @override
  ConsumerState<ContinueWatchingShelf> createState() =>
      _ContinueWatchingShelfState();
}

class _ContinueWatchingShelfState extends ConsumerState<ContinueWatchingShelf> {
  List<ContinueEntry> _entries = const [];
  Map<int, ShikimoriAnime> _anime = const {};
  Timer? _reloadDebounce;

  @override
  void initState() {
    super.initState();
    ContinueWatchingStore.revision.addListener(_scheduleReload);
    unawaited(_load());
  }

  @override
  void dispose() {
    _reloadDebounce?.cancel();
    ContinueWatchingStore.revision.removeListener(_scheduleReload);
    super.dispose();
  }

  // The player writes every few seconds; refresh the shelf calmly.
  void _scheduleReload() {
    _reloadDebounce?.cancel();
    _reloadDebounce = Timer(const Duration(seconds: 2), () => _load());
  }

  Future<void> _load() async {
    final entries = (await ContinueWatchingStore.entries()).take(12).toList();
    if (!mounted) return;
    setState(() => _entries = entries);
    final missing = entries
        .map((entry) => entry.animeId)
        .where((id) => !_anime.containsKey(id))
        .toList();
    if (missing.isEmpty) return;
    try {
      final items = await ref
          .read(apiClientProvider)
          .getAnimes(
            limit: missing.length,
            filters: {'ids': missing.join(',')},
          );
      if (!mounted) return;
      setState(() => _anime = {..._anime, for (final a in items) a.id: a});
    } catch (_) {
      // Titles saved with the entry still label the cards.
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_entries.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: AniMixSpacing.xl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                maxWidth: AniMixLayout.contentMaxWidth,
              ),
              child: const Padding(
                padding: EdgeInsets.symmetric(
                  horizontal: AniMixLayout.pageInset,
                ),
                child: AniMixSectionHeader(title: 'Продолжить просмотр'),
              ),
            ),
          ),
          const SizedBox(height: AniMixSpacing.sm),
          SizedBox(
            height: 112,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              physics: const BouncingScrollPhysics(),
              padding: const EdgeInsets.symmetric(
                horizontal: AniMixLayout.pageInset,
              ),
              itemCount: _entries.length,
              separatorBuilder: (_, _) =>
                  const SizedBox(width: AniMixSpacing.sm),
              itemBuilder: (context, index) {
                final entry = _entries[index];
                return _ContinueCard(
                  entry: entry,
                  anime: _anime[entry.animeId],
                  onRemove: () => ContinueWatchingStore.remove(entry.animeId),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ContinueCard extends StatelessWidget {
  const _ContinueCard({
    required this.entry,
    required this.anime,
    required this.onRemove,
  });

  final ContinueEntry entry;
  final ShikimoriAnime? anime;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final title =
        anime?.russian ??
        anime?.name ??
        entry.title ??
        'Аниме #${entry.animeId}';
    final left = entry.duration - entry.position;
    final status = entry.finished
        ? (entry.nextEpisode == null
              ? 'Серия ${entry.episode} досмотрена'
              : 'Дальше: серия ${entry.nextEpisode}')
        : 'Серия ${entry.episode} · осталось ${left.inMinutes.clamp(1, 999)} мин';
    return SizedBox(
      width: 300,
      child: AniMixSurface(
        padding: const EdgeInsets.all(10),
        onTap: () => Navigator.push(
          context,
          CupertinoPageRoute<void>(
            builder: (_) => AnimeDetailScreen(animeId: entry.animeId),
          ),
        ),
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onLongPress: () => _confirmRemove(context, title),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(AniMixRadius.sm),
                child: SizedBox(
                  width: 62,
                  height: 92,
                  child: SmartAnimePoster(
                    animeId: entry.animeId,
                    imageUrl: anime?.imageUrl,
                    title: anime?.name ?? title,
                    russianTitle: anime?.russian,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14.5,
                        height: 1.2,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      status,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(2),
                      child: LinearProgressIndicator(
                        minHeight: 3,
                        value: entry.finished ? 1 : entry.fraction,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 6),
              Icon(
                CupertinoIcons.play_circle_fill,
                size: 30,
                color: scheme.primary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _confirmRemove(BuildContext context, String title) async {
    final remove = await showCupertinoModalPopup<bool>(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: Text(title),
        actions: [
          CupertinoActionSheetAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(sheetContext, true),
            child: const Text('Убрать из «Продолжить просмотр»'),
          ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(sheetContext, false),
          child: const Text('Отмена'),
        ),
      ),
    );
    if (remove == true) onRemove();
  }
}
