import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'animix_network_image.dart';

import '../core/app_logging.dart';
import '../core/config.dart';

Future<void> showAniMixMediaViewer(
  BuildContext context, {
  required List<String> images,
  int initialIndex = 0,
  Map<String, String> headers = const {},
  String title = 'Изображение',
}) {
  final validImages = images
      .where((url) => Uri.tryParse(url)?.hasScheme == true)
      .toList(growable: false);
  if (validImages.isEmpty) return Future.value();
  return Navigator.of(context).push<void>(
    PageRouteBuilder<void>(
      opaque: true,
      transitionDuration: const Duration(milliseconds: 220),
      reverseTransitionDuration: const Duration(milliseconds: 180),
      pageBuilder: (_, animation, _) => FadeTransition(
        opacity: animation,
        child: AniMixMediaViewer(
          images: validImages,
          initialIndex: initialIndex.clamp(0, validImages.length - 1),
          headers: headers,
          title: title,
        ),
      ),
    ),
  );
}

class AniMixMediaViewer extends StatefulWidget {
  const AniMixMediaViewer({
    required this.images,
    this.initialIndex = 0,
    this.headers = const {},
    this.title = 'Изображение',
    super.key,
  });

  final List<String> images;
  final int initialIndex;
  final Map<String, String> headers;
  final String title;

  @override
  State<AniMixMediaViewer> createState() => _AniMixMediaViewerState();
}

class _AniMixMediaViewerState extends State<AniMixMediaViewer> {
  late final PageController _pageController;
  late int _index;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _pageController = PageController(initialPage: _index);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      final file = await AniMixMediaSaver.save(
        widget.images[_index],
        headers: widget.headers,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            Platform.isIOS
                ? 'Сохранено в «Файлы → AniMix»: ${file.uri.pathSegments.last}'
                : 'Сохранено: ${file.path}',
          ),
        ),
      );
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Media viewer',
        context: 'Не удалось сохранить ${widget.images[_index]}',
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось сохранить изображение')),
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Colors.black,
    appBar: AppBar(
      backgroundColor: Colors.black.withValues(alpha: .88),
      foregroundColor: Colors.white,
      leading: IconButton(
        tooltip: 'Закрыть',
        onPressed: () => Navigator.pop(context),
        icon: const Icon(CupertinoIcons.xmark),
      ),
      title: Text(
        widget.images.length == 1
            ? widget.title
            : '${_index + 1} / ${widget.images.length}',
      ),
      actions: [
        IconButton(
          key: const ValueKey('media_viewer_save'),
          tooltip: Platform.isIOS ? 'Сохранить в Файлы' : 'Скачать',
          onPressed: _saving ? null : _save,
          icon: _saving
              ? const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : const Icon(CupertinoIcons.arrow_down_to_line),
        ),
        const SizedBox(width: 6),
      ],
    ),
    body: PageView.builder(
      controller: _pageController,
      itemCount: widget.images.length,
      onPageChanged: (value) => setState(() => _index = value),
      itemBuilder: (context, index) => _ZoomableMedia(
        imageUrl: widget.images[index],
        headers: widget.headers,
      ),
    ),
  );
}

class _ZoomableMedia extends StatefulWidget {
  const _ZoomableMedia({required this.imageUrl, required this.headers});

  final String imageUrl;
  final Map<String, String> headers;

  @override
  State<_ZoomableMedia> createState() => _ZoomableMediaState();
}

class _ZoomableMediaState extends State<_ZoomableMedia> {
  final TransformationController _transform = TransformationController();
  TapDownDetails? _doubleTapDetails;

  @override
  void dispose() {
    _transform.dispose();
    super.dispose();
  }

  void _toggleZoom() {
    if (_transform.value != Matrix4.identity()) {
      _transform.value = Matrix4.identity();
      return;
    }
    final position = _doubleTapDetails?.localPosition ?? Offset.zero;
    _transform.value = Matrix4.identity()
      ..translateByDouble(-position.dx * 1.4, -position.dy * 1.4, 0, 1)
      ..scaleByDouble(2.4, 2.4, 1, 1);
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
    onDoubleTapDown: (details) => _doubleTapDetails = details,
    onDoubleTap: _toggleZoom,
    child: InteractiveViewer(
      transformationController: _transform,
      minScale: .75,
      maxScale: 6,
      clipBehavior: Clip.none,
      child: Center(
        child: AniMixNetworkImage(
          imageUrl: widget.imageUrl,
          httpHeaders: widget.headers,
          fit: BoxFit.contain,
          fadeInDuration: const Duration(milliseconds: 120),
          placeholder: (_, _) =>
              const CupertinoActivityIndicator(radius: 15, color: Colors.white),
          errorWidget: (_, _, _) => const Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                CupertinoIcons.exclamationmark_triangle,
                color: Colors.white,
              ),
              SizedBox(height: 10),
              Text(
                'Изображение не загрузилось',
                style: TextStyle(color: Colors.white70),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

abstract final class AniMixMediaSaver {
  static Future<File> save(
    String url, {
    Map<String, String> headers = const {},
  }) async {
    final uri = Uri.parse(url);
    final root = Platform.isWindows
        ? (await getDownloadsDirectory() ??
              await getApplicationDocumentsDirectory())
        : await getApplicationDocumentsDirectory();
    final directory = Platform.isWindows
        ? root
        : Directory('${root.path}${Platform.pathSeparator}Saved Images');
    if (!await directory.exists()) await directory.create(recursive: true);

    final sourceName = uri.pathSegments.isEmpty
        ? 'image'
        : uri.pathSegments.last;
    final rawExtension = RegExp(
      r'\.(png|jpe?g|gif|webp)$',
      caseSensitive: false,
    ).firstMatch(sourceName)?.group(0);
    final extension = rawExtension?.toLowerCase() ?? '.jpg';
    final stem = sourceName
        .replaceFirst(RegExp(r'\.[^.]+$'), '')
        .replaceAll(RegExp(r'[^a-zA-Z0-9_-]+'), '_')
        .replaceAll(RegExp(r'^_+|_+$'), '');
    final safeStem = stem.isEmpty ? 'animix_image' : stem;
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final file = File(
      '${directory.path}${Platform.pathSeparator}${safeStem}_$timestamp$extension',
    );
    final dio = Dio();
    Future<void> download(String source) => dio.download(
      source,
      file.path,
      options: Options(
        headers: headers,
        receiveTimeout: const Duration(minutes: 2),
      ),
    );
    try {
      await download(uri.toString());
    } catch (_) {
      final fallback = Config.fallbackImageUrl(uri.toString());
      if (fallback == uri.toString()) rethrow;
      await download(fallback);
    }
    return file;
  }
}
