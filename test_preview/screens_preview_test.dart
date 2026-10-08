// ignore_for_file: invalid_use_of_visible_for_testing_member
// Visual previews of key screens for design review. Not part of the regular
// suite (lives outside test/). Run:
//   flutter test test_preview --update-goldens
// PNGs are written to test_preview/out/.
import 'dart:io';

import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/catalog/catalog_screen.dart';
import 'package:animix/features/downloads/downloads_screen.dart';
import 'package:animix/features/home/home_screen.dart';
import 'package:animix/features/profile/profile_screen.dart';
import 'package:animix/features/profile/settings_screen.dart';
import 'package:animix/features/recommendation/recommendation_screen.dart';
import 'package:animix/main.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:animix/models/shikimori_user.dart';
import 'package:animix/core/shikimori_api_client.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:animix/providers/user_provider.dart';
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
  if (want('catalog')) {
    testWidgets('catalog', (t) => shoot(t, 'catalog', const CatalogScreen()));
  }
  if (want('recs')) {
    testWidgets('recs', (t) => shoot(t, 'recs', const RecommendationScreen()));
  }
  if (want('downloads')) {
    testWidgets(
      'downloads',
      (t) => shoot(t, 'downloads', const DownloadsScreen()),
    );
  }
  if (want('profile')) {
    testWidgets('profile', (t) => shoot(t, 'profile', const ProfileScreen()));
  }
  if (want('settings')) {
    testWidgets(
      'settings',
      (t) => shoot(t, 'settings', const SettingsScreen()),
    );
  }
}
