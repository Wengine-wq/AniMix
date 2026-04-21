import 'shikimori_anime.dart';   // ← ОБЯЗАТЕЛЬНЫЙ ИМПОРТ

class ShikimoriAnimeDetail {
  final int id;
  final String? name;
  final String? russian;
  final String? english;
  final String? description;
  final String? imageUrl;
  final double? score;
  final String? status;
  final String? kind;
  final int? episodes;
  final int? episodesAired;
  final String? airedOn;
  final String? releasedOn;
  final List<String> genres;
  final List<String> screenshots;
  final List<ShikimoriAnime> relatedAnimes;
  final List<String> studios;

  ShikimoriAnimeDetail.fromJson(Map<String, dynamic> json)
      : id = json['id'] ?? 0,
        name = _safeString(json['name']),
        russian = _safeString(json['russian']),
        english = _safeString(json['english'] is List ? (json['english'] as List).join(', ') : json['english']),
        description = _cleanDescription(json['description'] ?? json['description_html']),
        imageUrl = _buildFullImageUrl(
          json['image']?['original'] ?? json['image']?['preview'] ?? json['image']?['x160'] ?? '',
        ),
        score = double.tryParse(json['score'].toString()),
        status = _safeString(json['status']),
        kind = _safeString(json['kind']),
        episodes = json['episodes'],
        episodesAired = json['episodes_aired'],
        airedOn = _safeString(json['aired_on']),
        releasedOn = _safeString(json['released_on']),
        genres = (json['genres'] as List? ?? [])
            .map((g) => _safeString(g?['russian'] ?? g?['name']) ?? '')
            .where((g) => g.isNotEmpty)
            .toList(),
        screenshots = (json['screenshots'] as List? ?? [])
            .map((s) => _buildFullImageUrl(s?['original'] ?? s?['preview'] ?? ''))
            .where((url) => url.isNotEmpty)
            .toList(),
        relatedAnimes = (json['related_animes'] as List? ?? [])
            .map((r) => ShikimoriAnime.fromJson(r))
            .toList(),
        studios = (json['studios'] as List? ?? [])
            .map((s) => _safeString(s?['name']) ?? '')
            .where((s) => s.isNotEmpty)
            .toList();

  static String? _safeString(dynamic value) {
    if (value == null) return null;
    if (value is String) return value;
    if (value is List) return value.join(', ');
    return value.toString();
  }

  static String? _cleanDescription(dynamic value) {
    if (value == null) return null;
    String text = value.toString();
    text = text.replaceAll(RegExp(r'\[character=\d+\]'), '');
    text = text.replaceAll(RegExp(r'\[anime=\d+\]'), '');
    text = text.replaceAll(RegExp(r'\[user=\d+\]'), '');
    text = text.replaceAll(RegExp(r'\[/?\w+=\d+\]'), '');
    return text.trim();
  }

  static String _buildFullImageUrl(String path) {
    if (path.isEmpty) return '';
    if (path.startsWith('http')) return path;
    return 'https://shikimori.io$path';
  }
}