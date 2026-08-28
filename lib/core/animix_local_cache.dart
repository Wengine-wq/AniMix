import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// Small durable cache for the user-owned AniMix layer.
///
/// It deliberately contains no tokens or secrets. The cache lets the profile
/// and library remain readable when the edge endpoint is temporarily
/// unreachable, while SecureStorage stays the only owner of credentials.
class AniMixLocalCache {
  AniMixLocalCache._();

  static const _profileKey = 'animix_profile_cache_v2';
  static const _libraryKey = 'animix_library_cache_v2';

  static Future<Map<String, dynamic>?> readProfile() async {
    final preferences = await SharedPreferences.getInstance();
    return _decodeMap(preferences.getString(_profileKey));
  }

  static Future<bool> writeProfile(Map<String, dynamic> profile) async {
    final preferences = await SharedPreferences.getInstance();
    final encoded = jsonEncode(profile);
    final changed = preferences.getString(_profileKey) != encoded;
    if (changed) await preferences.setString(_profileKey, encoded);
    return changed;
  }

  static Future<List<Map<String, dynamic>>?> readLibrary() async {
    final preferences = await SharedPreferences.getInstance();
    final raw = preferences.getString(_libraryKey);
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return null;
      return decoded
          .whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList(growable: false);
    } catch (_) {
      await preferences.remove(_libraryKey);
      return null;
    }
  }

  static Future<void> writeLibrary(List<Map<String, dynamic>> entries) async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_libraryKey, jsonEncode(entries));
  }

  static Future<void> upsertLibraryEntry(Map<String, dynamic> entry) async {
    final current = await readLibrary() ?? <Map<String, dynamic>>[];
    final id = entry['shikimori_id']?.toString();
    if (id == null || id.isEmpty) return;
    final index = current.indexWhere(
      (item) => item['shikimori_id']?.toString() == id,
    );
    if (index < 0) {
      current.insert(0, entry);
    } else {
      current[index] = entry;
    }
    await writeLibrary(current);
  }

  static Future<void> clear() async {
    final preferences = await SharedPreferences.getInstance();
    await Future.wait([
      preferences.remove(_profileKey),
      preferences.remove(_libraryKey),
    ]);
  }

  static Map<String, dynamic>? _decodeMap(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      return decoded is Map ? Map<String, dynamic>.from(decoded) : null;
    } catch (_) {
      return null;
    }
  }
}
