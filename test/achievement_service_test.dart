import 'dart:io';

import 'package:animix/core/achievement_service.dart';
import 'package:animix/features/profile/achievements_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:image/image.dart' as image;

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('five episodes on one day unlock marathon only once', () async {
    final service = AchievementService.instance;
    final day = DateTime(2026, 9, 24, 12);
    for (var i = 0; i < 5; i++) {
      await service.episodeWatched(day.add(Duration(minutes: i)));
    }
    final earned = await service.unlocked();
    expect(earned.keys, containsAll(['first', 'marathon']));
    expect(earned, isNot(contains('owl')));
    await service.episodeWatched(day.add(const Duration(days: 1)));
    expect((await service.unlocked())['marathon'], earned['marathon']);
  });

  test('night owl only unlocks for the local early morning window', () async {
    final service = AchievementService.instance;
    await service.episodeWatched(DateTime(2026, 9, 24, 1, 59));
    expect(await service.unlocked(), isNot(contains('owl')));
    await service.episodeWatched(DateTime(2026, 9, 24, 2));
    expect(await service.unlocked(), contains('owl'));
  });

  test('Death Note award requires its completed status', () async {
    final service = AchievementService.instance;
    await service.librarySaved(1535, 'planned');
    expect(await service.unlocked(), isNot(contains('death_note')));
    await service.librarySaved(1535, 'completed');
    expect(await service.unlocked(), contains('death_note'));
    expect(await service.unlocked(), contains('completed'));
  });

  test('title challenge needs every episode and completed status', () async {
    final prefs = await SharedPreferences.getInstance();
    final service = AchievementService.instance;
    expect(
      achievements.where((item) => item.requiredEpisodes.isNotEmpty),
      hasLength(10),
    );
    await service.librarySaved(16498, 'completed');
    expect(await service.unlocked(), isNot(contains('titan_wall')));

    await prefs.setStringList('watched_eps_v2_16498', [
      for (var episode = 1; episode < 25; episode++) '$episode',
      '01', // Duplicate numbering must not count as another episode.
    ]);
    expect(await service.unlocked(), isNot(contains('titan_wall')));
    expect((await service.titleProgress())['titan_wall'], '24/25 серий');

    await prefs.setStringList('watched_eps_v2_16498', [
      for (var episode = 1; episode <= 25; episode++) '$episode',
    ]);
    expect(await service.unlocked(), contains('titan_wall'));
  });

  test(
    'stale cached status cannot override a newer local completion',
    () async {
      SharedPreferences.setMockInitialValues({
        'animix_library_cache_v2':
            '[{"shikimori_id":16498,"status":"planned"}]',
        'watched_eps_v2_16498': [
          for (var episode = 1; episode <= 25; episode++) '$episode',
        ],
      });
      await AchievementService.instance.librarySaved(16498, 'completed');
      expect(
        await AchievementService.instance.unlocked(),
        contains('titan_wall'),
      );
    },
  );

  test('two-season challenge needs both complete seasons', () async {
    final prefs = await SharedPreferences.getInstance();
    final service = AchievementService.instance;
    await prefs.setStringList('watched_eps_v2_1575', [
      for (var episode = 1; episode <= 25; episode++) '$episode',
    ]);
    await prefs.setStringList('watched_eps_v2_2904', [
      for (var episode = 1; episode <= 25; episode++) '$episode',
    ]);
    await service.librarySaved(1575, 'completed');
    expect(await service.unlocked(), isNot(contains('geass')));
    await service.librarySaved(2904, 'completed');
    expect(await service.unlocked(), contains('geass'));
  });

  test(
    'ongoing One Piece challenge requires a hundred numbered episodes',
    () async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setStringList('watched_eps_v2_21', [
        for (var episode = 1; episode <= 100; episode++) '$episode',
      ]);
      expect(
        await AchievementService.instance.unlocked(),
        contains('one_piece'),
      );
    },
  );

  test('both badge atlases have transparent backgrounds', () {
    for (final name in ['atlas.png', 'titles_atlas.png']) {
      final bytes = File('assets/achievements/$name').readAsBytesSync();
      final atlas = image.decodePng(bytes)!;
      expect(atlas.getPixel(0, 0).a, 0, reason: name);
      expect(
        atlas.getPixel(atlas.width - 1, atlas.height - 1).a,
        0,
        reason: name,
      );
    }
  });

  test('existing local watch time unlocks the 24 hour milestone', () async {
    SharedPreferences.setMockInitialValues({
      'usage_seconds_v1_2026-09-23': 12 * 60 * 60,
      'usage_seconds_v1_2026-09-24': 12 * 60 * 60,
    });
    expect(await AchievementService.instance.unlocked(), contains('insane'));
  });

  test('existing cached library unlocks title and collection awards', () async {
    SharedPreferences.setMockInitialValues({
      'animix_library_cache_v2':
          '[${List.generate(25, (index) => '{"shikimori_id":${index == 0 ? 1535 : index + 2000},"status":"completed"}').join(',')}]',
    });
    final earned = await AchievementService.instance.unlocked();
    expect(earned.keys, containsAll(['completed', 'collector', 'death_note']));
  });

  test(
    'already loaded library rows can unlock awards without a new request',
    () async {
      await AchievementService.instance.reconcileLibraryRows([
        for (var i = 0; i < 25; i++)
          {'shikimori_id': i == 0 ? 1535 : 2000 + i, 'status': 'completed'},
      ]);
      expect(
        (await AchievementService.instance.unlocked()).keys,
        containsAll(['completed', 'collector', 'death_note']),
      );
    },
  );

  testWidgets('achievement gallery renders its atlas icons', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1100, 850));
    await tester.pumpWidget(const MaterialApp(home: AchievementsScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Достижения'), findsOneWidget);
    expect(find.byType(AchievementIcon), findsWidgets);
    expect(tester.takeException(), isNull);
    await tester.binding.setSurfaceSize(null);
  });
}
