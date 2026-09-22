import '../core/config.dart';

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
  final int? duration;
  final String? airedOn;
  final String? rating;
  final List<String> genres;

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
      episodesAired = int.tryParse(json['episodes_aired']?.toString() ?? ''),
      duration = int.tryParse(json['duration']?.toString() ?? ''),
      airedOn = json['aired_on']?.toString(),
      rating = json['rating']?.toString(),
      genres = (json['genres'] as List? ?? const [])
          .whereType<Map>()
          .map(
            (genre) =>
                (genre['russian'] ?? genre['name'] ?? '').toString().trim(),
          )
          .where((name) => name.isNotEmpty)
          .toList(growable: false);

  int? get year => int.tryParse(airedOn?.split('-').first ?? '');

  static String _buildFullImageUrl(String path) {
    return Config.proxiedImageUrl(path);
  }
}
