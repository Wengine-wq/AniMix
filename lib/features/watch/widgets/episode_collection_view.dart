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
    final available = episodes.where((episode) => episode.available).toList();
    final watched = episodes.where((episode) => episode.watched).length;
    // Resume point: first available episode not yet watched (or the first).
    final next = available.isEmpty
        ? null
        : available.firstWhere(
            (episode) => !episode.watched,
            orElse: () => available.first,
          );
    final showHeader = next != null;
    return AnimatedBuilder(
      animation: downloads,
      builder: (context, _) => ListView.separated(
        key: const PageStorageKey<String>('episode-list'),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        itemCount: episodes.length + (showHeader ? 1 : 0),
        separatorBuilder: (_, index) =>
            SizedBox(height: showHeader && index == 0 ? 14 : 2),
        itemBuilder: (context, index) {
          if (showHeader && index == 0) {
            return _ResumeHeader(
              next: next,
              watched: watched,
              total: episodes.length,
              onPlay: () => onPlay(next),
            );
          }
          final episode = episodes[index - (showHeader ? 1 : 0)];
          return _EpisodeCard(
            episode: episode,
            download: downloads.itemFor(episode.downloadId),
            onPlay: onPlay,
            onDownload: onDownload,
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

/// One clear next step above the list: continue from the first unwatched
/// episode, plus how far along the season is.
class _ResumeHeader extends StatelessWidget {
  const _ResumeHeader({
    required this.next,
    required this.watched,
    required this.total,
    required this.onPlay,
  });

  final EpisodeViewData next;
  final int watched;
  final int total;
  final VoidCallback onPlay;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final started = watched > 0;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            started ? 'Просмотрено $watched из $total' : '$total серий',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
          ),
          if (started) ...[
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(
                minHeight: 3,
                value: total == 0 ? 0 : watched / total,
              ),
            ),
          ],
          const SizedBox(height: 14),
          FilledButton.icon(
            onPressed: onPlay,
            icon: const Icon(Icons.play_arrow_rounded),
            label: Text(
              started ? 'Продолжить: ${next.title}' : 'Смотреть ${next.title}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}
