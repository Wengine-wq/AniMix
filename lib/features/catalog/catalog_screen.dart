import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/app_logging.dart';
import '../../core/achievement_service.dart';
import '../../core/app_settings.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../auth/login_screen.dart';

enum BookmarkTab {
  watching(
    'Смотрю',
    CupertinoIcons.play_circle_fill,
    CupertinoColors.systemBlue,
  ),
  planned('В планах', CupertinoIcons.clock_fill, CupertinoColors.systemGrey),
  completed(
    'Просмотрено',
    CupertinoIcons.check_mark_circled_solid,
    CupertinoColors.systemGreen,
  ),
  onHold(
    'Отложено',
    CupertinoIcons.pause_circle_fill,
    CupertinoColors.systemOrange,
  ),
  dropped(
    'Брошено',
    CupertinoIcons.xmark_circle_fill,
    CupertinoColors.systemRed,
  );

  const BookmarkTab(this.label, this.icon, this.color);
  final String label;
  final IconData icon;
  final Color color;

  String get apiValue => switch (this) {
    BookmarkTab.watching => 'watching',
    BookmarkTab.planned => 'planned',
    BookmarkTab.completed => 'completed',
    BookmarkTab.onHold => 'on_hold',
    BookmarkTab.dropped => 'dropped',
  };
}

class BookmarkEntry {
  const BookmarkEntry({
    required this.anime,
    required this.status,
    required this.score,
    required this.watchedEpisodes,
  });

  final ShikimoriAnime anime;
  final String status;
  final int score;
  final int watchedEpisodes;
}

class BookmarkTabNotifier extends Notifier<BookmarkTab> {
  @override
  BookmarkTab build() => BookmarkTab.watching;
  void select(BookmarkTab value) => state = value;
}

final bookmarkTabProvider = NotifierProvider<BookmarkTabNotifier, BookmarkTab>(
  BookmarkTabNotifier.new,
);

final bookmarksProvider = FutureProvider.autoDispose<List<BookmarkEntry>>((
  ref,
) async {
  if (!await ref.watch(isLoggedInProvider.future)) {
    throw const _BookmarksAuthRequired();
  }
  ref.watch(userDataRevisionProvider);
  final user = await ref.read(currentUserProvider.future);
  if (user == null) throw const _BookmarksAuthRequired();
  final api = ref.read(apiClientProvider);
  if (user.isAniMix) {
    final rawEntries = await ref
        .read(animixAuthServiceProvider)
        .getLibraryEntries();
    if (rawEntries == null) {
      throw const _BookmarksUnavailable('AniMix временно не отвечает.');
    }
    final records = rawEntries
        .map(_AniMixBookmarkRecord.fromJson)
        .where((entry) => entry.animeId > 0)
        .toList(growable: false);
    final animeById = <int, ShikimoriAnime>{};
    const batchSize = 50;
    const parallelBatches = 4;
    final batches = <List<int>>[];
    for (var start = 0; start < records.length; start += batchSize) {
      batches.add(
        records
            .skip(start)
            .take(batchSize)
            .map((entry) => entry.animeId)
            .toList(growable: false),
      );
    }
    for (var start = 0; start < batches.length; start += parallelBatches) {
      final wave = batches.skip(start).take(parallelBatches);
      final results = await Future.wait(
        wave.map((ids) async {
          try {
            return await api.getAnimes(
              limit: ids.length,
              filters: {'ids': ids.join(',')},
            );
          } catch (error, stackTrace) {
            AppLogBuffer.instance.recordError(
              error,
              stackTrace,
              source: 'AniMix library metadata',
            );
            return const <ShikimoriAnime>[];
          }
        }),
      );
      for (final anime in results.expand((items) => items)) {
        animeById[anime.id] = anime;
      }
    }
    return records
        .map(
          (record) => BookmarkEntry(
            anime:
                animeById[record.animeId] ??
                ShikimoriAnime.fromJson({
                  'id': record.animeId,
                  'name': 'Anime #${record.animeId}',
                  'russian': 'Аниме #${record.animeId}',
                  'image': const <String, dynamic>{},
                  'score': 0,
                }),
            status: record.status,
            score: record.score,
            watchedEpisodes: record.watchedEpisodes,
          ),
        )
        .toList(growable: false);
  }
  final rates = await api.getUserAnimeRates(user.id);
  return rates
      .whereType<Map>()
      .where((raw) => raw['anime'] is Map)
      .map(
        (raw) => BookmarkEntry(
          anime: ShikimoriAnime.fromJson(
            Map<String, dynamic>.from(raw['anime'] as Map),
          ),
          status: raw['status']?.toString() ?? '',
          score: int.tryParse(raw['score']?.toString() ?? '') ?? 0,
          watchedEpisodes: int.tryParse(raw['episodes']?.toString() ?? '') ?? 0,
        ),
      )
      .toList();
});

