import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/watch_mapping.dart';

class WatchMappingRepository {
  static const _key = 'watch_mappings_v2';

  Future<WatchMapping?> get(String key) async {
    final prefs = await SharedPreferences.getInstance();
    final jsonString = prefs.getString(_key);
    if (jsonString == null) return null;

    final Map<String, dynamic> map = jsonDecode(jsonString);
    return map.containsKey(key) ? WatchMapping.fromJson(map[key]) : null;
  }

  Future<void> save(WatchMapping mapping) async {
    final prefs = await SharedPreferences.getInstance();
    final jsonString = prefs.getString(_key) ?? '{}';
    final Map<String, dynamic> map = jsonDecode(jsonString);

    map[mapping.key] = mapping.toJson();
    await prefs.setString(_key, jsonEncode(map));
  }

  // 🔥 Методы для экрана настроек: получение всех записей и удаление
  Future<List<WatchMapping>> getAll() async {
    final prefs = await SharedPreferences.getInstance();
    final jsonString = prefs.getString(_key) ?? '{}';
    final Map<String, dynamic> map = jsonDecode(jsonString);
    
    final list = map.values.map((e) => WatchMapping.fromJson(e)).toList();
    // Сортируем: новые сверху
    list.sort((a, b) => b.savedAt.compareTo(a.savedAt));
    return list;
  }

  Future<void> delete(String key) async {
    final prefs = await SharedPreferences.getInstance();
    final jsonString = prefs.getString(_key) ?? '{}';
    final Map<String, dynamic> map = jsonDecode(jsonString);

    if (map.containsKey(key)) {
      map.remove(key);
      await prefs.setString(_key, jsonEncode(map));
    }
  }

  Future<void> clearAll() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_key);
  }
}