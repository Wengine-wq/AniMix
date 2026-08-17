import 'dart:async';
import 'dart:convert';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:dio/dio.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_widget_from_html_core/flutter_widget_from_html_core.dart';
import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/app_logging.dart';
import '../../core/animix_theme.dart';
import '../../core/media_cache.dart';
import '../../core/shikimori_smileys.dart';
import '../../models/shikimori_comment.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_media_viewer.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';

const _shikimoriUrl = 'https://shikimori.io';

enum CommentOrder { newest, oldest }

enum CommentFilter { all, discussion, offtopic, replies, media }

extension on CommentOrder {
  String get label => switch (this) {
    CommentOrder.newest => 'Сначала новые',
    CommentOrder.oldest => 'Сначала старые',
  };
}

extension on CommentFilter {
  String get label => switch (this) {
    CommentFilter.all => 'Все',
    CommentFilter.discussion => 'По теме',
    CommentFilter.offtopic => 'Оффтоп',
    CommentFilter.replies => 'Ответы',
    CommentFilter.media => 'С медиа',
  };
}

class CommentsScreen extends ConsumerStatefulWidget {
  const CommentsScreen({required this.topicId, super.key});

  final int topicId;

  @override
  ConsumerState<CommentsScreen> createState() => _CommentsScreenState();
}

