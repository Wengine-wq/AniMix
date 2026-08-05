import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

class ResolvedStreamCache {
  const ResolvedStreamCache._();

  static const _storageKey = 'kodik_resolved_streams_v4';
  static const _freshFor = Duration(minutes: 20);
  static const _maxEntries = 80;

  static Future<Map<String, String>?> get(String embedUrl) async {
    final entries = await _load();
    final raw = entries[embedUrl];
    if (raw is! Map) return null;
    final savedAt = DateTime.tryParse(raw['savedAt']?.toString() ?? '');
    if (savedAt == null || DateTime.now().difference(savedAt) > _freshFor) {
      entries.remove(embedUrl);
      await _save(entries);
      return null;
    }
    final sources = raw['sources'];
    if (sources is! Map) return null;
    return Map<String, String>.from(sources);
  }

  static Future<void> put(String embedUrl, Map<String, String> sources) async {
    if (sources.isEmpty) return;
    final entries = await _load();
    entries[embedUrl] = {
      'savedAt': DateTime.now().toIso8601String(),
      'sources': sources,
    };
    if (entries.length > _maxEntries) {
      final sorted = entries.entries.toList()
        ..sort((a, b) {
          final aDate =
              DateTime.tryParse(
                (a.value as Map?)?['savedAt']?.toString() ?? '',
              ) ??
              DateTime.fromMillisecondsSinceEpoch(0);
          final bDate =
              DateTime.tryParse(
                (b.value as Map?)?['savedAt']?.toString() ?? '',
              ) ??
              DateTime.fromMillisecondsSinceEpoch(0);
          return bDate.compareTo(aDate);
        });
      entries
        ..clear()
        ..addEntries(sorted.take(_maxEntries));
    }
    await _save(entries);
  }

  static Future<void> invalidate(String embedUrl) async {
    final entries = await _load();
    if (entries.remove(embedUrl) != null) await _save(entries);
  }

  static Future<void> clear() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_storageKey);
  }

  static Future<Map<String, dynamic>> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_storageKey);
    if (raw == null) return {};
    try {
      return Map<String, dynamic>.from(jsonDecode(raw) as Map);
    } catch (_) {
      return {};
    }
  }

  static Future<void> _save(Map<String, dynamic> entries) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_storageKey, jsonEncode(entries));
  }
}
