import 'dart:math' as math;
import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:dio/dio.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../models/shikimori_comment.dart';
import '../../providers/user_provider.dart';

// Актуальный домен Шикимори
const String _shikiUrl = 'https://shikimori.io';

// =========================================================================================
// СЕРВИС МЕДИА (КЭШИРОВАНИЕ И ПОИСК ИЗОБРАЖЕНИЙ)
// =========================================================================================
class ShikiMediaService {
  static final Map<String, String> _cache = {};

  static Future<String?> resolveImageUrl(String id, {bool isPoster = false}) async {
    if (_cache.containsKey(id)) return _cache[id];

    final dio = Dio();

    // 1. Попытка API запроса (Концепция извлечения JSON данных)
    try {
      final res = await dio.get('$_shikiUrl/api/forum/critiques/image=$id', 
          options: Options(validateStatus: (s) => true));
      if (res.statusCode == 200 && res.data != null) {
        final data = res.data is String ? jsonDecode(res.data) : res.data;
        final url = data['preview_url'] ?? data['original_url'] ?? data['url'];
        if (url != null) {
          final fullUrl = url.toString().startsWith('http') ? url : '$_shikiUrl$url';
          _cache[id] = fullUrl;
          return fullUrl;
        }
      }
    } catch (_) {}

    // 2. Умный HEAD-поиск (Если API не отдал ссылку, ищем по расширениям)
    final paths = isPoster 
        ? ['/system/images/original/$id.jpg', '/system/images/original/$id.png', '/system/images/original/$id.gif']
        : ['/system/forum/images/$id.jpg', '/system/forum/images/$id.png', '/system/forum/images/$id.gif'];

    for (final path in paths) {
      try {
        final testUrl = '$_shikiUrl$path';
        final res = await dio.head(testUrl, options: Options(validateStatus: (s) => true));
        if (res.statusCode == 200 || res.statusCode == 301 || res.statusCode == 302) {
          _cache[id] = testUrl;
          return testUrl;
        }
      } catch (_) {}
    }

    return null;
  }
}

// =========================================================================================
// ГЛАВНЫЙ ЭКРАН КОММЕНТАРИЕВ
// =========================================================================================
class CommentsScreen extends StatefulHookConsumerWidget {
  final int topicId;
  const CommentsScreen({required this.topicId, super.key});

  @override
  ConsumerState<CommentsScreen> createState() => _CommentsScreenState();
}

class _CommentsScreenState extends ConsumerState<CommentsScreen> {
  List<ShikimoriComment> commentsList = [];
  int _commentsPage = 1;
  int _totalCommentsCount = 0;
  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _hasMore = true;

  ShikimoriComment? _replyingTo;

  @override
  void initState() {
    super.initState();
    _fetchTopicInfo();
    _loadComments();
  }

  Future<void> _fetchTopicInfo() async {
    try {
      final res = await Dio().get('$_shikiUrl/api/topics/${widget.topicId}');
      if (mounted) {
        setState(() {
          _totalCommentsCount = res.data['comments_count'] ?? 0;
        });
      }
    } catch (e) {
      debugPrint('Failed to load topic info: $e');
    }
  }

