import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

import 'app_settings.dart';
import 'media_cache.dart';

/// Downloads poster files reliably and remembers which URL worked per title.
///
/// Before this, every poster widget fetched on its own: dozens of parallel
/// requests made Shikimori throttle some of them, a 10 s deadline then
/// swapped in a different fallback image, and a failure stayed blank until
/// the user retried by hand. Now downloads go through one queue with a small
/// concurrency limit and automatic retries, identical URLs share one request,
/// and the last working URL for each title is tried first next time, so a
/// poster never "jumps" to another picture.
class PosterLoader {
  PosterLoader._();
  static final PosterLoader instance = PosterLoader._();

  static const _maxParallel = 4;
  static const _memoryKey = 'poster_good_urls_v1';
  static const _memoryLimit = 3000;

  final Map<String, Future<File?>> _inflight = {};
  final Queue<Completer<void>> _waiting = Queue();
  int _active = 0;

  final LinkedHashMap<int, String> _goodUrls = LinkedHashMap();
  Future<void>? _loadingMemory;
  Timer? _saveTimer;

  Future<void> _ensureMemory() => _loadingMemory ??= () async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_memoryKey);
      if (raw == null) return;
      final map = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      for (final entry in map.entries) {
        final id = int.tryParse(entry.key);
        if (id != null && entry.value is String) _goodUrls[id] = entry.value;
      }
    } catch (_) {
      // A corrupt memory only costs one extra lookup per poster.
    }
  }();

  /// URL that last produced a poster for [animeId], if any.
  Future<String?> rememberedUrl(int animeId) async {
    await _ensureMemory();
    return _goodUrls[animeId];
  }

  void remember(int animeId, String url) {
    if (animeId <= 0 || _goodUrls[animeId] == url) return;
    _goodUrls.remove(animeId);
    _goodUrls[animeId] = url;
    while (_goodUrls.length > _memoryLimit) {
      _goodUrls.remove(_goodUrls.keys.first);
    }
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(seconds: 2), _saveMemory);
  }

  Future<void> forget(int animeId) async {
    await _ensureMemory();
    if (_goodUrls.remove(animeId) != null) unawaited(_saveMemory());
  }

  Future<void> _saveMemory() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        _memoryKey,
        jsonEncode({
          for (final entry in _goodUrls.entries) '${entry.key}': entry.value,
        }),
      );
    } catch (_) {}
  }

  /// Returns the poster file for [url], from disk when already saved.
  Future<File?> load(String url) =>
      _inflight[url] ??= _load(url).whenComplete(() => _inflight.remove(url));

  Future<File?> _load(String url) async {
    final cache = AniMixMediaCache.postersFor(
      AppSettingsController.instance.posterStorage,
    );
    try {
      final cached = await cache.getFileFromCache(url);
      if (cached != null && await cached.file.exists()) return cached.file;
    } catch (_) {}
    const delays = [Duration(milliseconds: 700), Duration(seconds: 2)];
    for (var attempt = 0; attempt <= delays.length; attempt++) {
      await _acquire();
      try {
        final file = await cache
            .getSingleFile(url)
            .timeout(const Duration(seconds: 30));
        if (await file.length() > 0) return file;
      } catch (_) {
        // Retry below; a throttled or flaky request often succeeds later.
      } finally {
        _release();
      }
      if (attempt < delays.length) await Future<void>.delayed(delays[attempt]);
    }
    return null;
  }

  Future<void> _acquire() async {
    if (_active < _maxParallel) {
      _active++;
      return;
    }
    final turn = Completer<void>();
    _waiting.add(turn);
    await turn.future;
  }

  void _release() {
    if (_waiting.isNotEmpty) {
      _waiting.removeFirst().complete();
    } else {
      _active--;
    }
  }
}
