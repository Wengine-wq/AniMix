import 'dart:convert';
import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

/// A profile that lives only on this device: name, lists and progress.
///
/// It is never sent to the AniMix server. When the person later signs in to
/// an AniMix account, the local lists can be merged into the account; the
/// local profile itself stays on the device.
class LocalProfile {
  LocalProfile._();

  static const _profileKey = 'local_profile_v1';
  static const _libraryKey = 'local_library_v1';

  static Map<String, dynamic>? _cached;
  static bool _loaded = false;

  /// Loaded before the first frame so the start screen knows about it.
  static Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _cached = _decode(prefs.getString(_profileKey));
    _loaded = true;
  }

  static bool get exists => _loaded && _cached != null;

  static Future<Map<String, dynamic>?> read() async {
    if (!_loaded) await load();
    return _cached == null ? null : Map<String, dynamic>.from(_cached!);
  }

  static Future<Map<String, dynamic>> create(String displayName) async {
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final random = Random.secure();
    final id = List.generate(
      12,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
    final profile = {
      'id': 'local-$id',
      'display_name': displayName.trim(),
      'created_at': now,
      'local': true,
    };
    await _saveProfile(profile);
    return profile;
  }

  static Future<Map<String, dynamic>?> rename(String displayName) async {
    final profile = await read();
    if (profile == null) return null;
    profile['display_name'] = displayName.trim();
    await _saveProfile(profile);
    return profile;
  }

  /// Removes the profile and its lists from this device.
  static Future<void> delete() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_profileKey);
    await prefs.remove(_libraryKey);
    _cached = null;
  }

  static Future<void> _saveProfile(Map<String, dynamic> profile) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_profileKey, jsonEncode(profile));
    _cached = profile;
    _loaded = true;
  }

  /// Entries in the same shape as the AniMix library API:
  /// `{shikimori_id, status, score, episodes_watched, updated_at}`.
  static Future<List<Map<String, dynamic>>> library() async {
    final prefs = await SharedPreferences.getInstance();
    try {
      final decoded = jsonDecode(prefs.getString(_libraryKey) ?? '[]');
      if (decoded is! List) return [];
      final entries = decoded
          .whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList();
      entries.sort(
        (a, b) => _int(b['updated_at']).compareTo(_int(a['updated_at'])),
      );
      return entries;
    } catch (_) {
      return [];
    }
  }

  static Future<Map<String, dynamic>> saveEntry({
    required int animeId,
    required String status,
    required int score,
    required int episodesWatched,
  }) async {
    final entries = await library();
    entries.removeWhere((entry) => _int(entry['shikimori_id']) == animeId);
    final entry = {
      'shikimori_id': animeId,
      'status': status,
      'score': score,
      'episodes_watched': episodesWatched,
      'updated_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
    };
    entries.insert(0, entry);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_libraryKey, jsonEncode(entries));
    return entry;
  }

  /// Profile JSON in the AniMix `/v1/me` shape, with list statistics.
  static Future<Map<String, dynamic>?> asAccountJson() async {
    final profile = await read();
    if (profile == null) return null;
    final stats = <String, int>{
      'planned': 0,
      'watching': 0,
      'completed': 0,
      'on_hold': 0,
      'dropped': 0,
      'rewatching': 0,
      'scores': 0,
      'episodes_watched': 0,
    };
    for (final entry in await library()) {
      final status = entry['status']?.toString() ?? '';
      if (stats.containsKey(status)) stats[status] = stats[status]! + 1;
      if (_int(entry['score']) > 0) stats['scores'] = stats['scores']! + 1;
      stats['episodes_watched'] =
          stats['episodes_watched']! + _int(entry['episodes_watched']);
    }
    return {...profile, 'stats': stats};
  }

  static int _int(Object? value) => int.tryParse('$value') ?? 0;

  static Map<String, dynamic>? _decode(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      return decoded is Map ? Map<String, dynamic>.from(decoded) : null;
    } catch (_) {
      return null;
    }
  }
}
