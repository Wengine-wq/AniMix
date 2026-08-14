enum DownloadState { downloading, completed, failed }

class DownloadItem {
  final String episodeId;
  final int animeId;
  final String animeTitle;
  final String episodeName;
  final String? posterUrl;
  final String quality;
  final String? sourceUrl;
  final double progress;
  final DownloadState state;
  final String? localPath;
  final int? fileSizeBytes;
  final String? error;

  const DownloadItem({
    required this.episodeId,
    this.animeId = 0,
    required this.animeTitle,
    required this.episodeName,
    required this.quality,
    this.sourceUrl,
    required this.progress,
    required this.state,
    this.posterUrl,
    this.localPath,
    this.fileSizeBytes,
    this.error,
  });

  DownloadItem copyWith({
    double? progress,
    DownloadState? state,
    String? localPath,
    int? fileSizeBytes,
    String? error,
  }) => DownloadItem(
    episodeId: episodeId,
    animeId: animeId,
    animeTitle: animeTitle,
    episodeName: episodeName,
    posterUrl: posterUrl,
    quality: quality,
    sourceUrl: sourceUrl,
    progress: progress ?? this.progress,
    state: state ?? this.state,
    localPath: localPath ?? this.localPath,
    fileSizeBytes: fileSizeBytes ?? this.fileSizeBytes,
    error: error ?? this.error,
  );

  Map<String, dynamic> toJson() => {
    'episodeId': episodeId,
    'animeId': animeId,
    'animeTitle': animeTitle,
    'episodeName': episodeName,
    'posterUrl': posterUrl,
    'quality': quality,
    'sourceUrl': sourceUrl,
    'progress': progress,
    'state': state.name,
    'localPath': localPath,
    'fileSizeBytes': fileSizeBytes,
    'error': error,
  };

  factory DownloadItem.fromJson(Map<String, dynamic> json) {
    final episodeId = json['episodeId']?.toString() ?? '';
    final parsedAnimeId = int.tryParse(episodeId.split('_').first) ?? 0;
    return DownloadItem(
      episodeId: episodeId,
      animeId: int.tryParse(json['animeId']?.toString() ?? '') ?? parsedAnimeId,
      animeTitle: json['animeTitle']?.toString() ?? '',
      episodeName: json['episodeName']?.toString() ?? '',
      posterUrl: json['posterUrl']?.toString(),
      quality: json['quality']?.toString() ?? 'Авто',
      sourceUrl: json['sourceUrl']?.toString(),
      progress: (json['progress'] as num?)?.toDouble() ?? 0,
      state: DownloadState.values.firstWhere(
        (value) => value.name == json['state'],
        orElse: () => DownloadState.failed,
      ),
      localPath: json['localPath']?.toString(),
      fileSizeBytes: (json['fileSizeBytes'] as num?)?.toInt(),
      error: json['error']?.toString(),
    );
  }
}
