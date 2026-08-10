import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/app_settings.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
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
  final api = ref.read(apiClientProvider);
  final user = await api.getCurrentUser();
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

class _BookmarksAuthRequired implements Exception {
  const _BookmarksAuthRequired();
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
              loading: () =>
                  const Center(child: CupertinoActivityIndicator(radius: 15)),
              error: (error, _) => error is _BookmarksAuthRequired
                  ? AniMixEmptyState(
                      icon: CupertinoIcons.person_crop_circle_badge_exclam,
                      title: 'Нужен аккаунт Shikimori',
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
                      message: 'Shikimori временно не отвечает.',
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
                  fontWeight: FontWeight.w800,
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
      await ref
          .read(apiClientProvider)
          .setUserRate(
            entry.anime.id,
            value,
            score: entry.score,
            episodes: entry.watchedEpisodes,
            userId: user.id,
          );
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
  Widget build(BuildContext context) => SizedBox(
    height: 58,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      itemCount: BookmarkTab.values.length,
      separatorBuilder: (_, _) => const SizedBox(width: 8),
      itemBuilder: (context, index) {
        final tab = BookmarkTab.values[index];
        final active = selected == tab;
        return Material(
          color: active ? tab.color : tab.color.withValues(alpha: .10),
          borderRadius: BorderRadius.circular(999),
          child: InkWell(
            borderRadius: BorderRadius.circular(999),
            onTap: () => onSelected(tab),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  Icon(
                    tab.icon,
                    size: 15,
                    color: active ? Colors.white : tab.color,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    tab.label,
                    style: TextStyle(
                      color: active ? Colors.white : Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if ((counts[tab] ?? 0) > 0) ...[
                    const SizedBox(width: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: active
                            ? Colors.white.withValues(alpha: .20)
                            : Colors.white10,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        '${counts[tab]}',
                        style: const TextStyle(fontSize: 10),
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
            separatorBuilder: (_, _) => const SizedBox(height: 9),
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
                    borderRadius: BorderRadius.circular(22),
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
                    top: 8,
                    left: 8,
                    child: AniMixMetadataPill(label: '★ ${entry.score}'),
                  ),
                if (entry.watchedEpisodes > 0)
                  Positioned(
                    top: 8,
                    right: 8,
                    child: AniMixMetadataPill(
                      label: total > 0
                          ? '${entry.watchedEpisodes}/$total'
                          : '${entry.watchedEpisodes}',
                      accent: true,
                    ),
                  ),
              ],
            ),
          ),
          if (showProgress) ...[
            const SizedBox(height: 7),
            ClipRRect(
              borderRadius: BorderRadius.circular(3),
              child: LinearProgressIndicator(
                minHeight: 3,
                value: progress,
                backgroundColor: Colors.white12,
              ),
            ),
          ],
          const SizedBox(height: 8),
          Text(
            anime.russian ?? anime.name ?? 'Без названия',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 14,
              height: 1.15,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            anime.kind?.toUpperCase() ?? 'TV',
            style: const TextStyle(color: AniMixTheme.subtleText, fontSize: 10),
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
  Widget build(BuildContext context) => AniMixSurface(
    radius: 20,
    onTap: () => _open(context, entry.anime.id),
    padding: const EdgeInsets.all(10),
    child: GestureDetector(
      onLongPress: onLongPress,
      behavior: HitTestBehavior.opaque,
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: SizedBox(
              width: 64,
              height: 94,
              child: SmartAnimePoster(
                animeId: entry.anime.id,
                imageUrl: entry.anime.imageUrl,
                title: entry.anime.name ?? '',
                russianTitle: entry.anime.russian,
              ),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.anime.russian ?? entry.anime.name ?? 'Без названия',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 7,
                  runSpacing: 7,
                  children: [
                    if (entry.score > 0)
                      AniMixMetadataPill(label: '★ ${entry.score}'),
                    if (entry.watchedEpisodes > 0)
                      AniMixMetadataPill(
                        label: entry.anime.episodes != null
                            ? '${entry.watchedEpisodes}/${entry.anime.episodes}'
                            : '${entry.watchedEpisodes} эп.',
                        accent: true,
                      ),
                  ],
                ),
              ],
            ),
          ),
          const Icon(
            CupertinoIcons.chevron_right,
            color: Colors.white30,
            size: 15,
          ),
        ],
      ),
    ),
  );
}

void _open(BuildContext context, int animeId) => Navigator.push(
  context,
  CupertinoPageRoute<void>(builder: (_) => AnimeDetailScreen(animeId: animeId)),
);
