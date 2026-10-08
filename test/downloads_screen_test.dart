import 'dart:convert';

import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/downloads/downloads_screen.dart';
import 'package:animix/features/downloads/hls_download_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Map<String, Object?> _episode(int animeId, String title, int number) => {
  'episodeId': '${animeId}_kodik-anidub_$number',
  'animeId': animeId,
  'animeTitle': title,
  'episodeName': 'Серия $number',
  'quality': '720p',
  'progress': 1.0,
  'state': 'completed',
  'fileSizeBytes': 100 * 1024 * 1024,
};

void main() {
  testWidgets('downloads are title cards; episodes open in ascending order', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      'animix_hls_downloads_v1': jsonEncode([
        _episode(5, 'Фрирен', 3),
        _episode(5, 'Фрирен', 1),
        _episode(7, 'Ванпанчмен', 1),
        _episode(5, 'Фрирен', 2),
      ]),
    });
    await tester.runAsync(HlsDownloadManager.instance.initialize);
    tester.view.physicalSize = const Size(800, 1400);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.resetPhysicalSize);
    await tester.pumpWidget(
      MaterialApp(
        theme: AniMixTheme.material(
          const Color(0xFF8B5CF6),
          AniMixThemeStyle.graphite,
        ),
        home: const DownloadsScreen(),
      ),
    );
    await tester.pump();

    expect(find.text('Фрирен'), findsOneWidget);
    expect(find.text('Ванпанчмен'), findsOneWidget);
    expect(find.text('Серия 1'), findsNothing);

    await tester.tap(find.text('Фрирен'));
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 200));
    }
    final first = tester.getTopLeft(find.text('Серия 1')).dy;
    final second = tester.getTopLeft(find.text('Серия 2')).dy;
    final third = tester.getTopLeft(find.text('Серия 3')).dy;
    expect(first < second && second < third, isTrue);

    // Deleting the whole title asks once, then removes every episode.
    await tester.tap(find.byTooltip('Удалить все серии'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('Удалить все серии?'), findsOneWidget);
    await tester.tap(find.text('Отмена'));
    await tester.pump(const Duration(milliseconds: 300));
    // Real file IO cannot run inside the fake test clock, so drive the same
    // bulk call the dialog makes outside of it.
    await tester.runAsync(
      () => HlsDownloadManager.instance.deleteMany([
        '5_kodik-anidub_1',
        '5_kodik-anidub_2',
        '5_kodik-anidub_3',
      ]),
    );
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 200));
    }

    expect(HlsDownloadManager.instance.downloads.map((item) => item.animeId), [
      7,
    ]);
    expect(find.text('Ванпанчмен'), findsOneWidget);
    expect(find.text('Фрирен'), findsNothing);
    // Let poster load deadlines expire.
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 11));
  });
}
