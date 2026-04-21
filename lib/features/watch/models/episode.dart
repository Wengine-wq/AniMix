class Episode {
  final int number;
  final String title;
  final String? videoUrl;           // заполнится позже
  final Duration? duration;         // если известно
  bool isWatched;                   // локально
  Duration? lastPosition;           // прогресс

  Episode({
    required this.number,
    required this.title,
    this.videoUrl,
    this.duration,
    this.isWatched = false,
    this.lastPosition,
  });
}