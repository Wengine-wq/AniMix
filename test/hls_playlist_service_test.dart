import 'dart:typed_data';

import 'package:animix/features/watch/services/hls_playlist_service.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('HlsPlaylistService', () {
    test('extracts, sorts and resolves adaptive qualities', () {
      const playlist = '''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480
video/480/index.m3u8?token=abc
#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
/streams/1080/index.m3u8?token=abc
#EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720
video/720/index.m3u8?token=abc
''';

      final variants = HlsPlaylistService().parseMasterPlaylist(
        playlist,
        Uri.parse('https://cdn.example.org/master/list.m3u8?token=abc'),
      );

      expect(variants.map((item) => item.label), ['1080p', '720p', '480p']);
      expect(
        variants.first.uri.toString(),
        'https://cdn.example.org/streams/1080/index.m3u8?token=abc',
      );
      expect(
        variants.last.uri.toString(),
        'https://cdn.example.org/master/video/480/index.m3u8?token=abc',
      );
    });

    test('keeps only the highest bandwidth duplicate resolution', () {
      const playlist = '''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720-low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
720-high.m3u8
''';

      final variants = HlsPlaylistService().parseMasterPlaylist(
        playlist,
        Uri.parse('https://cdn.example.org/master.m3u8'),
      );

      expect(variants, hasLength(1));
      expect(variants.single.uri.path, '/720-high.m3u8');
    });

    test('does not invent a resolution from bandwidth alone', () {
      const playlist = '''
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2200000
video/index.m3u8
''';

      final variants = HlsPlaylistService().parseMasterPlaylist(
        playlist,
        Uri.parse('https://cdn.example.org/master.m3u8'),
      );

      expect(variants.single.label, 'Поток · 2.2 Мбит/с');
    });

    test('parses PlayerJS quality expression from Kodik', () {
      final sources = HlsPlaylistService().parsePlayerJsExpression(
        r'[1080p]https:\/\/cdn.example.org\/1080.mp4?token=x,'
        r'[720p]//cdn.example.org/720.mp4?token=x',
      );

      expect(sources.keys, ['1080p', '720p']);
      expect(sources['1080p'], 'https://cdn.example.org/1080.mp4?token=x');
      expect(sources['720p'], 'https://cdn.example.org/720.mp4?token=x');
    });

    test('derives Kodik sibling HLS manifests', () {
      final sources = HlsPlaylistService.deriveSiblingHlsUrls(
        Uri.parse('https://cdn.example.org/signed/360.mp4:hls:manifest.m3u8'),
      );

      expect(sources['1080p'], contains('/1080.mp4:hls:manifest.m3u8'));
      expect(sources['720p'], contains('/720.mp4:hls:manifest.m3u8'));
      expect(sources['360p'], contains('/360.mp4:hls:manifest.m3u8'));
    });

    test('rejects an expired cached HLS URL', () async {
      final dio = Dio()..httpClientAdapter = _PlaylistAdapter(statusCode: 403);
      final service = HlsPlaylistService(dio: dio);

      expect(
        await service.isReachable('https://cdn.example.org/expired.m3u8'),
        isFalse,
      );
    });

    test('accepts a live cached HLS manifest', () async {
      final dio = Dio()..httpClientAdapter = _PlaylistAdapter(statusCode: 200);
      final service = HlsPlaylistService(dio: dio);

      expect(
        await service.isReachable('https://cdn.example.org/live.m3u8'),
        isTrue,
      );
    });
  });
}

class _PlaylistAdapter implements HttpClientAdapter {
  const _PlaylistAdapter({required this.statusCode});

  final int statusCode;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    statusCode == 200 ? '#EXTM3U\n#EXT-X-VERSION:3' : 'expired',
    statusCode,
    headers: {
      Headers.contentTypeHeader: ['application/vnd.apple.mpegurl'],
    },
  );

  @override
  void close({bool force = false}) {}
}
