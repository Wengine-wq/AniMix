import 'package:animix/features/watch/watch_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
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
