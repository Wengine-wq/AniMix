// ignore_for_file: invalid_use_of_visible_for_testing_member
import 'dart:convert';
// Visual previews of key screens for design review. Not part of the regular
// suite (lives outside test/). Run:
//   flutter test test_preview --update-goldens
// PNGs are written to test_preview/out/.
import 'dart:io';

import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/catalog/catalog_screen.dart';
import 'package:animix/features/downloads/downloads_screen.dart';
import 'package:animix/features/downloads/hls_download_manager.dart';
import 'package:animix/features/home/home_screen.dart';
import 'package:animix/features/home/anime_search_sheet.dart';
import 'package:animix/features/profile/profile_screen.dart';
import 'package:animix/features/profile/settings_screen.dart';
import 'package:animix/features/wrapped/wrapped_banner.dart';
import 'package:animix/features/wrapped/wrapped_data.dart';
import 'package:animix/features/wrapped/wrapped_screen.dart';
import 'package:animix/features/wrapped/wrapped_stats.dart';
import 'package:animix/features/recommendation/recommendation_screen.dart';
import 'package:animix/main.dart';
import 'package:animix/features/auth/login_screen.dart';
import 'package:animix/core/server_health.dart';
import 'package:animix/core/server_status.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:animix/models/shikimori_anime_detail.dart';
import 'package:animix/features/anime_detail/anime_detail_screen.dart';
import 'package:animix/models/shikimori_user.dart';
import 'package:animix/core/shikimori_api_client.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:animix/providers/user_provider.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> _loadFont(String family, List<String> paths) async {
  final loader = FontLoader(family);
  for (final path in paths) {
    final bytes = File(path).readAsBytesSync();
    loader.addFont(Future.value(ByteData.view(bytes.buffer)));
  }
  await loader.load();
}

ShikimoriAnime _anime(int id) => ShikimoriAnime.fromJson({
  'id': id,
  'name': 'Anime $id',
  'russian': [
    'Магическая битва',
    'Ходячий замок',
    'Атака титанов',
    'Фрирен, провожающая в последний путь',
    'Ванпанчмен',
    'Клинок, рассекающий демонов',
  ][id % 6],
  'image': <String, dynamic>{},
  'score': '${7 + (id % 3)}.${id % 10}',
  'status': id.isEven ? 'released' : 'ongoing',
  'kind': 'tv',
  'episodes': 12 + id,
  'aired_on': '202${id % 6}-04-01',
  'genres': [
    {'russian': 'Экшен'},
    {'russian': 'Фэнтези'},
  ],
});

class _FakeApi extends ShikimoriApiClient {
  _FakeApi(super.ref);

  @override
  Future<List<ShikimoriAnime>> getAnimes({
    int page = 1,
    int limit = 30,
    Map<String, dynamic> filters = const {},
  }) async => page > 1 ? const [] : List.generate(limit.clamp(1, 12), _anime);

  @override
  Future<ShikimoriAnimeDetail> getAnimeDetail(int id) async =>
      ShikimoriAnimeDetail.fromJson({
        'id': id,
        'russian': 'Фрирен, провожающая в последний путь',
        'name': 'Sousou no Frieren',
        'description':
            'Эльфийка-волшебница Фрирен вместе с героем Химмелем и его отрядом '
            'победила Короля демонов. Спустя пятьдесят лет она отправляется в '
            'новое путешествие, чтобы лучше понять людей и время, которое '
            'течёт для них совсем иначе.',
        'image': <String, dynamic>{},
        'score': '9.3',
        'status': 'released',
        'kind': 'tv',
        'episodes': 28,
        'episodes_aired': 28,
        'aired_on': '2023-09-29',
        'released_on': '2024-03-22',
        'duration': 24,
        'rating': 'pg_13',
        'genres': [
          {'russian': 'Приключения'},
          {'russian': 'Драма'},
          {'russian': 'Фэнтези'},
        ],
        'studios': [
          {'name': 'Madhouse'},
        ],
        'rates_statuses_stats': [
          {'name': 'Смотрю', 'value': 1200},
        ],
      });

  @override
  Future<List<String>> getAnimeScreenshots(int animeId) async => const [];

  @override
  Future<List<ShikimoriAnime>> getSimilarAnimes(int animeId) async =>
      List.generate(6, _anime);

