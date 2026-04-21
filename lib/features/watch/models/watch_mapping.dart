class WatchMapping {
  final int shikimoriId;
  final String provider; // 'anilibria' | 'yummy_kodik'
  final String releaseId;
  final String releaseTitle;
  final String? posterUrl;
  final DateTime savedAt;

  WatchMapping({
    required this.shikimoriId,
    required this.provider,
    required this.releaseId,
    required this.releaseTitle,
    this.posterUrl,
    required this.savedAt,
  });

  String get key => '${shikimoriId}_$provider';

  Map<String, dynamic> toJson() => {
        'shikimoriId': shikimoriId,
        'provider': provider,
        'releaseId': releaseId,
        'releaseTitle': releaseTitle,
        'posterUrl': posterUrl,
        'savedAt': savedAt.toIso8601String(),
      };

  factory WatchMapping.fromJson(Map<String, dynamic> json) => WatchMapping(
        shikimoriId: json['shikimoriId'],
        provider: json['provider'],
        releaseId: json['releaseId'],
        releaseTitle: json['releaseTitle'],
        posterUrl: json['posterUrl'],
        savedAt: DateTime.parse(json['savedAt']),
      );
}