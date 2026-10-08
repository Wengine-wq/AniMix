import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'animix_local_cache.dart';

class Achievement {
  const Achievement(
    this.id,
    this.title,
    this.description,
    this.hint, {
    this.requiredEpisodes = const {},
    this.requiresCompletion = false,
  });

  final String id;
  final String title;
  final String description;
  final String hint;
  final Map<int, int> requiredEpisodes;
  final bool requiresCompletion;
}

/// Local-only awards. Each group of ten selects a cell in its own 5 × 2 atlas.
const achievements = <Achievement>[
  Achievement(
    'first',
    'Ну всё, понеслось',
    'Первая серия в AniMix.',
    'Посмотри одну серию до 85%.',
  ),
  Achievement(
    'marathon',
    'Ещё одну и спать',
    'Пять разных серий за один день.',
    'Посмотри пять серий за день.',
  ),
  Achievement(
    'owl',
    'Сон для слабых',
    'Серия между двумя и пятью ночи.',
    'Досмотри серию с 02:00 до 04:59.',
  ),
  Achievement(
    'insane',
    'ТЫ СОВСЕМ ЕБАНУЛСЯ?',
    'Сутки чистого времени просмотра.',
    'Набери 24 часа просмотра в приложении.',
  ),
  Achievement(
    'completed',
    'Титры, занавес',
    'Первый завершённый тайтл.',
    'Отметь аниме как просмотренное.',
  ),
  Achievement(
    'collector',
    'Плюшкин-сенпай',
    '25 тайтлов в локальной коллекции.',
    'Сохрани 25 разных тайтлов в библиотеку.',
  ),
  Achievement(
    'skip',
    'Опенинг? Не сегодня',
    'Первый пропущенный опенинг или аутро.',
    'Нажми «Пропустить» или включи автоскип.',
  ),
  Achievement(
    'download',
    'Интернет переоценён',
    'Первая скачанная серия.',
    'Дождись завершения загрузки серии.',
  ),
  Achievement(
    'friend',
    'Социализация разблокирована',
    'Первая принятая заявка в друзья.',
    'Прими заявку в друзья.',
  ),
  Achievement(
    'death_note',
    'Имя записано',
    '«Тетрадь смерти» завершена.',
    'Отметь «Тетрадь смерти» как просмотренную.',
  ),
  Achievement(
    'titan_wall',
    'Стены больше нет',
    'Первый сезон «Атаки титанов» пройден целиком.',
    'Посмотри серии 1–25 и заверши первый сезон.',
    requiredEpisodes: {16498: 25},
    requiresCompletion: true,
  ),
  Achievement(
    'alchemy',
    'Эквивалентный обмен? Сном',
    'Все 64 серии «Стального алхимика: Братство» позади.',
    'Посмотри серии 1–64 и заверши «Братство».',
    requiredEpisodes: {5114: 64},
    requiresCompletion: true,
  ),
  Achievement(
    'hunter',
    'Лицензия на безделье',
    'Экзамен длиной в 148 серий сдан.',
    'Посмотри серии 1–148 и заверши «Охотник х Охотник» (2011).',
    requiredEpisodes: {11061: 148},
    requiresCompletion: true,
  ),
  Achievement(
    'steins',
    'Эль Псай Конгру...',
    'Все мировые линии «Врат Штейна» пережиты.',
    'Посмотри серии 1–24 и заверши «Врата Штейна».',
    requiredEpisodes: {9253: 24},
    requiresCompletion: true,
  ),
  Achievement(
    'kira',
    'Кира, хватит писать',
    'Все 37 серий «Тетради смерти» просмотрены.',
    'Посмотри серии 1–37 и заверши «Тетрадь смерти».',
    requiredEpisodes: {1535: 37},
    requiresCompletion: true,
  ),
  Achievement(
    'demon_blade',
    'Дыши, блин',
    'Первый сезон «Клинка, рассекающего демонов» пройден.',
    'Посмотри серии 1–26 и заверши первый сезон.',
    requiredEpisodes: {38000: 26},
    requiresCompletion: true,
  ),
  Achievement(
    'geass',
    'Приказ: досмотреть всё',
    'Оба сезона «Кода Гиас» завершены.',
    'Посмотри по 25 серий и заверши оба сезона.',
    requiredEpisodes: {1575: 25, 2904: 25},
    requiresCompletion: true,
  ),
  Achievement(
    'naruto',
    'Хокаге по выслуге',
    'Все 220 серий оригинального «Наруто» пройдены.',
    'Посмотри серии 1–220 и заверши «Наруто».',
    requiredEpisodes: {20: 220},
    requiresCompletion: true,
  ),
  Achievement(
    'monster',
    'Психотерапевт нужен уже тебе',
    'Все 74 серии «Монстра» выдержаны.',
    'Посмотри серии 1–74 и заверши «Монстр».',
    requiredEpisodes: {19: 74},
    requiresCompletion: true,
  ),
  Achievement(
    'one_piece',
    'Это только начало',
    'Первые сто серий «Ван-Писа» позади.',
    'Посмотри серии 1–100 «Ван-Писа».',
    requiredEpisodes: {21: 100},
  ),
];