class _AniMixBookmarkRecord {
  const _AniMixBookmarkRecord({
    required this.animeId,
    required this.status,
    required this.score,
    required this.watchedEpisodes,
  });

  factory _AniMixBookmarkRecord.fromJson(Map<String, dynamic> json) =>
      _AniMixBookmarkRecord(
        animeId: int.tryParse(json['shikimori_id']?.toString() ?? '') ?? 0,
        status: json['status']?.toString() ?? '',
        score: int.tryParse(json['score']?.toString() ?? '') ?? 0,
        watchedEpisodes:
            int.tryParse(json['episodes_watched']?.toString() ?? '') ?? 0,
      );

  final int animeId;
  final String status;
  final int score;
  final int watchedEpisodes;
}

class _BookmarksAuthRequired implements Exception {
  const _BookmarksAuthRequired();
}

class _BookmarksUnavailable implements Exception {
  const _BookmarksUnavailable(this.message);
  final String message;
}

class CatalogScreen extends ConsumerWidget {
  const CatalogScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(bookmarkTabProvider);
    final async = ref.watch(bookmarksProvider);
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Закладки'),
        actions: [
          IconButton(
            tooltip: 'Обновить',
            onPressed: () => ref.invalidate(bookmarksProvider),
            icon: const Icon(CupertinoIcons.refresh),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          async.maybeWhen(
            data: (items) => _TabBar(
              selected: selected,
              counts: {
                for (final tab in BookmarkTab.values)
                  tab: _forTab(items, tab).length,
              },
              onSelected: (value) =>
                  ref.read(bookmarkTabProvider.notifier).select(value),
            ),
            orElse: () => _TabBar(
              selected: selected,
              counts: const {},
              onSelected: (value) =>
                  ref.read(bookmarkTabProvider.notifier).select(value),
            ),
          ),
          Expanded(
            child: async.when(
              loading: () => const AniMixCatalogSkeleton(),
              error: (error, _) => error is _BookmarksAuthRequired
                  ? AniMixEmptyState(
                      icon: CupertinoIcons.person_crop_circle_badge_exclam,
                      title: 'Нужен аккаунт AniMix',
                      message:
                          'Войдите, чтобы синхронизировать списки и прогресс.',
                      actionLabel: 'Войти',
                      onAction: () => Navigator.push(
                        context,
                        CupertinoPageRoute<void>(
                          builder: (_) => const LoginScreen(),
                        ),
                      ),
                    )
                  : AniMixEmptyState(
                      icon: CupertinoIcons.wifi_exclamationmark,
                      title: 'Не удалось загрузить закладки',
                      message: error is _BookmarksUnavailable
                          ? error.message
                          : 'Каталог временно не отвечает.',
                      actionLabel: 'Повторить',
                      onAction: () => ref.invalidate(bookmarksProvider),
                    ),
              data: (items) {
                final filtered = _forTab(items, selected);
                if (filtered.isEmpty) {
                  return AniMixEmptyState(
                    icon: selected.icon,
                    title: 'Список пуст',
                    message: 'Добавленные аниме появятся в этом разделе.',
                  );
                }
                return _BookmarksContent(
                  items: filtered,
                  watching: selected == BookmarkTab.watching,
                  onChangeStatus: (entry) =>
                      _showStatusMenu(context, ref, entry),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  static List<BookmarkEntry> _forTab(
    List<BookmarkEntry> items,
    BookmarkTab tab,
  ) {
    if (tab == BookmarkTab.watching) {
      return items
          .where(
            (item) => item.status == 'watching' || item.status == 'rewatching',
          )
          .toList();
    }
    return items.where((item) => item.status == tab.apiValue).toList();
  }

  Future<void> _showStatusMenu(
    BuildContext context,
    WidgetRef ref,
    BookmarkEntry entry,
  ) async {
    final value = await showModalBottomSheet<String>(
      context: context,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      builder: (context) => Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 22),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text(
                entry.anime.russian ?? entry.anime.name ?? 'Аниме',
                style: const TextStyle(
                  fontSize: 19,
                  fontWeight: FontWeight.w700,
                ),
              ),
              subtitle: const Text('Переместить в список'),
            ),
            for (final tab in BookmarkTab.values)
              ListTile(
                leading: Icon(tab.icon, color: tab.color),
                title: Text(tab.label),
                trailing: entry.status == tab.apiValue
                    ? const Icon(CupertinoIcons.check_mark)
                    : null,
                onTap: () => Navigator.pop(context, tab.apiValue),
              ),
          ],
        ),
      ),
    );
    if (value == null) return;
    try {
      final user = await ref.read(currentUserProvider.future);
      if (user == null) return;
      if (user.isAniMix) {
        final saved = await ref
            .read(animixAuthServiceProvider)
            .saveLibraryEntry(
              animeId: entry.anime.id,
              status: value,
              score: entry.score,
              episodesWatched: entry.watchedEpisodes,
            );
        if (!saved) throw StateError('AniMix library update failed');
        ref.read(userDataRevisionProvider.notifier).bump();
      } else {
        await ref
            .read(apiClientProvider)
            .setUserRate(
              entry.anime.id,
              value,
              score: entry.score,
              episodes: entry.watchedEpisodes,
              userId: user.id,
            );
        await AchievementService.instance.librarySaved(entry.anime.id, value);
      }
      ref.invalidate(bookmarksProvider);
    } catch (_) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось обновить статус')),
      );
    }
  }
}

