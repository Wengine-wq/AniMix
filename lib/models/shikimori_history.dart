import 'shikimori_anime.dart';

class ShikimoriHistory {
  final int id;
  final String createdAt;
  final String description;
  final ShikimoriAnime? anime;

  ShikimoriHistory.fromJson(Map<String, dynamic> json)
      : id = json['id'] ?? 0,
        createdAt = json['created_at'] ?? '',
        description = _stripHtml(json['description_html'] ?? json['description'] ?? 'Действие'),
        anime = json['target'] != null && json['target'] is Map
            ? (json['target']['anime'] != null
                ? ShikimoriAnime.fromJson(json['target']['anime'])
                : ShikimoriAnime.fromJson(json['target']))
            : null;

  static String _stripHtml(String input) {
    if (input.isEmpty) return input;
    String text = input.replaceAll(RegExp(r'<[^>]*>'), ' ');
    text = text.replaceAll(RegExp(r'\s+'), ' ').trim();
    return text;
  }
}