class AchievementService {
  AchievementService._();
  static final instance = AchievementService._();
  static const _prefix = 'local_achievements_v1_';
  static const _deathNoteId = 1535;

  final ValueNotifier<int> revision = ValueNotifier(0);
  final ValueNotifier<Achievement?> latestUnlock = ValueNotifier(null);

  Future<Map<String, DateTime>> unlocked() async {
    final prefs = await SharedPreferences.getInstance();
    await _backfillWatchMilestones(prefs);
    await _backfillLibrary(prefs);
    await _evaluateTitleChallenges(prefs, announce: false);
    final result = <String, DateTime>{};
    for (final achievement in achievements) {
      final epoch = prefs.getInt('$_prefix${achievement.id}');
      if (epoch != null) {
        result[achievement.id] = DateTime.fromMillisecondsSinceEpoch(epoch);
      }
    }
    return result;
  }

  Future<void> episodeWatched(DateTime when, {int? animeId}) async {
    final prefs = await SharedPreferences.getInstance();
    await _unlock(prefs, 'first', when);
    if (when.hour >= 2 && when.hour < 5) await _unlock(prefs, 'owl', when);
    final day = '${when.year}-${when.month}-${when.day}';
    final key = '${_prefix}daily_$day';
    final count = (prefs.getInt(key) ?? 0) + 1;
    await prefs.setInt(key, count);
    if (count >= 5) await _unlock(prefs, 'marathon', when);
    if (animeId != null) {
      await _evaluateTitleChallenges(prefs, animeId: animeId);
    }
  }

