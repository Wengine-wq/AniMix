import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'package:chewie/chewie.dart';

import 'watch_storage.dart';

class WatchPlayerScreen extends StatefulWidget {
  final int animeId;
  final String episodeNumber; // 🔥 ТЕПЕРЬ СТРОГО СТРОКА
  final String? videoUrl;
  final String episodeTitle;

  const WatchPlayerScreen({
    required this.animeId,
    required this.episodeNumber,
    required this.videoUrl,
    required this.episodeTitle,
    super.key,
  });

  @override
  State<WatchPlayerScreen> createState() => _WatchPlayerScreenState();
}

class _WatchPlayerScreenState extends State<WatchPlayerScreen> {
  VideoPlayerController? _videoController;
  ChewieController? _chewieController;
  Timer? _saveTimer;
  bool _isWatched = false;
  String? _initError;
  
  int _lastSaveTime = 0; // 🔥 ПЕРЕМЕННАЯ ДЛЯ ТОЧНОГО ОТСЛЕЖИВАНИЯ

  // Умный выбор качества
  String _currentQuality = '1080p';
  bool _isChangingQuality = false;

  // Поддерживаемые платформы для нативного плеера
  bool get _isSupportedPlatform => Platform.isIOS || Platform.isAndroid || Platform.isWindows || Platform.isMacOS;

  @override
  void initState() {
    super.initState();
    if (_isSupportedPlatform) {
      _initPlayer();
    } else {
      _initError = "Воспроизведение видео пока не поддерживается на этой платформе.";
    }
  }

  Future<void> _initPlayer() async {
    if (widget.videoUrl == null) {
      setState(() => _initError = 'Видео недоступно.');
      return;
    }

    try {
      _videoController = VideoPlayerController.networkUrl(Uri.parse(widget.videoUrl!));
      await _videoController!.initialize();

      // 🔥 ВОССТАНОВЛЕНИЕ ПРОГРЕССА ИЗ ПРАВИЛЬНОГО ХРАНИЛИЩА
      final startPosition = await WatchStorage.getProgress(widget.animeId, widget.episodeNumber);
      if (startPosition != null && startPosition < _videoController!.value.duration) {
        await _videoController!.seekTo(startPosition);
      }

      _videoController!.addListener(_onVideoProgress);

      _chewieController = ChewieController(
        videoPlayerController: _videoController!,
        autoPlay: true,
        looping: false,
        aspectRatio: 16 / 9,
        allowFullScreen: true,
        fullScreenByDefault: false,
        errorBuilder: (context, errorMessage) {
          return Center(
            child: Text(
              errorMessage,
              style: const TextStyle(color: Colors.white),
            ),
          );
        },
      );

      if (mounted) setState(() {});
    } catch (e) {
      setState(() => _initError = 'Не удалось загрузить видео. Ошибка 401 (Токен устарел) или сервер недоступен.');
    }
  }

  void _onVideoProgress() {
    if (_videoController == null || !_videoController!.value.isInitialized) return;
    
    final pos = _videoController!.value.position;
    final dur = _videoController!.value.duration;
    if (dur.inSeconds == 0) return;

    // 🔥 НАДЕЖНОЕ СОХРАНЕНИЕ КАЖДЫЕ 5 СЕКУНД (забудем про багованный %)
    if (pos.inSeconds > 0 && (pos.inSeconds - _lastSaveTime).abs() >= 5) {
      _lastSaveTime = pos.inSeconds;
      WatchStorage.saveProgress(widget.animeId, widget.episodeNumber, pos);
    }

    // Проверка на просмотренность (85%)
    if (!_isWatched && pos.inSeconds > 0 && dur.inSeconds > 0) {
      if ((pos.inSeconds / dur.inSeconds) > 0.85) {
        _isWatched = true;
        WatchStorage.markEpisodeWatched(widget.animeId, widget.episodeNumber);
      }
    }
  }

  @override
  void dispose() {
    _videoController?.removeListener(_onVideoProgress);
    _videoController?.dispose();
    _chewieController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Colors.black.withOpacity(0.8),
        middle: Text(widget.episodeTitle, style: const TextStyle(color: Colors.white)),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Icon(CupertinoIcons.back, color: Colors.white),
        ),
      ),
      child: SafeArea(
        child: Center(
          child: AspectRatio(
            aspectRatio: 16 / 9,
            child: Container(
              color: Colors.black,
              child: _initError != null 
                  ? _buildInitialError()
                  : _chewieController != null && _videoController?.value.isInitialized == true && !_isChangingQuality
                    ? Center(child: Chewie(controller: _chewieController!))
                    : const Center(child: CupertinoActivityIndicator(radius: 32)),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInitialError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(CupertinoIcons.xmark_octagon_fill, color: Colors.red, size: 64),
            const SizedBox(height: 24),
            Text(_initError!, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 17)),
            const SizedBox(height: 32),
            CupertinoButton.filled(onPressed: () => Navigator.pop(context), child: const Text('Назад к эпизодам')),
          ],
        ),
      ),
    );
  }
}