import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/animix_theme.dart';
import '../../core/app_settings.dart';
import '../../widgets/animix_surface.dart';
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
          return LayoutBuilder(
            builder: (context, constraints) {
              final preference = AppSettingsController.instance.contentLayout;
              final useCards =
                  preference == AniMixContentLayout.cards ||
                  (preference == AniMixContentLayout.automatic &&
                      constraints.maxWidth >= 720);
              return CustomScrollView(
                physics: const BouncingScrollPhysics(),
                slivers: [
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
                    sliver: SliverToBoxAdapter(child: _summary(items)),
                  ),
                  if (useCards)
                    SliverPadding(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                      sliver: SliverGrid(
                        gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 390,
                          mainAxisExtent: 150,
                          crossAxisSpacing: 14,
                          mainAxisSpacing: 14,
                        ),
                        delegate: SliverChildBuilderDelegate(
                          (_, index) =>
                              _downloadCard(items[index], compact: true),
                          childCount: items.length,
                        ),
                      ),
                    )
                  else
                    SliverPadding(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                      sliver: SliverList.separated(
                        itemCount: items.length,
                        separatorBuilder: (_, _) => const SizedBox(height: 12),
                        itemBuilder: (_, index) => _downloadCard(items[index]),
                      ),
                    ),
                ],
              );
            },
          );
        },
      ),
    );
  }

  Widget _summary(List<DownloadItem> items) {
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
        _stat('$completed', 'готово'),
        const SizedBox(width: 10),
        _stat('$active', 'активно'),
        const Spacer(),
        Text(
          _formatBytes(bytes),
          style: const TextStyle(
            color: AniMixTheme.subtleText,
            fontSize: 13,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _stat(String value, String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: 0.06),
      borderRadius: BorderRadius.circular(13),
      border: Border.all(color: AniMixTheme.divider),
    ),
    child: Text.rich(
      TextSpan(
        children: [
          TextSpan(
            text: value,
            style: const TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w800,
            ),
          ),
          TextSpan(
            text: '  $label',
            style: const TextStyle(color: AniMixTheme.subtleText),
          ),
        ],
      ),
      style: const TextStyle(fontSize: 12),
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
              Container(
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
              const SizedBox(height: 22),
              const Text(
                'Нет загрузок',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  decoration: TextDecoration.none,
                ),
              ),
              const SizedBox(height: 9),
              const Text(
                'Откройте тайтл, выберите озвучку и серию, затем нажмите кнопку загрузки рядом с эпизодом.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: AniMixTheme.subtleText,
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

  Widget _downloadCard(DownloadItem item, {bool compact = false}) {
    final completed = item.state == DownloadState.completed;
    final failed = item.state == DownloadState.failed;
    final accent = Theme.of(context).colorScheme.primary;
    return AniMixSurface(
      onTap: completed ? () => _play(item) : null,
      padding: const EdgeInsets.all(12),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: SizedBox(
              width: compact ? 76 : 62,
              height: compact ? 112 : 86,
              child: item.posterUrl?.isNotEmpty == true
                  ? CachedNetworkImage(
                      imageUrl: item.posterUrl!,
                      fit: BoxFit.cover,
                      errorWidget: (_, _, _) => _posterPlaceholder(),
                    )
                  : _posterPlaceholder(),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.animeTitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  '${item.episodeName}  •  ${item.quality}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AniMixTheme.subtleText,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 12),
                if (item.state == DownloadState.downloading) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      minHeight: 4,
                      value: item.progress,
                      color: accent,
                      backgroundColor: Colors.white12,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${(item.progress * 100).round()}%',
                    style: TextStyle(color: accent, fontSize: 11),
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
          if (failed && item.sourceUrl != null)
            IconButton(
              tooltip: 'Повторить',
              onPressed: () => _retry(item),
              icon: Icon(
                CupertinoIcons.arrow_clockwise,
                color: accent,
                size: 20,
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
              color: AniMixTheme.subtleText,
              size: 20,
            ),
          ),
        ],
      ),
    );
  }

  Widget _posterPlaceholder() => const ColoredBox(
    color: AniMixTheme.elevated,
    child: Center(child: Icon(CupertinoIcons.film, color: Colors.white30)),
  );

  void _retry(DownloadItem item) {
    final url = item.sourceUrl;
    if (url == null) return;
    _manager.startDownload(
      url: url,
      episodeId: item.episodeId,
      animeTitle: item.animeTitle,
      episodeName: item.episodeName,
      quality: item.quality,
      posterUrl: item.posterUrl,
    );
  }

  Future<void> _play(DownloadItem item) async {
    final uri = await _manager.playbackUriFor(item.episodeId);
    if (uri == null) return;
    if (!mounted) return;
    final separator = item.episodeId.lastIndexOf('_');
    final animeId =
        int.tryParse(
          separator < 0
              ? item.episodeId
              : item.episodeId.substring(0, separator),
        ) ??
        0;
    final episode = separator < 0
        ? '1'
        : item.episodeId.substring(separator + 1);
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
              : 'Удалить загрузку?',
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