  Future<void> _loadComments() async {
    try {
      final api = ref.read(apiClientProvider);
      final comms = await api.getComments(widget.topicId, page: 1);
      if (mounted) {
        setState(() {
          commentsList = comms;
          _hasMore = comms.length >= 30;
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Ошибка загрузки комментов: $e');
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _loadMoreComments() async {
    if (_isLoadingMore || !_hasMore) return;
    setState(() => _isLoadingMore = true);
    
    try {
      final api = ref.read(apiClientProvider);
      final more = await api.getComments(widget.topicId, page: _commentsPage + 1);
      if (mounted) {
        setState(() {
          _commentsPage++;
          commentsList.addAll(more);
          _hasMore = more.length >= 30;
        });
      }
    } catch (e) {
      debugPrint('Ошибка подгрузки комментов: $e');
    } finally {
      if (mounted) setState(() => _isLoadingMore = false);
    }
  }

  Future<bool> _postComment(String text) async {
    if (text.trim().isEmpty) return false;

    try {
      final api = ref.read(apiClientProvider);
      String finalBody = text.trim();
      
      if (_replyingTo != null) {
        finalBody = '[comment=${_replyingTo!.id};${_replyingTo!.userId ?? 0}]${_replyingTo!.userNickname}[/comment], $finalBody';
      }

      final newComment = await api.postComment(widget.topicId, finalBody);
      
      if (mounted) {
        setState(() {
          commentsList.insert(0, newComment);
          _totalCommentsCount++;
          _replyingTo = null;
        });
      }
      return true;
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ошибка отправки: $e'), backgroundColor: Colors.redAccent));
      }
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final Set<int> loadedCommentIds = commentsList.map((c) => c.id).toSet();
    final Map<int, List<ShikimoriComment>> repliesMap = {};
    
    for (var c in commentsList) {
      final match = RegExp(r'\[comment=[^\]]+\]').firstMatch(c.body);
      if (match != null) {
        final idMatch = RegExp(r'\d+').firstMatch(match.group(0)!);
        if (idMatch != null) {
          final parentId = int.tryParse(idMatch.group(0)!);
          if (parentId != null) {
            repliesMap[parentId] ??= [];
            repliesMap[parentId]!.add(c);
          }
        }
      }
    }

    final rootComments = commentsList.where((c) {
      final match = RegExp(r'\[comment=[^\]]+\]').firstMatch(c.body);
      if (match != null) {
        final idMatch = RegExp(r'\d+').firstMatch(match.group(0)!);
        if (idMatch != null) {
          final parentId = int.tryParse(idMatch.group(0)!);
          if (loadedCommentIds.contains(parentId)) return false;
        }
      }
      return true;
    }).toList();

    return Scaffold(
      backgroundColor: const Color(0xFF09090B),
      appBar: AppBar(
        backgroundColor: const Color(0xFF09090B).withOpacity(0.95),
        elevation: 0,
        surfaceTintColor: Colors.transparent,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Комментарии', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white)),
            if (_totalCommentsCount > 0)
              Text('Всего: $_totalCommentsCount', style: const TextStyle(fontSize: 12, color: CupertinoColors.systemGrey)),
          ],
        ),
        leading: IconButton(icon: const Icon(CupertinoIcons.back, color: Colors.white), onPressed: () => Navigator.pop(context)),
      ),
      body: Column(
        children: [
          Expanded(
            child: _isLoading 
              ? const Center(child: CupertinoActivityIndicator(radius: 16))
              : rootComments.isEmpty
                ? const Center(child: Text('Здесь пока пусто. Станьте первым!', style: TextStyle(color: CupertinoColors.systemGrey)))
                : ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
                    itemCount: rootComments.length + 1,
                    itemBuilder: (context, index) {
                      if (index == rootComments.length) {
                        if (!_hasMore) return const SizedBox(height: 40);
                        
                        final remaining = math.max(0, _totalCommentsCount - commentsList.length);
                        final btnText = remaining > 0 ? 'Загрузить ещё (осталось $remaining)' : 'Загрузить предыдущие';

                        return Padding(
                          padding: const EdgeInsets.only(top: 10.0, bottom: 40.0),
                          child: CupertinoButton(
                            color: const Color(0xFF1C1C1E),
                            borderRadius: BorderRadius.circular(14),
                            onPressed: _loadMoreComments,
                            child: _isLoadingMore 
                              ? const CupertinoActivityIndicator() 
                              : Text(btnText, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
                          ),
                        );
                      }

                      final c = rootComments[index];
                      final replies = repliesMap[c.id] ?? [];

                      return _CommentCard(
                        topicId: widget.topicId,
                        comment: c, 
                        replies: replies,
                        allComments: commentsList,
                        onReplyTap: () => setState(() => _replyingTo = c),
                        onNewReplyAdded: (newC) => setState(() {
                          commentsList.insert(0, newC);
                          _totalCommentsCount++;
                        }),
                      );
                    },
                  ),
          ),
          
          _CommentInputBar(
            replyTo: _replyingTo,
            onCancelReply: () => setState(() => _replyingTo = null),
            onSend: _postComment,
          ),
        ],
      ),
    );
  }
}

// =========================================================================================
// КАРТОЧКА КОММЕНТАРИЯ
// =========================================================================================
class _CommentCard extends StatelessWidget {
  final int topicId;
  final ShikimoriComment comment;
  final List<ShikimoriComment> replies;
  final List<ShikimoriComment> allComments;
  final VoidCallback onReplyTap;
  final Function(ShikimoriComment)? onNewReplyAdded;
  final bool isInsideThread;

  const _CommentCard({
    required this.topicId,
    required this.comment, 
    required this.replies, 
    required this.allComments,
    required this.onReplyTap,
    this.onNewReplyAdded,
    this.isInsideThread = false,
  });

  String _formatDate(String isoDate) {
    if (isoDate.isEmpty) return '';
    try {
      final date = DateTime.parse(isoDate).toLocal();
      final now = DateTime.now();
      final diff = now.difference(date);
      if (diff.inDays == 0) return 'сегодня в ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
      if (diff.inDays == 1) return 'вчера в ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
      if (diff.inDays < 7) return '${diff.inDays} дн. назад';
      final months = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
      return '${date.day} ${months[date.month - 1]} ${date.year}';
    } catch (_) { return ''; }
  }

  @override
  Widget build(BuildContext context) {
    final c = comment;
    final cleanBBCode = _BBCodeParser.cleanHtmlToBBCode(c.body);
    if (cleanBBCode.isEmpty) return const SizedBox();

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF18181B),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withOpacity(0.05)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 20,
            backgroundColor: const Color(0xFF2A2A2A),
            backgroundImage: c.userAvatar != null ? CachedNetworkImageProvider(c.userAvatar!) : null,
            child: c.userAvatar == null ? const Icon(CupertinoIcons.person_fill, color: Colors.grey, size: 20) : null,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Text(
                        c.userNickname ?? 'Аноним', 
                        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 15), 
                        overflow: TextOverflow.ellipsis
                      )
                    ),
                    Text(_formatDate(c.createdAt), style: const TextStyle(color: CupertinoColors.systemGrey2, fontSize: 12)),
                  ],
                ),
                const SizedBox(height: 6),
                
                // Рендер текста
                Text.rich(
                  TextSpan(children: _BBCodeParser.buildSpans(context, cleanBBCode)),
                ),
                
                const SizedBox(height: 14),
                
                Row(
                  children: [
                    GestureDetector(
                      onTap: onReplyTap,
                      behavior: HitTestBehavior.opaque,
                      child: Container(
                        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 12),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.05),
                          borderRadius: BorderRadius.circular(20)
                        ),
                        child: const Row(
                          children: [
                            Icon(CupertinoIcons.reply, color: CupertinoColors.systemGrey, size: 14),
                            SizedBox(width: 6),
                            Text('Ответить', style: TextStyle(color: CupertinoColors.systemGrey, fontSize: 13, fontWeight: FontWeight.w600)),
                          ],
                        ),
                      ),
                    ),
                    const Spacer(),
                    if (replies.isNotEmpty && !isInsideThread)
                      GestureDetector(
                        onTap: () => Navigator.of(context, rootNavigator: true).push(
                          CupertinoPageRoute(
                            builder: (_) => _CommentThreadScreen(
                              topicId: topicId,
                              parentComment: c, 
                              allComments: allComments,
                              onNewReplyAdded: onNewReplyAdded,
                            )
                          ),
                        ),
                        behavior: HitTestBehavior.opaque,
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 12),
                          decoration: BoxDecoration(
                            color: const Color(0xFFFF5722).withOpacity(0.1),
                            borderRadius: BorderRadius.circular(20)
                          ),
                          child: Row(
                            children: [
                              Text('${replies.length} ответ(а)', style: const TextStyle(color: Color(0xFFFF5722), fontSize: 13, fontWeight: FontWeight.bold)),
                              const SizedBox(width: 4),
                              const Icon(CupertinoIcons.chevron_right, color: Color(0xFFFF5722), size: 12),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// =========================================================================================
// ЭКРАН ВЕТКИ ОТВЕТОВ
// =========================================================================================
class _CommentThreadScreen extends StatefulWidget {
  final int topicId;
  final ShikimoriComment parentComment;
  final List<ShikimoriComment> allComments;
  final Function(ShikimoriComment)? onNewReplyAdded;

  const _CommentThreadScreen({required this.topicId, required this.parentComment, required this.allComments, this.onNewReplyAdded});

  @override
  State<_CommentThreadScreen> createState() => _CommentThreadScreenState();
}

class _CommentThreadScreenState extends State<_CommentThreadScreen> {
  late List<ShikimoriComment> localAllComments;
  ShikimoriComment? _replyingTo;

  @override
  void initState() {
    super.initState();
    localAllComments = List.from(widget.allComments);
    _replyingTo = widget.parentComment; 
  }

  Future<bool> _postComment(String text) async {
    if (text.trim().isEmpty) return false;

    try {
      final api = ProviderScope.containerOf(context).read(apiClientProvider);
      String finalBody = text.trim();
      
      if (_replyingTo != null) {
        finalBody = '[comment=${_replyingTo!.id};${_replyingTo!.userId ?? 0}]${_replyingTo!.userNickname}[/comment], $finalBody';
      }

      final newComment = await api.postComment(widget.topicId, finalBody);
      
      if (mounted) {
        setState(() {
          localAllComments.insert(0, newComment);
          _replyingTo = widget.parentComment; 
        });
        widget.onNewReplyAdded?.call(newComment);
      }
      return true;
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ошибка: $e')));
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final replies = localAllComments.where((c) {
      final match = RegExp(r'\[comment=[^\]]+\]').firstMatch(c.body);
      if (match != null) {
         final idMatch = RegExp(r'\d+').firstMatch(match.group(0)!);
         return idMatch != null && idMatch.group(0) == widget.parentComment.id.toString();
      }
      return false;
    }).toList();
    
    final sortedReplies = replies.reversed.toList();

    return Scaffold(
      backgroundColor: const Color(0xFF09090B),
      appBar: AppBar(
        backgroundColor: const Color(0xFF09090B).withOpacity(0.95),
        title: const Text('Ветка ответов', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white)),
        leading: IconButton(icon: const Icon(CupertinoIcons.back, color: Colors.white), onPressed: () => Navigator.pop(context)),
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _CommentCard(
                  topicId: widget.topicId,
                  comment: widget.parentComment, 
                  replies: const [], 
                  allComments: const [], 
                  onReplyTap: () => setState(() => _replyingTo = widget.parentComment), 
                  isInsideThread: true,
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8, horizontal: 16),
                  child: Divider(color: Color(0xFF2A2A2A), thickness: 1),
                ),
                
                if (sortedReplies.isEmpty)
                  const Text('Больше нет ответов', style: TextStyle(color: CupertinoColors.systemGrey), textAlign: TextAlign.center)
                else
                  ...sortedReplies.map((r) => Padding(
                    padding: const EdgeInsets.only(left: 32), 
                    child: _CommentCard(
                      topicId: widget.topicId,
                      comment: r, 
                      replies: const [], 
                      allComments: const [], 
                      onReplyTap: () => setState(() => _replyingTo = r), 
                      isInsideThread: true,
                    ),
                  )),
              ],
            ),
          ),

          _CommentInputBar(
            replyTo: _replyingTo,
            onCancelReply: () => setState(() => _replyingTo = null),
            onSend: _postComment,
          ),
        ],
      ),
    );
  }
}

