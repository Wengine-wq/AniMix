import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/animix_theme.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../watch/watch_player_screen.dart';
import 'download_item.dart';
import 'hls_download_manager.dart';

/// Downloads as a shelf of titles. Each card opens that title's episodes;
/// long-press (or "Выбрать") enters selection mode for bulk deletion.
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  final _manager = HlsDownloadManager.instance;
  final _selected = <String>{};
  bool _selecting = false;

  @override
  void initState() {
    super.initState();
    _manager.initialize();
  }

  void _toggle(String key) => setState(() {
    if (!_selected.remove(key)) _selected.add(key);
    if (_selected.isEmpty) _selecting = false;
  });

  void _exitSelection() => setState(() {
    _selecting = false;
    _selected.clear();
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _manager,
    builder: (context, _) {
      final groups = DownloadGroup.fromItems(_manager.downloads);
      _selected.removeWhere((key) => !groups.any((group) => group.key == key));
      return AniMixPage(
        title: _selecting ? 'Выбрано: ${_selected.length}' : 'Загрузки',
        leading: _selecting
            ? IconButton(
                tooltip: 'Отменить выбор',
                onPressed: _exitSelection,
                icon: const Icon(CupertinoIcons.xmark),
              )
            : null,
        actions: [
          if (groups.isNotEmpty && !_selecting)
            TextButton(
              onPressed: () => setState(() => _selecting = true),
              child: const Text('Выбрать'),
            ),
          if (_selecting)
            TextButton(
              onPressed: () => setState(() {
                if (_selected.length == groups.length) {
                  _selected.clear();
                } else {
                  _selected
                    ..clear()
                    ..addAll(groups.map((group) => group.key));
                }
              }),
              child: Text(
                _selected.length == groups.length ? 'Снять все' : 'Все',
              ),
            ),
        ],
        child: groups.isEmpty
            ? const _EmptyDownloads()
            : Column(
                children: [
                  Expanded(
                    child: CustomScrollView(
                      physics: const BouncingScrollPhysics(),
                      slivers: [
                        SliverPadding(
                          padding: const EdgeInsets.fromLTRB(20, 6, 20, 18),
                          sliver: SliverToBoxAdapter(
                            child: _Summary(groups: groups),
                          ),
                        ),
                        SliverPadding(
                          padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                          sliver: SliverGrid.builder(
                            itemCount: groups.length,
                            gridDelegate:
                                const SliverGridDelegateWithMaxCrossAxisExtent(
                                  maxCrossAxisExtent: 150,
                                  childAspectRatio: .5,
                                  crossAxisSpacing: 12,
                                  mainAxisSpacing: 20,
                                ),
                            itemBuilder: (context, index) {
                              final group = groups[index];
                              return _TitleCard(
                                group: group,
                                selecting: _selecting,
                                selected: _selected.contains(group.key),
                                onTap: _selecting
                                    ? () => _toggle(group.key)
                                    : () => Navigator.push(
                                        context,
                                        CupertinoPageRoute<void>(
                                          builder: (_) => DownloadedTitleScreen(
                                            groupKey: group.key,
                                          ),
                                        ),
                                      ),
                                onLongPress: () => setState(() {
                                  _selecting = true;
                                  _selected.add(group.key);
                                }),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (_selecting)
                    _DeleteBar(
                      label: _selected.isEmpty
                          ? 'Выберите тайтлы'
                          : 'Удалить ${_plural(_selected.length, 'тайтл', 'тайтла', 'тайтлов')}',
                      onDelete: _selected.isEmpty
                          ? null
                          : () async {
                              final ids = [
                                for (final group in groups)
                                  if (_selected.contains(group.key))
                                    ...group.items.map(
                                      (item) => item.episodeId,
                                    ),
                              ];
                              final approved = await _confirm(
                                context,
                                'Удалить выбранные тайтлы?',
                                'Будут удалены все скачанные серии: ${ids.length}.',
                              );
                              if (approved != true) return;
                              await _manager.deleteMany(ids);
                              if (mounted) _exitSelection();
                            },
                    ),
                ],
              ),
      );
    },
  );
}

/// Episodes of one downloaded title, oldest episode first.
class DownloadedTitleScreen extends StatefulWidget {
  const DownloadedTitleScreen({required this.groupKey, super.key});

  final String groupKey;

  @override
  State<DownloadedTitleScreen> createState() => _DownloadedTitleScreenState();
}

class _DownloadedTitleScreenState extends State<DownloadedTitleScreen> {
  final _manager = HlsDownloadManager.instance;
  final _selected = <String>{};
  bool _selecting = false;

  void _toggle(String id) => setState(() {
    if (!_selected.remove(id)) _selected.add(id);
    if (_selected.isEmpty) _selecting = false;
  });

  void _exitSelection() => setState(() {
    _selecting = false;
    _selected.clear();
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _manager,
    builder: (context, _) {
      DownloadGroup? group;
      for (final candidate in DownloadGroup.fromItems(_manager.downloads)) {
        if (candidate.key == widget.groupKey) group = candidate;
      }
      if (group == null) {
        // Everything was deleted: leave the screen on the next frame.
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) Navigator.maybePop(context);
        });
        return const AniMixPage(title: 'Загрузки', child: SizedBox.shrink());
      }
      final items = group.items;
      _selected.removeWhere((id) => !items.any((item) => item.episodeId == id));
      return AniMixPage(
        title: _selecting ? 'Выбрано: ${_selected.length}' : group.title,
        leading: _selecting
            ? IconButton(
                tooltip: 'Отменить выбор',
                onPressed: _exitSelection,
                icon: const Icon(CupertinoIcons.xmark),
              )
            : null,
        actions: [
          if (_selecting)
            TextButton(
              onPressed: () => setState(() {
                if (_selected.length == items.length) {
                  _selected.clear();
                } else {
                  _selected
                    ..clear()
                    ..addAll(items.map((item) => item.episodeId));
                }
              }),
              child: Text(
                _selected.length == items.length ? 'Снять все' : 'Все',
              ),
            )
          else ...[
            TextButton(
              onPressed: () => setState(() => _selecting = true),
              child: const Text('Выбрать'),
            ),
            IconButton(
              tooltip: 'Удалить все серии',
              onPressed: () async {
                final approved = await _confirm(
                  context,
                  'Удалить все серии?',
                  '«${group!.title}»: ${_plural(items.length, 'серия', 'серии', 'серий')}.',
                );
                if (approved == true) {
                  await _manager.deleteMany(
                    items.map((item) => item.episodeId),
                  );
                }
              },
              icon: const Icon(CupertinoIcons.trash),
            ),
          ],
        ],
        child: Column(
          children: [
            Expanded(
              child: ListView(
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
                children: [
                  _TitleHeader(group: group),
                  const SizedBox(height: 18),
                  for (final item in items)
                    _EpisodeRow(
                      item: item,
                      selecting: _selecting,
                      selected: _selected.contains(item.episodeId),
                      onTap: _selecting
                          ? () => _toggle(item.episodeId)
                          : item.state == DownloadState.completed
                          ? () => _openOffline(item)
                          : null,
                      onLongPress: () => setState(() {
                        _selecting = true;
                        _selected.add(item.episodeId);
                      }),
                      onRetry: () => _retry(item),
                      onDelete: () async {
                        final approved = await _confirm(
                          context,
                          item.state == DownloadState.downloading
                              ? 'Отменить загрузку?'
                              : 'Удалить серию?',
                          item.episodeName,
                        );
                        if (approved == true) {
                          await _manager.delete(item.episodeId);
                        }
                      },
                    ),
                ],
              ),
            ),
            if (_selecting)
              _DeleteBar(
                label: _selected.isEmpty
                    ? 'Выберите серии'
                    : 'Удалить ${_plural(_selected.length, 'серию', 'серии', 'серий')}',
                onDelete: _selected.isEmpty
                    ? null
                    : () async {
                        final approved = await _confirm(
                          context,
                          'Удалить выбранные серии?',
                          _plural(_selected.length, 'серия', 'серии', 'серий'),
                        );
                        if (approved != true) return;
                        await _manager.deleteMany(Set.of(_selected));
                        if (mounted) _exitSelection();
                      },
              ),
          ],
        ),
      );
    },
  );

  void _retry(DownloadItem item) {
    final url = item.sourceUrl;
    if (url == null) return;
    _manager.startDownload(
      url: url,
      episodeId: item.episodeId,
      animeId: item.animeId,
      animeTitle: item.animeTitle,
      episodeName: item.episodeName,
      quality: item.quality,
      posterUrl: item.posterUrl,
    );
  }

  Future<void> _openOffline(
    DownloadItem item, {
    bool replaceCurrent = false,
  }) async {
    final uri = await _manager.playbackUriFor(item.episodeId);
    if (uri == null || !mounted) {
      throw StateError('Офлайн-файл следующей серии недоступен');
    }
    final next = await _manager.nextCompletedEpisode(item);
    if (!mounted) return;
    final animeId = item.animeId > 0
        ? item.animeId
        : int.tryParse(item.episodeId.split('_').first) ?? 0;
    final episode = item.episodeId.split('_').last;
    final route = MaterialPageRoute<void>(
      builder: (_) => WatchPlayerScreen(
        animeId: animeId,
        episodeNumber: episode,
        episodeTitle: item.episodeName,
        animeTitle: item.animeTitle,
        videoUrl: uri.toString(),
        sources: {'Офлайн': uri.toString()},
        posterUrl: item.posterUrl,
        nextEpisodeTitle: next?.episodeName,
        onPlayNext: next == null
            ? null
            : () => _openOffline(next, replaceCurrent: true),
      ),
    );
    if (replaceCurrent) {
      await Navigator.of(context).pushReplacement<void, void>(route);
    } else {
      await Navigator.of(context).push<void>(route);
    }
  }
}

/// All downloaded episodes of one title.
class DownloadGroup {
  DownloadGroup({required this.key, required this.sample});

  final String key;
  final DownloadItem sample;
  final List<DownloadItem> items = <DownloadItem>[];

  String get title => sample.animeTitle.trim().isEmpty
      ? 'Без названия'
      : sample.animeTitle.trim();

  int get completed =>
      items.where((item) => item.state == DownloadState.completed).length;

  int get failed =>
      items.where((item) => item.state == DownloadState.failed).length;

  int get active =>
      items.where((item) => item.state == DownloadState.downloading).length;

  int get bytes =>
      items.fold<int>(0, (sum, item) => sum + (item.fileSizeBytes ?? 0));

  /// Overall progress of unfinished episodes, or null when none are active.
  double? get activeProgress {
    final running = items
        .where((item) => item.state == DownloadState.downloading)
        .toList();
    if (running.isEmpty) return null;
    return running.fold<double>(0, (sum, item) => sum + item.progress) /
        running.length;
  }

  /// Titles in the order they were last added to; episodes inside a title in
  /// ascending episode order (1, 2, 3 …).
  static List<DownloadGroup> fromItems(List<DownloadItem> downloads) {
    final grouped = <String, DownloadGroup>{};
    for (final item in downloads.reversed) {
      final key = HlsDownloadManager.groupKey(item);
      final group = grouped.putIfAbsent(
        key,
        () => DownloadGroup(key: key, sample: item),
      );
      group.items.add(item);
    }
    for (final group in grouped.values) {
      group.items.sort((a, b) {
        final left = HlsDownloadManager.episodeNumber(a);
        final right = HlsDownloadManager.episodeNumber(b);
        if (left != null && right != null) return left.compareTo(right);
        if (left != null) return -1;
        if (right != null) return 1;
        return a.episodeName.compareTo(b.episodeName);
      });
    }
    return grouped.values.toList(growable: false);
  }
}

class _Summary extends StatelessWidget {
  const _Summary({required this.groups});
  final List<DownloadGroup> groups;

  @override
  Widget build(BuildContext context) {
    final episodes = groups.fold<int>(0, (sum, g) => sum + g.items.length);
    final active = groups.fold<int>(0, (sum, g) => sum + g.active);
    final bytes = groups.fold<int>(0, (sum, g) => sum + g.bytes);
    final text = [
      _plural(groups.length, 'тайтл', 'тайтла', 'тайтлов'),
      _plural(episodes, 'серия', 'серии', 'серий'),
      if (active > 0) 'загружается $active',
      _formatBytes(bytes),
    ].join('  ·  ');
    return Text(
      text,
      style: TextStyle(
        color: Theme.of(context).colorScheme.onSurfaceVariant,
        fontSize: 13,
      ),
    );
  }
}

class _TitleCard extends StatelessWidget {
  const _TitleCard({
    required this.group,
    required this.selecting,
    required this.selected,
    required this.onTap,
    required this.onLongPress,
  });

  final DownloadGroup group;
  final bool selecting;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final progress = group.activeProgress;
    final meta = [
      _plural(group.items.length, 'серия', 'серии', 'серий'),
      if (group.failed > 0)
        'ошибок: ${group.failed}'
      else if (group.bytes > 0)
        _formatBytes(group.bytes),
    ].join(' · ');
    return GestureDetector(
      onTap: onTap,
      onLongPress: onLongPress,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: AnimatedScale(
              scale: selected ? .94 : 1,
              duration: const Duration(milliseconds: 160),
              curve: Curves.easeOutCubic,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(AniMixRadius.md),
                    child: DownloadPoster(item: group.sample),
                  ),
                  if (selecting)
                    Positioned(
                      top: 8,
                      right: 8,
                      child: _SelectMark(selected: selected),
                    ),
                  if (progress != null)
                    Positioned(
                      left: 8,
                      right: 8,
                      bottom: 8,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(2),
                        child: LinearProgressIndicator(
                          minHeight: 3,
                          value: progress,
                          backgroundColor: const Color(0x66000000),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: MediaQuery.textScalerOf(context).scale(13.5) * 1.2 * 2 + 1,
            child: Text(
              group.title,
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
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 11.5),
          ),
        ],
      ),
    );
  }
}

class _TitleHeader extends StatelessWidget {
  const _TitleHeader({required this.group});
  final DownloadGroup group;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(AniMixRadius.md),
            child: SizedBox(
              width: 92,
              height: 132,
              child: DownloadPoster(item: group.sample),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  group.title,
                  style: const TextStyle(
                    fontSize: 20,
                    height: 1.15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  [
                    '${group.completed} из ${group.items.length} готово',
                    if (group.bytes > 0) _formatBytes(group.bytes),
                  ].join('  ·  '),
                  style: TextStyle(
                    color: scheme.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _EpisodeRow extends StatelessWidget {
  const _EpisodeRow({
    required this.item,
    required this.selecting,
    required this.selected,
    required this.onTap,
    required this.onLongPress,
    required this.onRetry,
    required this.onDelete,
  });

  final DownloadItem item;
  final bool selecting;
  final bool selected;
  final VoidCallback? onTap;
  final VoidCallback onLongPress;
  final VoidCallback onRetry;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final downloading = item.state == DownloadState.downloading;
    final failed = item.state == DownloadState.failed;
    final number = HlsDownloadManager.episodeNumber(item);
    final status = downloading
        ? 'Загрузка ${(item.progress * 100).round()}%'
        : failed
        ? (item.error ?? 'Ошибка загрузки')
        : [item.quality, _formatBytes(item.fileSizeBytes ?? 0)].join(' · ');
    return InkWell(
      borderRadius: BorderRadius.circular(AniMixRadius.md),
      onTap: onTap,
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: Row(
          children: [
            if (selecting) ...[
              _SelectMark(selected: selected),
              const SizedBox(width: 12),
            ],
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(AniMixRadius.md - 2),
              ),
              child: downloading
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        value: item.progress,
                        strokeWidth: 2,
                      ),
                    )
                  : Text(
                      number == null
                          ? '•'
                          : (number == number.roundToDouble()
                                ? number.toInt().toString()
                                : number.toString()),
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    item.episodeName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    status,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: failed ? scheme.error : scheme.onSurfaceVariant,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            if (!selecting) ...[
              if (failed && item.sourceUrl != null)
                IconButton(
                  tooltip: 'Повторить',
                  onPressed: onRetry,
                  icon: Icon(
                    CupertinoIcons.arrow_clockwise,
                    size: 19,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              IconButton(
                tooltip: downloading ? 'Отменить' : 'Удалить',
                onPressed: onDelete,
                icon: Icon(
                  downloading ? CupertinoIcons.xmark : CupertinoIcons.trash,
                  size: 19,
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Cover for a downloaded title: the offline copy when it exists, otherwise
/// the network poster (which, once resolved, is saved for offline use).
class DownloadPoster extends StatelessWidget {
  const DownloadPoster({required this.item, super.key});
  final DownloadItem item;

  @override
  Widget build(BuildContext context) {
    final manager = HlsDownloadManager.instance;
    final file = manager.offlinePoster(item);
    final placeholder = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      child: Center(
        child: Icon(
          CupertinoIcons.film,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
    if (file != null) {
      return Image.file(
        file,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        cacheWidth: 480,
        errorBuilder: (_, _, _) => placeholder,
      );
    }
    if (item.animeId <= 0 && item.posterUrl == null) return placeholder;
    return SmartAnimePoster(
      animeId: item.animeId,
      imageUrl: item.posterUrl,
      title: item.animeTitle,
      onResolved: (url) => manager.updateAnimePoster(
        animeId: item.animeId,
        animeTitle: item.animeTitle,
        posterUrl: url,
      ),
    );
  }
}

class _SelectMark extends StatelessWidget {
  const _SelectMark({required this.selected});
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AnimatedContainer(
      duration: const Duration(milliseconds: 140),
      width: 24,
      height: 24,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: selected ? scheme.primary : const Color(0x59000000),
        border: Border.all(
          color: selected ? scheme.primary : Colors.white70,
          width: 1.5,
        ),
      ),
      child: selected
          ? Icon(Icons.check_rounded, size: 16, color: scheme.onPrimary)
          : null,
    );
  }
}

class _DeleteBar extends StatelessWidget {
  const _DeleteBar({required this.label, required this.onDelete});
  final String label;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) => SafeArea(
    top: false,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
      child: SizedBox(
        width: double.infinity,
        child: FilledButton.icon(
          onPressed: onDelete,
          style: FilledButton.styleFrom(
            backgroundColor: Theme.of(context).colorScheme.error,
            foregroundColor: Theme.of(context).colorScheme.onError,
          ),
          icon: const Icon(CupertinoIcons.trash, size: 18),
          label: Text(label),
        ),
      ),
    ),
  );
}

class _EmptyDownloads extends StatelessWidget {
  const _EmptyDownloads();

  @override
  Widget build(BuildContext context) => const AniMixEmptyState(
    icon: CupertinoIcons.arrow_down_to_line,
    title: 'Нет загрузок',
    message:
        'Откройте тайтл, выберите серию — скачанные серии соберутся здесь '
        'карточками по тайтлам.',
  );
}

Future<bool?> _confirm(BuildContext context, String title, String message) =>
    showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(dialogContext).colorScheme.error,
              foregroundColor: Theme.of(dialogContext).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );

String _plural(int count, String one, String few, String many) {
  final mod10 = count % 10;
  final mod100 = count % 100;
  final word = mod10 == 1 && mod100 != 11
      ? one
      : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
      ? few
      : many;
  return '$count $word';
}

String _formatBytes(int bytes) {
  if (bytes <= 0) return '0 МБ';
  if (bytes >= 1024 * 1024 * 1024) {
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} ГБ';
  }
  if (bytes >= 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(0)} МБ';
  }
  return '${(bytes / 1024).toStringAsFixed(0)} КБ';
}
