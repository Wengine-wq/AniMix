import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'download_item.dart';
import 'offline_media_server.dart';
import '../../core/config.dart';

class HlsDownloadManager extends ChangeNotifier {
  HlsDownloadManager._();

  static final HlsDownloadManager instance = HlsDownloadManager._();
  static const _storageKey = 'animix_hls_downloads_v1';

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(minutes: 2),
      headers: Config.providerMediaHeaders,
    ),
  );

  final List<DownloadItem> _downloads = [];
  final Map<String, CancelToken> _cancelTokens = {};
  Future<void>? _initializing;

  List<DownloadItem> get downloads => List.unmodifiable(_downloads);

  Future<void> initialize() => _initializing ??= _load();

  DownloadItem? itemFor(String episodeId) {
    for (final item in _downloads) {
      if (item.episodeId == episodeId) return item;
    }
    return null;
  }

  Future<void> startDownload({
    required String url,
    required String episodeId,
    required String animeTitle,
    required String episodeName,
    required String quality,
    String? posterUrl,
  }) async {
    await initialize();
    if (itemFor(episodeId)?.state == DownloadState.downloading) return;

    final item = DownloadItem(
      episodeId: episodeId,
      animeTitle: animeTitle,
      episodeName: episodeName,
      posterUrl: posterUrl,
      quality: quality,
      sourceUrl: url,
      progress: 0,
      state: DownloadState.downloading,
    );
    _replace(item);
    await _save();

    final token = CancelToken();
    _cancelTokens[episodeId] = token;
    try {
      final root = await getApplicationSupportDirectory();
      final folder = Directory(
        '${root.path}${Platform.pathSeparator}animix_downloads${Platform.pathSeparator}${_safeName(episodeId)}',
      );
      if (await folder.exists()) await folder.delete(recursive: true);
      await folder.create(recursive: true);

      final uri = Uri.parse(url.startsWith('//') ? 'https:$url' : url);
      final String localPath;
      if (uri.toString().toLowerCase().contains('.m3u8')) {
        localPath = await _downloadHls(uri, folder, item, token);
      } else {
        localPath = '${folder.path}${Platform.pathSeparator}video.mp4';
        await _dio.downloadUri(
          uri,
          localPath,
          cancelToken: token,
          onReceiveProgress: (received, total) {
            if (total > 0) _updateProgress(episodeId, received / total);
          },
        );
      }

      final size = await _directorySize(folder);
      _replace(
        item.copyWith(
          progress: 1,
          state: DownloadState.completed,
          localPath: localPath,
          fileSizeBytes: size,
        ),
      );
    } catch (error) {
      final wasCancelled = error is DioException && CancelToken.isCancel(error);
      if (!wasCancelled) {
        _replace(
          item.copyWith(
            state: DownloadState.failed,
            error: 'Не удалось скачать: $error',
          ),
        );
      }
    } finally {
      _cancelTokens.remove(episodeId);
      await _save();
      notifyListeners();
    }
  }

  Future<String> _downloadHls(
    Uri initialUri,
    Directory folder,
    DownloadItem item,
    CancelToken token,
  ) async {
    var playlistUri = initialUri;
    var playlist = await _loadText(playlistUri, token);
    if (!playlist.contains('#EXTM3U')) {
      throw const FormatException('Источник не вернул HLS-плейлист');
    }

    if (playlist.contains('#EXT-X-STREAM-INF')) {
      final variant = _pickBestVariant(playlist, playlistUri);
      if (variant != null) {
        playlistUri = variant;
        playlist = await _loadText(playlistUri, token);
      }
    }

    final lines = playlist.split(RegExp(r'\r?\n'));
    final resourceUris = <Uri>[];
    final rewritten = <String>[];
    var resourceIndex = 0;

    for (final originalLine in lines) {
      var line = originalLine;
      if (line.startsWith('#EXT-X-KEY:') || line.startsWith('#EXT-X-MAP:')) {
        final uriMatch = RegExp(r'URI="([^"]+)"').firstMatch(line);
        if (uriMatch != null) {
          final remote = playlistUri.resolve(uriMatch.group(1)!);
          final name = _resourceName(resourceIndex++, remote);
          resourceUris.add(remote);
          line = line.replaceFirst(uriMatch.group(1)!, name);
        }
      } else if (line.trim().isNotEmpty && !line.startsWith('#')) {
        final remote = playlistUri.resolve(line.trim());
        final name = _resourceName(resourceIndex++, remote);
        resourceUris.add(remote);
        line = name;
      }
      rewritten.add(line);
    }

    final manifest = File(
      '${folder.path}${Platform.pathSeparator}offline.m3u8',
    );
    await manifest.writeAsString(rewritten.join('\n'), flush: true);

    if (resourceUris.isEmpty) {
      throw const FormatException('HLS-плейлист не содержит медиасегментов');
    }

    var nextIndex = 0;
    var completed = 0;
    Future<void> worker() async {
      while (true) {
        final index = nextIndex++;
        if (index >= resourceUris.length) return;
        final remote = resourceUris[index];
        final local =
            '${folder.path}${Platform.pathSeparator}${_resourceName(index, remote)}';
        await _dio.downloadUri(remote, local, cancelToken: token);
        completed++;
        _updateProgress(item.episodeId, completed / resourceUris.length);
      }
    }

    final workerCount = resourceUris.length.clamp(1, 4);
    await Future.wait(
      List<Future<void>>.generate(workerCount, (_) => worker()),
    );
    return manifest.path;
  }

  Uri? _pickBestVariant(String master, Uri baseUri) {
    final lines = master.split(RegExp(r'\r?\n'));
    Uri? selected;
    var bestBandwidth = -1;
    for (var index = 0; index < lines.length; index++) {
      final line = lines[index].trim();
      if (!line.startsWith('#EXT-X-STREAM-INF:')) continue;
      final bandwidth =
          int.tryParse(
            RegExp(r'BANDWIDTH=(\d+)').firstMatch(line)?.group(1) ?? '',
          ) ??
          0;
      var next = index + 1;
      while (next < lines.length &&
          (lines[next].trim().isEmpty || lines[next].startsWith('#'))) {
        next++;
      }
      if (next < lines.length && bandwidth > bestBandwidth) {
        bestBandwidth = bandwidth;
        selected = baseUri.resolve(lines[next].trim());
      }
    }
    return selected;
  }

  Future<String> _loadText(Uri uri, CancelToken token) async {
    final response = await _dio.getUri<String>(
      uri,
      cancelToken: token,
      options: Options(responseType: ResponseType.plain),
    );
    return response.data ?? '';
  }

  Future<void> delete(String episodeId) async {
    await initialize();
    _cancelTokens.remove(episodeId)?.cancel('Удалено пользователем');
    final item = itemFor(episodeId);
    if (item?.localPath != null) {
      final parent = File(item!.localPath!).parent;
      if (await parent.exists()) await parent.delete(recursive: true);
    } else {
      final root = await getApplicationSupportDirectory();
      final partial = Directory(
        '${root.path}${Platform.pathSeparator}animix_downloads${Platform.pathSeparator}${_safeName(episodeId)}',
      );
      if (await partial.exists()) await partial.delete(recursive: true);
    }
    _downloads.removeWhere((entry) => entry.episodeId == episodeId);
    await _save();
    notifyListeners();
  }

  Future<Uri?> playbackUriFor(String episodeId) async {
    final item = itemFor(episodeId);
    if (item?.state != DownloadState.completed || item?.localPath == null) {
      return null;
    }
    final file = File(item!.localPath!);
    if (!file.existsSync()) return null;
    if (file.path.toLowerCase().endsWith('.m3u8')) {
      return OfflineMediaServer.instance.serve(episodeId, file);
    }
    return file.uri;
  }

  void _updateProgress(String episodeId, double progress) {
    final current = itemFor(episodeId);
    if (current == null) return;
    _replace(current.copyWith(progress: progress.clamp(0, 1)));
  }

  void _replace(DownloadItem item) {
    final index = _downloads.indexWhere(
      (entry) => entry.episodeId == item.episodeId,
    );
    if (index == -1) {
      _downloads.add(item);
    } else {
      _downloads[index] = item;
    }
    notifyListeners();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_storageKey);
    if (raw == null) return;
    try {
      final values = jsonDecode(raw) as List<dynamic>;
      _downloads
        ..clear()
        ..addAll(
          values.map(
            (entry) =>
                DownloadItem.fromJson(Map<String, dynamic>.from(entry as Map)),
          ),
        );
      // Background work cannot survive every Flutter platform. Stale tasks are
      // made retryable instead of being shown forever as active.
      for (var index = 0; index < _downloads.length; index++) {
        if (_downloads[index].state == DownloadState.downloading) {
          _downloads[index] = _downloads[index].copyWith(
            state: DownloadState.failed,
            error: 'Загрузка была прервана. Нажмите повторить.',
          );
        }
      }
      notifyListeners();
    } catch (_) {
      // Ignore incompatible metadata from early development builds.
    }
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _storageKey,
      jsonEncode(_downloads.map((item) => item.toJson()).toList()),
    );
  }

  static String _resourceName(int index, Uri uri) {
    final extension = uri.pathSegments.isEmpty
        ? '.bin'
        : '.${uri.pathSegments.last.split('.').last.split('?').first}';
    final safeExtension = RegExp(r'^\.[a-zA-Z0-9]{1,5}$').hasMatch(extension)
        ? extension
        : '.bin';
    return 'resource_${index.toString().padLeft(5, '0')}$safeExtension';
  }

  static String _safeName(String value) =>
      value.replaceAll(RegExp(r'[^a-zA-Z0-9_-]'), '_');

  static Future<int> _directorySize(Directory directory) async {
    var size = 0;
    await for (final entity in directory.list(
      recursive: true,
      followLinks: false,
    )) {
      if (entity is File) size += await entity.length();
    }
    return size;
  }
}
