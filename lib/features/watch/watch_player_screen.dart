import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; // <-- Обязательно для работы Material-плеера на ПК
import 'package:video_player/video_player.dart';
import 'package:chewie/chewie.dart';

import 'watch_storage.dart';

class WatchPlayerScreen extends StatefulWidget {
  final int animeId;
  final int episodeNumber;
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
  
  // Умный выбор качества
  String _currentQuality = '1080p';
  bool _isChangingQuality = false;

  // Поддерживаем iOS (родной Chewie) + Windows (video_player_win)
  bool get _isSupportedPlatform => Platform.isIOS || Platform.isWindows;

  @override
  void initState() {
    super.initState();
    if (_isSupportedPlatform && widget.videoUrl != null && widget.videoUrl!.isNotEmpty) {
      // Пытаемся угадать исходное качество по URL
      if (widget.videoUrl!.contains('720')) _currentQuality = '720p';
      if (widget.videoUrl!.contains('480')) _currentQuality = '480p';
      
      _initializePlayer(widget.videoUrl!);
    } else {
      _initError = "Ссылка на видео отсутствует или не поддерживается на данной платформе.";
    }
  }

  Future<void> _initializePlayer(String url) async {
    try {
      setState(() => _initError = null);
      
      // Запоминаем время, чтобы при смене качества продолжить с того же места
      final oldPosition = _videoController?.value.position;

      final newController = VideoPlayerController.networkUrl(Uri.parse(url));
      await newController.initialize();

      // Восстанавливаем прогресс (либо после смены качества, либо из БД)
      if (oldPosition != null) {
        await newController.seekTo(oldPosition);
      } else {
        final savedPosition = await WatchStorage.getProgress(widget.animeId, widget.episodeNumber);
        if (savedPosition != null && savedPosition.inSeconds > 5) {
          await newController.seekTo(savedPosition);
        }
      }

      if (!mounted) return;

      setState(() {
        _videoController?.dispose();
        _videoController = newController;

        _chewieController?.dispose();
        _chewieController = ChewieController(
          videoPlayerController: _videoController!,
          autoPlay: true,
          looping: false,
          showControls: true,
          // Задаем аспект, чтобы UI не растягивался и не ломался на Windows
          aspectRatio: _videoController!.value.aspectRatio > 0 ? _videoController!.value.aspectRatio : 16 / 9,
          materialProgressColors: ChewieProgressColors(playedColor: const Color(0xFFFF5722)),
          cupertinoProgressColors: ChewieProgressColors(playedColor: const Color(0xFFFF5722)),
          placeholder: const Center(child: CupertinoActivityIndicator(radius: 20)),
          allowFullScreen: true,
          allowedScreenSleep: false,
          
          // 🔥 ОБРАБОТКА ОШИБОК В ПЛЕЕРЕ
          errorBuilder: (context, errorMessage) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(CupertinoIcons.exclamationmark_shield_fill, color: Colors.red, size: 42),
                    const SizedBox(height: 16),
                    const Text(
                      'Ошибка воспроизведения',
                      style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 18),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Возможно, ссылка устарела или произошла ошибка авторизации (401). Попробуйте перезайти в аккаунт Shikimori.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white70, fontSize: 14),
                    ),
                    const SizedBox(height: 20),
                    CupertinoButton(
                      color: const Color(0xFFFF5722),
                      onPressed: () => _initializePlayer(url),
                      child: const Text('Повторить'),
                    )
                  ],
                ),
              ),
            );
          },

          // 🔥 МЕНЮ ВЫБОРА КАЧЕСТВА И СКОРОСТИ
          additionalOptions: (chewieContext) {
            return [
              OptionItem(
                onTap: (menuContext) {
                  Navigator.of(menuContext).pop(); // Закрываем родное меню Chewie
                  Future.delayed(const Duration(milliseconds: 150), () {
                    if (mounted) _showQualityPicker();
                  });
                },
                iconData: Platform.isIOS ? CupertinoIcons.settings : Icons.high_quality,
                title: 'Качество: $_currentQuality',
              ),
            ];
          },
        );
        _isChangingQuality = false;
      });

      _saveTimer?.cancel();
      _saveTimer = Timer.periodic(const Duration(seconds: 8), (_) => _saveCurrentProgress());
      _videoController!.addListener(_onVideoEnd);
    } catch (e) {
      if (mounted) {
        setState(() => _initError = "Не удалось инициализировать плеер. Проверьте соединение или авторизацию.");
      }
    }
  }

  void _showQualityPicker() {
    showCupertinoModalPopup(
      context: context, // Используем безопасный context нашего экрана
      builder: (ctx) => CupertinoActionSheet(
        title: const Text('Выберите качество видео'),
        actions: ['1080p', '720p', '480p'].map((q) {
          return CupertinoActionSheetAction(
            onPressed: () {
              Navigator.pop(ctx);
              _changeQuality(q);
            },
            child: Text(
              q, 
              style: TextStyle(color: _currentQuality == q ? const Color(0xFFFF5722) : CupertinoColors.white)
            ),
          );
        }).toList(),
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Отмена'),
        ),
      ),
    );
  }

  void _changeQuality(String quality) {
    if (_currentQuality == quality || widget.videoUrl == null) return;
    
    setState(() => _isChangingQuality = true);
    _currentQuality = quality;

    // Редактируем ссылку налету (Anilibria использует пути .../1080.m3u8)
    String newUrl = widget.videoUrl!;
    if (quality == '1080p') newUrl = newUrl.replaceAll(RegExp(r'(720|480)'), '1080');
    if (quality == '720p') newUrl = newUrl.replaceAll(RegExp(r'(1080|480)'), '720');
    if (quality == '480p') newUrl = newUrl.replaceAll(RegExp(r'(1080|720)'), '480');

    _initializePlayer(newUrl);
  }

  void _saveCurrentProgress() {
    if (_videoController?.value.isInitialized == true) {
      WatchStorage.saveProgress(widget.animeId, widget.episodeNumber, _videoController!.value.position);
    }
  }

  void _onVideoEnd() {
    if (_videoController?.value.isInitialized == true &&
        _videoController!.value.position >= _videoController!.value.duration * 0.95 &&
        !_isWatched) {
      _isWatched = true;
      WatchStorage.markEpisodeWatched(widget.animeId, widget.episodeNumber);
    }
  }

  @override
  void dispose() {
    _saveTimer?.cancel();
    _videoController?.removeListener(_onVideoEnd);
    _chewieController?.dispose();
    _videoController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isSupportedPlatform) {
      return CupertinoPageScaffold(
        backgroundColor: Colors.black,
        navigationBar: CupertinoNavigationBar(
          middle: Text('Серия ${widget.episodeNumber}'),
          backgroundColor: Colors.black.withValues(alpha: 0.9),
        ),
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(CupertinoIcons.exclamationmark_triangle, size: 90, color: Color(0xFFFF9800)),
              const SizedBox(height: 30),
              const Text('Плеер работает только на iOS и Windows', 
                  style: TextStyle(fontSize: 24, color: Colors.white, fontWeight: FontWeight.w600)),
              const SizedBox(height: 50),
              CupertinoButton.filled(
                onPressed: () => Navigator.pop(context),
                child: const Text('Назад'),
              ),
            ],
          ),
        ),
      );
    }

    return PopScope(
      canPop: true,
      onPopInvokedWithResult: (didPop, result) {
        _saveCurrentProgress();
      },
      child: CupertinoPageScaffold(
        backgroundColor: Colors.black,
        navigationBar: CupertinoNavigationBar(
          middle: Text('Серия ${widget.episodeNumber} • ${widget.episodeTitle}', 
            style: const TextStyle(color: Colors.white, fontSize: 16),
            overflow: TextOverflow.ellipsis,
          ),
          backgroundColor: Colors.black.withValues(alpha: 0.9),
          leading: CupertinoButton(
            padding: EdgeInsets.zero,
            onPressed: () => Navigator.pop(context),
            child: const Icon(CupertinoIcons.back, color: CupertinoColors.white),
          ),
        ),
        child: SafeArea(
          child: Localizations(
            locale: const Locale('ru', 'RU'),
            delegates: const <LocalizationsDelegate<dynamic>>[
              DefaultMaterialLocalizations.delegate,
              DefaultCupertinoLocalizations.delegate,
              DefaultWidgetsLocalizations.delegate,
            ],
            child: Scaffold(
              backgroundColor: Colors.transparent,
              body: Theme(
                data: ThemeData.dark().copyWith(
                  primaryColor: const Color(0xFFFF5722),
                  colorScheme: const ColorScheme.dark(primary: Color(0xFFFF5722)),
                ),
                child: _initError != null 
                  ? _buildInitialError()
                  : _chewieController != null && _videoController?.value.isInitialized == true && !_isChangingQuality
                    ? Center(child: Chewie(controller: _chewieController!))
                    : const Center(child: CupertinoActivityIndicator(radius: 32)),
              ),
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
            Text(
              _initError!,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white, fontSize: 17),
            ),
            const SizedBox(height: 12),
            const Text(
              "Если вы видите ошибку 401, попробуйте выйти и зайти в аккаунт снова в разделе Профиль.",
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white54, fontSize: 14),
            ),
            const SizedBox(height: 32),
            CupertinoButton.filled(
              onPressed: () => Navigator.pop(context),
              child: const Text('Назад к эпизодам'),
            )
          ],
        ),
      ),
    );
  }
}