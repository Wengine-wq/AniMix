import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../core/config.dart';
import '../core/poster_fallback_service.dart';
import '../core/poster_loader.dart';
import 'image_decode_size.dart';

/// Anime poster with a stable, self-healing loading pipeline.
///
/// Candidate URLs are tried in a fixed order — the URL that worked last time,
/// the catalog image, its mirror domain, then the fallback resolver — and the
/// poster is only shown once a file is actually on disk. So it never flashes
/// to a different picture mid-load, and a failed poster retries by itself.
class SmartAnimePoster extends StatefulWidget {
  final int animeId;
  final String? imageUrl;
  final String title;
  final String? russianTitle;
  final BoxFit fit;
  final Alignment alignment;
  final ValueChanged<String>? onResolved;

  /// Forces a decode width in pixels (e.g. tiny for blurred backdrops).
  final int? decodeWidth;

  const SmartAnimePoster({
    required this.animeId,
    required this.imageUrl,
    required this.title,
    this.russianTitle,
    this.fit = BoxFit.cover,
    this.alignment = Alignment.center,
    this.onResolved,
    this.decodeWidth,
    super.key,
  });

  @override
  State<SmartAnimePoster> createState() => _SmartAnimePosterState();
}

class _SmartAnimePosterState extends State<SmartAnimePoster> {
  File? _file;
  bool _failed = false;
  int _generation = 0;
  int _autoRetries = 0;
  Timer? _retryTimer;

  bool get _isKnownPlaceholder {
    final url = widget.imageUrl?.toLowerCase() ?? '';
    return url.isEmpty ||
        url.contains('/assets/globals/missing_') ||
        url.contains('missing_original') ||
        url.contains('missing_preview');
  }

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void didUpdateWidget(covariant SmartAnimePoster oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.animeId != widget.animeId ||
        oldWidget.imageUrl != widget.imageUrl) {
      _autoRetries = 0;
      // Keep showing the previous file until the new one is ready.
      unawaited(_load());
    }
  }

  @override
  void dispose() {
    _generation++;
    _retryTimer?.cancel();
    super.dispose();
  }

  Future<void> _load({bool manual = false}) async {
    final generation = ++_generation;
    _retryTimer?.cancel();
    if (_failed && mounted) setState(() => _failed = false);
    final loader = PosterLoader.instance;
    final fallbacks = PosterFallbackService.instance;
    await fallbacks.initialize();

    final candidates = <String>[];
    void add(String? url) {
      if (url != null && url.isNotEmpty && !candidates.contains(url)) {
        candidates.add(url);
      }
    }

    if (!manual) add(await loader.rememberedUrl(widget.animeId));
    if (!_isKnownPlaceholder && widget.imageUrl != null) {
      final original = Config.proxiedImageUrl(widget.imageUrl!);
      add(original);
      add(Config.fallbackImageUrl(original));
    }
    add(fallbacks.cached(widget.animeId));

    for (final url in candidates) {
      if (!mounted || generation != _generation) return;
      final file = await loader.load(url);
      if (file != null) return _show(file, url, generation);
    }
    if (!mounted || generation != _generation) return;
    final resolved = await fallbacks
        .resolve(
          shikimoriId: widget.animeId,
          title: widget.title,
          russianTitle: widget.russianTitle,
        )
        .timeout(const Duration(seconds: 15), onTimeout: () => null);
    if (resolved != null && !candidates.contains(resolved)) {
      final file = await loader.load(resolved);
      if (file != null) return _show(file, resolved, generation);
    }
    if (!mounted || generation != _generation) return;
    setState(() => _failed = _file == null);
    // Networks recover; try again on our own a couple of times.
    if (_autoRetries < 2) {
      _autoRetries++;
      _retryTimer = Timer(
        Duration(seconds: 12 * _autoRetries),
        () => unawaited(_load()),
      );
    }
  }

  void _show(File file, String url, int generation) {
    if (!mounted || generation != _generation) return;
    PosterLoader.instance.remember(widget.animeId, url);
    setState(() {
      _file = file;
      _failed = false;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onResolved?.call(url);
    });
  }

  @override
  Widget build(BuildContext context) {
    final file = _file;
    if (file == null) {
      return _placeholder(
        showProgress: !_failed,
        onRetry: _failed
            ? () {
                _autoRetries = 0;
                unawaited(PosterLoader.instance.forget(widget.animeId));
                unawaited(_load(manual: true));
              }
            : null,
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final width =
            widget.decodeWidth ??
            decodeWidthFor(context, constraints, fit: widget.fit);
        final ImageProvider provider = width == null
            ? FileImage(file)
            : ResizeImage(FileImage(file), width: width);
        return Image(
          image: provider,
          fit: widget.fit,
          alignment: widget.alignment,
          gaplessPlayback: true,
          frameBuilder: (context, child, frame, wasSynchronous) =>
              wasSynchronous
              ? child
              : AnimatedOpacity(
                  opacity: frame == null ? 0 : 1,
                  duration: const Duration(milliseconds: 180),
                  child: child,
                ),
          errorBuilder: (_, _, _) => _placeholder(showProgress: false),
        );
      },
    );
  }

  Widget _placeholder({required bool showProgress, VoidCallback? onRetry}) =>
      DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0x334F46E5), Color(0x332A153E), Color(0xFF17171C)],
          ),
        ),
        child: Center(
          child: showProgress
              // Static while loading: no spinner per tile in a scrolling grid.
              ? const SizedBox.shrink()
              : IconButton(
                  tooltip: onRetry == null ? 'Обложка недоступна' : 'Повторить',
                  onPressed: onRetry,
                  icon: Icon(
                    onRetry == null
                        ? CupertinoIcons.photo_on_rectangle
                        : CupertinoIcons.arrow_clockwise,
                    color: Colors.white.withValues(alpha: 0.42),
                    size: 26,
                  ),
                ),
        ),
      );
}
