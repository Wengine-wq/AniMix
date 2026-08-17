import 'package:flutter/foundation.dart';

class ShikimoriUser {
  final int id;
  final String nickname;
  final String? avatarUrl;
  final String? imageUrl;
  final String? name;
  final String? sex; // муж / жен
  final String? birthOn; // дата рождения
  final String? joinedAt; // дата регистрации
  final String? lastOnlineAt;
  final int scores;
  final int watched;
  final int planned;
  final int watching;
  final int onHold;
  final int dropped;
  final int rewatched;

  ShikimoriUser.fromJson(Map<String, dynamic> json)
    : id = _safeInt(json['id']),
      nickname = _safeString(json['nickname']),
      avatarUrl = _normalizeUrl(
        _imageValue(json['image'], const ['original', 'x160']),
      ),
      imageUrl = _normalizeUrl(
        _imageValue(json['image'], const ['x160', 'original']),
      ),
      name = _nullableString(json['name']),
      sex = _nullableString(json['sex']),
      birthOn = _nullableString(json['birth_on']),
      joinedAt = _nullableString(json['created_at']),
      lastOnlineAt = _nullableString(json['last_online_at']),
      scores = _parseTotalScores(json),
      watched = _parseStatus(json, 'completed'),
      planned = _parseStatus(json, 'planned'),
      watching = _parseStatus(json, 'watching'),
      onHold = _parseStatus(json, 'on_hold'),
      dropped = _parseStatus(json, 'dropped'),
      rewatched = _parseStatus(json, 'rewatching') {
    debugPrint('📊 FULL USER LOADED: $nickname');
  }

  static int _safeInt(dynamic value) {
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  static String _safeString(dynamic value) => value?.toString() ?? '';

  static String? _nullableString(dynamic value) {
    final result = value?.toString().trim();
    return result == null || result.isEmpty ? null : result;
  }

  static dynamic _imageValue(dynamic image, List<String> preferredKeys) {
    if (image is String) return image;
    if (image is! Map) return null;
    for (final key in preferredKeys) {
      final value = image[key];
      if (value?.toString().trim().isNotEmpty == true) return value;
    }
    return null;
  }

  static int _parseTotalScores(Map<String, dynamic> json) {
    final stats = json['stats'];
    if (stats is! Map) return 0;
    final scoresData = stats['scores'];
    if (scoresData is List) {
      return _sumScoreDistribution(scoresData);
    }
    if (scoresData is Map) {
      final anime = scoresData['anime'];
      if (anime is List) return _sumScoreDistribution(anime);
      return scoresData.values.fold<int>(0, (sum, value) {
        if (value is List) return sum + _sumScoreDistribution(value);
        return sum + _safeInt(value);
      });
    }
    return _safeInt(scoresData);
  }

  static int _sumScoreDistribution(List<dynamic> items) {
    return items.fold<int>(0, (sum, item) {
      if (item is! Map) return sum;
      return sum + _safeInt(item['value'] ?? item['size']);
    });
  }

  static int _parseStatus(Map<String, dynamic> json, String neededKey) {
    final stats = json['stats'];
    if (stats is! Map) return 0;

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

  static String? _normalizeUrl(dynamic rawUrl) {
    final value = rawUrl?.toString().trim();
    if (value == null || value.isEmpty) return null;
    final url = value.replaceAll('shikimori.one', 'shikimori.io');
    if (url.startsWith('http://') || url.startsWith('https://')) return url;
    if (url.startsWith('//')) return 'https:$url';
    return 'https://shikimori.io/${url.replaceFirst(RegExp(r'^/+'), '')}';
  }
}
