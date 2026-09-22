import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/animix_auth_service.dart';
import '../../core/secure_storage.dart';

class OpeningTiming {
  const OpeningTiming({required this.startSecond, required this.endSecond});

  final int startSecond;
  final int endSecond;
}

class WatchStorage {
  // Используем новые ключи (_v2_), чтобы сбросить старые сломанные типы
  static const _watchedKey = 'watched_eps_v2_';
  static const _progressKey = 'progress_v2_';
  static const _openingTimingsKey = 'opening_timings_v1';
  static const _usageOutboxPrefix = 'usage_event_outbox_v1_';
  static Future<void>? _usageSyncInFlight;

  /// Lets an already open native player react when its hidden provider player
  /// discovers an opening skip button a little later in the episode.
  static final ValueNotifier<int> openingTimingRevision = ValueNotifier(0);

  static Future<void> markEpisodeWatched(
    int animeId,
    String episodeNumber,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_watchedKey$animeId';
    final list = prefs.getStringList(key) ?? [];
    if (!list.contains(episodeNumber)) {
      list.add(episodeNumber);
      await prefs.setStringList(key, list);
      final timestamp = DateTime.now().microsecondsSinceEpoch;
      await _enqueueUsageEvent({
        'action': 'episode_watched',
        'anime_id': animeId,
        'event_id': 'e${timestamp}_$animeId',
        'metadata': {
          'episode': episodeNumber,
          'occurred_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
        },
      });
    }
  }

  static Future<void> recordWatchSeconds(int animeId, int seconds) async {
    if (seconds <= 0) return;
    final prefs = await SharedPreferences.getInstance();
    final day = DateTime.now().toLocal();
    final dateKey =
        '${day.year}-${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';
    final key = 'usage_seconds_v1_$dateKey';
    await prefs.setInt(key, (prefs.getInt(key) ?? 0) + seconds);
    final timestamp = DateTime.now();
    await _enqueueUsageEvent({
      'action': 'watch_seconds',
      'anime_id': animeId,
      'event_id': 'w${timestamp.microsecondsSinceEpoch}_$animeId',
      'metadata': {
        'seconds': seconds,
        'occurred_at': timestamp.millisecondsSinceEpoch ~/ 1000,
      },
    });
  }

  static Future<void> _enqueueUsageEvent(Map<String, dynamic> event) async {
    final token = await SecureStorage.getAniMixAccessToken();
    if (token == null || token.isEmpty) return;
    final profile = await AniMixAuthService().getCachedCurrentUser();
    final userId = profile?['id']?.toString();
    if (userId == null || userId.isEmpty) return;
    final outboxKey = '$_usageOutboxPrefix$userId';
    final prefs = await SharedPreferences.getInstance();
    final queue = prefs.getStringList(outboxKey) ?? <String>[];
    queue.add(jsonEncode(event));
    await prefs.setStringList(outboxKey, queue);
    unawaited(syncPendingUsageEvents());
  }

  static Future<void> syncPendingUsageEvents() {
    final active = _usageSyncInFlight;
    if (active != null) return active;
    final request = _syncPendingUsageEvents();
    _usageSyncInFlight = request;
    return request.whenComplete(() {
      if (identical(_usageSyncInFlight, request)) _usageSyncInFlight = null;
    });
  }

  static Future<void> _syncPendingUsageEvents() async {
    try {
      final profile = await AniMixAuthService().getCachedCurrentUser();
      final userId = profile?['id']?.toString();
      if (userId == null || userId.isEmpty) return;
      final outboxKey = '$_usageOutboxPrefix$userId';
      final prefs = await SharedPreferences.getInstance();
      while (true) {
        final queue = prefs.getStringList(outboxKey) ?? <String>[];
        if (queue.isEmpty) return;
        Map<String, dynamic> event;
        try {
          event = Map<String, dynamic>.from(jsonDecode(queue.first) as Map);
        } catch (_) {
          queue.removeAt(0);
          await prefs.setStringList(outboxKey, queue);
          continue;
        }
        final rawMetadata = event['metadata'];
        final saved = await AniMixAuthService().recordUsageEvent(
          action: event['action']?.toString() ?? '',
          animeId: int.tryParse(event['anime_id']?.toString() ?? '') ?? 0,
          eventId: event['event_id']?.toString() ?? '',
          metadata: rawMetadata is Map
              ? Map<String, Object?>.from(rawMetadata)
              : const <String, Object?>{},
        );
        if (!saved) return;
        queue.removeAt(0);
        await prefs.setStringList(outboxKey, queue);
      }
    } catch (_) {
      // Keep queued activity and retry the next time the profile opens.
    }
  }