class _TabBar extends StatelessWidget {
  const _TabBar({
    required this.selected,
    required this.counts,
    required this.onSelected,
  });
  final BookmarkTab selected;
  final Map<BookmarkTab, int> counts;
  final ValueChanged<BookmarkTab> onSelected;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      height: 56,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        itemCount: BookmarkTab.values.length,
        separatorBuilder: (_, _) => const SizedBox(width: 6),
        itemBuilder: (context, index) {
          final tab = BookmarkTab.values[index];
          final active = selected == tab;
          final count = counts[tab] ?? 0;
          // Quiet segmented chips: the active one is simply inverted.
          return Material(
            color: active ? scheme.onSurface : scheme.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(999),
            child: InkWell(
              borderRadius: BorderRadius.circular(999),
              onTap: () => onSelected(tab),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14),
                child: Row(
                  children: [
                    Text(
                      tab.label,
                      style: TextStyle(
                        color: active ? scheme.surface : scheme.onSurface,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    if (count > 0) ...[
                      const SizedBox(width: 6),
                      Text(
                        '$count',
                        style: TextStyle(
                          color: active
                              ? scheme.surface.withValues(alpha: .6)
                              : scheme.onSurfaceVariant,
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _BookmarksContent extends StatelessWidget {
  const _BookmarksContent({
    required this.items,
    required this.watching,
    required this.onChangeStatus,
  });
  final List<BookmarkEntry> items;
  final bool watching;
  final ValueChanged<BookmarkEntry> onChangeStatus;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: AppSettingsController.instance,
    builder: (context, _) => LayoutBuilder(
      builder: (context, constraints) {
        final preference = AppSettingsController.instance.contentLayout;
        final list =
            preference == AniMixContentLayout.list ||
            (preference == AniMixContentLayout.automatic &&
                constraints.maxWidth < 620);
        if (list) {
          return ListView.separated(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 6, 20, 40),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(height: 4),
            itemBuilder: (context, index) => _BookmarkRow(
              entry: items[index],
              onLongPress: () => onChangeStatus(items[index]),
            ),
          );
        }
        return GridView.builder(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 40),
          itemCount: items.length,
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 210,
            childAspectRatio: .56,
            crossAxisSpacing: 14,
            mainAxisSpacing: 20,
          ),
          itemBuilder: (context, index) => _BookmarkCard(
            entry: items[index],
            showProgress: watching,
            onLongPress: () => onChangeStatus(items[index]),
          ),
        );
      },
    ),
  );
}

class _PosterBadge extends StatelessWidget {
  const _PosterBadge(this.label);
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
    decoration: BoxDecoration(
      color: const Color(0x99000000),
      borderRadius: BorderRadius.circular(999),
    ),
    child: Text(
      label,
      style: const TextStyle(
        color: Colors.white,
        fontSize: 10.5,
        fontWeight: FontWeight.w600,
      ),
    ),
  );
}

class _BookmarkCard extends StatelessWidget {
  const _BookmarkCard({
    required this.entry,
    required this.showProgress,
    required this.onLongPress,
  });
  final BookmarkEntry entry;
  final bool showProgress;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final anime = entry.anime;
    final total = anime.episodes ?? 0;
    final progress = total > 0
        ? (entry.watchedEpisodes / total).clamp(0.0, 1.0)
        : 0.0;
    final meta = [
      anime.kind?.toUpperCase() ?? 'TV',
      if (entry.watchedEpisodes > 0)
        total > 0
            ? '${entry.watchedEpisodes} из $total'
            : '${entry.watchedEpisodes} эп.',
    ].join(' · ');
    return GestureDetector(
      onTap: () => _open(context, anime.id),
      onLongPress: onLongPress,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(AniMixRadius.md),
                    child: SmartAnimePoster(
                      animeId: anime.id,
                      imageUrl: anime.imageUrl,
                      title: anime.name ?? '',
                      russianTitle: anime.russian,
                    ),
                  ),
                ),
                if (entry.score > 0)
                  Positioned(
                    top: 6,
                    right: 6,
                    child: _PosterBadge('★ ${entry.score}'),
                  ),
              ],
            ),
          ),
          if (showProgress) ...[
            const SizedBox(height: 6),
            ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(minHeight: 2, value: progress),
            ),
          ],
          const SizedBox(height: 8),
          SizedBox(
            height: MediaQuery.textScalerOf(context).scale(13.5) * 1.2 * 2 + 1,
            child: Text(
              anime.russian ?? anime.name ?? 'Без названия',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 13.5,
                height: 1.2,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(height: 2),
          Text(
            meta,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              fontSize: 11.5,
            ),
          ),
        ],
      ),
    );
  }
}

