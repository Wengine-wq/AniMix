import 'dart:io';

import 'package:animix/features/downloads/offline_media_server.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('serves an offline HLS package and byte ranges over loopback', () async {
    final directory = await Directory.systemTemp.createTemp(
      'animix_offline_hls_',
    );
    final manifest = File('${directory.path}/offline.m3u8');
    final segment = File('${directory.path}/resource_00000.ts');
    await manifest.writeAsString(
      '#EXTM3U\n#EXTINF:4,\nresource_00000.ts\n#EXT-X-ENDLIST',
    );
    await segment.writeAsBytes(List<int>.generate(32, (index) => index));

    final server = OfflineMediaServer.instance;
    final manifestUri = await server.serve('20_1', manifest);
    final client = HttpClient();
    try {
      final manifestRequest = await client.getUrl(manifestUri);
      final manifestResponse = await manifestRequest.close();
      expect(manifestResponse.statusCode, HttpStatus.ok);
      expect(
        manifestResponse.headers.contentType?.mimeType,
        'application/vnd.apple.mpegurl',
      );

      final segmentRequest = await client.getUrl(
        manifestUri.resolve('resource_00000.ts'),
      );
      segmentRequest.headers.set(HttpHeaders.rangeHeader, 'bytes=4-11');
      final segmentResponse = await segmentRequest.close();
      final bytes = await segmentResponse.fold<List<int>>(
        <int>[],
        (buffer, chunk) => buffer..addAll(chunk),
      );
      expect(segmentResponse.statusCode, HttpStatus.partialContent);
      expect(bytes, List<int>.generate(8, (index) => index + 4));
    } finally {
      client.close(force: true);
      await server.close();
      await directory.delete(recursive: true);
    }
  });
}