  Future<void> watchSecondsAdded({bool announce = true}) async {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.containsKey('${_prefix}insane')) return;
    var seconds = 0;
    for (final key in prefs.getKeys()) {
      if (key.startsWith('usage_seconds_v1_')) {
        seconds += prefs.getInt(key) ?? 0;
      }
    }
    if (seconds >= 24 * 60 * 60) {
      await _unlock(prefs, 'insane', DateTime.now(), announce: announce);
    }
  }

  Future<void> librarySaved(int animeId, String status) async {
    final prefs = await SharedPreferences.getInstance();
    await _backfillLibrary(prefs);
    final ids = prefs.getStringList('${_prefix}library_ids') ?? <String>[];
    if (!ids.contains('$animeId')) {
      ids.add('$animeId');
      await prefs.setStringList('${_prefix}library_ids', ids);
    }
    final now = DateTime.now();
    if (ids.length >= 25) await _unlock(prefs, 'collector', now);
    if (status == 'completed') {
      await _unlock(prefs, 'completed', now);
      if (animeId == _deathNoteId) await _unlock(prefs, 'death_note', now);
    }
    await prefs.setString('${_prefix}status_$animeId', status);
    await _evaluateTitleChallenges(prefs, animeId: animeId);
  }

  /// Uses library rows already loaded by the profile; never starts a request.
  Future<void> reconcileLibraryRows(List<Map<String, dynamic>> rows) async {
    final prefs = await SharedPreferences.getInstance();
    await _reconcileLibraryRows(prefs, rows, overwriteStatus: true);
  }

  Future<Map<String, DateTime>>? _unlockedSnapshot;
  Future<Map<String, String>>? _progressSnapshot;
  int _snapshotRevision = -1;
  DateTime _snapshotAt = DateTime.fromMillisecondsSinceEpoch(0);

  void _refreshSnapshotsIfStale() {
    final now = DateTime.now();
    if (_snapshotRevision == revision.value &&
        now.difference(_snapshotAt) < const Duration(seconds: 15)) {
      return;
    }
    _snapshotRevision = revision.value;
    _snapshotAt = now;
    _unlockedSnapshot = null;
    _progressSnapshot = null;
  }

  /// For `FutureBuilder`s: [unlocked] re-runs every backfill and creates a new
  /// future on each rebuild, which recomputed everything and flashed the
  /// widgets back to their empty state. This one is shared until the revision
  /// changes (or 15 s pass, to pick up watch-time milestones).
  Future<Map<String, DateTime>> unlockedSnapshot() {
    _refreshSnapshotsIfStale();
    return _unlockedSnapshot ??= unlocked();
  }

  Future<Map<String, String>> titleProgressSnapshot() {
    _refreshSnapshotsIfStale();
    return _progressSnapshot ??= titleProgress();
  }

  Future<Map<String, String>> titleProgress() async {
    final prefs = await SharedPreferences.getInstance();
    final result = <String, String>{};
    for (final achievement in achievements.where(
      (item) => item.requiredEpisodes.isNotEmpty,
    )) {
      final parts = <String>[];
      for (final requirement in achievement.requiredEpisodes.entries) {
        final watched = _watchedNumberedEpisodes(prefs, requirement.key);
        final progress = Iterable<int>.generate(
          requirement.value,
          (index) => index + 1,
        ).where(watched.contains).length;
        parts.add('$progress/${requirement.value} серий');
      }
      result[achievement.id] = parts.join(' · ');
    }
    return result;
  }

  Future<void> openingSkipped() => _unlockNow('skip');
  Future<void> episodeDownloaded() => _unlockNow('download');
  Future<void> friendAccepted() => _unlockNow('friend');

  Future<void> _unlockNow(String id) async =>
      _unlock(await SharedPreferences.getInstance(), id, DateTime.now());

  Future<void> _unlock(
    SharedPreferences prefs,
    String id,
    DateTime when, {
    bool announce = true,
  }) async {
    final key = '$_prefix$id';
    if (prefs.containsKey(key)) return;
    if (await prefs.setInt(key, when.millisecondsSinceEpoch)) {
      revision.value++;
      if (announce) {
        latestUnlock.value = achievements.firstWhere((item) => item.id == id);
      }
    }
  }

  Future<void> _backfillWatchMilestones(SharedPreferences prefs) async {
    final hasWatched =
        !prefs.containsKey('${_prefix}first') &&
        prefs.getKeys().any(
          (key) =>
              key.startsWith('watched_eps_v2_') &&
              (prefs.getStringList(key)?.isNotEmpty ?? false),
        );
    if (hasWatched) {
      await _unlock(prefs, 'first', DateTime.now(), announce: false);
    }
    await watchSecondsAdded(announce: false);
  }

  Future<void> _backfillLibrary(SharedPreferences prefs) async {
    final rows = await AniMixLocalCache.readLibrary();
    if (rows == null || rows.isEmpty) return;
    await _reconcileLibraryRows(prefs, rows);
  }

  Future<void> _evaluateTitleChallenges(
    SharedPreferences prefs, {
    int? animeId,
    bool announce = true,
  }) async {
    for (final achievement in achievements.where(
      (item) =>
          item.requiredEpisodes.isNotEmpty &&
          (animeId == null || item.requiredEpisodes.containsKey(animeId)),
    )) {
      if (prefs.containsKey('$_prefix${achievement.id}')) continue;
      var complete = true;
      for (final requirement in achievement.requiredEpisodes.entries) {
        final watched = _watchedNumberedEpisodes(prefs, requirement.key);
        for (var episode = 1; episode <= requirement.value; episode++) {
          if (!watched.contains(episode)) {
            complete = false;
            break;
          }
        }
        if (!complete) break;
        if (achievement.requiresCompletion &&
            prefs.getString('${_prefix}status_${requirement.key}') !=
                'completed') {
          complete = false;
          break;
        }
      }
      if (complete) {
        await _unlock(
          prefs,
          achievement.id,
          DateTime.now(),
          announce: announce,
        );
      }
    }
  }

  Set<int> _watchedNumberedEpisodes(SharedPreferences prefs, int animeId) {
    final raw =
        prefs.getStringList('watched_eps_v2_$animeId') ?? const <String>[];
    final result = <int>{};
    for (final value in raw) {
      final match = RegExp(
        r'^\s*(?:серия\s*)?0*(\d{1,4})(?:\.0+)?\s*$',
        caseSensitive: false,
      ).firstMatch(value);
      final number = int.tryParse(match?.group(1) ?? '');
      if (number != null && number > 0) result.add(number);
    }
    return result;
  }

  Future<void> _reconcileLibraryRows(
    SharedPreferences prefs,
    List<Map<String, dynamic>> rows, {
    bool overwriteStatus = false,
  }) async {
    final ids = (prefs.getStringList('${_prefix}library_ids') ?? <String>[])
        .toSet();
    for (final row in rows) {
      final id = int.tryParse('${row['shikimori_id']}');
      if (id == null || id <= 0) continue;
      ids.add('$id');
      if (row['status'] == 'completed') {
        await _unlock(prefs, 'completed', DateTime.now(), announce: false);
        if (id == _deathNoteId) {
          await _unlock(prefs, 'death_note', DateTime.now(), announce: false);
        }
      }
      final status = row['status']?.toString();
      if (status != null && status.isNotEmpty) {
        final key = '${_prefix}status_$id';
        final previous = prefs.getString(key);
        if (previous == null || (overwriteStatus && previous != status)) {
          await prefs.setString(key, status);
        }
      }
    }
    final known =
        prefs.getStringList('${_prefix}library_ids') ?? const <String>[];
    if (ids.length != known.length) {
      await prefs.setStringList('${_prefix}library_ids', ids.toList());
    }
    if (ids.length >= 25) {
      await _unlock(prefs, 'collector', DateTime.now(), announce: false);
    }
    await _evaluateTitleChallenges(prefs, announce: false);
  }
}