  static Future<List<String>> getWatchedEpisodes(int animeId) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getStringList('$_watchedKey$animeId') ?? [];
  }

  static Future<Map<String, dynamic>> getLocalUsageStats() async {
    final prefs = await SharedPreferences.getInstance();
    var watchSeconds = 0;
    final activity = <Map<String, dynamic>>[];
    for (final key in prefs.getKeys()) {
      if (key.startsWith('usage_seconds_v1_')) {
        final seconds = prefs.getInt(key) ?? 0;
        watchSeconds += seconds;
        final date = key.substring('usage_seconds_v1_'.length);
        activity.add({
          'created_at': DateTime.tryParse(date)?.millisecondsSinceEpoch ?? 0,
          'action': 'watch_seconds',
          'anime_id': 0,
          'metadata': {'seconds': seconds},
        });
      }
    }
    var episodes = 0;
    for (final key in prefs.getKeys().where(
      (key) => key.startsWith(_watchedKey),
    )) {
      episodes += prefs.getStringList(key)?.length ?? 0;
    }
    return {
      'watch_seconds': watchSeconds,
      'episodes_watched': episodes,
      'activity': activity,
    };
  }

  /// Offline events are keyed by AniMix account, unlike legacy daily totals.
  /// Use these for the current account's streak until YDB acknowledges them.
  static Future<List<Map<String, dynamic>>> getPendingUsageActivity() async {
    final profile = await AniMixAuthService().getCachedCurrentUser();
    final userId = profile?['id']?.toString();
    if (userId == null || userId.isEmpty) return const [];
    final prefs = await SharedPreferences.getInstance();
    final queue = prefs.getStringList('$_usageOutboxPrefix$userId') ?? const [];
    final activity = <Map<String, dynamic>>[];
    for (final encoded in queue) {
      try {
        final event = jsonDecode(encoded);
        if (event is! Map) continue;
        final metadata = event['metadata'];
        final values = metadata is Map ? metadata : const <String, dynamic>{};
        final occurredAt = int.tryParse('${values['occurred_at']}') ?? 0;
        if (occurredAt <= 0) continue;
        activity.add({
          'created_at': occurredAt,
          'action': event['action']?.toString() ?? '',
          'anime_id': event['anime_id'],
          'metadata': values,
        });
      } catch (_) {
        /* Ignore an invalid queued event. */
      }
    }
    return activity;
  }

  static Future<Set<int>> getLocalWatchedAnimeIds() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs
        .getKeys()
        .where((key) => key.startsWith(_watchedKey))
        .where(
          (key) => (prefs.getStringList(key) ?? const <String>[]).isNotEmpty,
        )
        .map((key) => int.tryParse(key.substring(_watchedKey.length)) ?? 0)
        .where((id) => id > 0)
        .toSet();
  }

  static Future<void> saveProgress(
    int animeId,
    String episodeNumber,
    Duration position,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_progressKey${animeId}_$episodeNumber';
    await prefs.setInt(key, position.inSeconds);
  }

  static Future<Duration?> getProgress(
    int animeId,
    String episodeNumber,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final seconds = prefs.getInt('$_progressKey${animeId}_$episodeNumber');
    return seconds != null ? Duration(seconds: seconds) : null;
  }

  static Future<OpeningTiming?> getOpeningTiming(
    int animeId,
    String episodeNumber,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_openingTimingsKey);
    if (raw == null) return null;
    try {
      final timings = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      final value = timings[_openingKey(animeId, episodeNumber)];
      if (value is! Map) return null;
      final start = int.tryParse(value['start']?.toString() ?? '');
      final end = int.tryParse(value['end']?.toString() ?? '');
      if (!_isValidOpeningTiming(start, end)) return null;
      return OpeningTiming(startSecond: start!, endSecond: end!);
    } catch (_) {
      return null;
    }
  }

  /// Persists only actual jumps observed after a provider's explicit
  /// "skip opening" action. A duration bound keeps random seek controls from
  /// poisoning the next native playback.
  static Future<void> saveOpeningTiming(
    int animeId,
    String episodeNumber, {
    required int startSecond,
    required int endSecond,
  }) async {
    if (!_isValidOpeningTiming(startSecond, endSecond)) return;
    final prefs = await SharedPreferences.getInstance();
    Map<String, dynamic> timings = {};
    final raw = prefs.getString(_openingTimingsKey);
    if (raw != null) {
      try {
        timings = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      } catch (_) {
        // A malformed old cache must not prevent a newly observed timing.
      }
    }
    final key = _openingKey(animeId, episodeNumber);
    final previous = timings[key];
    final next = {'start': startSecond, 'end': endSecond};
    if (previous is Map &&
        previous['start'] == startSecond &&
        previous['end'] == endSecond) {
      return;
    }
    timings[key] = next;
    await prefs.setString(_openingTimingsKey, jsonEncode(timings));
    openingTimingRevision.value++;
  }

  static String _openingKey(int animeId, String episodeNumber) =>
      '${animeId}_$episodeNumber';

  static bool _isValidOpeningTiming(int? start, int? end) =>
      start != null &&
      end != null &&
      start >= 0 &&
      end - start >= 5 &&
      end - start <= 600;
}