// =========================================================================================
// ПАНЕЛЬ ВВОДА С ИНСТРУМЕНТАМИ
// =========================================================================================
class _CommentInputBar extends StatefulWidget {
  final ShikimoriComment? replyTo;
  final VoidCallback onCancelReply;
  final Future<bool> Function(String) onSend;

  const _CommentInputBar({required this.replyTo, required this.onCancelReply, required this.onSend});

  @override
  State<_CommentInputBar> createState() => _CommentInputBarState();
}

class _CommentInputBarState extends State<_CommentInputBar> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  bool _isSending = false;
  bool _showEmoji = false;

  void _insertTag(String tag) {
    final text = _controller.text;
    final selection = _controller.selection;
    if (selection.start == -1) return;
    
    final selectedText = selection.textInside(text);
    final newText = text.replaceRange(selection.start, selection.end, '[$tag]$selectedText[/$tag]');
    _controller.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: selection.start + tag.length + 2 + selectedText.length + tag.length + 3),
    );
  }

  void _insertText(String str) {
    final text = _controller.text;
    final selection = _controller.selection;
    final offset = selection.start == -1 ? text.length : selection.start;
    
    final newText = text.replaceRange(offset, selection.end == -1 ? text.length : selection.end, str);
    _controller.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: offset + str.length),
    );
  }

  void _insertImagePrompt() {
    final urlController = TextEditingController();
    showCupertinoDialog(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Вставить медиа'),
        content: Padding(
          padding: const EdgeInsets.only(top: 8.0),
          child: CupertinoTextField(
            controller: urlController,
            placeholder: 'URL картинки или видео...',
            style: const TextStyle(color: Colors.white),
          ),
        ),
        actions: [
          CupertinoDialogAction(child: const Text('Отмена'), onPressed: () => Navigator.pop(ctx)),
          CupertinoDialogAction(
            child: const Text('Добавить', style: TextStyle(color: Color(0xFFFF5722))),
            onPressed: () {
              Navigator.pop(ctx);
              final url = urlController.text.trim();
              if (url.isNotEmpty) {
                if (url.contains('youtube.com') || url.contains('youtu.be')) {
                  _insertText('[video]$url[/video]');
                } else if (url.endsWith('.jpg') || url.endsWith('.png') || url.endsWith('.gif') || url.endsWith('.webp')) {
                  _insertText('[img]$url[/img]');
                } else {
                  _insertText('[url=$url]Ссылка[/url]');
                }
              }
            }
          ),
        ],
      ),
    );
  }

  Future<void> _handleSend() async {
    if (_controller.text.trim().isEmpty) return;
    setState(() => _isSending = true);
    
    final success = await widget.onSend(_controller.text);
    
    if (mounted) {
      setState(() => _isSending = false);
      if (success) {
        _controller.clear();
        _showEmoji = false;
        _focusNode.unfocus();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF18181B),
        border: Border(top: BorderSide(color: Colors.white.withOpacity(0.05))),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.replyTo != null)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                color: const Color(0xFFFF5722).withOpacity(0.1),
                child: Row(
                  children: [
                    const Icon(CupertinoIcons.reply, color: Color(0xFFFF5722), size: 16),
                    const SizedBox(width: 8),
                    Expanded(child: Text('Ответ ${widget.replyTo!.userNickname}', style: const TextStyle(color: Color(0xFFFF5722), fontWeight: FontWeight.bold, fontSize: 13))),
                    GestureDetector(
                      onTap: widget.onCancelReply,
                      child: const Icon(CupertinoIcons.clear_thick, color: Color(0xFFFF5722), size: 16),
                    ),
                  ],
                ),
              ),
              
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: Container(
                      constraints: const BoxConstraints(maxHeight: 120),
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      decoration: BoxDecoration(
                        color: const Color(0xFF27272A), 
                        borderRadius: BorderRadius.circular(24),
                        border: Border.all(color: Colors.white.withOpacity(0.1))
                      ),
                      child: TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        maxLines: null,
                        keyboardType: TextInputType.multiline,
                        onTap: () { if (_showEmoji) setState(() => _showEmoji = false); },
                        style: const TextStyle(color: Colors.white, fontSize: 15),
                        decoration: const InputDecoration(
                          hintText: 'Написать комментарий...',
                          hintStyle: TextStyle(color: CupertinoColors.systemGrey),
                          border: InputBorder.none,
                          isDense: true,
                          contentPadding: EdgeInsets.symmetric(vertical: 12),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  GestureDetector(
                    onTap: _isSending ? null : _handleSend,
                    child: Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(colors: [Color(0xFFFF5722), Color(0xFFFF8A65)]),
                        borderRadius: BorderRadius.circular(22),
                        boxShadow: [BoxShadow(color: const Color(0xFFFF5722).withOpacity(0.3), blurRadius: 8, offset: const Offset(0, 4))]
                      ),
                      child: _isSending
                          ? const CupertinoActivityIndicator(color: Colors.white)
                          : const Icon(CupertinoIcons.paperplane_fill, color: Colors.white, size: 20),
                    ),
                  ),
                ],
              ),
            ),

            Container(
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
              child: Row(
                children: [
                  _ToolBtn(icon: CupertinoIcons.bold, onTap: () => _insertTag('b')),
                  const SizedBox(width: 8),
                  _ToolBtn(icon: CupertinoIcons.italic, onTap: () => _insertTag('i')),
                  const SizedBox(width: 8),
                  _ToolBtn(icon: CupertinoIcons.strikethrough, onTap: () => _insertTag('s')),
                  const SizedBox(width: 8),
                  _ToolBtn(icon: CupertinoIcons.link, onTap: _insertImagePrompt),
                  const SizedBox(width: 12),
                  GestureDetector(
                    onTap: () => _insertTag('spoiler'),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(color: Colors.white.withOpacity(0.05), borderRadius: BorderRadius.circular(8)),
                      child: const Text('СПОЙЛЕР', style: TextStyle(color: Colors.white70, fontWeight: FontWeight.bold, fontSize: 11)),
                    ),
                  ),
                  const Spacer(),
                  GestureDetector(
                    onTap: () {
                      if (_showEmoji) {
                        _focusNode.requestFocus();
                        setState(() => _showEmoji = false);
                      } else {
                        _focusNode.unfocus();
                        setState(() => _showEmoji = true);
                      }
                    },
                    child: Icon(_showEmoji ? CupertinoIcons.keyboard : CupertinoIcons.smiley, color: _showEmoji ? const Color(0xFFFF5722) : CupertinoColors.systemGrey, size: 26),
                  ),
                ],
              ),
            ),
            
            if (_showEmoji)
              SizedBox(
                height: 260,
                child: _EmojiGrid(onEmojiSelected: (e) => _insertText(e)),
              ),
          ],
        ),
      ),
    );
  }
}

