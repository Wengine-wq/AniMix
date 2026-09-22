import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../downloads/download_item.dart';
import '../downloads/hls_download_manager.dart';
import '../../core/app_logging.dart';
import '../../core/app_settings.dart';
import '../../core/config.dart';
import 'services/anime_skip_service.dart';
import 'watch_storage.dart';
import 'widgets/player_gesture_layer.dart';

class WatchPlayerScreen extends StatefulWidget {
  final int animeId;
  final String episodeNumber;
  final String? videoUrl;
  final Map<String, String>? sources;
  final String episodeTitle;
  final String? animeTitle;
  final String? posterUrl;
  final Future<void> Function()? onPlayNext;
  final String? nextEpisodeTitle;

  const WatchPlayerScreen({
    required this.animeId,
    required this.episodeNumber,
    required this.episodeTitle,
    this.videoUrl,
    this.sources,
    this.animeTitle,
    this.posterUrl,
    this.onPlayNext,
    this.nextEpisodeTitle,
    super.key,
  });

  @override
  State<WatchPlayerScreen> createState() => _WatchPlayerScreenState();
}

class _WatchPlayerScreenState extends State<WatchPlayerScreen> {
  VideoPlayerController? _videoController;
  String? _initError;
  late final Map<String, String> _sources;
  late String _selectedQuality;
  bool _isChangingQuality = false;
  bool _isWatched = false;
  int _lastSaveTime = 0;
  Duration? _lastObservedPosition;
  int _pendingPlaybackMilliseconds = 0;
  int _initializationGeneration = 0;
  bool _playerErrorHandled = false;
  int? _actualVideoHeight;
  final Set<String> _failedSources = <String>{};
  OpeningTiming? _openingTiming;
  List<AnimeSkipSegment> _apiSkipSegments = const [];
  final Set<String> _skippedSegments = <String>{};
  AnimeSkipSegment? _activeSkipSegment;
  bool _skipSegmentQueued = false;
  int? _nextCountdown;
  bool _nextCancelled = false;
  bool _isAdvancingToNext = false;
  bool _isFullScreen = false;
  bool _controlsVisible = true;
  Timer? _hideControlsTimer;
  Timer? _seekFeedbackTimer;
  int? _seekFeedbackSeconds;
  double? _dragPositionSeconds;
  double _playbackSpeed = 1;
  int? _manualOpeningStart;
  bool _wakeLockEnabled = false;

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
    _showControls();
    WatchStorage.openingTimingRevision.addListener(_reloadOpeningTiming);
    unawaited(_reloadOpeningTiming());
    if (_isSupportedPlatform) {
      _initPlayer(_sources[_selectedQuality]);
    } else {
      _initError = 'Воспроизведение пока не поддерживается на этой платформе.';
    }
  }

  Future<void> _reloadOpeningTiming() async {
    final timing = await WatchStorage.getOpeningTiming(
      widget.animeId,
      widget.episodeNumber,
    );
    if (!mounted || timing == _openingTiming) return;
    setState(() => _openingTiming = timing);
    final position = _videoController?.value.position;
    if (position != null) _updateSkipSegment(position);
  }

  Future<void> _initPlayer(
    String? source, {
    Duration? position,
    bool autoPlay = true,
  }) async {
    final generation = ++_initializationGeneration;
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
      await controller.initialize().timeout(
        Duration(seconds: Platform.isWindows ? 14 : 28),
        onTimeout: () => throw TimeoutException(
          'Источник не ответил за ${Platform.isWindows ? 14 : 28} секунд',
        ),
      );
      if (!mounted || generation != _initializationGeneration) {
        await controller.dispose();
        return;
      }
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
      if (_playbackSpeed != 1) {
        try {
          await controller.setPlaybackSpeed(_playbackSpeed);
        } catch (error) {
          debugPrint('[AniMix player] speed unavailable: $error');
          _playbackSpeed = 1;
        }
      }
      if (!mounted || generation != _initializationGeneration) {
        await controller.dispose();
        return;
      }
      controller.addListener(_onVideoProgress);
      _playerErrorHandled = false;
      setState(() {
        _videoController = controller;
        _actualVideoHeight = _decodedHeight(controller!);
        _initError = null;
        _isChangingQuality = false;
      });
      debugPrint(
        '[AniMix player] ready: $_selectedQuality, '
        'duration ${controller.value.duration}',
      );
      unawaited(_loadAnimeSkipSegments(controller.value.duration));
      // Let VideoPlayer mount its native texture before starting the decoder.
      // Starting playback first is fragile on platform video backends.
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted ||
          generation != _initializationGeneration ||
          !identical(_videoController, controller)) {
        return;
      }
      if (autoPlay) {
        await controller.play();
        debugPrint('[AniMix player] playback started: $_selectedQuality');
      }
      _syncWakeLock(controller.value.isPlaying);
    } catch (error, stackTrace) {
      controller?.removeListener(_onVideoProgress);
      if (mounted && identical(_videoController, controller)) {
        setState(() => _videoController = null);
      }
      await controller?.dispose();
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Windows player',
        context:
            'Не удалось открыть $_selectedQuality (${Uri.tryParse(source)?.host ?? 'unknown'})',
      );
      _failedSources.add(source);
      final fallbackQuality = _sortedQualities().cast<String?>().firstWhere((
        quality,
      ) {
        final candidate = quality == null ? null : _sources[quality];
        return candidate != null && !_failedSources.contains(candidate);
      }, orElse: () => null);
      final maxAttempts = Platform.isWindows ? 2 : 3;
      if (mounted &&
          generation == _initializationGeneration &&
          fallbackQuality != null &&
          _failedSources.length < maxAttempts) {
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
    final position = oldVideo?.value.position;
    final wasPlaying = oldVideo?.value.isPlaying ?? true;
    final selectedSource = _sources[quality];
    _failedSources.clear();
    if (selectedSource != null) _failedSources.remove(selectedSource);

    setState(() {
      _selectedQuality = quality;
      _isChangingQuality = true;
      _actualVideoHeight = null;
      _videoController = null;
    });
    oldVideo?.removeListener(_onVideoProgress);
    await oldVideo?.dispose();
    await _initPlayer(
      _sources[quality],
      position: position,
      autoPlay: wasPlaying,
    );
  }

  void _onVideoProgress() {
    final video = _videoController;
    if (video == null) return;
    if (video.value.hasError && !_playerErrorHandled) {
      _playerErrorHandled = true;
      final message = video.value.errorDescription ?? 'Неизвестная ошибка';
      AppLogBuffer.instance.recordError(
        StateError(message),
        StackTrace.current,
        source: 'Windows player',
        context: 'Ошибка после запуска $_selectedQuality',
      );
      if (mounted) {
        setState(() => _initError = 'Видеопоток прерван: $message');
      }
      return;
    }
    if (!video.value.isInitialized) return;
    final position = video.value.position;
    if (video.value.isPlaying) {
      final previous = _lastObservedPosition;
      if (previous != null) {
        final elapsed = position.inMilliseconds - previous.inMilliseconds;
        // Count normal forward playback only. Seeks, buffering jumps and
        // timeline resets are deliberately excluded from viewing time.
        if (elapsed > 0 && elapsed <= 2500) {
          _pendingPlaybackMilliseconds += elapsed;
          if (_pendingPlaybackMilliseconds >= 30000) {
            final seconds = _pendingPlaybackMilliseconds ~/ 1000;
            _pendingPlaybackMilliseconds %= 1000;
            unawaited(WatchStorage.recordWatchSeconds(widget.animeId, seconds));
          }
        }
      }
      _lastObservedPosition = position;
    } else {
      _lastObservedPosition = null;
      _flushPlaybackTime();
    }
    _syncWakeLock(video.value.isPlaying);
    if (!video.value.isPlaying && !_controlsVisible) _showControls();
    final decodedHeight = _decodedHeight(video);
    if (decodedHeight != null &&
        decodedHeight != _actualVideoHeight &&
        mounted) {
      setState(() => _actualVideoHeight = decodedHeight);
    }
    final duration = video.value.duration;
    if (duration.inSeconds == 0) return;

    if (position.inSeconds > 0 &&
        (position.inSeconds - _lastSaveTime).abs() >= 5) {
      _lastSaveTime = position.inSeconds;
      WatchStorage.saveProgress(widget.animeId, widget.episodeNumber, position);
    }
    _updateSkipSegment(position);
    _updateNextEpisode(position, duration);
    if (!_isWatched && position.inSeconds / duration.inSeconds >= 0.85) {
      _isWatched = true;
      WatchStorage.markEpisodeWatched(widget.animeId, widget.episodeNumber);
    }
  }

  void _flushPlaybackTime() {
    final seconds = _pendingPlaybackMilliseconds ~/ 1000;
    _pendingPlaybackMilliseconds %= 1000;
    if (seconds > 0) {
      unawaited(WatchStorage.recordWatchSeconds(widget.animeId, seconds));
    }
  }

  void _updateNextEpisode(Duration position, Duration duration) {
    if (widget.onPlayNext == null ||
        _nextCancelled ||
        _isAdvancingToNext ||
        !AppSettingsController.instance.autoPlayNextEpisode) {
      return;
    }
    final remaining = duration - position;
    if (remaining > const Duration(seconds: 5)) return;
    if (remaining <= const Duration(milliseconds: 750)) {
      unawaited(_playNextEpisode());
      return;
    }
    final countdown = remaining.inMilliseconds.ceil() ~/ 1000;
    if (_nextCountdown != countdown && mounted) {
      setState(() => _nextCountdown = countdown);
    }
  }

  Future<void> _playNextEpisode() async {
    final callback = widget.onPlayNext;
    if (callback == null || _isAdvancingToNext || _nextCancelled) return;
    _isAdvancingToNext = true;
    if (mounted) setState(() => _nextCountdown = 0);
    try {
      await callback();
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Autoplay',
        context: 'Не удалось открыть следующую серию',
      );
      if (mounted) {
        setState(() {
          _isAdvancingToNext = false;
          _nextCancelled = true;
          _nextCountdown = null;
        });
      }
    }
  }

  void _cancelNextEpisode() {
    setState(() {
      _nextCancelled = true;
      _nextCountdown = null;
    });
  }

  Future<void> _loadAnimeSkipSegments(Duration videoDuration) async {
    final segments = await AnimeSkipService.instance.segmentsForEpisode(
      malId: widget.animeId,
      episodeNumber: widget.episodeNumber,
      videoDuration: videoDuration,
      title: widget.animeTitle,
    );
    if (!mounted) return;
    setState(() => _apiSkipSegments = segments);
    final position = _videoController?.value.position;
    if (position != null) _updateSkipSegment(position);
  }

  AnimeSkipSegment? _segmentAt(Duration position) {
    final timing = _openingTiming;
    if (timing != null) {
      final manual = AnimeSkipSegment(
        id: 'manual-opening-${widget.animeId}-${widget.episodeNumber}',
        kind: AnimeSkipSegmentKind.opening,
        start: Duration(seconds: timing.startSecond),
        end: Duration(seconds: timing.endSecond),
      );
      if (!_skippedSegments.contains(manual.id) &&
          position >= manual.start &&
          position < manual.end) {
        return manual;
      }
    }
    for (final segment in _apiSkipSegments) {
      if (!_skippedSegments.contains(segment.id) &&
          position >= segment.start &&
          position < segment.end) {
        return segment;
      }
    }
    return null;
  }

  void _updateSkipSegment(Duration position) {
    final segment = _segmentAt(position);
    if (segment != null && AppSettingsController.instance.autoSkipOpenings) {
      unawaited(_skipSegment(segment));
      return;
    }
    if (_activeSkipSegment?.id != segment?.id && mounted) {
      setState(() => _activeSkipSegment = segment);
    }
  }

  Future<void> _skipSegment([AnimeSkipSegment? requested]) async {
    final segment = requested ?? _activeSkipSegment;
    final video = _videoController;
    if (segment == null ||
        video == null ||
        _skippedSegments.contains(segment.id) ||
        _skipSegmentQueued) {
      return;
    }
    _skipSegmentQueued = true;
    try {
      await video.seekTo(segment.end);
      if (mounted) {
        setState(() {
          _skippedSegments.add(segment.id);
          _activeSkipSegment = null;
        });
      }
    } finally {
      _skipSegmentQueued = false;
    }
  }

  Future<void> _showSourceMenu() async {
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: const Text('Качество видео'),
        message: Text(
          _actualVideoHeight == null
              ? 'Сейчас: $_selectedQuality'
              : 'Сейчас декодируется: ${_actualVideoHeight}p',
        ),
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
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(quality),
                      const SizedBox(height: 3),
                      Text(
                        _qualityDescription(quality),
                        style: const TextStyle(
                          fontSize: 11,
                          color: CupertinoColors.systemGrey,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ],
                  ),
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

  static int? _decodedHeight(VideoPlayerController controller) {
    final height = controller.value.size.height.round();
    return height > 0 ? height : null;
  }

  String get _qualityBadge {
    final actual = _actualVideoHeight;
    if (actual == null) return _selectedQuality;
    final requested = RegExp(r'(\d+)p').firstMatch(_selectedQuality)?.group(1);
    if (requested == '$actual') return '${actual}p';
    if (_selectedQuality == 'Авто' || requested == null) {
      return 'Авто · ${actual}p';
    }
    return '$_selectedQuality → ${actual}p';
  }

  static String _qualityDescription(String quality) {
    if (RegExp(r'\d+p', caseSensitive: false).hasMatch(quality)) {
      return 'Фиксированная версия потока';
    }
    if (quality == 'Авто') return 'CDN меняет качество по скорости сети';
    return 'Высота не указана источником';
  }

  void _retryPlayer() {
    final source = _sources[_selectedQuality];
    if (source != null) _failedSources.remove(source);
    _failedSources.clear();
    setState(() => _initError = null);
    _initPlayer(source);
  }

  void _showControls() {
    _hideControlsTimer?.cancel();
    if (mounted) setState(() => _controlsVisible = true);
    _hideControlsTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && _videoController?.value.isPlaying == true) {
        setState(() => _controlsVisible = false);
      }
    });
  }

  void _syncWakeLock(bool enabled) {
    if (_wakeLockEnabled == enabled) return;
    _wakeLockEnabled = enabled;
    unawaited(
      WakelockPlus.toggle(enable: enabled).catchError((Object error) {
        debugPrint('[AniMix player] wakelock unavailable: $error');
      }),
    );
  }

  void _toggleControls() {
    if (_controlsVisible) {
      _hideControlsTimer?.cancel();
      setState(() => _controlsVisible = false);
    } else {
      _showControls();
    }
  }

  Future<void> _togglePause() async {
    final video = _videoController;
    if (video == null) return;
    if (video.value.isPlaying) {
      await video.pause();
    } else {
      if (video.value.position >= video.value.duration) {
        await video.seekTo(Duration.zero);
      }
      await video.play();
    }
    _showControls();
  }

  Future<void> _seekRelative(int seconds) async {
    final video = _videoController;
    if (video == null || !video.value.isInitialized) return;
    final next = video.value.position + Duration(seconds: seconds);
    await video.seekTo(
      next < Duration.zero
          ? Duration.zero
          : next > video.value.duration
          ? video.value.duration
          : next,
    );
    _showControls();
  }

  void _handleGestureSeek(int seconds) {
    _seekFeedbackTimer?.cancel();
    setState(() => _seekFeedbackSeconds = seconds);
    _seekFeedbackTimer = Timer(const Duration(milliseconds: 650), () {
      if (mounted) setState(() => _seekFeedbackSeconds = null);
    });
    unawaited(_seekRelative(seconds));
  }

  Future<void> _setFullScreen(bool value) async {
    if (_isFullScreen == value) return;
    setState(() => _isFullScreen = value);
    if (Platform.isIOS || Platform.isAndroid) {
      if (value) {
        await SystemChrome.setPreferredOrientations(const [
          DeviceOrientation.landscapeLeft,
          DeviceOrientation.landscapeRight,
        ]);
        await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
      } else {
        await _restoreSystemUi();
      }
    }
    _showControls();
  }

  Future<void> _restoreSystemUi() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    await SystemChrome.setPreferredOrientations(const []);
  }

  Future<void> _showPlaybackOptions() async {
    _hideControlsTimer?.cancel();
    if (!mounted) return;
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (sheetContext) => CupertinoActionSheet(
        title: const Text('Настройки воспроизведения'),
        actions: [
          CupertinoActionSheetAction(
            onPressed: () {
              Navigator.pop(sheetContext);
              _showSourceMenu();
            },
            child: Text('Качество · $_qualityBadge'),
          ),
          for (final speed in const [0.75, 1.0, 1.25, 1.5, 2.0])
            CupertinoActionSheetAction(
              onPressed: () async {
                Navigator.pop(sheetContext);
                try {
                  await _videoController?.setPlaybackSpeed(speed);
                  _playbackSpeed = speed;
                } catch (error) {
                  _showMessage('Не удалось изменить скорость: $error');
                }
                _showControls();
              },
              child: Text(
                'Скорость $speed×${speed == _playbackSpeed ? ' ✓' : ''}',
              ),
            ),
          CupertinoActionSheetAction(
            onPressed: () {
              Navigator.pop(sheetContext);
              _markOpeningBoundary();
            },
            child: Text(
              _manualOpeningStart == null
                  ? 'Отметить начало опенинга'
                  : 'Отметить конец опенинга',
            ),
          ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(sheetContext),
          child: const Text('Закрыть'),
        ),
      ),
    );
    _showControls();
  }

  Future<void> _markOpeningBoundary() async {
    final second = _videoController?.value.position.inSeconds;
    if (second == null) return;
    final start = _manualOpeningStart;
    if (start == null) {
      setState(() => _manualOpeningStart = second);
      _showMessage(
        'Начало опенинга: ${_formatTime(Duration(seconds: second))}. Перемотайте к концу и отметьте его в настройках.',
      );
      return;
    }
    if (second <= start + 4 || second > start + 600) {
      _showMessage('Конец должен быть позже начала на 5–600 секунд.');
      return;
    }
    await WatchStorage.saveOpeningTiming(
      widget.animeId,
      widget.episodeNumber,
      startSecond: start,
      endSecond: second,
    );
    if (!mounted) return;
    setState(() => _manualOpeningStart = null);
    _showMessage('Тайминг опенинга сохранён для этой серии.');
  }

  static String _formatTime(Duration value) {
    final seconds = value.inSeconds.clamp(0, 359999);
    final hours = seconds ~/ 3600;
    final minutes = (seconds % 3600) ~/ 60;
    final rest = seconds % 60;
    if (hours > 0) {
      return '$hours:${minutes.toString().padLeft(2, '0')}:${rest.toString().padLeft(2, '0')}';
    }
    return '$minutes:${rest.toString().padLeft(2, '0')}';
  }

  @override
  void dispose() {
    _flushPlaybackTime();
    _initializationGeneration++;
    _hideControlsTimer?.cancel();
    _seekFeedbackTimer?.cancel();
    if (_isFullScreen) {
      unawaited(_restoreSystemUi());
    }
    WatchStorage.openingTimingRevision.removeListener(_reloadOpeningTiming);
    _videoController?.removeListener(_onVideoProgress);
    _videoController?.dispose();
    _syncWakeLock(false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_isFullScreen,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _isFullScreen) unawaited(_setFullScreen(false));
      },
      child: CupertinoPageScaffold(
        backgroundColor: Colors.black,
        navigationBar: _isFullScreen
            ? null
            : CupertinoNavigationBar(
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
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .10),
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(color: Colors.white24),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              CupertinoIcons.slider_horizontal_3,
                              size: 15,
                              color: Colors.white,
                            ),
                            const SizedBox(width: 6),
                            Text(
                              _qualityBadge,
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
        child: SafeArea(
          top: !_isFullScreen,
          bottom: !_isFullScreen,
          child: Center(
            child: _isFullScreen
                ? SizedBox.expand(child: _buildPlayerSurface())
                : AspectRatio(
                    aspectRatio: 16 / 9,
                    child: _buildPlayerSurface(),
                  ),
          ),
        ),
      ),
    );
  }

  Widget _buildPlayerSurface() {
    final video = _videoController;
    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          Positioned.fill(
            child: ColoredBox(
              color: Colors.black,
              child: _initError != null
                  ? _buildError()
                  : video != null && !_isChangingQuality
                  ? Center(
                      child: AspectRatio(
                        aspectRatio: video.value.aspectRatio > 0
                            ? video.value.aspectRatio
                            : 16 / 9,
                        child: VideoPlayer(video),
                      ),
                    )
                  : _buildLoading(),
            ),
          ),
          if (video != null && _initError == null && !_isChangingQuality)
            Positioned.fill(
              child: PlayerGestureLayer(
                key: const Key('player_gesture_area'),
                onTap: _toggleControls,
                onSeekBackward: () => _handleGestureSeek(-10),
                onSeekForward: () => _handleGestureSeek(10),
                onTogglePlayback: () => unawaited(_togglePause()),
              ),
            ),
          if (video != null && _initError == null)
            Positioned.fill(
              child: IgnorePointer(
                ignoring: !_controlsVisible,
                child: AnimatedOpacity(
                  opacity: _controlsVisible ? 1 : 0,
                  duration: const Duration(milliseconds: 180),
                  curve: Curves.easeOut,
                  child: _buildControls(video),
                ),
              ),
            ),
          if (_seekFeedbackSeconds != null)
            Positioned.fill(
              child: IgnorePointer(
                child: Align(
                  alignment: _seekFeedbackSeconds! < 0
                      ? const Alignment(-0.62, 0)
                      : const Alignment(0.62, 0),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: .62),
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white24),
                    ),
                    child: SizedBox.square(
                      dimension: 82,
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            _seekFeedbackSeconds! < 0
                                ? Icons.replay_10_rounded
                                : Icons.forward_10_rounded,
                            color: Colors.white,
                            size: 30,
                          ),
                          const SizedBox(height: 2),
                          const Text(
                            '10 секунд',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 10,
                              fontWeight: FontWeight.w700,
                              decoration: TextDecoration.none,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          if (_activeSkipSegment != null)
            Positioned(
              right: 18,
              bottom: _controlsVisible ? 85 : 18,
              child: FilledButton.icon(
                key: const Key('skip_segment_button'),
                onPressed: _skipSegment,
                style: FilledButton.styleFrom(
                  foregroundColor: Colors.black,
                  backgroundColor: Colors.white,
                  elevation: 8,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 18,
                    vertical: 13,
                  ),
                  shape: const StadiumBorder(),
                ),
                icon: const Icon(Icons.skip_next_rounded, size: 20),
                label: Text(
                  _activeSkipSegment!.buttonLabel,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
              ),
            ),
          if (_nextCountdown != null)
            Positioned(
              left: 16,
              right: 16,
              bottom: _controlsVisible ? 80 : 18,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: .78),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: Colors.white24),
                ),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(14, 8, 8, 8),
                  child: Row(
                    children: [
                      const Icon(
                        CupertinoIcons.play_fill,
                        size: 15,
                        color: Colors.white,
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          _nextCountdown == 0
                              ? 'Открываем следующую серию…'
                              : 'Следующая серия${widget.nextEpisodeTitle == null ? '' : ': ${widget.nextEpisodeTitle}'} через $_nextCountdown с',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      CupertinoButton(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 9,
                          vertical: 5,
                        ),
                        onPressed: _cancelNextEpisode,
                        child: const Text('Отмена'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildControls(
    VideoPlayerController video,
  ) => ValueListenableBuilder<VideoPlayerValue>(
    valueListenable: video,
    builder: (context, value, _) {
      final length = value.duration.inMilliseconds / 1000;
      final current = value.position.inMilliseconds / 1000;
      final accent = AppSettingsController.instance.accentColor;
      return Stack(
        children: [
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xCC000000),
                    Color(0x18000000),
                    Color(0x18000000),
                    Color(0xE0000000),
                  ],
                  stops: [0, .28, .58, 1],
                ),
              ),
            ),
          ),
          Positioned(
            top: 8,
            left: 8,
            right: 8,
            child: Row(
              children: [
                if (_isFullScreen)
                  _playerIconButton(
                    tooltip: 'Выйти из полноэкранного режима',
                    icon: Icons.arrow_back_ios_new_rounded,
                    onPressed: () => unawaited(_setFullScreen(false)),
                  ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.episodeTitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.w800,
                          decoration: TextDecoration.none,
                          shadows: [Shadow(blurRadius: 8)],
                        ),
                      ),
                      if (widget.animeTitle?.isNotEmpty == true)
                        Text(
                          widget.animeTitle!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 11,
                            decoration: TextDecoration.none,
                          ),
                        ),
                    ],
                  ),
                ),
                _playerIconButton(
                  tooltip: 'Скачать',
                  icon: Icons.file_download_outlined,
                  onPressed: _downloadCurrentQuality,
                ),
                const SizedBox(width: 6),
                _playerIconButton(
                  key: const Key('player_settings_button'),
                  tooltip: 'Настройки воспроизведения',
                  icon: Icons.more_horiz_rounded,
                  onPressed: _showPlaybackOptions,
                ),
              ],
            ),
          ),
          Center(
            child: value.isBuffering
                ? CircularProgressIndicator(strokeWidth: 3, color: accent)
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _playerIconButton(
                        key: const Key('rewind_10_button'),
                        tooltip: 'Назад на 10 секунд',
                        icon: Icons.replay_10_rounded,
                        iconSize: 31,
                        size: 52,
                        onPressed: () => unawaited(_seekRelative(-10)),
                      ),
                      const SizedBox(width: 22),
                      _playerIconButton(
                        key: const Key('pause_play_button'),
                        tooltip: value.isPlaying ? 'Пауза' : 'Воспроизвести',
                        icon: value.isPlaying
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                        iconSize: 43,
                        size: 68,
                        background: Colors.white,
                        foreground: Colors.black,
                        onPressed: () => unawaited(_togglePause()),
                      ),
                      const SizedBox(width: 22),
                      _playerIconButton(
                        key: const Key('forward_10_button'),
                        tooltip: 'Вперёд на 10 секунд',
                        icon: Icons.forward_10_rounded,
                        iconSize: 31,
                        size: 52,
                        onPressed: () => unawaited(_seekRelative(10)),
                      ),
                    ],
                  ),
          ),
          Positioned(
            left: 12,
            right: 12,
            bottom: 6,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SliderTheme(
                  data: SliderThemeData(
                    activeTrackColor: accent,
                    inactiveTrackColor: Colors.white30,
                    thumbColor: accent,
                    overlayColor: accent.withValues(alpha: .18),
                    trackHeight: 3,
                    thumbShape: const RoundSliderThumbShape(
                      enabledThumbRadius: 6,
                    ),
                  ),
                  child: Slider(
                    key: const Key('player_progress_slider'),
                    min: 0,
                    max: length > 0 ? length : 1,
                    value: (_dragPositionSeconds ?? current).clamp(
                      0,
                      length > 0 ? length : 1,
                    ),
                    onChanged: (seconds) {
                      _hideControlsTimer?.cancel();
                      setState(() => _dragPositionSeconds = seconds);
                    },
                    onChangeEnd: (seconds) {
                      setState(() => _dragPositionSeconds = null);
                      unawaited(
                        video.seekTo(
                          Duration(milliseconds: (seconds * 1000).round()),
                        ),
                      );
                      _showControls();
                    },
                  ),
                ),
                Row(
                  children: [
                    Text(
                      '${_formatTime(Duration(seconds: (_dragPositionSeconds ?? current).round()))} / ${_formatTime(value.duration)}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        decoration: TextDecoration.none,
                      ),
                    ),
                    const Spacer(),
                    GestureDetector(
                      onTap: _showSourceMenu,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .14),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 9,
                            vertical: 5,
                          ),
                          child: Text(
                            _qualityBadge,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              decoration: TextDecoration.none,
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 7),
                    _playerIconButton(
                      key: const Key('fullscreen_button'),
                      tooltip: _isFullScreen ? 'Свернуть' : 'На весь экран',
                      icon: _isFullScreen
                          ? Icons.fullscreen_exit_rounded
                          : Icons.fullscreen_rounded,
                      size: 38,
                      iconSize: 25,
                      onPressed: () =>
                          unawaited(_setFullScreen(!_isFullScreen)),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      );
    },
  );

  Widget _playerIconButton({
    Key? key,
    required String tooltip,
    required IconData icon,
    required VoidCallback onPressed,
    double size = 42,
    double iconSize = 23,
    Color background = const Color(0x52000000),
    Color foreground = Colors.white,
  }) => Tooltip(
    message: tooltip,
    child: Material(
      key: key,
      color: background,
      shape: const CircleBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onPressed,
        child: SizedBox.square(
          dimension: size,
          child: Icon(icon, color: foreground, size: iconSize),
        ),
      ),
    ),
  );

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
