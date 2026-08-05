import 'package:dio/dio.dart';

import '../../../core/config.dart';
import '../models/video_source.dart';

/// Converts a stream expression captured from a provider WebView into verified
/// native-player sources. Supports adaptive HLS, a single MP4 and the
/// PlayerJS `[1080p]url,[720p]url` format used by Kodik/Alloha.
class HlsPlaylistService {
  HlsPlaylistService({Dio? dio}) : _dio = dio ?? Dio(_options);

  static final BaseOptions _options = BaseOptions(
    connectTimeout: const Duration(seconds: 12),
    receiveTimeout: const Duration(seconds: 15),
    responseType: ResponseType.plain,
    headers: Config.providerMediaHeaders,
    validateStatus: (status) => status != null && status < 500,
  );

  final Dio _dio;

  Future<Map<String, String>> resolveQualities(String capturedValue) async {
    final expressionSources = parsePlayerJsExpression(capturedValue);
    if (expressionSources.length > 1) return _sorted(expressionSources);

    final value = expressionSources.isEmpty
        ? normalizeCapturedUrl(capturedValue)
        : expressionSources.values.first;
    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme) return const {};

    if (!_isHls(uri)) {
      if (uri.path.toLowerCase().endsWith('.mp4')) {
        final verified = await _verifySiblingMp4Qualities(uri);
        if (verified.isNotEmpty) return _sorted(verified);
      }
      return {_qualityFromText(value): value};
    }

    // Kodik frequently returns a media playlist for the lowest quality rather
    // than an adaptive master. Its CDN keeps sibling manifests at the same
    // signed path, so discover and verify them before parsing the playlist.
    if (deriveSiblingHlsUrls(uri).isNotEmpty) {
      final siblings = await _verifySiblingHlsQualities(uri);
      if (siblings.length > 1) return _sorted(siblings);
    }