class _ToolBtn extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  const _ToolBtn({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(color: const Color(0xFF27272A), borderRadius: BorderRadius.circular(8)),
        child: Icon(icon, color: Colors.white70, size: 16),
      ),
    );
  }
}

// =========================================================================================
// АКТУАЛЬНАЯ БАЗА ЭМОДЗИ С НАТИВНЫМ ОТОБРАЖЕНИЕМ
// =========================================================================================
class _EmojiGrid extends StatelessWidget {
  final Function(String) onEmojiSelected;
  const _EmojiGrid({required this.onEmojiSelected});

  @override
  Widget build(BuildContext context) {
    final standardEmojis = [
      ':pepe:', ':aww:', ':lol:', ':facepalm:', ':kiss:', ':cry:', ':evil:', ':hopeless:',
      ':yummy:', ':smirk:', ':bored:', ':ololo:', ':shy:', ':wow:', ':smile:', ':D',
      ':(', '+_(', ':|', ':\\'
    ];

    // Генерируем с запасом. Несуществующие скроются сами
    final onionEmojis = List.generate(200, (i) => ':v${200 + i}:');
    final allEmojis = [...standardEmojis, ...onionEmojis];

    return Container(
      color: const Color(0xFF18181B),
      child: GridView.builder(
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 5, 
          mainAxisSpacing: 16, 
          crossAxisSpacing: 16
        ),
        itemCount: allEmojis.length,
        itemBuilder: (context, index) {
          final code = allEmojis[index];
          final url = _BBCodeParser.getEmojiUrl(code);
          
          return GestureDetector(
            onTap: () => onEmojiSelected(code),
            // Используем Image.network напрямую, он лучше крутит гифки и проще глушит 404
            child: Image.network(
              url,
              fit: BoxFit.contain,
              errorBuilder: (context, error, stackTrace) => const SizedBox.shrink(),
            ),
          );
        },
      ),
    );
  }
}

