import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../core/media_cache.dart';
import '../core/poster_fallback_service.dart';

class SmartAnimePoster extends StatefulWidget {
  final int animeId;
  final String? imageUrl;
  final String title;
  final String? russianTitle;
  final BoxFit fit;
  final Alignment alignment;
  final ValueChanged<String>? onResolved;

  const SmartAnimePoster({
    required this.animeId,
    required this.imageUrl,
    required this.title,
    this.russianTitle,
    this.fit = BoxFit.cover,
    this.alignment = Alignment.center,
    this.onResolved,
    super.key,
  });

  @override
  State<SmartAnimePoster> createState() => _SmartAnimePosterState();
}

class _SmartAnimePosterState extends State<SmartAnimePoster> {
  String? _providerUrl;
  String? _failedUrl;
  bool _resolvingFallback = false;
  bool _fallbackExhausted = false;
  Timer? _networkDeadline;
  String? _reportedUrl;

  bool get _isKnownPlaceholder {
    final url = widget.imageUrl?.toLowerCase() ?? '';
    return url.isEmpty ||
        url.contains('/assets/globals/missing_') ||
        url.contains('missing_original') ||
        url.contains('missing_preview');
  }

  String? get _displayUrl {
    final cached = PosterFallbackService.instance.cached(widget.animeId);
    if (_providerUrl != null && _providerUrl != _failedUrl) return _providerUrl;
    if (cached != null && cached != _failedUrl) return cached;
    if (_isKnownPlaceholder) return null;
    final original = widget.imageUrl;
    if (original == null || original.isEmpty) return null;
    final absolute = original.startsWith('http')
        ? original
        : original.startsWith('//')
        ? 'https:$original'
        : 'https://shikimori.io$original';
    return absolute == _failedUrl ? null : absolute;
  }

  @override
  void initState() {
    super.initState();
    PosterFallbackService.instance.initialize().then((_) {
      if (mounted) setState(() {});
    });
    if (_isKnownPlaceholder) unawaited(_requestFallback());
  }

  @override
  void didUpdateWidget(covariant SmartAnimePoster oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.animeId != widget.animeId ||
        oldWidget.imageUrl != widget.imageUrl) {
      _providerUrl = null;
      _failedUrl = null;
      _fallbackExhausted = false;
      _reportedUrl = null;
      _networkDeadline?.cancel();
      if (_isKnownPlaceholder) unawaited(_requestFallback());
    }
  }

  @override
  void dispose() {
    _networkDeadline?.cancel();
    super.dispose();
  }

  Future<void> _requestFallback({bool retry = false}) async {
    if (_resolvingFallback || (_fallbackExhausted && !retry)) return;
    if (retry) {
      await PosterFallbackService.instance.invalidate(widget.animeId);
      _failedUrl = null;
    }
    if (!mounted) return;
    setState(() {
      _resolvingFallback = true;
      _fallbackExhausted = false;
    });
    final url = await PosterFallbackService.instance
        .resolve(
          shikimoriId: widget.animeId,
          title: widget.title,
          russianTitle: widget.russianTitle,
        )
        .timeout(const Duration(seconds: 12), onTimeout: () => null);
    if (!mounted) return;
    setState(() {
      _resolvingFallback = false;
      if (url != null && url != _failedUrl) {
        _providerUrl = url;
      } else {
        _fallbackExhausted = true;
      }
    });
  }

  void _armNetworkDeadline(String url) {
    if (_networkDeadline?.isActive == true) return;
    _networkDeadline = Timer(const Duration(seconds: 10), () {
      if (mounted) _handleImageFailure(url);
    });
  }

  void _handleImageFailure(String url) {
    if (!mounted || _failedUrl == url) return;
    _networkDeadline?.cancel();
    PosterFallbackService.instance.markFailed(widget.animeId, url);
    setState(() {
      _failedUrl = url;
      if (_providerUrl == url) _providerUrl = null;
    });
    unawaited(_requestFallback());
  }

  void _handleImageLoaded(String url) {
    _networkDeadline?.cancel();
    if (_reportedUrl == url) return;
    _reportedUrl = url;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onResolved?.call(url);
    });
  }

  @override
  Widget build(BuildContext context) {
    final url = _displayUrl;
    if (url == null) {
      if (!_resolvingFallback && !_fallbackExhausted) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) unawaited(_requestFallback());
        });
      }
      return _placeholder(
        showProgress: _resolvingFallback,
        onRetry: _fallbackExhausted
            ? () => unawaited(_requestFallback(retry: true))
            : null,
      );
    }
    return CachedNetworkImage(
      imageUrl: url,
      cacheManager: AniMixMediaCache.posters,
      fit: widget.fit,
      alignment: widget.alignment,
      fadeInDuration: const Duration(milliseconds: 180),
      imageBuilder: (_, provider) {
        _handleImageLoaded(url);
        return Image(
          image: provider,
          fit: widget.fit,
          alignment: widget.alignment,
        );
      },
      placeholder: (_, _) {
        _armNetworkDeadline(url);
        return _placeholder(showProgress: true);
      },
      errorWidget: (_, _, _) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _handleImageFailure(url);
        });
        return _placeholder(showProgress: _resolvingFallback);
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
              ? const CupertinoActivityIndicator(radius: 10)
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
