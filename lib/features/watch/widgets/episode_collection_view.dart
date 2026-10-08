import 'package:flutter/material.dart';

import '../../../core/animix_theme.dart';
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
    final downloads = HlsDownloadManager.instance;
    return AnimatedBuilder(
      animation: downloads,
      builder: (context, _) => ListView.separated(
        key: const PageStorageKey<String>('episode-list'),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        itemCount: episodes.length,
        separatorBuilder: (_, _) => const SizedBox(height: 2),
        itemBuilder: (context, index) => _EpisodeCard(
          episode: episodes[index],
          download: downloads.itemFor(episodes[index].downloadId),
          onPlay: onPlay,
          onDownload: onDownload,
        ),
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
  });

  final EpisodeViewData episode;
  final DownloadItem? download;
  final ValueChanged<EpisodeViewData> onPlay;
  final ValueChanged<EpisodeViewData> onDownload;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final state = download?.state;
    final status = _statusText(state, episode.watched, download?.progress ?? 0);
    final dim = episode.watched && state == null;
    return InkWell(
      borderRadius: BorderRadius.circular(AniMixRadius.md),
      onTap: episode.available ? () => onPlay(episode) : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(AniMixRadius.md - 2),
              ),
              child: episode.watched
                  ? Icon(
                      Icons.check_rounded,
                      size: 20,
                      color: scheme.onSurfaceVariant,
                    )
                  : Text(
                      episode.number,
                      maxLines: 1,
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
                    episode.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: dim ? scheme.onSurfaceVariant : scheme.onSurface,
                    ),
                  ),
                  if (status.isNotEmpty) ...[
                    const SizedBox(height: 3),
                    Text(
                      status,
                      style: TextStyle(
                        color: state == DownloadState.failed
                            ? scheme.error
                            : scheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (!episode.available)
              Padding(
                padding: const EdgeInsets.only(left: 8),
                child: Icon(
                  Icons.error_outline_rounded,
                  size: 20,
                  color: scheme.error,
                ),
              )
            else
              IconButton(
                tooltip: state == DownloadState.completed
                    ? 'Скачано'
                    : 'Скачать серию',
                onPressed:
                    state != DownloadState.downloading &&
                        state != DownloadState.completed
                    ? () => onDownload(episode)
                    : null,
                icon: state == DownloadState.downloading
                    ? SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          value: download?.progress,
                          strokeWidth: 2,
                        ),
                      )
                    : Icon(
                        state == DownloadState.completed
                            ? Icons.download_done_rounded
                            : Icons.file_download_outlined,
                        size: 21,
                        color: state == DownloadState.completed
                            ? scheme.onSurface
                            : scheme.onSurfaceVariant,
                      ),
              ),
          ],
        ),
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