// =========================================================================================
// УЛУЧШЕННЫЙ ПАРСЕР (HTML, СПОЙЛЕРЫ, ЦИТАТЫ, КАРТИНКИ, ВИДЕО, УПОМИНАНИЯ)
// =========================================================================================
class _BBCodeParser {
  static String cleanHtmlToBBCode(String html) {
    String t = html;
    
    // 1. Извлекаем прямые ссылки на эмодзи из HTML (чтобы не ломать их реальные расширения .gif / .png)
    t = t.replaceAllMapped(RegExp(r'<img[^>]*src="([^"]*smileys[^"]+)"[^>]*>', caseSensitive: false), (m) {
      String url = m.group(1)!;
      if (url.startsWith('/')) url = '$_shikiUrl$url';
      return '[emoji_url=$url]';
    });

    // 2. Игнорируем замену [image=...] и [poster=...], чтобы затем передать их в виджет
    // Оставляем их текстом: [image=123] -> [image=123]

    t = t.replaceAll('<br>', '\n').replaceAll('<br/>', '\n').replaceAll('<br />', '\n');
    t = t.replaceAll('</p><p>', '\n\n').replaceAll('<p>', '').replaceAll('</p>', '');
    t = t.replaceAll(RegExp(r'<div class="b-text_with_paragraphs">|<\/div>'), '');

    // 3. Спойлеры
    t = t.replaceAllMapped(
      RegExp(r'<div class="b-spoiler_block"[^>]*>.*?<span>(.*?)</span>.*?<div class="inside">(.*?)</div>\s*</div>', dotAll: true),
      (m) => '[spoiler=${m.group(1)}]${m.group(2)}[/spoiler]'
    );
    t = t.replaceAllMapped(
      RegExp(r'<div class="b-spoiler_block"[^>]*>.*?<div class="inside">(.*?)</div>\s*</div>', dotAll: true),
      (m) => '[spoiler]${m.group(1)}[/spoiler]'
    );

    // 4. Форматирование
    t = t.replaceAllMapped(RegExp(r'<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', caseSensitive: false, dotAll: true), (m) => '[url=${m.group(1)}]${m.group(2)}[/url]');
    t = t.replaceAllMapped(RegExp(r'<strong[^>]*>(.*?)</strong>', caseSensitive: false, dotAll: true), (m) => '[b]${m.group(1)}[/b]');
    t = t.replaceAllMapped(RegExp(r'<b[^>]*>(.*?)</b>', caseSensitive: false, dotAll: true), (m) => '[b]${m.group(1)}[/b]');
    t = t.replaceAllMapped(RegExp(r'<em[^>]*>(.*?)</em>', caseSensitive: false, dotAll: true), (m) => '[i]${m.group(1)}[/i]');
    t = t.replaceAllMapped(RegExp(r'<i[^>]*>(.*?)</i>', caseSensitive: false, dotAll: true), (m) => '[i]${m.group(1)}[/i]');
    t = t.replaceAllMapped(RegExp(r'<del[^>]*>(.*?)</del>', caseSensitive: false, dotAll: true), (m) => '[s]${m.group(1)}[/s]');
    t = t.replaceAllMapped(RegExp(r'<s[^>]*>(.*?)</s>', caseSensitive: false, dotAll: true), (m) => '[s]${m.group(1)}[/s]');

    t = t.replaceAll(RegExp(r'<[^>]*>'), '');
    t = t.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');

    // 5. Упоминания и системный мусор
    t = t.replaceAllMapped(RegExp(r'\[comment=[^\]]+\](.*?)\[/comment\],?\s*', caseSensitive: false), (m) => '[mention]${m.group(1)}[/mention] ');
    t = t.replaceAll(RegExp(r'\[/?solid\]', caseSensitive: false), '');
    t = t.replaceAll(RegExp(r'\[replies=[^\]]+\]'), '');
    t = t.replaceAll(RegExp(r'>\?c\d+;\d+;[^\s\n]+'), '');
    t = t.replaceAllMapped(RegExp(r'\[(?:character|anime|manga|person|user)(?:=[^\]]+)?\](.*?)\[/(?:character|anime|manga|person|user)\]', caseSensitive: false), (m) => '[b]${m.group(1)}[/b]');

    return t.trim();
  }