    try {
      final response = await _dio.getUri<String>(uri);
      final body = response.data ?? '';
      if (!body.contains('#EXTM3U')) return {'Авто': value};
      final variants = parseMasterPlaylist(body, uri);
      if (variants.isEmpty) {
        final siblings = await _verifySiblingHlsQualities(uri);
        return siblings.isEmpty ? {'Авто': value} : _sorted(siblings);
      }

      return _sorted({
        'Авто': value,
        for (final source in variants) source.label: source.uri.toString(),
      });
    } catch (_) {
      // Signed CDNs sometimes reject probing while accepting native playback.
      return {'Авто': value};
    }
  }

  /// Parses values such as `[1080p]https://...,[720p]https://...` and also
  /// accepts a plain URL. PlayerJS sometimes escapes slashes as `\/`.
  Map<String, String> parsePlayerJsExpression(String input) {
    final cleaned = input
        .trim()
        .replaceAll(r'\/', '/')
        .replaceAll('&amp;', '&')
        .replaceAll(RegExp(r'''^["']|["']$'''), '');
    final result = <String, String>{};
    final tagged = RegExp(
      r'\[([^\]]+)\]\s*((?:https?:)?//[^,\s]+)',
      caseSensitive: false,
    );
    for (final match in tagged.allMatches(cleaned)) {
      final url = normalizeCapturedUrl(match.group(2)!);
      if (_isMediaUrl(url)) result[_normalizeQuality(match.group(1)!)] = url;
    }
    if (result.isNotEmpty) return result;

    final plain = normalizeCapturedUrl(cleaned);
    if (_isMediaUrl(plain)) result[_qualityFromText(plain)] = plain;
    return result;
  }

  List<VideoSource> parseMasterPlaylist(String body, Uri baseUri) {
    final lines = body.split(RegExp(r'\r?\n'));
    final byLabel = <String, VideoSource>{};

    for (var index = 0; index < lines.length; index++) {
      final line = lines[index].trim();
      if (!line.startsWith('#EXT-X-STREAM-INF:')) continue;

      var uriLineIndex = index + 1;
      while (uriLineIndex < lines.length &&
          (lines[uriLineIndex].trim().isEmpty ||
              lines[uriLineIndex].trim().startsWith('#'))) {
        uriLineIndex++;
      }
      if (uriLineIndex >= lines.length) continue;

      final attributes = _parseAttributes(
        line.substring(line.indexOf(':') + 1),
      );
      final bandwidth = int.tryParse(attributes['BANDWIDTH'] ?? '') ?? 0;
      final label = _qualityLabel(attributes['RESOLUTION'], bandwidth);
      final candidate = VideoSource(
        label: label,
        uri: baseUri.resolve(lines[uriLineIndex].trim()),
        bandwidth: bandwidth,
      );
      final existing = byLabel[label];
      if (existing == null || candidate.bandwidth > existing.bandwidth) {
        byLabel[label] = candidate;
      }
    }

    return byLabel.values.toList()
      ..sort((a, b) => _qualityRank(b.label).compareTo(_qualityRank(a.label)));
  }

  Future<Map<String, String>> _verifySiblingMp4Qualities(Uri source) async {
    final sourceText = source.toString();
    final resolutionMatch = RegExp(
      r'(?<=/)(1080|720|480|360)(?=\.mp4)',
    ).firstMatch(sourceText);
    if (resolutionMatch == null) return {};

    final found = <String, String>{};
    await Future.wait(
      [1080, 720, 480, 360].map((height) async {
        final candidate = sourceText.replaceFirst(
          resolutionMatch.group(0)!,
          '$height',
        );
        try {
          final response = await _dio.headUri<void>(Uri.parse(candidate));
          if ((response.statusCode ?? 500) < 400) {
            found['${height}p'] = candidate;
          }
        } catch (_) {
          // A missing sibling quality is expected.
        }
      }),
    );
    return found;
  }

  Future<Map<String, String>> _verifySiblingHlsQualities(Uri source) async {
    final found = <String, String>{};
    await Future.wait(
      deriveSiblingHlsUrls(source).entries.map((entry) async {
        try {
          final response = await _dio.headUri<void>(Uri.parse(entry.value));
          if ((response.statusCode ?? 500) < 400) {
            found[entry.key] = entry.value;
          }
        } catch (_) {
          // Missing resolutions are normal for older releases.
        }
      }),
    );
    return found;
  }

  static Map<String, String> deriveSiblingHlsUrls(Uri source) {
    final sourceText = source.toString();
    final resolutionMatch = RegExp(
      r'/(1080|720|480|360)(?=\.mp4:hls:)',
    ).firstMatch(sourceText);
    if (resolutionMatch == null) return {};
    final current = resolutionMatch.group(1)!;
    return {
      for (final height in [1080, 720, 480, 360])
        '${height}p': sourceText.replaceFirst(
          '/$current.mp4:hls:',
          '/$height.mp4:hls:',
        ),
    };
  }

  static Map<String, String> _parseAttributes(String input) {
    final result = <String, String>{};
    final expression = RegExp(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)');
    for (final match in expression.allMatches(input)) {
      result[match.group(1)!] = match.group(2)!.replaceAll('"', '');
    }
    return result;
  }

  static String normalizeCapturedUrl(String rawUrl) {
    var normalized = rawUrl.trim().replaceAll(r'\/', '/');
    if (normalized.startsWith('//')) normalized = 'https:$normalized';
    return normalized;
  }

  static Map<String, String> _sorted(Map<String, String> sources) {
    final entries = sources.entries.toList()
      ..sort((a, b) => _qualityRank(b.key).compareTo(_qualityRank(a.key)));
    return {for (final entry in entries) entry.key: entry.value};
  }

  static String _normalizeQuality(String value) {
    final number = RegExp(r'\d+').firstMatch(value)?.group(0);
    return number == null ? value.trim() : '${number}p';
  }

  static String _qualityLabel(String? resolution, int bandwidth) {
    final height = resolution == null
        ? null
        : int.tryParse(resolution.split('x').last.toLowerCase());
    if (height != null && height > 0) return '${height}p';
    if (bandwidth >= 5 * 1000 * 1000) return '1080p';
    if (bandwidth >= 2 * 1000 * 1000) return '720p';
    if (bandwidth >= 900 * 1000) return '480p';
    return 'Поток';
  }

  static String _qualityFromText(String value) {
    final match = RegExp(
      r'(2160|1440|1080|720|480|360)p?',
      caseSensitive: false,
    ).firstMatch(value);
    return match == null ? 'Авто' : '${match.group(1)}p';
  }

  static int _qualityRank(String label) =>
      int.tryParse(RegExp(r'\d+').firstMatch(label)?.group(0) ?? '') ??
      (label == 'Авто' ? -1 : 0);

  static bool _isMediaUrl(String value) =>
      RegExp(r'\.(m3u8|mp4)(?:\?|$)', caseSensitive: false).hasMatch(value);

  static bool _isHls(Uri uri) =>
      uri.path.toLowerCase().endsWith('.m3u8') ||
      uri.toString().toLowerCase().contains('.m3u8');
}
