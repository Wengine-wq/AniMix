import 'package:shared_preferences/shared_preferences.dart';

class WatchStorage {
  // Используем новые ключи (_v2_), чтобы сбросить старые сломанные типы
  static const _watchedKey = 'watched_eps_v2_';
  static const _progressKey = 'progress_v2_';

  static Future<void> markEpisodeWatched(int animeId, String episodeNumber) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_watchedKey$animeId';
    final list = prefs.getStringList(key) ?? [];
    if (!list.contains(episodeNumber)) {
      list.add(episodeNumber);
      await prefs.setStringList(key, list);
    }
  }

  static Future<List<String>> getWatchedEpisodes(int animeId) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getStringList('$_watchedKey$animeId') ?? [];
  }

  static Future<void> saveProgress(int animeId, String episodeNumber, Duration position) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_progressKey${animeId}_$episodeNumber';
    await prefs.setInt(key, position.inSeconds);
  }

  static Future<Duration?> getProgress(int animeId, String episodeNumber) async {
    final prefs = await SharedPreferences.getInstance();
    final seconds = prefs.getInt('$_progressKey${animeId}_$episodeNumber');
    return seconds != null ? Duration(seconds: seconds) : null;
  }
}