  // Генерация ссылок для смайлов при ручном вводе
  static String getEmojiUrl(String code) {
    final map = {
      ':pepe:': 'pepe.png', ':aww:': 'aww.gif', ':lol:': 'lol.gif', ':facepalm:': 'facepalm.gif',
      ':kiss:': 'kiss.gif', ':cry:': 'cry.gif', ':evil:': 'evil.gif', ':hopeless:': 'hopeless.gif',
      ':yummy:': 'yummy.gif', ':smirk:': 'smirk.gif', ':bored:': 'bored.gif', ':ololo:': 'ololo.gif',
      ':shy:': 'shy.gif', ':wow:': 'wow.gif', ':smile:': 'smile.gif', ':D': 'D.gif', 
      ':(': 'sad.gif', '+_(': 'plus_sad.gif', ':|': 'mda.gif', ':\\': 'mda.gif'
    };
    final file = map[code] ?? '${code.replaceAll(':', '')}.gif';
    return '$_shikiUrl/images/smileys/$file';
  }

  static List<InlineSpan> buildSpans(BuildContext context, String text) {
    final spans = <InlineSpan>[];
    
    final pattern = RegExp(
      r'(\[mention\](.*?)\[/mention\])|' // 1, 2
      r'(\[quote(?:=(?:c\d+;\d+;)?([^\]]+))?\](.*?)\[/quote\])|' // 3, 4, 5
      r'(\[spoiler(?:=([^\]]*))?\](.*?)\[/spoiler\])|' // 6, 7, 8
      r'(\[img(?:=[^\]]+)?\](.*?)\[/img\])|' // 9, 10
      r'(\[img=(.*?)\])|' // 11, 12 
      r'(\[image=(\d+)\])|' // 13, 14 (Асинхронные вложения Шикимори)
      r'(\[poster=(\d+)\])|' // 15, 16 (Асинхронные постеры)
      r'(\[video\](.*?)\[/video\])|' // 17, 18
      r'(\[video=(.*?)\])|' // 19, 20
      r'(\[emoji_url=([^\]]+)\])|' // 21, 22 (Точный URL из HTML)
      r'((?<=^|\s)(:[a-zA-Z0-9_+-]+:)(?=\s|$))|' // 23, 24 (Ручной ввод :v200:)
      r'(\[url=([^\]]+)\](.*?)\[/url\])|' // 25, 26, 27
      r'(\[b\](.*?)\[/b\])|' // 28, 29
      r'(\[i\](.*?)\[/i\])|' // 30, 31
      r'(\[s\](.*?)\[/s\])', // 32, 33
      dotAll: true, caseSensitive: false
    );
    
    int lastIndex = 0;
    for (final match in pattern.allMatches(text)) {
      if (match.start > lastIndex) {
        spans.add(TextSpan(text: text.substring(lastIndex, match.start), style: const TextStyle(color: Colors.white, fontSize: 15, height: 1.4)));
      }
      
      if (match.group(1) != null) { 
        spans.add(TextSpan(text: '@${match.group(2)} ', style: const TextStyle(color: Color(0xFFFF5722), fontWeight: FontWeight.bold, fontSize: 14)));
      } else if (match.group(3) != null) { 
        final name = match.group(4) ?? 'Цитата';
        spans.add(WidgetSpan(child: _QuoteBlock(name: name, content: match.group(5)!)));
      } else if (match.group(6) != null) { 
        final title = match.group(7) ?? 'Спойлер';
        spans.add(WidgetSpan(child: _InlineSpoiler(title: title, content: match.group(8)!)));
      } else if (match.group(9) != null || match.group(11) != null) { 
        final url = match.group(10) ?? match.group(12)!;
        spans.add(WidgetSpan(child: _ImageThumbnail(url: url)));
      } else if (match.group(13) != null) {
        // Рендер асинхронного изображения (Форумные вложения)
        spans.add(WidgetSpan(child: _AsyncShikiImage(imageId: match.group(14)!)));
      } else if (match.group(15) != null) {
        // Рендер асинхронного постера
        spans.add(WidgetSpan(child: _AsyncShikiImage(imageId: match.group(16)!, isPoster: true)));
      } else if (match.group(17) != null || match.group(19) != null) { 
        final url = match.group(18) ?? match.group(20)!;
        spans.add(WidgetSpan(child: _VideoThumbnail(url: url)));
      } else if (match.group(21) != null) { 
        // 100% точный URL из оригинального HTML Шикимори
        final url = match.group(22)!;
        spans.add(WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2),
            child: Image.network(url, height: 32, errorBuilder: (_, __, ___) => const Icon(Icons.error, size: 16)),
          ),
        ));
      } else if (match.group(23) != null) { 
        // Если пользователь сам напечатал :v200:
        final code = match.group(24)!;
        final url = getEmojiUrl(code);
        spans.add(WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2),
            child: Image.network(url, height: 32, errorBuilder: (_, __, ___) => Text(code, style: const TextStyle(color: Colors.white))),
          ),
        ));
      } else if (match.group(25) != null) { 
        final url = match.group(26)!;
        spans.add(WidgetSpan(
          child: GestureDetector(
            onTap: () => _launchURL(url),
            child: Text(match.group(27)!, style: const TextStyle(color: Color(0xFFFF5722), decoration: TextDecoration.underline, fontSize: 15, height: 1.4)),
          )
        ));
      } else if (match.group(28) != null) { 
        spans.add(TextSpan(children: buildSpans(context, match.group(29)!), style: const TextStyle(fontWeight: FontWeight.bold)));
      } else if (match.group(30) != null) { 
        spans.add(TextSpan(children: buildSpans(context, match.group(31)!), style: const TextStyle(fontStyle: FontStyle.italic)));
      } else if (match.group(32) != null) { 
        spans.add(TextSpan(children: buildSpans(context, match.group(33)!), style: const TextStyle(decoration: TextDecoration.lineThrough)));
      }
      
      lastIndex = match.end;
    }
    
    if (lastIndex < text.length) {
      spans.add(TextSpan(text: text.substring(lastIndex), style: const TextStyle(color: Colors.white, fontSize: 15, height: 1.4)));
    }
    return spans;
  }

  static Future<void> _launchURL(String url) async {
    final uri = Uri.tryParse(url.startsWith('http') ? url : '$_shikiUrl$url');
    if (uri != null && await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }
}

