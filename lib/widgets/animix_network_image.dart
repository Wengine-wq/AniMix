import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart'
    show BaseCacheManager;

import '../core/config.dart';
import 'image_decode_size.dart';

/// Loads media directly and retries through Shikimori's alternate public
/// domain. Catalog images never spend an AniMix/Yandex API invocation.
class AniMixNetworkImage extends StatefulWidget {
  const AniMixNetworkImage({
    required this.imageUrl,
    this.cacheManager,
    this.httpHeaders,
    this.fit,
    this.width,
    this.height,
    this.fadeInDuration = const Duration(milliseconds: 160),
    this.placeholder,
    this.errorWidget,
    this.resizeToLayout = true,
    super.key,
  });

  /// Decode at the drawn size. Turn off for zoomable full-screen viewers.
  final bool resizeToLayout;

  final String imageUrl;
  final BaseCacheManager? cacheManager;
  final Map<String, String>? httpHeaders;
  final BoxFit? fit;
  final double? width;
  final double? height;
  final Duration fadeInDuration;
  final PlaceholderWidgetBuilder? placeholder;
  final LoadingErrorWidgetBuilder? errorWidget;

  @override
  State<AniMixNetworkImage> createState() => _AniMixNetworkImageState();
}

class _AniMixNetworkImageState extends State<AniMixNetworkImage> {
  late String _url;
  bool _fallbackAttempted = false;

  @override
  void initState() {
    super.initState();
    _url = widget.imageUrl;
  }

  @override
  void didUpdateWidget(covariant AniMixNetworkImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.imageUrl != widget.imageUrl) {
      _url = widget.imageUrl;
      _fallbackAttempted = false;
    }
  }

  void _tryFallback() {
    if (!mounted || _fallbackAttempted) return;
    final fallback = Config.fallbackImageUrl(widget.imageUrl);
    if (fallback == _url) return;
    setState(() {
      _fallbackAttempted = true;
      _url = fallback;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.resizeToLayout) return _image(null);
    if (widget.width != null) {
      return _image(
        decodeWidthFor(
          context,
          const BoxConstraints(),
          width: widget.width,
          height: widget.height,
          fit: widget.fit,
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) => _image(
        decodeWidthFor(
          context,
          constraints,
          height: widget.height,
          fit: widget.fit,
        ),
      ),
    );
  }

  Widget _image(int? decodeWidth) => CachedNetworkImage(
    imageUrl: _url,
    cacheManager: widget.cacheManager,
    httpHeaders: widget.httpHeaders,
    fit: widget.fit,
    width: widget.width,
    height: widget.height,
    memCacheWidth: decodeWidth,
    fadeInDuration: widget.fadeInDuration,
    placeholder: widget.placeholder,
    errorWidget: (context, url, error) {
      final fallback = Config.fallbackImageUrl(widget.imageUrl);
      if (!_fallbackAttempted && fallback != _url) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _tryFallback());
        return widget.placeholder?.call(context, url) ??
            const SizedBox.shrink();
      }
      return widget.errorWidget?.call(context, url, error) ??
          const SizedBox.shrink();
    },
  );
}
