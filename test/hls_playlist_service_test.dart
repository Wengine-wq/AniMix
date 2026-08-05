import 'package:animix/features/watch/services/hls_playlist_service.dart';
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
  });
}
