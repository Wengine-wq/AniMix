import 'package:flutter_test/flutter_test.dart';

import 'package:animix/features/downloads/download_item.dart';

void main() {
  group('DownloadItem poster metadata', () {
    test('normalizes persistable poster URLs', () {
      expect(
        DownloadItem.normalizePosterUrl('//cdn.example.test/poster.jpg'),
        'https://cdn.example.test/poster.jpg',
      );
      expect(
        DownloadItem.normalizePosterUrl('/system/animes/original/1.jpg'),
        'https://shikimori.io/system/animes/original/1.jpg',
      );
      expect(DownloadItem.normalizePosterUrl('/relative/broken.jpg'), isNull);
    });

    test('keeps resolved poster URL through JSON storage', () {
      const item = DownloadItem(
        episodeId: '1_kodik_1',
        animeId: 1,
        animeTitle: 'Cowboy Bebop',
        episodeName: 'Серия 1',
        posterUrl: 'https://cdn.example.test/bebop.jpg',
        quality: '1080p',
        progress: 1,
        state: DownloadState.completed,
      );

      final restored = DownloadItem.fromJson(item.toJson());

      expect(restored.animeId, 1);
      expect(restored.posterUrl, item.posterUrl);
      expect(
        restored
            .copyWith(posterUrl: 'https://cdn.example.test/new.jpg')
            .posterUrl,
        'https://cdn.example.test/new.jpg',
      );
    });
  });
}
