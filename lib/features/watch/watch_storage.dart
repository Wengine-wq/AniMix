import 'package:shared_preferences/shared_preferences.dart';


class WatchStorage {
  static const _watchedKey = 'watched_episodes_';
  static const _progressKey = 'progress_';

  static Future<void> markEpisodeWatched(int animeId, int episodeNumber) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_watchedKey$animeId';
    final list = prefs.getStringList(key) ?? [];
    if (!list.contains(episodeNumber.toString())) {
      list.add(episodeNumber.toString());
      await prefs.setStringList(key, list);
    }
  }

  static Future<List<int>> getWatchedEpisodes(int animeId) async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList('$_watchedKey$animeId') ?? [];
    return list.map(int.parse).toList()..sort();
  }

  static Future<void> saveProgress(int animeId, int episodeNumber, Duration position) async {
    final prefs = await SharedPreferences.getInstance();
    final key = '$_progressKey${animeId}_$episodeNumber';
    await prefs.setInt(key, position.inSeconds);
  }

  static Future<Duration?> getProgress(int animeId, int episodeNumber) async {
    final prefs = await SharedPreferences.getInstance();
    final seconds = prefs.getInt('$_progressKey${animeId}_$episodeNumber');
    return seconds != null ? Duration(seconds: seconds) : null;
  }
}