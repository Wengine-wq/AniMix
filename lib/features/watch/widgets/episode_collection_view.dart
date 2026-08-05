import 'package:flutter/material.dart';

import '../../../core/animix_theme.dart';
import '../../../core/app_settings.dart';
import '../../../widgets/animix_surface.dart';
import '../../downloads/download_item.dart';
import '../../downloads/hls_download_manager.dart';

class EpisodeViewData {
  const EpisodeViewData({
    required this.number,
    required this.title,
    required this.downloadId,
    this.available = true,
    this.watched = false,
  });

  final String number;
  final String title;
  final String downloadId;
  final bool available;
  final bool watched;
}

class EpisodeCollectionView extends StatelessWidget {
  const EpisodeCollectionView({
    required this.episodes,
    required this.onPlay,
    required this.onDownload,
    super.key,
  });

  final List<EpisodeViewData> episodes;
  final ValueChanged<EpisodeViewData> onPlay;
  final ValueChanged<EpisodeViewData> onDownload;

  @override
  Widget build(BuildContext context) {
    final settings = AppSettingsController.instance;
    final downloads = HlsDownloadManager.instance;
    return AnimatedBuilder(
      animation: Listenable.merge([settings, downloads]),
      builder: (context, _) => LayoutBuilder(
        builder: (context, constraints) {
          final useCards = switch (settings.contentLayout) {
            AniMixContentLayout.cards => true,
            AniMixContentLayout.list => false,
            AniMixContentLayout.automatic => constraints.maxWidth >= 720,
          };
          if (useCards) {
            final count = constraints.maxWidth >= 1040
                ? 4
                : constraints.maxWidth >= 720
                ? 3
                : 2;
            return GridView.builder(
              padding: const EdgeInsets.all(20),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: count,
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
                childAspectRatio: 2.05,
              ),
              itemCount: episodes.length,
              itemBuilder: (context, index) => _EpisodeCard(
                episode: episodes[index],
                download: downloads.itemFor(episodes[index].downloadId),
                onPlay: onPlay,
                onDownload: onDownload,
                compact: true,
              ),
            );
          }
          return ListView.separated(
            padding: const EdgeInsets.all(20),
            itemCount: episodes.length,
            separatorBuilder: (_, _) => const SizedBox(height: 10),
            itemBuilder: (context, index) => _EpisodeCard(
              episode: episodes[index],
              download: downloads.itemFor(episodes[index].downloadId),
              onPlay: onPlay,
              onDownload: onDownload,
              compact: false,
            ),
          );
        },
      ),
    );
  }
}

class _EpisodeCard extends StatelessWidget {
  const _EpisodeCard({
    required this.episode,
    required this.download,
    required this.onPlay,
    required this.onDownload,
    required this.compact,
  });

  final EpisodeViewData episode;
  final DownloadItem? download;
  final ValueChanged<EpisodeViewData> onPlay;
  final ValueChanged<EpisodeViewData> onDownload;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    final state = download?.state;
    return AniMixSurface(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 14 : 18,
        vertical: compact ? 12 : 14,
      ),
      onTap: episode.available ? () => onPlay(episode) : null,
      child: Row(
        children: [
          Container(
            width: 42,
            height: 42,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: accent.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Text(
              episode.number,
              maxLines: 1,
              style: TextStyle(color: accent, fontWeight: FontWeight.w800),
            ),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  episode.title,
                  maxLines: compact ? 2 : 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                if (episode.watched || state != null) ...[
                  const SizedBox(height: 5),
                  Text(
                    _statusText(
                      state,
                      episode.watched,
                      download?.progress ?? 0,
                    ),
                    style: TextStyle(
                      color: state == DownloadState.failed
                          ? Colors.redAccent
                          : AniMixTheme.subtleText,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
          IconButton(
            tooltip: state == DownloadState.completed
                ? 'Скачано'
                : 'Скачать серию',
            onPressed:
                episode.available &&
                    state != DownloadState.downloading &&
                    state != DownloadState.completed
                ? () => onDownload(episode)
                : null,
            icon: state == DownloadState.downloading
                ? SizedBox(
                    width: 21,
                    height: 21,
                    child: CircularProgressIndicator(
                      value: download?.progress,
                      strokeWidth: 2.4,
                      color: accent,
                    ),
                  )
                : Icon(
                    state == DownloadState.completed
                        ? Icons.download_done_rounded
                        : Icons.download_rounded,
                    color: state == DownloadState.completed
                        ? Colors.greenAccent
                        : accent,
                  ),
          ),
          Icon(
            episode.available
                ? Icons.play_circle_fill_rounded
                : Icons.error_outline_rounded,
            color: episode.available ? Colors.white54 : Colors.redAccent,
          ),
        ],
      ),
    );
  }

  static String _statusText(
    DownloadState? state,
    bool watched,
    double progress,
  ) {
    if (state == DownloadState.downloading) {
      return 'Загрузка ${(progress * 100).round()}%';
    }
    if (state == DownloadState.completed) return 'Доступно офлайн';
    if (state == DownloadState.failed) {
      return 'Ошибка загрузки — можно повторить';
    }
    return watched ? 'Просмотрено' : '';
  }
}
