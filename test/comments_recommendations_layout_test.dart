import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/core/config.dart';
import 'package:animix/core/shikimori_api_client.dart';
import 'package:animix/features/data/comments_screen.dart';
import 'package:animix/features/home/home_screen.dart';
import 'package:animix/features/recommendation/recommendation_screen.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:animix/models/shikimori_comment.dart';
import 'package:animix/models/shikimori_user.dart';
import 'package:animix/providers/user_provider.dart';
import 'package:animix/widgets/animix_media_viewer.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues(<String, Object>{}));

  final theme = AniMixTheme.material(
    const Color(0xFF8B5CF6),
    AniMixThemeStyle.graphite,
  );

  Future<void> pumpAt(
    WidgetTester tester,
    Widget child, {
    Size size = const Size(320, 568),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(theme: theme, home: child));
    await tester.pump();
    expect(tester.takeException(), isNull);
  }

  testWidgets('long comment and reply thread stay finite on narrow screens', (
    tester,
  ) async {
    final parent = _comment(
      1,
      List.generate(20, (index) => 'Длинная строка номер $index').join('\n'),
    );
    final replies = [
      _comment(2, '[comment=1;7]Автор[/comment], ответ 1'),
      _comment(3, '[comment=1;7]Автор[/comment], ответ 2'),
      _comment(4, '[comment=1;7]Автор[/comment], ответ 3'),
    ];
    ShikimoriComment? selectedReply;

    await pumpAt(
      tester,
      Scaffold(
        body: SingleChildScrollView(
          child: CommentThread(
            comment: parent,
            replies: replies,
            onReply: (comment) => selectedReply = comment,
          ),
        ),
      ),
    );

    expect(find.text('Показать полностью'), findsOneWidget);
    expect(find.text('Показать ещё 1'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.ensureVisible(find.byKey(const ValueKey('reply_1')));
    await tester.tap(find.byKey(const ValueKey('reply_1')));
    expect(selectedReply?.id, 1);

    await tester.ensureVisible(
      find.byKey(const ValueKey('comment_expand_button')),
    );
    await tester.tap(find.byKey(const ValueKey('comment_expand_button')));
    await tester.pumpAndSettle();
    expect(find.text('Свернуть'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const ValueKey('replies_toggle_1')));
    await tester.tap(find.byKey(const ValueKey('replies_toggle_1')));
    await tester.pumpAndSettle();
    expect(find.textContaining('ответ 3', findRichText: true), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  test('comment document removes layout-breaking markup', () {
    final document = CommentDocument.parse(
      '[quote=User]текст[/quote]\n'
      '[img]https://example.com/a.jpg[/img]\n'
      '<script>bad()</script>[b]готово[/b]',
    );

    expect(document.html, contains('<img'), reason: document.html);
    expect(document.images, ['https://example.com/a.jpg']);
    expect(document.text, contains('текст'));
    expect(document.text, contains('готово'));
    expect(document.text, isNot(contains('bad()')));
    expect(document.text, isNot(contains('[img]')));
  });

  test('resolved Shikimori HTML keeps uploaded images and smileys', () {
    final document = CommentDocument.parse(
      '[image=1678532] :happy_cry:',
      htmlBody: '''
        <a class="b-image" href="/system/user_images/original/1/1678532.jpg">
          <img src="/system/user_images/thumbnail/1/1678532.jpg"
               data-width="1919" data-height="936">
        </a>
        <img class="smiley" src="/images/smileys/:happy_cry:.gif"
             alt=":happy_cry:">
      ''',
    );

    expect(document.images, [
      Config.proxiedImageUrl(
        'https://shikimori.io/system/user_images/thumbnail/1/1678532.jpg',
      ),
    ]);
    expect(document.html, contains('https://shikimori.io/'));
    expect(document.html, isNot(contains('/v1/media/proxy?url=')));
    expect(document.html, contains('class="smiley"'));
  });

  testWidgets('Shikimori media, smiley, quote and spoiler fit a phone card', (
    tester,
  ) async {
    final comment = ShikimoriComment.fromJson({
      'id': 77,
      'body': '[image=12] :happy_cry: [spoiler]тайна[/spoiler]',
      'html_body': '''
        <div class="b-quote"><div class="quote-content">Цитата</div></div>
        <a class="b-image" href="/system/user_images/original/1/12.jpg">
          <img src="/system/user_images/thumbnail/1/12.jpg"
               data-width="800" data-height="1200">
        </a>
        <img class="smiley" src="/images/smileys/:happy_cry:.gif"
             alt=":happy_cry:">
        <div class="b-spoiler"><label>Детали</label><div>Тайна</div></div>
      ''',
      'created_at': '2026-08-17T10:00:00Z',
      'user': {'id': 7, 'nickname': 'Автор', 'image': <String, dynamic>{}},
    });

    await pumpAt(
      tester,
      Scaffold(
        body: SingleChildScrollView(
          child: CommentTile(comment: comment, onReply: (_) {}),
        ),
      ),
    );
    await tester.pump();

    expect(find.byType(CommentRemoteImage), findsOneWidget);
    expect(find.byType(CommentSpoiler), findsOneWidget);
    expect(find.text('Детали'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('Детали'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.textContaining('Тайна', findRichText: true), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('collapsed media comment stays finite and opens in-app viewer', (
    tester,
  ) async {
    final comment = ShikimoriComment.fromJson({
      'id': 78,
      'body': '${List.filled(12, 'Длинный отзыв').join('\n')} [image=12]',
      'html_body':
          '''
        <p>${List.filled(12, 'Длинный отзыв').join('<br>')}</p>
        <a class="b-image" href="https://example.com/original.jpg">
          <img src="https://example.com/preview.jpg"
               data-width="800" data-height="1200">
        </a>
      ''',
      'created_at': '2026-08-17T10:00:00Z',
      'user': {'id': 7, 'nickname': 'Автор', 'image': <String, dynamic>{}},
    });

    await pumpAt(
      tester,
      Scaffold(
        body: SingleChildScrollView(
          child: CommentTile(comment: comment, onReply: (_) {}),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('Показать полностью'), findsOneWidget);
    expect(find.byType(CommentRemoteImage), findsOneWidget);
    final remoteImage = tester.widget<CommentRemoteImage>(
      find.byType(CommentRemoteImage),
    );
    expect(remoteImage.originalUrl, startsWith('https://'));
    expect(tester.takeException(), isNull);

    final imageTap = find.byKey(const ValueKey('comment_image_open'));
    expect(imageTap, findsOneWidget);
    await tester.ensureVisible(imageTap);
    final onTap = tester.widget<InkWell>(imageTap).onTap;
    expect(onTap, isNotNull);
    onTap!();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));
    expect(tester.takeException(), isNull);
    expect(find.byType(AniMixMediaViewer), findsOneWidget);
    expect(find.byKey(const ValueKey('media_viewer_save')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('comments expose retry after a failed first request', (
    tester,
  ) async {
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWith(
            (ref) => _FakeCommentsApiClient(ref, failFirst: true),
          ),
        ],
        child: const CommentsScreen(topicId: 42),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Комментарии не загрузились'), findsOneWidget);
    await tester.tap(find.text('Повторить'));
    await tester.pumpAndSettle();

    expect(find.text('Автор 1'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('comments auto-pagination appends once and stops duplicates', (
    tester,
  ) async {
    late _FakeCommentsApiClient api;
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWith(
            (ref) => api = _FakeCommentsApiClient(ref),
          ),
        ],
        child: const CommentsScreen(topicId: 42),
      ),
    );
    await tester.pumpAndSettle();

    for (
      var attempt = 0;
      attempt < 12 && find.text('Автор 31').evaluate().isEmpty;
      attempt++
    ) {
      await tester.drag(
        find.byKey(const ValueKey('comments_list')),
        const Offset(0, -500),
      );
      await tester.pumpAndSettle();
    }

    expect(find.text('Автор 31'), findsOneWidget);
    expect(api.calls, lessThanOrEqualTo(3));
    expect(tester.takeException(), isNull);
  });

  testWidgets('comments provide working category filters and ordering', (
    tester,
  ) async {
    final comments = [
      _comment(1, 'Обычное обсуждение'),
      _comment(2, 'Разговор не по теме', isOfftopic: true),
      _comment(3, '[comment=1;7]Автор[/comment], ответ'),
      _comment(4, '[image=42] вложение'),
    ];
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWith(
            (ref) => _FakeCommentsApiClient(ref, firstPage: comments),
          ),
        ],
        child: const CommentsScreen(topicId: 42),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('comments_order')), findsOneWidget);
    for (final filter in CommentFilter.values) {
      expect(
        find.byKey(ValueKey('comment_filter_${filter.name}')),
        findsOneWidget,
      );
    }

    final offtopic = find.byKey(const ValueKey('comment_filter_offtopic'));
    await tester.ensureVisible(offtopic);
    await tester.tap(offtopic);
    await tester.pump();
    expect(find.byKey(const ValueKey('comment_thread_2')), findsOneWidget);
    expect(find.byKey(const ValueKey('comment_thread_1')), findsNothing);

    final media = find.byKey(const ValueKey('comment_filter_media'));
    await tester.ensureVisible(media);
    await tester.tap(media);
    await tester.pump();
    expect(find.byKey(const ValueKey('comment_thread_4')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('comment composer inserts a real Shikimori smiley token', (
    tester,
  ) async {
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [apiClientProvider.overrideWith(_FakeCommentsApiClient.new)],
        child: const CommentsScreen(topicId: 42),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('comment_emoji')));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byKey(const ValueKey('shikimori_smiley_grid')), findsOneWidget);

    final firstSmiley = find.byKey(const ValueKey('smiley_:)'));
    await tester.ensureVisible(firstSmiley);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(firstSmiley);
    await tester.pump(const Duration(milliseconds: 400));

    final input = tester.widget<TextField>(
      find.byKey(const ValueKey('comment_input')),
    );
    expect(input.controller?.text, ':) ');
    expect(tester.takeException(), isNull);
  });

  testWidgets('recommendation swipe dismisses exactly one card', (
    tester,
  ) async {
    final anime = List.generate(6, (index) => _anime(index + 1));
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWith((ref) => _FakeApiClient(ref, anime)),
        ],
        child: const RecommendationScreen(),
      ),
      size: const Size(390, 844),
    );
    await tester.pumpAndSettle();

    expect(find.text('Аниме 1'), findsOneWidget);
    final card = find.byKey(const ValueKey('recommendation_1'));
    expect(card, findsOneWidget);

    await tester.drag(card, const Offset(-280, 0));
    await tester.pumpAndSettle();

    expect(find.text('Аниме 1'), findsNothing);
    expect(find.text('Аниме 2'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(milliseconds: 1400));
  });

  testWidgets('plan swipe calls API once and advances once', (tester) async {
    final anime = List.generate(6, (index) => _anime(index + 1));
    late _FakeApiClient api;
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWith(
            (ref) => api = _FakeApiClient(ref, anime),
          ),
          currentUserProvider.overrideWith(
            (ref) async =>
                ShikimoriUser.fromJson({'id': 7, 'nickname': 'Tester'}),
          ),
        ],
        child: const RecommendationScreen(),
      ),
      size: const Size(1280, 720),
    );
    await tester.pumpAndSettle();

    await tester.drag(
      find.byKey(const ValueKey('recommendation_1')),
      const Offset(420, 0),
    );
    await tester.pumpAndSettle();

    expect(api.rateWrites, 1);
    expect(find.text('Аниме 1'), findsNothing);
    expect(find.text('Аниме 2'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(milliseconds: 1400));
  });

  testWidgets('home header does not duplicate profile navigation', (
    tester,
  ) async {
    const data = HomeData(
      hero: [],
      popular: [],
      ongoing: [],
      topRated: [],
      announced: [],
    );
    await pumpAt(
      tester,
      ProviderScope(
        overrides: [
          homeDataProvider.overrideWith((ref) async => data),
          currentUserProvider.overrideWith((ref) async => null),
        ],
        child: const HomeScreen(),
      ),
      size: const Size(390, 844),
    );
    await tester.pumpAndSettle();

    expect(find.byIcon(CupertinoIcons.search), findsOneWidget);
    expect(find.byIcon(CupertinoIcons.person_crop_circle_fill), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

ShikimoriComment _comment(int id, String body, {bool isOfftopic = false}) =>
    ShikimoriComment.fromJson({
      'id': id,
      'body': body,
      'is_offtopic': isOfftopic,
      'created_at': '2026-08-17T10:00:00Z',
      'user': {'id': 7, 'nickname': 'Автор $id', 'image': <String, dynamic>{}},
    });

ShikimoriAnime _anime(int id) => ShikimoriAnime.fromJson({
  'id': id,
  'name': 'Anime $id',
  'russian': 'Аниме $id',
  'image': <String, dynamic>{},
  'score': '8.0',
  'status': 'released',
  'kind': 'tv',
  'episodes': 12,
});

class _FakeApiClient extends ShikimoriApiClient {
  _FakeApiClient(super.ref, this.items);

  final List<ShikimoriAnime> items;
  int rateWrites = 0;

  @override
  Future<List<ShikimoriAnime>> getAnimes({
    int page = 1,
    int limit = 30,
    Map<String, dynamic> filters = const {},
  }) async => items;

  @override
  Future<void> setUserRate(
    int animeId,
    String status, {
    int? score,
    int? episodes,
    required int userId,
  }) async {
    rateWrites++;
  }
}

class _FakeCommentsApiClient extends ShikimoriApiClient {
  _FakeCommentsApiClient(super.ref, {this.failFirst = false, this.firstPage});

  final bool failFirst;
  final List<ShikimoriComment>? firstPage;
  int calls = 0;

  @override
  Future<List<ShikimoriComment>> getComments(
    int topicId, {
    int page = 1,
    bool descending = true,
  }) async {
    calls++;
    if (failFirst && calls == 1) throw StateError('offline');
    if (page > 1) return [_comment(31, 'Следующая страница')];
    if (firstPage != null) return firstPage!;
    if (failFirst) return [_comment(1, 'После повтора')];
    return List.generate(30, (index) => _comment(index + 1, 'Текст'));
  }
}
