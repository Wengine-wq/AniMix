class ShikimoriComment {
  final int id;
  final String body;
  final String createdAt;
  final String? userNickname;
  final String? userAvatar;

  ShikimoriComment.fromJson(Map<String, dynamic> json)
      : id = json['id'],
        body = json['body_html'] ?? json['body'] ?? '',
        createdAt = json['created_at'] ?? '',
        userNickname = json['user']?['nickname'],
        userAvatar = _normalizeUrl(json['user']?['image']?['x160']);

  static String? _normalizeUrl(String? url) {
    if (url == null) return null;
    return url.replaceAll('shikimori.one', 'shikimori.io');
  }
}