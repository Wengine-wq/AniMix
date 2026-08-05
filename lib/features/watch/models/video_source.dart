class VideoSource {
  final String label;
  final Uri uri;
  final int bandwidth;

  const VideoSource({
    required this.label,
    required this.uri,
    this.bandwidth = 0,
  });
}
