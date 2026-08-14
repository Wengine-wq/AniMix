import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../core/poster_fallback_service.dart';

class SmartAnimePoster extends StatefulWidget {
  final int animeId;
  final String? imageUrl;
  final String title;
  final String? russianTitle;
  final BoxFit fit;
  final Alignment alignment;

  const SmartAnimePoster({
    required this.animeId,
    required this.imageUrl,
    required this.title,
    this.russianTitle,
    this.fit = BoxFit.cover,
    this.alignment = Alignment.center,
    super.key,
  });

  @override
  State<SmartAnimePoster> createState() => _SmartAnimePosterState();
}

class _SmartAnimePosterState extends State<SmartAnimePoster> {
  String? _providerUrl;
  bool _requestedFallback = false;

  bool get _isKnownPlaceholder {
    final url = widget.imageUrl?.toLowerCase() ?? '';
    return url.isEmpty ||
        url.contains('/assets/globals/missing_') ||
        url.contains('missing_original') ||
        url.contains('missing_preview');
  }

  String? get _displayUrl {
    final cached = PosterFallbackService.instance.cached(widget.animeId);
    if (_providerUrl != null) return _providerUrl;
    if (cached != null) return cached;
    if (_isKnownPlaceholder) return null;
    final original = widget.imageUrl;
    if (original == null || original.isEmpty) return null;
    if (original.startsWith('http')) return original;
    return 'https://shikimori.io$original';
  }

  @override
  void initState() {
    super.initState();
    PosterFallbackService.instance.initialize().then((_) {
      if (mounted) setState(() {});
    });
    if (_isKnownPlaceholder) _requestFallback();
  }

  @override
  void didUpdateWidget(covariant SmartAnimePoster oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.animeId != widget.animeId ||
        oldWidget.imageUrl != widget.imageUrl) {
      _providerUrl = null;
      _requestedFallback = false;
      if (_isKnownPlaceholder) _requestFallback();
    }
  }

  Future<void> _requestFallback() async {
    if (_requestedFallback) return;
    _requestedFallback = true;
    final url = await PosterFallbackService.instance.resolve(
      shikimoriId: widget.animeId,
      title: widget.title,
      russianTitle: widget.russianTitle,
    );
    if (mounted && url != null) setState(() => _providerUrl = url);
  }

  @override
  Widget build(BuildContext context) {
    final url = _displayUrl;
    if (url == null) {
      _requestFallback();
      return _placeholder(showProgress: _requestedFallback);
    }
    return CachedNetworkImage(
      imageUrl: url,
      fit: widget.fit,
      alignment: widget.alignment,
      fadeInDuration: const Duration(milliseconds: 180),
      placeholder: (_, _) => _placeholder(showProgress: true),
      errorWidget: (_, _, _) {
        // Any provider image can expire or return a broken placeholder. Try
        // the cached Yummy/AniLiberty poster once instead of leaving a grey
        // block in lists such as Downloads.
        _requestFallback();
        return _placeholder(showProgress: false);
      },
    );
  }

  Widget _placeholder({required bool showProgress}) => DecoratedBox(
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
          : Icon(
              CupertinoIcons.photo_on_rectangle,
              color: Colors.white.withValues(alpha: 0.35),
              size: 28,
            ),
    ),
  );
}
