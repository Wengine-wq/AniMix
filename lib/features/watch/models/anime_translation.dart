class AnimeTranslation {
  final String id;           // уникальный id озвучки
  final String name;         // "AniLibria", "Yummy", "StudioBand" и т.д.
  final String? author;      // автор озвучки
  final int episodesCount;   // сколько серий доступно

  AnimeTranslation({
    required this.id,
    required this.name,
    this.author,
    required this.episodesCount,
  });

  factory AnimeTranslation.fromJson(Map<String, dynamic> json) {
    // будет заполняться позже под каждого провайдера
    return AnimeTranslation(
      id: json['id'] ?? '',
      name: json['name'] ?? '',
      author: json['author'],
      episodesCount: json['episodes_count'] ?? 0,
    );
  }
}