class _CommentsScreenState extends ConsumerState<CommentsScreen> {
  final List<ShikimoriComment> _comments = [];
  final ScrollController _scrollController = ScrollController();
  int _page = 1;
  int? _totalCount;
  bool _loading = true;
  bool _loadingMore = false;
  bool _hasMore = true;
  Object? _loadError;
  Object? _loadMoreError;
  ShikimoriComment? _replyingTo;
  CommentOrder _order = CommentOrder.newest;
  CommentFilter _filter = CommentFilter.all;
  bool _apiExhausted = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    unawaited(_loadTopicCount());
    unawaited(_loadFirstPage());
  }

  @override
  void dispose() {
    _scrollController
      ..removeListener(_onScroll)
      ..dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    if (_scrollController.position.extentAfter < 560) {
      unawaited(_loadMore());
    }
  }

  Future<void> _loadTopicCount() async {
    try {
      final response = await Dio().get<Map<String, dynamic>>(
        '$_shikimoriUrl/api/topics/${widget.topicId}',
        options: Options(receiveTimeout: const Duration(seconds: 12)),
      );
      if (!mounted) return;
      setState(() {
        _totalCount = int.tryParse(
          response.data?['comments_count']?.toString() ?? '',
        );
        if (!_apiExhausted && _totalCount != null) {
          _hasMore = _comments.length < _totalCount!;
        }
      });
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Comments',
        context: 'Не удалось получить счётчик темы ${widget.topicId}',
      );
      // The list remains fully usable when the optional total is unavailable.
    }
  }

  Future<void> _loadFirstPage() async {
    setState(() {
      _loading = true;
      _loadError = null;
      _loadMoreError = null;
      _page = 1;
      _apiExhausted = false;
    });
    try {
      final comments = await ref
          .read(apiClientProvider)
          .getComments(
            widget.topicId,
            descending: _order == CommentOrder.newest,
          );
      if (!mounted) return;
      setState(() {
        _comments
          ..clear()
          ..addAll(comments);
        _apiExhausted = comments.isEmpty;
        _hasMore =
            comments.isNotEmpty &&
            (_totalCount == null || _comments.length < _totalCount!);
      });
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Comments',
        context: 'Не удалось загрузить тему ${widget.topicId}',
      );
      if (mounted) setState(() => _loadError = error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || !_hasMore) return;
    setState(() {
      _loadingMore = true;
      _loadMoreError = null;
    });
    try {
      final nextPage = _page + 1;
      final next = await ref
          .read(apiClientProvider)
          .getComments(
            widget.topicId,
            page: nextPage,
            descending: _order == CommentOrder.newest,
          );
      if (!mounted) return;
      final knownIds = _comments.map((comment) => comment.id).toSet();
      final unique = next.where((comment) => knownIds.add(comment.id)).toList();
      setState(() {
        _page = nextPage;
        _comments.addAll(unique);
        // An empty page or a page fully duplicated by a drifting API cursor is
        // the real end. Without this guard auto-pagination can request forever.
        _apiExhausted = next.isEmpty || unique.isEmpty;
        _hasMore =
            !_apiExhausted &&
            (_totalCount == null || _comments.length < _totalCount!);
      });
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Comments',
        context: 'Не удалось загрузить страницу ${_page + 1}',
      );
      if (mounted) setState(() => _loadMoreError = error);
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<bool> _send(String text) async {
    final body = text.trim();
    if (body.isEmpty) return false;
    final target = _replyingTo;
    final payload = target == null
        ? body
        : '[comment=${target.id};${target.userId ?? 0}]'
              '${target.userNickname ?? 'Пользователь'}[/comment], $body';
    try {
      final created = await ref
          .read(apiClientProvider)
          .postComment(widget.topicId, payload);
      if (!mounted) return true;
      setState(() {
        if (_order == CommentOrder.newest) {
          _comments.insert(0, created);
        } else {
          _comments.add(created);
        }
        _replyingTo = null;
        _totalCount = (_totalCount ?? _comments.length - 1) + 1;
      });
      return true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Comments',
        context: 'Не удалось отправить комментарий',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось отправить комментарий')),
        );
      }
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final visibleComments = _comments.where(_matchesFilter).toList();
    final tree = CommentTree.from(visibleComments);
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Комментарии'),
            Text(
              _totalCount == null
                  ? 'Обсуждение'
                  : '$_totalCount ${_commentWord(_totalCount!)}',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 11,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
        actions: [
          PopupMenuButton<CommentOrder>(
            key: const ValueKey('comments_order'),
            tooltip: 'Порядок комментариев',
            initialValue: _order,
            onSelected: (value) {
              if (value == _order) return;
              setState(() => _order = value);
              unawaited(_loadFirstPage());
            },
            itemBuilder: (context) => CommentOrder.values
                .map(
                  (value) => CheckedPopupMenuItem<CommentOrder>(
                    value: value,
                    checked: value == _order,
                    child: Text(value.label),
                  ),
                )
                .toList(),
            icon: const Icon(CupertinoIcons.sort_down),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Column(
        children: [
          _CommentsFilterBar(
            selected: _filter,
            onSelected: (value) => setState(() => _filter = value),
          ),
          Expanded(child: _buildContent(tree)),
          CommentComposer(
            replyTo: _replyingTo,
            onCancelReply: () => setState(() => _replyingTo = null),
            onSend: _send,
          ),
        ],
      ),
    );
  }

  bool _matchesFilter(ShikimoriComment comment) => switch (_filter) {
    CommentFilter.all => true,
    CommentFilter.discussion => !comment.isOfftopic,
    CommentFilter.offtopic => comment.isOfftopic,
    CommentFilter.replies => comment.isReply,
    CommentFilter.media => comment.hasMedia,
  };

  Widget _buildContent(CommentTree tree) {
    if (_loading) {
      return const AniMixCommentsSkeleton();
    }
    if (_loadError != null) {
      return _CommentsEmptyState(
        icon: CupertinoIcons.wifi_exclamationmark,
        title: 'Комментарии не загрузились',
        message: 'Проверьте соединение и повторите попытку.',
        actionLabel: 'Повторить',
        onAction: _loadFirstPage,
      );
    }
    if (tree.roots.isEmpty) {
      final canLoadMore = _hasMore || _loadMoreError != null;
      return _CommentsEmptyState(
        icon: CupertinoIcons.chat_bubble_2,
        title: _comments.isEmpty
            ? 'Обсуждение пока пустое'
            : 'По фильтру ничего нет',
        message: _comments.isEmpty
            ? 'Можно оставить первый комментарий.'
            : 'Попробуйте другой фильтр или загрузите больше комментариев.',
        actionLabel: canLoadMore
            ? (_loadMoreError == null ? 'Загрузить ещё' : 'Повторить загрузку')
            : null,
        onAction: canLoadMore ? _loadMore : null,
      );
    }
    return RefreshIndicator.adaptive(
      onRefresh: _loadFirstPage,
      child: ListView.builder(
        key: const ValueKey('comments_list'),
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        itemCount: tree.roots.length + 1,
        itemBuilder: (context, index) {
          if (index == tree.roots.length) {
            return _CommentsFooter(
              hasMore: _hasMore,
              loading: _loadingMore,
              failed: _loadMoreError != null,
              onLoad: _loadMore,
            );
          }
          final comment = tree.roots[index];
          return CommentThread(
            key: ValueKey('comment_thread_${comment.id}'),
            comment: comment,
            replies: tree.replies[comment.id] ?? const [],
            onReply: (target) => setState(() => _replyingTo = target),
          );
        },
      ),
    );
  }
}

class _CommentsEmptyState extends StatelessWidget {
  const _CommentsEmptyState({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 48,
                      height: 48,
                      decoration: BoxDecoration(
                        color: colors.primary.withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      alignment: Alignment.center,
                      child: Icon(icon, size: 24, color: colors.primary),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      title,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      message,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colors.onSurfaceVariant,
                        height: 1.35,
                      ),
                    ),
                    if (actionLabel != null && onAction != null) ...[
                      const SizedBox(height: 14),
                      FilledButton.tonal(
                        onPressed: onAction,
                        child: Text(actionLabel!),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _CommentsFilterBar extends StatelessWidget {
  const _CommentsFilterBar({required this.selected, required this.onSelected});

  final CommentFilter selected;
  final ValueChanged<CommentFilter> onSelected;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      border: Border(bottom: BorderSide(color: AniMixTheme.divider)),
    ),
    child: SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
      child: Row(
        children: [
          for (final filter in CommentFilter.values) ...[
            ChoiceChip(
              key: ValueKey('comment_filter_${filter.name}'),
              label: Text(filter.label),
              selected: filter == selected,
              onSelected: (_) => onSelected(filter),
              showCheckmark: false,
              visualDensity: VisualDensity.compact,
            ),
            if (filter != CommentFilter.values.last) const SizedBox(width: 8),
          ],
        ],
      ),
    ),
  );
}

String _commentWord(int count) {
  final lastTwo = count % 100;
  if (lastTwo >= 11 && lastTwo <= 14) return 'комментариев';
  return switch (count % 10) {
    1 => 'комментарий',
    2 || 3 || 4 => 'комментария',
    _ => 'комментариев',
  };
}

class CommentTree {
  const CommentTree({required this.roots, required this.replies});

  final List<ShikimoriComment> roots;
  final Map<int, List<ShikimoriComment>> replies;

  factory CommentTree.from(List<ShikimoriComment> comments) {
    final ids = comments.map((comment) => comment.id).toSet();
    final roots = <ShikimoriComment>[];
    final replies = <int, List<ShikimoriComment>>{};
    for (final comment in comments) {
      final parent = commentParentId(comment.body);
      if (parent != null && ids.contains(parent)) {
        replies.putIfAbsent(parent, () => []).add(comment);
      } else {
        roots.add(comment);
      }
    }
    return CommentTree(roots: roots, replies: replies);
  }
}

int? commentParentId(String body) {
  final match = RegExp(
    r'\[comment=(\d+)(?:;[^\]]*)?\]',
    caseSensitive: false,
  ).firstMatch(body);
  return match == null ? null : int.tryParse(match.group(1)!);
}

class CommentThread extends StatefulWidget {
  const CommentThread({
    required this.comment,
    required this.replies,
    required this.onReply,
    super.key,
  });

  final ShikimoriComment comment;
  final List<ShikimoriComment> replies;
  final ValueChanged<ShikimoriComment> onReply;

  @override
  State<CommentThread> createState() => _CommentThreadState();
}

class _CommentThreadState extends State<CommentThread> {
  bool _showAllReplies = false;

  @override
  Widget build(BuildContext context) {
    final visibleReplies = _showAllReplies
        ? widget.replies
        : widget.replies.take(2).toList();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: AniMixSurface(
        radius: 20,
        padding: const EdgeInsets.fromLTRB(14, 14, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            CommentTile(comment: widget.comment, onReply: widget.onReply),
            if (visibleReplies.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                margin: const EdgeInsets.only(left: 18),
                padding: const EdgeInsets.only(left: 12),
                decoration: BoxDecoration(
                  border: Border(
                    left: BorderSide(
                      color: Theme.of(
                        context,
                      ).colorScheme.primary.withValues(alpha: .32),
                      width: 2,
                    ),
                  ),
                ),
                child: Column(
                  children: [
                    for (var i = 0; i < visibleReplies.length; i++) ...[
                      if (i > 0) const Divider(height: 18),
                      CommentTile(
                        comment: visibleReplies[i],
                        onReply: widget.onReply,
                        compact: true,
                      ),
                    ],
                  ],
                ),
              ),
            ],
            if (widget.replies.length > 2) ...[
              const SizedBox(height: 6),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: ValueKey('replies_toggle_${widget.comment.id}'),
                  onPressed: () =>
                      setState(() => _showAllReplies = !_showAllReplies),
                  icon: Icon(
                    _showAllReplies
                        ? CupertinoIcons.chevron_up
                        : CupertinoIcons.chevron_down,
                    size: 14,
                  ),
                  label: Text(
                    _showAllReplies
                        ? 'Скрыть ответы'
                        : 'Показать ещё ${widget.replies.length - 2}',
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class CommentTile extends StatelessWidget {
  const CommentTile({
    required this.comment,
    required this.onReply,
    this.compact = false,
    super.key,
  });

  final ShikimoriComment comment;
  final ValueChanged<ShikimoriComment> onReply;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _CommentAvatar(comment: comment, small: compact),
        SizedBox(width: compact ? 9 : 11),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      comment.userNickname?.trim().isNotEmpty == true
                          ? comment.userNickname!
                          : 'Пользователь',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: compact ? 13 : 14,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    formatCommentDate(comment.createdAt),
                    maxLines: 1,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontSize: 10,
                    ),
                  ),
                ],
              ),
              if (comment.body.trim().isNotEmpty ||
                  comment.htmlBody.trim().isNotEmpty) ...[
                const SizedBox(height: 6),
                CollapsibleCommentBody(comment: comment, compact: compact),
              ],
              const SizedBox(height: 7),
              Semantics(
                button: true,
                label: 'Ответить пользователю',
                child: InkWell(
                  key: ValueKey('reply_${comment.id}'),
                  borderRadius: BorderRadius.circular(9),
                  onTap: () => onReply(comment),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 3,
                      vertical: 4,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          CupertinoIcons.reply,
                          size: 13,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          'Ответить',
                          style: TextStyle(
                            color: Theme.of(
                              context,
                            ).colorScheme.onSurfaceVariant,
                            fontSize: 11,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _CommentAvatar extends StatelessWidget {
  const _CommentAvatar({required this.comment, required this.small});

  final ShikimoriComment comment;
  final bool small;

  @override
  Widget build(BuildContext context) {
    final size = small ? 28.0 : 36.0;
    final avatar = comment.userAvatar;
    return ClipOval(
      child: SizedBox.square(
        dimension: size,
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: avatar == null || avatar.isEmpty
              ? Icon(
                  CupertinoIcons.person_fill,
                  size: size * .48,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                )
              : CachedNetworkImage(
                  imageUrl: avatar,
                  fit: BoxFit.cover,
                  fadeInDuration: const Duration(milliseconds: 120),
                  placeholder: (_, _) => const SizedBox.shrink(),
                  errorWidget: (_, _, _) => Icon(
                    CupertinoIcons.person_fill,
                    size: size * .48,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
        ),
      ),
    );
  }
}

class CollapsibleCommentBody extends StatefulWidget {
  const CollapsibleCommentBody({
    required this.comment,
    this.compact = false,
    super.key,
  });

  final ShikimoriComment comment;
  final bool compact;

  @override
  State<CollapsibleCommentBody> createState() => _CollapsibleCommentBodyState();
}

class _CollapsibleCommentBodyState extends State<CollapsibleCommentBody> {
  bool _expanded = false;

  bool get _canCollapse {
    final source = widget.comment.body;
    return source.length > 420 ||
        '\n'.allMatches(source).length >= 8 ||
        widget.comment.htmlBody.length > 1800;
  }

  @override
  Widget build(BuildContext context) {
    final document = CommentDocument.parse(
      widget.comment.body,
      htmlBody: widget.comment.htmlBody,
    );
    final collapseMedia =
        _canCollapse && !_expanded && document.images.isNotEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AnimatedSize(
          duration: const Duration(milliseconds: 210),
          curve: Curves.easeOutCubic,
          alignment: Alignment.topCenter,
          child: ClipRect(
            child: ConstrainedBox(
              key: ValueKey(
                _expanded ? 'comment_expanded' : 'comment_collapsed',
              ),
              constraints: _canCollapse && !_expanded
                  ? BoxConstraints(maxHeight: widget.compact ? 190 : 250)
                  : const BoxConstraints(),
              child: CommentMarkupView(
                comment: widget.comment,
                compact: widget.compact,
                hideMedia: collapseMedia,
              ),
            ),
          ),
        ),
        if (collapseMedia) ...[
          CommentRemoteImage(
            previewUrl: document.images.first,
            originalUrl: document.images.first,
            maxHeight: widget.compact ? 130 : 170,
          ),
          if (document.images.length > 1)
            Padding(
              padding: const EdgeInsets.only(bottom: 2),
              child: Text(
                'Ещё ${document.images.length - 1} изображений внутри',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 11,
                ),
              ),
            ),
        ],
        if (_canCollapse)
          TextButton(
            key: const ValueKey('comment_expand_button'),
            onPressed: () => setState(() => _expanded = !_expanded),
            style: TextButton.styleFrom(
              padding: const EdgeInsets.only(top: 4),
              minimumSize: const Size(0, 30),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: Text(_expanded ? 'Свернуть' : 'Показать полностью'),
          ),
      ],
    );
  }
}

class CommentMarkupView extends StatelessWidget {
  const CommentMarkupView({
    required this.comment,
    required this.compact,
    this.hideMedia = false,
    super.key,
  });

  static final Uri _baseUri = Uri.parse('https://shikimori.io');
  static const _headers = <String, String>{
    'User-Agent': 'AniMix/2.0',
    'Referer': 'https://shikimori.io/',
  };

  final ShikimoriComment comment;
  final bool compact;
  final bool hideMedia;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    final document = CommentDocument.parse(
      comment.body,
      htmlBody: comment.htmlBody,
    );
    return HtmlWidget(
      document.html,
      baseUrl: _baseUri,
      textStyle: TextStyle(
        fontSize: compact ? 12.5 : 13.5,
        height: 1.38,
        color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .9),
      ),
      customStylesBuilder: (element) {
        if (element.classes.contains('b-quote')) {
          return {
            'background-color': _cssColor(
              Theme.of(context).colorScheme.surfaceContainerHigh,
            ),
            'border-left': '3px solid ${_cssColor(accent)}',
            'border-radius': '10px',
            'padding': '9px 11px',
            'margin': '7px 0',
          };
        }
        if (element.classes.contains('quoteable')) {
          return {
            'color': _cssColor(accent),
            'font-size': '11px',
            'font-weight': '700',
            'margin-bottom': '4px',
          };
        }
        if (element.localName == 'pre' || element.localName == 'code') {
          return {
            'background-color': _cssColor(
              Theme.of(context).colorScheme.surfaceContainer,
            ),
            'border-radius': '8px',
            'padding': '7px',
            'font-size': '12px',
          };
        }
        if (element.localName == 'a' || element.classes.contains('b-mention')) {
          return {'color': _cssColor(accent), 'text-decoration': 'none'};
        }
        return null;
      },
      customWidgetBuilder: (element) => _customElement(context, element),
      onTapUrl: (url) => _launch(url),
      onTapImage: (metadata) {
        if (metadata.sources.isEmpty) return;
        final url = _absoluteUrl(metadata.sources.first.url);
        if (url.isNotEmpty) {
          unawaited(
            showAniMixMediaViewer(
              context,
              images: [url],
              headers: _headers,
              title: 'Из комментария',
            ),
          );
        }
      },
      onErrorBuilder: (context, element, error) => Text(
        element.text.trim(),
        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    );
  }

  Widget? _customElement(BuildContext context, dom.Element element) {
    if (element.classes.contains('b-replies')) {
      return const SizedBox.shrink();
    }
    if (element.localName == 'img' && element.classes.contains('smiley')) {
      final url = _absoluteUrl(element.attributes['src']);
      final alt = element.attributes['alt'] ?? '';
      return InlineCustomWidget(
        child: CachedNetworkImage(
          imageUrl: url,
          cacheManager: AniMixMediaCache.commentMedia,
          width: compact ? 19 : 21,
          height: compact ? 19 : 21,
          fit: BoxFit.contain,
          httpHeaders: _headers,
          placeholder: (_, _) => const SizedBox.square(dimension: 18),
          errorWidget: (_, _, _) => Text(
            alt,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      );
    }
    if (element.localName == 'a' && element.classes.contains('b-image')) {
      return hideMedia ? const SizedBox.shrink() : _imageFrom(context, element);
    }
    if (element.localName == 'img') {
      final parent = element.parent;
      if (parent?.classes.contains('b-user16') == true) {
        return const SizedBox.shrink();
      }
      return hideMedia ? const SizedBox.shrink() : _imageFrom(context, element);
    }
    if (element.classes.contains('b-spoiler') ||
        element.classes.contains('b-spoiler_block')) {
      final clone = element.clone(true);
      final label = clone.querySelector('label')?.text.trim();
      clone.querySelector('label')?.remove();
      return CommentSpoiler(
        title: label?.isNotEmpty == true ? label! : 'Спойлер',
        html: clone.innerHtml,
        compact: compact,
      );
    }
    if (element.localName == 'iframe' || element.classes.contains('b-video')) {
      final url =
          element.attributes['src'] ??
          element.querySelector('a')?.attributes['href'];
      if (url == null) return null;
      return _CommentExternalMedia(url: _absoluteUrl(url), label: 'Видео');
    }
    return null;
  }

  Widget _imageFrom(BuildContext context, dom.Element element) {
    final image = element.localName == 'img'
        ? element
        : element.querySelector('img');
    final preview = _absoluteUrl(image?.attributes['src']);
    final original = element.localName == 'a'
        ? _absoluteUrl(element.attributes['href'])
        : preview;
    final width = double.tryParse(image?.attributes['data-width'] ?? '');
    final height = double.tryParse(image?.attributes['data-height'] ?? '');
    final ratio = width != null && height != null && height > 0
        ? width / height
        : null;
    if (preview.isEmpty) return const SizedBox.shrink();
    return CommentRemoteImage(
      previewUrl: preview,
      originalUrl: original,
      aspectRatio: ratio,
    );
  }

  static String _absoluteUrl(String? value) {
    final raw = value?.trim() ?? '';
    if (raw.isEmpty) return '';
    if (raw.startsWith('//')) return 'https:$raw';
    final uri = Uri.tryParse(raw);
    if (uri == null) return '';
    return uri.hasScheme ? uri.toString() : _baseUri.resolveUri(uri).toString();
  }

  static Future<bool> _launch(String raw) async {
    final url = _absoluteUrl(raw);
    final uri = Uri.tryParse(url);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return false;
    }
    return launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  static String _cssColor(Color color) =>
      '#${color.toARGB32().toRadixString(16).padLeft(8, '0').substring(2)}';
}

class CommentRemoteImage extends StatefulWidget {
  const CommentRemoteImage({
    required this.previewUrl,
    required this.originalUrl,
    this.aspectRatio,
    this.maxHeight = 360,
    super.key,
  });

  final String previewUrl;
  final String originalUrl;
  final double? aspectRatio;
  final double maxHeight;

  @override
  State<CommentRemoteImage> createState() => _CommentRemoteImageState();
}

class _CommentRemoteImageState extends State<CommentRemoteImage> {
  int _attempt = 0;

  Future<void> _retry() async {
    await CachedNetworkImage.evictFromCache(
      widget.previewUrl,
      cacheManager: AniMixMediaCache.commentMedia,
    );
    if (mounted) setState(() => _attempt++);
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final ratio = (widget.aspectRatio ?? 16 / 9).clamp(.45, 3.2);
      final height = (constraints.maxWidth / ratio).clamp(
        96.0,
        widget.maxHeight,
      );
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 7),
        child: Semantics(
          button: true,
          label: 'Открыть изображение',
          child: InkWell(
            key: const ValueKey('comment_image_open'),
            onTap: () => unawaited(
              showAniMixMediaViewer(
                context,
                images: [widget.originalUrl],
                headers: CommentMarkupView._headers,
                title: 'Из комментария',
              ),
            ),
            borderRadius: BorderRadius.circular(14),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: SizedBox(
                width: double.infinity,
                height: height,
                child: CachedNetworkImage(
                  key: ValueKey('${widget.previewUrl}#$_attempt'),
                  imageUrl: widget.previewUrl,
                  cacheManager: AniMixMediaCache.commentMedia,
                  httpHeaders: CommentMarkupView._headers,
                  fit: BoxFit.contain,
                  fadeInDuration: const Duration(milliseconds: 160),
                  placeholder: (_, _) => ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainerHigh,
                    child: const Center(child: CupertinoActivityIndicator()),
                  ),
                  errorWidget: (_, _, _) => ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainerHigh,
                    child: Center(
                      child: TextButton.icon(
                        onPressed: _retry,
                        icon: const Icon(CupertinoIcons.refresh, size: 17),
                        label: const Text('Повторить загрузку'),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

class CommentSpoiler extends StatefulWidget {
  const CommentSpoiler({
    required this.title,
    required this.html,
    required this.compact,
    super.key,
  });

  final String title;
  final String html;
  final bool compact;

  @override
  State<CommentSpoiler> createState() => _CommentSpoilerState();
}

class _CommentSpoilerState extends State<CommentSpoiler> {
  bool _open = false;

  @override
  Widget build(BuildContext context) => AniMixSurface(
    radius: 13,
    selected: _open,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          onTap: () => setState(() => _open = !_open),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                const Icon(CupertinoIcons.eye_slash_fill, size: 15),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    widget.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
                Icon(
                  _open
                      ? CupertinoIcons.chevron_up
                      : CupertinoIcons.chevron_down,
                  size: 14,
                ),
              ],
            ),
          ),
        ),
        if (_open)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: HtmlWidget(
              widget.html,
              baseUrl: CommentMarkupView._baseUri,
              textStyle: TextStyle(fontSize: widget.compact ? 12.5 : 13.5),
              onTapUrl: CommentMarkupView._launch,
            ),
          ),
      ],
    ),
  );
}

class _CommentExternalMedia extends StatelessWidget {
  const _CommentExternalMedia({required this.url, required this.label});

  final String url;
  final String label;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: OutlinedButton.icon(
      onPressed: () => unawaited(CommentMarkupView._launch(url)),
      icon: const Icon(CupertinoIcons.play_rectangle_fill),
      label: Text('Открыть: $label'),
    ),
  );
}

class CommentDocument {
  const CommentDocument({
    required this.html,
    required this.text,
    required this.images,
  });

  final String html;
  final String text;
  final List<String> images;

  factory CommentDocument.parse(String source, {String htmlBody = ''}) {
    final resolved = htmlBody.trim().isNotEmpty
        ? htmlBody
        : _legacyBbCodeToHtml(source);
    final fragment = html_parser.parseFragment(resolved);
    for (final unsafe in fragment.querySelectorAll(
      'script, style, object, embed, .b-replies',
    )) {
      unsafe.remove();
    }
    final images = <String>{};
    for (final link in fragment.querySelectorAll('a')) {
      final href = CommentMarkupView._absoluteUrl(link.attributes['href']);
      if (href.isNotEmpty) link.attributes['href'] = href;
    }
    for (final image in fragment.querySelectorAll('img')) {
      final src = CommentMarkupView._absoluteUrl(image.attributes['src']);
      if (src.isNotEmpty) image.attributes['src'] = src;
      if (src.isNotEmpty && !image.classes.contains('smiley')) {
        images.add(src);
      }
    }
    final serialized = fragment.nodes.map(_serializeNode).join().trim();
    final text = (fragment.text ?? '').replaceAll(RegExp(r'\s+'), ' ').trim();
    return CommentDocument(
      html: serialized,
      text: text,
      images: images.toList(growable: false),
    );
  }

  static String _serializeNode(dom.Node node) => switch (node) {
    dom.Element element => element.outerHtml,
    dom.Text text => const HtmlEscape(
      HtmlEscapeMode.element,
    ).convert(text.data),
    _ => '',
  };

  static String _legacyBbCodeToHtml(String source) {
    final withoutUnsafeHtml = source.replaceAll(
      RegExp(
        r'<(?:script|style|object|embed)[^>]*>.*?</(?:script|style|object|embed)>',
        caseSensitive: false,
        dotAll: true,
      ),
      '',
    );
    var text = const HtmlEscape(
      HtmlEscapeMode.element,
    ).convert(withoutUnsafeHtml);
    text = text
        .replaceAllMapped(
          RegExp(
            r'\[img[^\]]*\](https?://.*?)\[/img\]',
            caseSensitive: false,
            dotAll: true,
          ),
          (match) => '<img src="${match.group(1)?.trim() ?? ''}">',
        )
        .replaceAllMapped(
          RegExp(r'\[image=(https?://[^\]\s]+)[^\]]*\]', caseSensitive: false),
          (match) => '<img src="${match.group(1)}">',
        )
        .replaceAllMapped(
          RegExp(r'\[image=(\d+)[^\]]*\]', caseSensitive: false),
          (match) => '<span>[изображение #${match.group(1)}]</span>',
        )
        .replaceAllMapped(
          RegExp(
            r'\[comment=(\d+)(?:;[^\]]*)?\](.*?)\[/comment\]',
            caseSensitive: false,
            dotAll: true,
          ),
          (match) =>
              '<a href="https://shikimori.io/comments/${match.group(1)}">@${match.group(2)}</a>',
        )
        .replaceAllMapped(
          RegExp(
            r'\[url=([^\]]+)\](.*?)\[/url\]',
            caseSensitive: false,
            dotAll: true,
          ),
          (match) => '<a href="${match.group(1)}">${match.group(2)}</a>',
        )
        .replaceAllMapped(
          RegExp(r'\[url\](.*?)\[/url\]', caseSensitive: false, dotAll: true),
          (match) => '<a href="${match.group(1)}">${match.group(1)}</a>',
        );
    const pairedTags = <String, String>{
      'b': 'strong',
      'i': 'em',
      'u': 'u',
      's': 's',
      'code': 'code',
      'center': 'center',
    };
    for (final entry in pairedTags.entries) {
      text = text
          .replaceAll(
            RegExp('\\[${entry.key}(?:=[^\\]]+)?\\]', caseSensitive: false),
            '<${entry.value}>',
          )
          .replaceAll(
            RegExp('\\[/${entry.key}\\]', caseSensitive: false),
            '</${entry.value}>',
          );
    }
    text = text
        .replaceAllMapped(
          RegExp(
            r'\[quote(?:=[^\]]+)?\](.*?)\[/quote\]',
            caseSensitive: false,
            dotAll: true,
          ),
          (match) =>
              '<div class="b-quote"><div class="quote-content">${match.group(1)}</div></div>',
        )
        .replaceAllMapped(
          RegExp(
            r'\[spoiler(?:=([^\]]+))?\](.*?)\[/spoiler\]',
            caseSensitive: false,
            dotAll: true,
          ),
          (match) =>
              '<div class="b-spoiler"><label>${match.group(1) ?? 'Спойлер'}</label><div>${match.group(2)}</div></div>',
        )
        .replaceAll(RegExp(r'\[\*\]\s*'), '<br>• ')
        .replaceAll(RegExp(r'\[/?list[^\]]*\]', caseSensitive: false), '')
        .replaceAll(RegExp(r'\[br\s*/?\]', caseSensitive: false), '<br>')
        .replaceAll(RegExp(r'\[hr\s*/?\]', caseSensitive: false), '<hr>')
        .replaceAll(RegExp(r'\[replies=[^\]]+\]', caseSensitive: false), '')
        .replaceAll(RegExp(r'\[/?[a-z][^\]]*\]', caseSensitive: false), '')
        .replaceAll('\r\n', '\n')
        .replaceAll('\n', '<br>');
    return text;
  }
}

String formatCommentDate(String raw) {
  final date = DateTime.tryParse(raw)?.toLocal();
  if (date == null) return '';
  final difference = DateTime.now().difference(date);
  if (difference.inMinutes < 1) return 'сейчас';
  if (difference.inHours < 1) return '${difference.inMinutes} мин';
  if (difference.inDays < 1) return '${difference.inHours} ч';
  if (difference.inDays < 7) return '${difference.inDays} д';
  return '${date.day.toString().padLeft(2, '0')}.'
      '${date.month.toString().padLeft(2, '0')}.${date.year}';
}

class CommentComposer extends StatefulWidget {
  const CommentComposer({
    required this.replyTo,
    required this.onCancelReply,
    required this.onSend,
    super.key,
  });

  final ShikimoriComment? replyTo;
  final VoidCallback onCancelReply;
  final Future<bool> Function(String text) onSend;

  @override
  State<CommentComposer> createState() => _CommentComposerState();
}

class _CommentComposerState extends State<CommentComposer> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  bool _sending = false;

  @override
  void didUpdateWidget(covariant CommentComposer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.replyTo?.id != oldWidget.replyTo?.id && widget.replyTo != null) {
      _focusNode.requestFocus();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_sending || _controller.text.trim().isEmpty) return;
    setState(() => _sending = true);
    final sent = await widget.onSend(_controller.text);
    if (!mounted) return;
    if (sent) _controller.clear();
    setState(() => _sending = false);
  }

  void _insertText(String value) {
    final text = _controller.text;
    final selection = _controller.selection.isValid
        ? _controller.selection
        : TextSelection.collapsed(offset: text.length);
    final start = selection.start.clamp(0, text.length);
    final end = selection.end.clamp(start, text.length);
    final updated = text.replaceRange(start, end, value);
    _controller.value = TextEditingValue(
      text: updated,
      selection: TextSelection.collapsed(offset: start + value.length),
    );
    _focusNode.requestFocus();
  }

  void _wrapSelection(String opening, String closing, String placeholder) {
    final text = _controller.text;
    final selection = _controller.selection.isValid
        ? _controller.selection
        : TextSelection.collapsed(offset: text.length);
    final start = selection.start.clamp(0, text.length);
    final end = selection.end.clamp(start, text.length);
    final selected = text.substring(start, end);
    final inner = selected.isEmpty ? placeholder : selected;
    final replacement = '$opening$inner$closing';
    final updated = text.replaceRange(start, end, replacement);
    final innerStart = start + opening.length;
    _controller.value = TextEditingValue(
      text: updated,
      selection: selected.isEmpty
          ? TextSelection(
              baseOffset: innerStart,
              extentOffset: innerStart + inner.length,
            )
          : TextSelection.collapsed(offset: start + replacement.length),
    );
    _focusNode.requestFocus();
  }

  Future<void> _pickSmiley() async {
    final code = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => const _ShikimoriSmileyPicker(),
    );
    if (code != null && mounted) _insertText('$code ');
  }

  @override
  Widget build(BuildContext context) => SafeArea(
    top: false,
    child: DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        border: Border(
          top: BorderSide(
            color: Theme.of(
              context,
            ).colorScheme.outlineVariant.withValues(alpha: .55),
          ),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.replyTo != null)
              Row(
                children: [
                  Icon(
                    CupertinoIcons.reply,
                    size: 13,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      'Ответ для ${widget.replyTo!.userNickname ?? 'пользователя'}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 11,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Отменить ответ',
                    visualDensity: VisualDensity.compact,
                    onPressed: widget.onCancelReply,
                    icon: const Icon(CupertinoIcons.xmark, size: 15),
                  ),
                ],
              ),
            _CommentComposerToolbar(
              onSmiley: _pickSmiley,
              onBold: () => _wrapSelection('[b]', '[/b]', 'жирный текст'),
              onItalic: () => _wrapSelection('[i]', '[/i]', 'курсив'),
              onSpoiler: () =>
                  _wrapSelection('[spoiler]', '[/spoiler]', 'спойлер'),
              onQuote: () => _wrapSelection('[quote]', '[/quote]', 'цитата'),
            ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    key: const ValueKey('comment_input'),
                    controller: _controller,
                    focusNode: _focusNode,
                    enabled: !_sending,
                    minLines: 1,
                    maxLines: 4,
                    maxLength: 4000,
                    buildCounter:
                        (
                          _, {
                          required currentLength,
                          required isFocused,
                          maxLength,
                        }) => null,
                    textInputAction: TextInputAction.newline,
                    decoration: const InputDecoration(
                      hintText: 'Написать комментарий',
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 12,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  key: const ValueKey('comment_send'),
                  tooltip: 'Отправить',
                  style: IconButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.primary,
                    foregroundColor: Theme.of(context).colorScheme.onPrimary,
                    disabledBackgroundColor: Theme.of(
                      context,
                    ).colorScheme.primary.withValues(alpha: .34),
                    disabledForegroundColor: Theme.of(
                      context,
                    ).colorScheme.onPrimary.withValues(alpha: .65),
                  ),
                  onPressed: _sending ? null : _submit,
                  icon: _sending
                      ? SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Theme.of(context).colorScheme.onPrimary,
                          ),
                        )
                      : const Icon(CupertinoIcons.arrow_up, size: 18),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

class _CommentComposerToolbar extends StatelessWidget {
  const _CommentComposerToolbar({
    required this.onSmiley,
    required this.onBold,
    required this.onItalic,
    required this.onSpoiler,
    required this.onQuote,
  });

  final VoidCallback onSmiley;
  final VoidCallback onBold;
  final VoidCallback onItalic;
  final VoidCallback onSpoiler;
  final VoidCallback onQuote;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 42,
    child: ListView(
      scrollDirection: Axis.horizontal,
      children: [
        _ComposerToolButton(
          key: const ValueKey('comment_emoji'),
          tooltip: 'Смайлы Shikimori',
          icon: CupertinoIcons.smiley,
          onPressed: onSmiley,
        ),
        _ComposerToolButton(tooltip: 'Жирный', label: 'B', onPressed: onBold),
        _ComposerToolButton(
          tooltip: 'Курсив',
          label: 'I',
          italic: true,
          onPressed: onItalic,
        ),
        _ComposerToolButton(
          tooltip: 'Спойлер',
          icon: CupertinoIcons.eye_slash,
          onPressed: onSpoiler,
        ),
        _ComposerToolButton(
          tooltip: 'Цитата',
          icon: CupertinoIcons.quote_bubble,
          onPressed: onQuote,
        ),
      ],
    ),
  );
}

class _ComposerToolButton extends StatelessWidget {
  const _ComposerToolButton({
    required this.tooltip,
    required this.onPressed,
    this.icon,
    this.label,
    this.italic = false,
    super.key,
  });

  final String tooltip;
  final VoidCallback onPressed;
  final IconData? icon;
  final String? label;
  final bool italic;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: tooltip,
    visualDensity: VisualDensity.compact,
    onPressed: onPressed,
    icon: icon != null
        ? Icon(icon, size: 18)
        : Text(
            label!,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w900,
              fontStyle: italic ? FontStyle.italic : FontStyle.normal,
            ),
          ),
  );
}

