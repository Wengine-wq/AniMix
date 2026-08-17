class ShikimoriComment {
  final int id;
  final int?
  userId; // 🔥 Нужен для того, чтобы правильно отвечать на комментарий
  final String body;
  final String htmlBody;
  final String createdAt;
  final String? userNickname;
  final String? userAvatar;
  final bool isOfftopic;
  final bool isSummary;
  final bool canBeEdited;

  ShikimoriComment.fromJson(Map<String, dynamic> json)
    : id = int.tryParse(json['id']?.toString() ?? '') ?? 0,
      userId = int.tryParse(
        (json['user']?['id'] ?? json['user_id'])?.toString() ?? '',
      ),
      body = json['body']?.toString() ?? '',
      // Shikimori resolves uploaded image IDs, smileys, mentions and the
      // complete BBCode grammar on the server. Keeping this field prevents the
      // client from having to guess URLs such as [image=1678532].
      htmlBody = (json['html_body'] ?? json['body_html'])?.toString() ?? '',
      createdAt = json['created_at']?.toString() ?? '',
      userNickname = json['user']?['nickname']?.toString(),
      userAvatar = _normalizeUrl(
        json['user']?['image']?['x160'] ?? json['user']?['avatar'],
      ),
      isOfftopic = _safeBool(json['is_offtopic']),
      isSummary = _safeBool(json['is_summary']),
      canBeEdited = _safeBool(json['can_be_edited']);

  bool get hasMedia => RegExp(
    r'(\[image(?:=|\])|\[video(?:=|\])|<img\b|<video\b|https?://\S+\.(?:png|jpe?g|gif|webp))',
    caseSensitive: false,
  ).hasMatch('$body $htmlBody');

  bool get isReply => RegExp(
    r'\[comment=\d+(?:;[^\]]*)?\]',
    caseSensitive: false,
  ).hasMatch(body);

  static bool _safeBool(dynamic value) =>
      value == true || value == 1 || value?.toString().toLowerCase() == 'true';

  static String? _normalizeUrl(dynamic rawUrl) {
    final value = rawUrl?.toString().trim();
    if (value == null || value.isEmpty) return null;
    final url = value.replaceAll('shikimori.one', 'shikimori.io');
    if (url.startsWith('http')) return url;
    if (url.startsWith('//')) return 'https:$url';
    return 'https://shikimori.io/${url.replaceFirst(RegExp(r'^/+'), '')}';
  }
}