  @override
  Future<List<Map<String, dynamic>>> getRelatedAnimes(int animeId) async =>
      const [];
}

void main() {
  final flutterRoot =
      Platform.environment['FLUTTER_ROOT'] ?? 'E:/flittersdk/flutter';
  final fonts = '$flutterRoot/bin/cache/artifacts/material_fonts';
  final pubCache =
      '${Platform.environment['LOCALAPPDATA']}/Pub/Cache/hosted/pub.dev';

  setUpAll(() async {
    await _loadFont('Roboto', [
      '$fonts/roboto-regular.ttf',
      '$fonts/roboto-medium.ttf',
      '$fonts/roboto-bold.ttf',
    ]);
    await _loadFont('MaterialIcons', ['$fonts/materialicons-regular.otf']);
    await _loadFont('packages/cupertino_icons/CupertinoIcons', [
      '$pubCache/cupertino_icons-1.0.9/assets/CupertinoIcons.ttf',
    ]);
    await _loadFont('CupertinoIcons', [
      '$pubCache/cupertino_icons-1.0.9/assets/CupertinoIcons.ttf',
    ]);
  });

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    FlutterSecureStorage.setMockInitialValues({});
  });

  final profile = ShikimoriUser.localFromAniMixJson({
    'display_name': 'Илья',
    'shikimori_linked': true,
  });

  Future<void> shoot(
    WidgetTester tester,
    String name,
    Widget screen, {
    Size size = const Size(390, 844),
    Brightness brightness = Brightness.dark,
    bool loggedIn = true,
    Future<void> Function(WidgetTester tester)? before,
  }) async {
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final theme = AniMixTheme.material(
      AniMixAccent.violet.color,
      AniMixThemeStyle.graphite,
      brightness: brightness,
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          isLoggedInProvider.overrideWith((ref) async => loggedIn),
          currentUserProvider.overrideWith(
            (ref) async => loggedIn ? profile : null,
          ),
          apiClientProvider.overrideWith((ref) => _FakeApi(ref)),
          bookmarksProvider.overrideWith(
            (ref) async => [
              for (var i = 0; i < 8; i++)
                BookmarkEntry(
                  anime: _anime(i),
                  status: 'watching',
                  score: i.isEven ? 8 : 0,
                  watchedEpisodes: i * 2,
                ),
            ],
          ),
          homeDataProvider.overrideWith(
            (ref) async => HomeData(
              hero: List.generate(4, _anime),
              popular: List.generate(10, _anime),
              ongoing: List.generate(10, (i) => _anime(i + 3)),
              topRated: List.generate(10, (i) => _anime(i + 5)),
              announced: List.generate(10, (i) => _anime(i + 7)),
            ),
          ),
        ],
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: theme,
          home: screen,
        ),
      ),
    );
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    if (before != null) {
      await before(tester);
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 250));
      }
    }
    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('out/$name.png'),
    );
    // Let pending timers (image deadlines, animations) settle.
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 11));
  }

  final which = Platform.environment['PREVIEW'];
  bool want(String name) => which == null || which.split(',').contains(name);

  if (want('shell')) {
    testWidgets('shell', (t) => shoot(t, 'shell', const MainWrapper()));
  }
  if (want('home')) {
    testWidgets('home', (t) => shoot(t, 'home', const HomeScreen()));
    testWidgets(
      'home_light',
      (t) => shoot(
        t,
        'home_light',
        const HomeScreen(),
        brightness: Brightness.light,
      ),
    );
    testWidgets(
      'home_wide',
      (t) => shoot(
        t,
        'home_wide',
        const MainWrapper(),
        size: const Size(1280, 800),
      ),
    );
  }
  if (want('detail')) {
    testWidgets(
      'detail',
      (t) => shoot(t, 'detail', const AnimeDetailScreen(animeId: 5)),
    );
    testWidgets(
      'detail_light',
      (t) => shoot(
        t,
        'detail_light',
        const AnimeDetailScreen(animeId: 5),
        brightness: Brightness.light,
      ),
    );
  }
  if (want('search')) {
    testWidgets(
      'search',
      (t) => shoot(t, 'search', const Scaffold(body: AnimeSearchSheet())),
    );
  }
  if (want('catalog')) {
    testWidgets('catalog', (t) => shoot(t, 'catalog', const CatalogScreen()));
  }
  if (want('recs')) {
    testWidgets('recs', (t) => shoot(t, 'recs', const RecommendationScreen()));
  }
  if (want('downloads')) {
    testWidgets('downloads', (t) async {
      SharedPreferences.setMockInitialValues({
        'animix_hls_downloads_v1': jsonEncode([
          for (final (id, title) in [
            (1, 'Фрирен, провожающая в последний путь'),
            (2, 'Магическая битва'),
            (3, 'Ванпанчмен'),
          ])
            for (var n = 1; n <= id + 1; n++)
              {
                'episodeId': '${id}_kodik_$n',
                'animeId': id,
                'animeTitle': title,
                'episodeName': 'Серия $n',
                'quality': '720p',
                'progress': n == 2 && id == 3 ? .4 : 1.0,
                'state': n == 2 && id == 3 ? 'downloading' : 'completed',
                'fileSizeBytes': 180 * 1024 * 1024,
              },
        ]),
      });
      await t.runAsync(HlsDownloadManager.instance.initialize);
      await shoot(t, 'downloads', const DownloadsScreen());
    });
  }
  if (want('profile')) {
    testWidgets('profile', (t) => shoot(t, 'profile', const ProfileScreen()));
  }
  if (want('settings')) {
    testWidgets(
      'settings',
      (t) => shoot(t, 'settings', const SettingsScreen()),
    );
    testWidgets(
      'about_changelog',
      (t) => shoot(
        t,
        'about_changelog',
        const SettingsScreen(),
        size: const Size(390, 1900),
        before: (tester) async {
          await tester.tap(find.text('О приложении'));
          await tester.pump(const Duration(milliseconds: 600));
          await tester.drag(find.byType(ListView).last, const Offset(0, -560));
        },
      ),
    );
  }
  if (want('wrapped')) {
    final data = WrappedData(
      stats: WrappedStats(
        year: 2026,
        watchSeconds: 312 * 3600,
        episodes: 486,
        titles: 31,
        episodesByAnime: const {5: 64, 3: 40},
        marathonDay: DateTime(2026, 3, 14),
        marathonEpisodes: 17,
        busiestMonth: 7,
        busiestMonthSeconds: 52 * 3600,
        activeDays: 211,
      ),
      anime: {5: _anime(5), 3: _anime(3)},
      genres: const [
        MapEntry('Фэнтези', 0.34),
        MapEntry('Приключения', 0.22),
        MapEntry('Драма', 0.12),
      ],
      achievementIndices: const [0, 1, 2, 4, 6],
    );
    for (var slide = 0; slide < 9; slide++) {
      testWidgets(
        'wrapped_$slide',
        (t) => shoot(
          t,
          'wrapped_$slide',
          WrappedScreen(year: 2026, previewData: data, previewSlide: slide),
        ),
      );
    }
    testWidgets(
      'wrapped_banner',
      (t) => shoot(
        t,
        'wrapped_banner',
        Scaffold(
          body: SafeArea(
            child: Column(
              children: [
                WrappedBanner(debugNow: DateTime(2026, 11, 8, 18, 42, 7)),
              ],
            ),
          ),
        ),
      ),
    );
  }
  if (want('guest')) {
    testWidgets(
      'guest_login',
      (t) => shoot(
        t,
        'guest_login',
        const LoginScreen(),
        loggedIn: false,
        before: (tester) async {
          ServerStatusService.instance.status.value =
              ServerStatusInfo.fromProbe(
                error: DioExceptionType.connectionTimeout,
                timeout: const Duration(seconds: 10),
                at: DateTime(2026, 10, 11, 19, 12),
              );
        },
      ),
    );
    testWidgets(
      'guest_login_wide',
      (t) => shoot(
        t,
        'guest_login_wide',
        const LoginScreen(),
        loggedIn: false,
        size: const Size(1280, 800),
      ),
    );
    for (final (name, screen) in [
      ('guest_home', const MainWrapper() as Widget),
      ('guest_profile', const ProfileScreen()),
      ('guest_recs', const RecommendationScreen()),
    ]) {
      testWidgets(name, (t) {
        ServerHealth.instance.markDown();
        addTearDown(ServerHealth.instance.markUp);
        return shoot(t, name, screen, loggedIn: false);
      });
    }
  }
}
