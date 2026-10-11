import 'package:animix/features/wrapped/wrapped_schedule.dart';
import 'package:animix/features/wrapped/wrapped_stats.dart';
import 'package:flutter_test/flutter_test.dart';

int _ts(DateTime at) => at.millisecondsSinceEpoch ~/ 1000;

void main() {
  test('schedule: teaser from 1 Nov, open 20 Dec – end of January', () {
    expect(
      WrappedSchedule.at(DateTime(2026, 10, 20)).phase,
      WrappedPhase.hidden,
    );
    final teaser = WrappedSchedule.at(DateTime(2026, 11, 3));
    expect(teaser.phase, WrappedPhase.teaser);
    expect(teaser.untilOpen(DateTime(2026, 12, 19)), const Duration(days: 1));
    expect(WrappedSchedule.at(DateTime(2026, 12, 21)).phase, WrappedPhase.open);
    final january = WrappedSchedule.at(DateTime(2027, 1, 15));
    expect(january.phase, WrappedPhase.open);
    expect(january.year, 2026);
    expect(WrappedSchedule.at(DateTime(2027, 2, 2)).phase, WrappedPhase.hidden);
  });

  test('stats count only this year and find the marathon day', () {
    final events = [
      for (var e = 1; e <= 4; e++)
        {
          'created_at': _ts(DateTime(2026, 3, 14, 20, e)),
          'action': 'episode_watched',
          'anime_id': 5,
          'metadata': {'episode': '$e'},
        },
      {
        'created_at': _ts(DateTime(2026, 3, 14, 23)),
        'action': 'episode_watched',
        'anime_id': 5,
        'metadata': {'episode': '4'},
      },
      {
        'created_at': _ts(DateTime(2026, 5, 2)),
        'action': 'episode_watched',
        'anime_id': 7,
        'metadata': {'episode': '1'},
      },
      {
        'created_at': _ts(DateTime(2026, 3, 14)),
        'action': 'watch_seconds',
        'anime_id': 5,
        'metadata': {'seconds': 3600},
      },
      {
        'created_at': _ts(DateTime(2025, 12, 31)),
        'action': 'watch_seconds',
        'anime_id': 5,
        'metadata': {'seconds': 9999},
      },
    ];
    final stats = WrappedStats.fromEvents(2026, events);
    expect(stats.episodes, 5);
    expect(stats.titles, 2);
    expect(stats.watchSeconds, 3600);
    expect(stats.topAnimeId, 5);
    expect(stats.marathonEpisodes, 4);
    expect(stats.marathonDay, DateTime(2026, 3, 14));
    expect(stats.busiestMonth, 3);
  });
}