class _BookmarkRow extends StatelessWidget {
  const _BookmarkRow({required this.entry, required this.onLongPress});
  final BookmarkEntry entry;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final anime = entry.anime;
    final total = anime.episodes ?? 0;
    final meta = [
      if (entry.score > 0) '★ ${entry.score}',
      if (entry.watchedEpisodes > 0)
        total > 0
            ? '${entry.watchedEpisodes} из $total эп.'
            : '${entry.watchedEpisodes} эп.',
      anime.kind?.toUpperCase() ?? 'TV',
    ].join('  ·  ');
    return InkWell(
      borderRadius: BorderRadius.circular(AniMixRadius.md),
      onTap: () => _open(context, anime.id),
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(AniMixRadius.sm + 2),
              child: SizedBox(
                width: 60,
                height: 88,
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: anime.imageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    anime.russian ?? anime.name ?? 'Без названия',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    meta,
                    style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontSize: 12,
                    ),
                  ),
                  if (entry.watchedEpisodes > 0 && total > 0) ...[
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(2),
                      child: LinearProgressIndicator(
                        minHeight: 2,
                        value: (entry.watchedEpisodes / total).clamp(0.0, 1.0),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            IconButton(
              tooltip: 'Переместить в список',
              onPressed: onLongPress,
              icon: Icon(
                CupertinoIcons.ellipsis,
                size: 18,
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

void _open(BuildContext context, int animeId) => Navigator.push(
  context,
  CupertinoPageRoute<void>(builder: (_) => AnimeDetailScreen(animeId: animeId)),
);