// =========================================================================================
// АСИНХРОННЫЙ ВИДЖЕТ ИЗОБРАЖЕНИЯ (Для [image=123] / [poster=123])
// =========================================================================================
class _AsyncShikiImage extends StatefulWidget {
  final String imageId;
  final bool isPoster;
  const _AsyncShikiImage({required this.imageId, this.isPoster = false});

  @override
  State<_AsyncShikiImage> createState() => _AsyncShikiImageState();
}

class _AsyncShikiImageState extends State<_AsyncShikiImage> {
  String? _resolvedUrl;
  bool _hasError = false;

  @override
  void initState() {
    super.initState();
    _resolveImage();
  }

  Future<void> _resolveImage() async {
    final url = await ShikiMediaService.resolveImageUrl(widget.imageId, isPoster: widget.isPoster);
    if (mounted) {
      if (url != null) {
        setState(() => _resolvedUrl = url);
      } else {
        setState(() => _hasError = true);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_hasError) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: const Color(0xFF27272A), borderRadius: BorderRadius.circular(12)),
        child: const Row(
          children: [
             Icon(Icons.broken_image, color: Colors.grey), 
             SizedBox(width: 8), 
             Text('Вложение недоступно', style: TextStyle(color: Colors.grey))
          ]
        ),
      );
    }
    
    if (_resolvedUrl == null) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        height: 150, 
        width: double.infinity, 
        decoration: BoxDecoration(color: const Color(0xFF27272A), borderRadius: BorderRadius.circular(12)),
        child: const Center(child: CupertinoActivityIndicator())
      );
    }

    return _ImageThumbnail(url: _resolvedUrl!);
  }
}

