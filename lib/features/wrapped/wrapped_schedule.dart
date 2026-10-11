import '../../core/app_settings.dart';

enum WrappedPhase { hidden, teaser, open }

/// When "Итоги года" are teased and when they open.
///
/// The teaser appears two months before New Year (1 November) with a
/// countdown, the recap opens on 20 December and stays until the end of
/// January. The year shown is the one being summed up.
class WrappedSchedule {
  const WrappedSchedule({
    required this.phase,
    required this.year,
    required this.opensAt,
  });

  final WrappedPhase phase;
  final int year;
  final DateTime opensAt;

  Duration untilOpen(DateTime now) =>
      opensAt.isAfter(now) ? opensAt.difference(now) : Duration.zero;

  static DateTime teaserStart(int year) => DateTime(year, 11, 1);
  static DateTime openDate(int year) => DateTime(year, 12, 20);
  static DateTime closeDate(int year) => DateTime(year + 1, 2, 1);

  static WrappedSchedule at(DateTime now, {bool preview = false}) {
    // January: still showing last year's recap.
    final year = now.month == 1 ? now.year - 1 : now.year;
    final opens = openDate(year);
    if (preview) {
      return WrappedSchedule(
        phase: WrappedPhase.open,
        year: now.year,
        opensAt: openDate(now.year),
      );
    }
    final WrappedPhase phase;
    if (!now.isBefore(opens) && now.isBefore(closeDate(year))) {
      phase = WrappedPhase.open;
    } else if (!now.isBefore(teaserStart(year)) && now.isBefore(opens)) {
      phase = WrappedPhase.teaser;
    } else {
      phase = WrappedPhase.hidden;
    }
    return WrappedSchedule(phase: phase, year: year, opensAt: opens);
  }

  static WrappedSchedule current() => at(
    DateTime.now(),
    preview: AppSettingsController.instance.wrappedPreview,
  );
}
