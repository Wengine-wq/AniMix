import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/app_settings.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../watch/watch_player_screen.dart';
import 'download_item.dart';
import 'hls_download_manager.dart';

class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  final _manager = HlsDownloadManager.instance;
  final _collapsedGroups = <String>{};

  @override
  void initState() {
    super.initState();
    _manager.initialize();
  }

  @override
  Widget build(BuildContext context) {
    return AniMixPage(
      title: 'Загрузки',
      child: AnimatedBuilder(
        animation: Listenable.merge([_manager, AppSettingsController.instance]),
        builder: (context, _) {
          final items = _manager.downloads.reversed.toList();
          if (items.isEmpty) return _emptyState();
          final groups = _group(items);
          return CustomScrollView(
            physics: const BouncingScrollPhysics(),
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
                sliver: SliverToBoxAdapter(child: _summary(items, groups)),
              ),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                sliver: SliverList.separated(
                  itemCount: groups.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 14),
                  itemBuilder: (_, index) => _groupCard(groups[index]),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  List<_DownloadGroup> _group(List<DownloadItem> items) {
    final grouped = <String, _DownloadGroup>{};
    for (final item in items) {
      final key = item.animeId > 0
          ? 'id:${item.animeId}'
          : 'title:${_normalize(item.animeTitle)}';
      final group = grouped.putIfAbsent(
        key,
        () => _DownloadGroup(
          key: key,
          animeId: item.animeId,
          title: item.animeTitle.trim().isEmpty
              ? 'Без названия'
              : item.animeTitle,
          posterUrl: item.posterUrl,
        ),
      );
      group.items.add(item);
      if (group.posterUrl?.isNotEmpty != true &&
          item.posterUrl?.isNotEmpty == true) {
        group.posterUrl = item.posterUrl;
      }
    }
    return grouped.values.toList(growable: false);
  }

  Widget _summary(List<DownloadItem> items, List<_DownloadGroup> groups) {
    final completed = items
        .where((item) => item.state == DownloadState.completed)
        .length;
    final active = items
        .where((item) => item.state == DownloadState.downloading)
        .length;
    final bytes = items.fold<int>(
      0,
      (sum, item) => sum + (item.fileSizeBytes ?? 0),
    );
    return Row(
      children: [
        _stat('${groups.length}', 'тайтлов'),
        const SizedBox(width: 8),
        _stat('$completed', 'готово'),
        const SizedBox(width: 8),
        if (active > 0) _stat('$active', 'активно'),
        const Spacer(),
        Text(
          _formatBytes(bytes),
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            fontSize: 13,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _stat(String value, String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(13),
      border: Border.all(
        color: Theme.of(
          context,
        ).colorScheme.outlineVariant.withValues(alpha: .55),
      ),
    ),
    child: Text.rich(
      TextSpan(
        children: [
          TextSpan(
            text: value,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurface,
              fontWeight: FontWeight.w800,
            ),
          ),
          TextSpan(
            text: '  $label',
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
      style: const TextStyle(fontSize: 12),
    ),
  );

  Widget _groupCard(_DownloadGroup group) {
    final expanded = !_collapsedGroups.contains(group.key);
    final completed = group.items
        .where((item) => item.state == DownloadState.completed)
        .length;
    final active = group.items
        .where((item) => item.state == DownloadState.downloading)
        .length;
    final accent = Theme.of(context).colorScheme.primary;
    return AniMixSurface(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: () => setState(() {
              if (expanded) {
                _collapsedGroups.add(group.key);
              } else {
                _collapsedGroups.remove(group.key);
              }
            }),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 14, 12, 14),
              child: Row(
                children: [
                  _groupPoster(group, accent),
                  const SizedBox(width: 13),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          group.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurface,
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          '${group.items.length} серий  •  $completed готово${active > 0 ? '  •  $active активно' : ''}',
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
                  AnimatedRotation(
                    turns: expanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 220),
                    curve: Curves.easeOutCubic,
                    child: Icon(
                      CupertinoIcons.chevron_down,
                      color: accent,
                      size: 20,
                    ),
                  ),
                ],
              ),
            ),
          ),
          AnimatedSize(
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
            child: expanded
                ? Padding(
                    padding: const EdgeInsets.fromLTRB(14, 0, 14, 12),
                    child: Column(
                      children: [
                        const Divider(height: 1),
                        const SizedBox(height: 4),
                        for (final item in group.items) _episodeRow(item),
                      ],
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ],
      ),
    );
  }

  Widget _groupPoster(_DownloadGroup group, Color accent) {
    final child = group.animeId > 0
        ? SmartAnimePoster(
            animeId: group.animeId,
            imageUrl: group.posterUrl,
            title: group.title,
            fit: BoxFit.cover,
            onResolved: (url) => _manager.updateAnimePoster(
              animeId: group.animeId,
              animeTitle: group.title,
              posterUrl: url,
            ),
          )
        : (group.posterUrl?.isNotEmpty == true
              ? CachedNetworkImage(
                  imageUrl: group.posterUrl!,
                  fit: BoxFit.cover,
                  errorWidget: (_, _, _) => _posterPlaceholder(accent),
                )
              : _posterPlaceholder(accent));
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(width: 54, height: 72, child: child),
    );
  }

  Widget _episodeRow(DownloadItem item) {
    final completed = item.state == DownloadState.completed;
    final failed = item.state == DownloadState.failed;
    final accent = Theme.of(context).colorScheme.primary;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        children: [
          Expanded(
            child: InkWell(
              borderRadius: BorderRadius.circular(10),
              onTap: completed ? () => _play(item) : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.episodeName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurface,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      item.quality,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                    if (item.state == DownloadState.downloading) ...[
                      const SizedBox(height: 7),
                      TweenAnimationBuilder<double>(
                        tween: Tween(begin: 0, end: item.progress),
                        duration: const Duration(milliseconds: 260),
                        curve: Curves.easeOut,
                        builder: (_, value, _) => ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: LinearProgressIndicator(
                            minHeight: 4,
                            value: value,
                            color: accent,
                            backgroundColor: Colors.white12,
                          ),
                        ),
                      ),
                    ] else
                      Text(
                        failed
                            ? (item.error ?? 'Ошибка загрузки')
                            : _formatBytes(item.fileSizeBytes ?? 0),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: failed
                              ? CupertinoColors.systemRed
                              : const Color(0xFF34D399),
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          if (failed && item.sourceUrl != null)
            IconButton(
              tooltip: 'Повторить',
              onPressed: () => _retry(item),
              icon: Icon(
                CupertinoIcons.arrow_clockwise,
                color: accent,
                size: 19,
              ),
            ),
          IconButton(
            tooltip: item.state == DownloadState.downloading
                ? 'Отменить'
                : 'Удалить',
            onPressed: () => _confirmDelete(item),
            icon: Icon(
              item.state == DownloadState.downloading
                  ? CupertinoIcons.xmark_circle
                  : CupertinoIcons.trash,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              size: 19,
            ),
          ),
        ],
      ),
    );
  }

  Widget _posterPlaceholder(Color accent) => DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        colors: [
          accent.withValues(alpha: 0.25),
          Theme.of(context).colorScheme.surfaceContainerHigh,
        ],
      ),
    ),
    child: Center(
      child: Icon(
        CupertinoIcons.film,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    ),
  );

  Widget _emptyState() {
    final accent = Theme.of(context).colorScheme.primary;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 430),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TweenAnimationBuilder<double>(
                tween: Tween(begin: 0.86, end: 1),
                duration: const Duration(milliseconds: 700),
                curve: Curves.easeOutBack,
                builder: (_, scale, child) =>
                    Transform.scale(scale: scale, child: child),
                child: Container(
                  width: 82,
                  height: 82,
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(27),
                    border: Border.all(color: accent.withValues(alpha: 0.28)),
                  ),
                  child: Icon(
                    CupertinoIcons.arrow_down_to_line,
                    color: accent,
                    size: 36,
                  ),
                ),
              ),
              const SizedBox(height: 22),
              Text(
                'Нет загрузок',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurface,
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  decoration: TextDecoration.none,
                ),
              ),
              const SizedBox(height: 9),
              Text(
                'Откройте тайтл, выберите серию и качество — все эпизоды одного аниме будут собраны вместе.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 14,
                  height: 1.45,
                  decoration: TextDecoration.none,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

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

  Future<void> _play(DownloadItem item) async {
    final uri = await _manager.playbackUriFor(item.episodeId);
    if (uri == null || !mounted) return;
    final animeId = item.animeId > 0
        ? item.animeId
        : int.tryParse(item.episodeId.split('_').first) ?? 0;
    final episode = item.episodeId.split('_').last;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => WatchPlayerScreen(
          animeId: animeId,
          episodeNumber: episode,
          episodeTitle: item.episodeName,
          animeTitle: item.animeTitle,
          videoUrl: uri.toString(),
          sources: {'Офлайн': uri.toString()},
          posterUrl: item.posterUrl,
        ),
      ),
    );
  }

  Future<void> _confirmDelete(DownloadItem item) async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(
          item.state == DownloadState.downloading
              ? 'Отменить загрузку?'
              : 'Удалить эпизод?',
        ),
        content: Text(item.episodeName),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Нет'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (approved == true) await _manager.delete(item.episodeId);
  }

  static String _normalize(String value) =>
      value.toLowerCase().trim().replaceAll(RegExp(r'\s+'), ' ');

  static String _formatBytes(int bytes) {
    if (bytes >= 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} ГБ';
    }
    if (bytes >= 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} МБ';
    }
    return '${(bytes / 1024).toStringAsFixed(0)} КБ';
  }
}

class _DownloadGroup {
  _DownloadGroup({
    required this.key,
    required this.animeId,
    required this.title,
    this.posterUrl,
  });

  final String key;
  final int animeId;
  final String title;
  String? posterUrl;
  final List<DownloadItem> items = <DownloadItem>[];
}
