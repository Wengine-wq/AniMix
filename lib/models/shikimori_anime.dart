class ShikimoriAnime {
  final int id;
  final String? name;
  final String? russian;
  final String? imageUrl;
  final double? score;
  final String? status;
  final String? kind;
  final int? episodes;
  final int? episodesAired;

  ShikimoriAnime.fromJson(Map<String, dynamic> json)
    : id = json['id'],
      name = json['name'],
      russian = json['russian'],
      // 🔥 ФИКС: API отдаёт относительные пути → делаем полный URL
      imageUrl = _buildFullImageUrl(
        json['image']?['original'] ??
            json['image']?['preview'] ??
            json['image']?['x160'] ??
            '',
      ),
      score = double.tryParse(json['score'].toString()),
      status = json['status'],
      kind = json['kind']?.toString(),
      episodes = int.tryParse(json['episodes']?.toString() ?? ''),
      episodesAired = int.tryParse(json['episodes_aired']?.toString() ?? '');

  static String _buildFullImageUrl(String path) {
    if (path.isEmpty) return '';
    // Если уже полный URL — оставляем, иначе добавляем хост
    if (path.startsWith('http')) return path;
    return 'https://shikimori.io$path';
  }
}
