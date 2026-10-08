import 'package:animix/core/achievement_service.dart';
import 'package:animix/features/watch/watch_storage.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test(
    'recording a watched episode advances its title challenge once',
    () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      FlutterSecureStorage.setMockInitialValues({});
      await WatchStorage.markEpisodeWatched(16498, '01');
      await WatchStorage.markEpisodeWatched(16498, '01');

      expect(
        (await AchievementService.instance.titleProgress())['titan_wall'],
        '1/25 серий',
      );
      expect(await WatchStorage.getWatchedEpisodes(16498), ['01']);
    },
  );

  test('stores opening timing separately for each episode', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});

    await WatchStorage.saveOpeningTiming(
      42,
      '3',
      startSecond: 91,
      endSecond: 180,
    );

    final saved = await WatchStorage.getOpeningTiming(42, '3');
    final otherEpisode = await WatchStorage.getOpeningTiming(42, '4');

    expect(saved?.startSecond, 91);
    expect(saved?.endSecond, 180);
    expect(otherEpisode, isNull);
  });

  test(
    'does not store implausible provider seeks as opening timings',
    () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});

      await WatchStorage.saveOpeningTiming(
        42,
        '3',
        startSecond: 10,
        endSecond: 12,
      );
      await WatchStorage.saveOpeningTiming(
        42,
        '3',
        startSecond: 10,
        endSecond: 700,
      );

      expect(await WatchStorage.getOpeningTiming(42, '3'), isNull);
    },
  );
}