// ЦИТАТА
class _QuoteBlock extends StatelessWidget {
  final String name;
  final String content;
  const _QuoteBlock({required this.name, required this.content});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(vertical: 6),
      padding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
      decoration: const BoxDecoration(border: Border(left: BorderSide(color: Color(0xFFFF5722), width: 3))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(name, style: const TextStyle(color: Color(0xFFFF5722), fontWeight: FontWeight.bold, fontSize: 13)),
          const SizedBox(height: 4),
          Text.rich(TextSpan(children: _BBCodeParser.buildSpans(context, content))),
        ],
      ),
    );
  }
}

// СПОЙЛЕР
class _InlineSpoiler extends StatefulWidget {
  final String title;
  final String content;
  const _InlineSpoiler({required this.title, required this.content});

  @override
  State<_InlineSpoiler> createState() => _InlineSpoilerState();
}

class _InlineSpoilerState extends State<_InlineSpoiler> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    if (_revealed) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        margin: const EdgeInsets.symmetric(vertical: 6),
        decoration: BoxDecoration(color: const Color(0xFF27272A), borderRadius: BorderRadius.circular(12)),
        child: Text.rich(TextSpan(children: _BBCodeParser.buildSpans(context, widget.content))),
      );
    }
    return GestureDetector(
      onTap: () => setState(() => _revealed = true),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        margin: const EdgeInsets.symmetric(vertical: 6),
        decoration: BoxDecoration(
          color: const Color(0xFF27272A),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.white.withOpacity(0.05)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(CupertinoIcons.eye_slash_fill, color: Color(0xFFFF5722), size: 16),
            const SizedBox(width: 8),
            Text(widget.title.isNotEmpty ? widget.title : 'Спойлер', style: const TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

// СТАНДАРТНАЯ КАРТИНКА ПРЯМЫМ URL
class _ImageThumbnail extends StatelessWidget {
  final String url;
  const _ImageThumbnail({required this.url});

  @override
  Widget build(BuildContext context) {
    final safeUrl = url.startsWith('http') ? url : '$_shikiUrl$url';

    return GestureDetector(
      onTap: () {
        Navigator.of(context, rootNavigator: true).push(
          MaterialPageRoute(
            builder: (_) => Scaffold(
              backgroundColor: Colors.black,
              appBar: AppBar(backgroundColor: Colors.black, iconTheme: const IconThemeData(color: Colors.white)),
              body: Center(
                child: InteractiveViewer(
                  child: CachedNetworkImage(
                    imageUrl: safeUrl, 
                    fit: BoxFit.contain,
                    placeholder: (_, __) => const CupertinoActivityIndicator(color: Colors.white),
                    errorWidget: (_, __, ___) => const Icon(Icons.broken_image, color: Colors.white, size: 50),
                  ),
                ),
              ),
            )
          )
        );
      },
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        constraints: const BoxConstraints(maxHeight: 250, maxWidth: double.infinity),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: CachedNetworkImage(
            imageUrl: safeUrl, 
            fit: BoxFit.cover,
            placeholder: (_, __) => Container(height: 150, width: double.infinity, color: const Color(0xFF27272A), child: const Center(child: CupertinoActivityIndicator())),
            errorWidget: (_, __, ___) => Container(padding: const EdgeInsets.all(16), color: const Color(0xFF27272A), child: const Row(children: [Icon(Icons.broken_image, color: Colors.grey), SizedBox(width: 8), Text('Изображение недоступно', style: TextStyle(color: Colors.grey))])),
          ),
        ),
      ),
    );
  }
}

// ВИДЕО ПЛЕЕР (ПРЕВЬЮ YouTube)
class _VideoThumbnail extends StatelessWidget {
  final String url;
  const _VideoThumbnail({required this.url});

  String? _extractYoutubeId(String url) {
    final regExp = RegExp(r'(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/)([^"&?\/\s]{11})', caseSensitive: false);
    return regExp.firstMatch(url)?.group(1);
  }

  @override
  Widget build(BuildContext context) {
    final ytId = _extractYoutubeId(url);
    
    if (ytId == null) {
      return GestureDetector(
        onTap: () => _BBCodeParser._launchURL(url),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 8),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: const Color(0xFF27272A), borderRadius: BorderRadius.circular(12)),
          child: const Row(
            children: [
              Icon(CupertinoIcons.play_rectangle_fill, color: Color(0xFFFF5722)),
              SizedBox(width: 8),
              Expanded(child: Text('Открыть видео', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600))),
            ],
          ),
        ),
      );
    }

    return GestureDetector(
      onTap: () => _BBCodeParser._launchURL(url),
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        height: 200,
        width: double.infinity,
        child: Stack(
          fit: StackFit.expand,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: CachedNetworkImage(
                imageUrl: 'https://img.youtube.com/vi/$ytId/hqdefault.jpg',
                fit: BoxFit.cover,
                errorWidget: (_, __, ___) => Container(color: const Color(0xFF27272A)),
              ),
            ),
            Container(
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.3),
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            Center(
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFFFF5722).withOpacity(0.9),
                  shape: BoxShape.circle,
                ),
                child: const Icon(CupertinoIcons.play_fill, color: Colors.white, size: 28),
              ),
            ),
          ],
        ),
      ),
    );
  }
}