class _ShikimoriSmileyPicker extends StatelessWidget {
  const _ShikimoriSmileyPicker();

  @override
  Widget build(BuildContext context) {
    final smileys = ShikimoriSmileys.all;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 680),
        child: SizedBox(
          height: MediaQuery.sizeOf(context).height.clamp(360, 560) * .82,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Смайлы Shikimori',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Вставляются настоящим BBCode и корректно отображаются на сайте.',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: GridView.builder(
                  key: const ValueKey('shikimori_smiley_grid'),
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 62,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                  ),
                  itemCount: smileys.length,
                  itemBuilder: (context, index) {
                    final code = smileys[index];
                    return Tooltip(
                      message: code,
                      child: InkWell(
                        key: ValueKey('smiley_$code'),
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.pop(context, code),
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: Theme.of(
                              context,
                            ).colorScheme.surfaceContainerHigh,
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(
                              color: Theme.of(context)
                                  .colorScheme
                                  .outlineVariant
                                  .withValues(alpha: .55),
                            ),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(8),
                            child: CachedNetworkImage(
                              imageUrl: ShikimoriSmileys.imageUrl(code),
                              cacheManager: AniMixMediaCache.commentMedia,
                              fit: BoxFit.contain,
                              fadeInDuration: const Duration(milliseconds: 100),
                              placeholder: (_, _) =>
                                  const CupertinoActivityIndicator(radius: 8),
                              errorWidget: (_, _, _) => Center(
                                child: Text(
                                  code,
                                  maxLines: 1,
                                  overflow: TextOverflow.fade,
                                  style: const TextStyle(fontSize: 8),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CommentsFooter extends StatelessWidget {
  const _CommentsFooter({
    required this.hasMore,
    required this.loading,
    required this.failed,
    required this.onLoad,
  });

  final bool hasMore;
  final bool loading;
  final bool failed;
  final VoidCallback onLoad;

  @override
  Widget build(BuildContext context) {
    if (!hasMore && !failed) return const SizedBox(height: 8);
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Center(
        child: OutlinedButton.icon(
          key: const ValueKey('comments_load_more'),
          onPressed: loading ? null : onLoad,
          icon: loading
              ? const SizedBox.square(
                  dimension: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Icon(
                  failed ? CupertinoIcons.refresh : CupertinoIcons.chevron_down,
                  size: 15,
                ),
          label: Text(failed ? 'Повторить загрузку' : 'Загрузить ещё'),
        ),
      ),
    );
  }
}
