import 'package:flutter/foundation.dart';

import '../core/config.dart';

class ShikimoriUser {
  final int id;
  final String nickname;
  final String? avatarUrl;
  final String? imageUrl;
  final String? bannerUrl;
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
  final bool isAniMix;
  final bool shikimoriLinked;
  final String? shikimoriUserId;

  ShikimoriUser.fromJson(Map<String, dynamic> json)
    : id = _safeInt(json['id']),
      nickname = _safeString(json['nickname']),
      avatarUrl = _normalizeUrl(
        _imageValue(json['image'], const ['original', 'x160']),
      ),
      imageUrl = _normalizeUrl(
        _imageValue(json['image'], const ['x160', 'original']),
      ),
      bannerUrl = null,
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
      rewatched = _parseStatus(json, 'rewatching'),
      isAniMix = false,
      shikimoriLinked = true,
      shikimoriUserId = _safeInt(json['id']).toString() {
    debugPrint('📊 FULL USER LOADED: $nickname');
  }

  ShikimoriUser.localFromAniMixJson(Map<String, dynamic> json)
    : id = 0,
      nickname = (json['display_name'] ?? json['email'] ?? 'AniMix User')
          .toString(),
      avatarUrl = _nullableString(json['avatar_url']),
      imageUrl = _nullableString(json['avatar_url']),
      bannerUrl = _nullableString(json['banner_url']),
      name = _nullableString(json['display_name']),
      sex = null,
      birthOn = null,
      joinedAt = _timestampString(json['created_at']),
      lastOnlineAt = _timestampString(json['last_online_at']),
      scores = _stat(json, 'scores'),
      watched = _stat(json, 'completed'),
      planned = _stat(json, 'planned'),
      watching = _stat(json, 'watching'),
      onHold = _stat(json, 'on_hold'),
      dropped = _stat(json, 'dropped'),
      rewatched = _stat(json, 'rewatching'),
      isAniMix = true,
      shikimoriLinked = json['shikimori_linked'] == true,
      shikimoriUserId = _nullableString(json['shikimori_user_id']);

  static int _stat(Map<String, dynamic> json, String key) {
    final stats = json['stats'];
    if (stats is Map) {
      final value = stats[key];
      if (value is num) return value.toInt();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }
    return 0;
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

  static String? _timestampString(dynamic value) {
    if (value == null) return null;
    if (value is num || RegExp(r'^\d{9,13}$').hasMatch(value.toString())) {
      final raw = value is num ? value.toInt() : int.tryParse(value.toString());
      if (raw == null || raw <= 0) return null;
      final milliseconds = raw < 100000000000
          ? raw * 1000
          : raw < 100000000000000
          ? raw
          : raw < 100000000000000000
          ? raw ~/ 1000
          : raw ~/ 1000000;
      return DateTime.fromMillisecondsSinceEpoch(
        milliseconds,
        isUtc: true,
      ).toIso8601String();
    }
    final parsed = DateTime.tryParse(value.toString());
    return parsed?.toIso8601String() ?? _nullableString(value);
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
    return Config.proxiedImageUrl(value);
  }
}
