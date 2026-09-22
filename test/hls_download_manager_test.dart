import 'dart:convert';

import 'package:animix/features/downloads/download_item.dart';
import 'package:animix/features/downloads/hls_download_manager.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test(
    'autoplay selects the next completed episode from the same dub',
    () async {
      final current = _item('42_yummy-studio-a_1');
      final nextSameDub = _item('42_yummy-studio-a_2');
      final earlierOtherDub = _item('42_yummy-studio-b_1.5');
      SharedPreferences.setMockInitialValues({
        'animix_hls_downloads_v1': jsonEncode([
          current.toJson(),
          nextSameDub.toJson(),
          earlierOtherDub.toJson(),
        ]),
      });

      final manager = HlsDownloadManager.instance;
      await manager.initialize();

      final next = await manager.nextCompletedEpisode(current);

      expect(next?.episodeId, nextSameDub.episodeId);
    },
  );
}

DownloadItem _item(String episodeId) => DownloadItem(
  episodeId: episodeId,
  animeId: 42,
  animeTitle: 'Тест',
  episodeName: 'Серия ${episodeId.split('_').last}',
  quality: '720p',
  progress: 1,
  state: DownloadState.completed,
  localPath: 'C:/offline/$episodeId.m3u8',
);
