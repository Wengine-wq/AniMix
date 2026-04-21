import 'package:flutter/foundation.dart';

class ShikimoriUser {
  final int id;
  final String nickname;
  final String? avatarUrl;
  final String? imageUrl;
  final String? name;
  final String? sex;           // муж / жен
  final String? birthOn;       // дата рождения
  final String? joinedAt;      // дата регистрации
  final String? lastOnlineAt;
  final int? totalHours; // время за аниме  // последний онлайн
  final int scores;
  final int watched;
  final int planned;
  final int watching;
  final int dropped;
  final int rewatched;
  

  ShikimoriUser.fromJson(Map<String, dynamic> json)
      : id = json['id'] ?? 0,
        nickname = json['nickname'] ?? '',
        avatarUrl = _normalizeUrl(json['image']?['original'] ?? json['image']?['x160']),
        imageUrl = _normalizeUrl(json['image']?['x160']),
        name = json['name'],
        sex = json['sex'],
        birthOn = json['birth_on'],
        joinedAt = json['created_at'],
        lastOnlineAt = json['last_online_at'],
        scores = _parseTotalScores(json),
        watched = _parseStatus(json, 'completed'),
        planned = _parseStatus(json, 'planned'),
        watching = _parseStatus(json, 'watching'),
        dropped = _parseStatus(json, 'dropped'),
        rewatched = _parseStatus(json, 'rewatching'),
        totalHours = json['stats']?['activity']?.isNotEmpty == true
            ? (json['stats']['activity'][0]['value'] as int?) ?? 0
            : 0 {
    debugPrint('📊 FULL USER LOADED: $nickname');
  }

  static int _safeInt(dynamic value) => value is int ? value : 0;

  static int _parseTotalScores(Map<String, dynamic> json) {
    final stats = json['stats'];
    if (stats == null) return 0;
    final scoresData = stats['scores'];
    if (scoresData is Map) {
      return scoresData.values.fold(0, (sum, v) => sum + _safeInt(v));
    }
    return _safeInt(scoresData);
  }

  static int _parseStatus(Map<String, dynamic> json, String neededKey) {
    final stats = json['stats'];
    if (stats == null) return 0;

    // full_statuses (самый точный)
    final full = stats['full_statuses'];
    if (full is Map && full['anime'] is List) {
      for (var item in full['anime']) {
        if (item is Map) {
          final grouped = item['grouped_id']?.toString() ?? '';
          if (grouped == neededKey || grouped.contains(neededKey)) {
            return _safeInt(item['size']);
          }
        }
      }
    }

    // statuses.anime
    final statuses = stats['statuses'];
    if (statuses is Map && statuses['anime'] is List) {
      for (var item in statuses['anime']) {
        if (item is Map) {
          final grouped = item['grouped_id']?.toString() ?? '';
          if (grouped == neededKey || grouped.contains(neededKey)) {
            return _safeInt(item['size']);
          }
        }
      }
    }
    return 0;
  }

  static String? _normalizeUrl(String? url) {
    if (url == null) return null;
    return url.replaceAll('shikimori.one', 'shikimori.io');
  }
}