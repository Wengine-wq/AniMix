import 'dart:io';

import 'package:chewie/chewie.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../downloads/download_item.dart';
import '../downloads/hls_download_manager.dart';
import '../../core/app_settings.dart';
import '../../core/config.dart';
import 'watch_storage.dart';

class WatchPlayerScreen extends StatefulWidget {
  final int animeId;
  final String episodeNumber;
  final String? videoUrl;
  final Map<String, String>? sources;
  final String episodeTitle;
  final String? animeTitle;
  final String? posterUrl;

  const WatchPlayerScreen({
    required this.animeId,
    required this.episodeNumber,
    required this.episodeTitle,
    this.videoUrl,
    this.sources,
    this.animeTitle,
    this.posterUrl,
    super.key,
  });

  @override
  State<WatchPlayerScreen> createState() => _WatchPlayerScreenState();
}

class _WatchPlayerScreenState extends State<WatchPlayerScreen> {
  VideoPlayerController? _videoController;
  ChewieController? _chewieController;
  String? _initError;
  late final Map<String, String> _sources;
  late String _selectedQuality;
  bool _isChangingQuality = false;
  bool _isWatched = false;
  int _lastSaveTime = 0;
  final Set<String> _failedSources = <String>{};

  String get _episodeId => '${widget.animeId}_${widget.episodeNumber}';

  bool get _isSupportedPlatform =>
      Platform.isIOS ||
      Platform.isAndroid ||
      Platform.isWindows ||
      Platform.isMacOS;

  @override
  void initState() {
    super.initState();
    _sources = Map<String, String>.from(widget.sources ?? const {});
    if (_sources.isEmpty && widget.videoUrl != null) {
      _sources['Авто'] = widget.videoUrl!;
    }
    _selectedQuality = _bestQuality(_sources.keys);
    HlsDownloadManager.instance.initialize();
    if (_isSupportedPlatform) {
      _initPlayer(_sources[_selectedQuality]);
    } else {
      _initError = 'Воспроизведение пока не поддерживается на этой платформе.';
    }
  }

  Future<void> _initPlayer(
    String? source, {
    Duration? position,
    bool autoPlay = true,
  }) async {
    if (source == null || source.isEmpty) {
      if (mounted) setState(() => _initError = 'Видео недоступно.');
      return;
    }

    VideoPlayerController? controller;
    try {
      final uri = Uri.parse(source);
      controller = uri.scheme == 'file'
          ? VideoPlayerController.file(File.fromUri(uri))
          : VideoPlayerController.networkUrl(
              uri,
              httpHeaders: Config.providerMediaHeaders,
            );
      await controller.initialize().timeout(const Duration(seconds: 30));
      if (!controller.value.isInitialized ||
          controller.value.duration <= Duration.zero) {
        throw const FormatException('Плеер не получил метаданные потока');
      }

      final savedPosition =
          position ??
          await WatchStorage.getProgress(widget.animeId, widget.episodeNumber);
      if (savedPosition != null &&
          savedPosition > Duration.zero &&
          savedPosition < controller.value.duration) {
        await controller.seekTo(savedPosition);
      }
      controller.addListener(_onVideoProgress);

      final chewie = ChewieController(
        videoPlayerController: controller,
        autoPlay: autoPlay,
        looping: false,
        aspectRatio: 16 / 9,
        allowFullScreen: true,
        allowPlaybackSpeedChanging: true,
        materialProgressColors: ChewieProgressColors(
          playedColor: AppSettingsController.instance.accentColor,
          handleColor: Color.lerp(
            AppSettingsController.instance.accentColor,
            Colors.white,
            0.25,
          )!,
          bufferedColor: Colors.white38,
          backgroundColor: Colors.white12,
        ),
        errorBuilder: (_, message) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(message, textAlign: TextAlign.center),
          ),
        ),
      );

