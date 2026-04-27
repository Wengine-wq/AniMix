import 'shikimori_anime.dart';   // ← ОБЯЗАТЕЛЬНЫЙ ИМПОРТ

class ShikimoriAnimeDetail {
  final int id;
  final int? topicId; // 🔥 ДОБАВЛЕНО ДЛЯ КОММЕНТАРИЕВ
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
        topicId = json['topic_id'], // 🔥 ПАРСИМ TOPIC ID
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

    // 1. Идеально вычищаем весь сырой HTML (заменяем на пробелы, чтобы слова не слиплись)
    text = text.replaceAll(RegExp(r'<[^>]*>'), ' ');

    // 2. Убираем двойные скобки Шикимори для ссылок, оставляя только текст внутри ( [[Синигами]] -> Синигами )
    text = text.replaceAllMapped(RegExp(r'\[\[(.*?)\]\]'), (Match m) => m[1] ?? '');

    // 3. Вычищаем ВСЕ системные BB-теги Шикимори (открывающие и закрывающие)
    // [character=123], [/character], [anime=..], [b], [/b], [spoiler] и т.д.
    // 🔥 ВАЖНО: Обычные квадратные скобки с текстом или иероглифами (напр. [夜神月]) при этом сохраняются!
    text = text.replaceAll(RegExp(r'\[/?(character|anime|manga|person|user|b|i|u|s|spoiler|quote|size|url)[^\]]*\]', caseSensitive: false), '');

    // 4. Сжимаем множественные пробелы и переносы, оставшиеся после вырезания тегов
    text = text.replaceAll(RegExp(r'\s+'), ' ').trim();

    return text.isEmpty ? null : text;
  }

  static String _buildFullImageUrl(String path) {
    if (path.isEmpty) return '';
    if (path.startsWith('http')) return path;
    return 'https://shikimori.io$path';
  }
}