      if (!mounted) {
        chewie.dispose();
        controller.dispose();
        return;
      }
      setState(() {
        _videoController = controller;
        _chewieController = chewie;
        _initError = null;
        _isChangingQuality = false;
      });
    } catch (error) {
      await controller?.dispose();
      _failedSources.add(source);
      final fallbackQuality = _sortedQualities().cast<String?>().firstWhere((
        quality,
      ) {
        final candidate = quality == null ? null : _sources[quality];
        return candidate != null && !_failedSources.contains(candidate);
      }, orElse: () => null);
      if (mounted && fallbackQuality != null) {
        debugPrint(
          '[AniMix player] $_selectedQuality failed, trying $fallbackQuality: $error',
        );
        setState(() {
          _selectedQuality = fallbackQuality;
          _isChangingQuality = true;
        });
        await _initPlayer(
          _sources[fallbackQuality],
          position: position,
          autoPlay: autoPlay,
        );
        return;
      }
      if (!mounted) return;
      setState(() {
        _isChangingQuality = false;
        _initError = 'Не удалось открыть видеопоток: $error';
      });
    }
  }

  Future<void> _changeQuality(String quality) async {
    if (quality == _selectedQuality || _isChangingQuality) return;
    final oldVideo = _videoController;
    final oldChewie = _chewieController;
    final position = oldVideo?.value.position;
    final wasPlaying = oldVideo?.value.isPlaying ?? true;
    final selectedSource = _sources[quality];
    if (selectedSource != null) _failedSources.remove(selectedSource);

    setState(() {
      _selectedQuality = quality;
      _isChangingQuality = true;
      _chewieController = null;
      _videoController = null;
    });
    oldVideo?.removeListener(_onVideoProgress);
    oldChewie?.dispose();
    await oldVideo?.dispose();
    await _initPlayer(
      _sources[quality],
      position: position,
      autoPlay: wasPlaying,
    );
  }

  void _onVideoProgress() {
    final video = _videoController;
    if (video == null || !video.value.isInitialized) return;
    final position = video.value.position;
    final duration = video.value.duration;
    if (duration.inSeconds == 0) return;

    if (position.inSeconds > 0 &&
        (position.inSeconds - _lastSaveTime).abs() >= 5) {
      _lastSaveTime = position.inSeconds;
      WatchStorage.saveProgress(widget.animeId, widget.episodeNumber, position);
    }
    if (!_isWatched && position.inSeconds / duration.inSeconds >= 0.85) {
      _isWatched = true;
      WatchStorage.markEpisodeWatched(widget.animeId, widget.episodeNumber);
    }
  }

  Future<void> _showSourceMenu() async {
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: const Text('Качество видео'),
        message: Text('Сейчас: $_selectedQuality'),
        actions: [
          for (final quality in _sortedQualities())
            CupertinoActionSheetAction(
              onPressed: () {
                Navigator.pop(sheetContext);
                _changeQuality(quality);
              },
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(quality),
                  if (quality == _selectedQuality) ...[
                    const SizedBox(width: 8),
                    const Icon(CupertinoIcons.check_mark, size: 17),
                  ],
                ],
              ),
            ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(sheetContext),
          child: const Text('Отмена'),
        ),
      ),
    );
  }

  Future<void> _downloadCurrentQuality() async {
    final source = _sources[_selectedQuality];
    if (source == null) return;
    final manager = HlsDownloadManager.instance;
    final existing = manager.itemFor(_episodeId);
    if (existing?.state == DownloadState.completed) {
      _showMessage('Эпизод уже скачан');
      return;
    }
    manager.startDownload(
      url: source,
      episodeId: _episodeId,
      animeId: widget.animeId,
      animeTitle: widget.animeTitle ?? widget.episodeTitle,
      episodeName: widget.episodeTitle,
      posterUrl: widget.posterUrl,
      quality: _selectedQuality,
    );
    _showMessage('Загрузка $_selectedQuality началась');
  }

  void _showMessage(String message) {
    if (!mounted) return;
    showCupertinoDialog<void>(
      context: context,
      builder: (dialogContext) => CupertinoAlertDialog(
        content: Text(message),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('ОК'),
          ),
        ],
      ),
    );
  }

  List<String> _sortedQualities() {
    final qualities = _sources.keys.toList();
    qualities.sort((a, b) => _qualityRank(b).compareTo(_qualityRank(a)));
    return qualities;
  }

  static String _bestQuality(Iterable<String> qualities) {
    if (qualities.isEmpty) return 'Авто';
    final sorted = qualities.toList()
      ..sort((a, b) => _qualityRank(b).compareTo(_qualityRank(a)));
    return sorted.first;
  }

  static int _qualityRank(String label) {
    final number = int.tryParse(
      RegExp(r'\d+').firstMatch(label)?.group(0) ?? '',
    );
    if (number != null) return number;
    return label == 'Авто' ? -1 : 0;
  }

  void _retryPlayer() {
    final source = _sources[_selectedQuality];
    if (source != null) _failedSources.remove(source);
    setState(() => _initError = null);
    _initPlayer(source);
  }

  @override
  void dispose() {
    _videoController?.removeListener(_onVideoProgress);
    _chewieController?.dispose();
    _videoController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Colors.black.withValues(alpha: 0.82),
        middle: Text(widget.episodeTitle),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Icon(CupertinoIcons.xmark, color: Colors.white),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            CupertinoButton(
              padding: const EdgeInsets.symmetric(horizontal: 7),
              onPressed: _downloadCurrentQuality,
              child: const Icon(
                CupertinoIcons.arrow_down_circle,
                color: Colors.white,
              ),
            ),
            CupertinoButton(
              padding: const EdgeInsets.only(left: 7),
              onPressed: _sources.length > 1 ? _showSourceMenu : null,
              child: Text(
                _selectedQuality,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
      ),
      child: SafeArea(
        child: Center(
          child: AspectRatio(
            aspectRatio: 16 / 9,
            child: ColoredBox(
              color: Colors.black,
              child: _initError != null
                  ? _buildError()
                  : _chewieController != null && !_isChangingQuality
                  ? Chewie(controller: _chewieController!)
                  : _buildLoading(),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildError() => Center(
    child: Padding(
      padding: const EdgeInsets.all(28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(
            CupertinoIcons.xmark_octagon_fill,
            color: CupertinoColors.systemRed,
            size: 52,
          ),
          const SizedBox(height: 18),
          Text(_initError!, textAlign: TextAlign.center),
          const SizedBox(height: 18),
          CupertinoButton.filled(
            onPressed: _retryPlayer,
            child: const Text('Повторить'),
          ),
        ],
      ),
    ),
  );

  Widget _buildLoading() => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        CupertinoActivityIndicator(
          radius: 20,
          color: AppSettingsController.instance.accentColor,
        ),
        const SizedBox(height: 16),
        Text(
          _isChangingQuality
              ? 'Переключаем на $_selectedQuality'
              : 'Запускаем $_selectedQuality',
          style: const TextStyle(
            color: Colors.white70,
            fontSize: 14,
            fontWeight: FontWeight.w600,
            decoration: TextDecoration.none,
          ),
        ),
      ],
    ),
  );
}
