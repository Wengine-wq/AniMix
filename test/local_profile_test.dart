import 'package:animix/core/local_profile.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await LocalProfile.load();
  });

  test('a local profile keeps its lists on the device', () async {
    expect(LocalProfile.exists, isFalse);
    await LocalProfile.create('  Илья  ');
    expect(LocalProfile.exists, isTrue);

    await LocalProfile.saveEntry(
      animeId: 21,
      status: 'watching',
      score: 9,
      episodesWatched: 100,
    );
    await LocalProfile.saveEntry(
      animeId: 1535,
      status: 'completed',
      score: 0,
      episodesWatched: 37,
    );
    await LocalProfile.saveEntry(
      animeId: 21,
      status: 'watching',
      score: 9,
      episodesWatched: 101,
    );

    final library = await LocalProfile.library();
    expect(library.map((e) => e['shikimori_id']), containsAll([21, 1535]));
    expect(library, hasLength(2));
    expect(
      library.firstWhere((e) => e['shikimori_id'] == 21)['episodes_watched'],
      101,
    );

    final account = (await LocalProfile.asAccountJson())!;
    expect(account['display_name'], 'Илья');
    expect(account['local'], isTrue);
    final stats = account['stats'] as Map;
    expect(stats['watching'], 1);
    expect(stats['completed'], 1);
    expect(stats['scores'], 1);
    expect(stats['episodes_watched'], 138);

    await LocalProfile.rename('Тхкизхвх');
    expect((await LocalProfile.read())!['display_name'], 'Тхкизхвх');

    await LocalProfile.delete();
    expect(LocalProfile.exists, isFalse);
    expect(await LocalProfile.library(), isEmpty);
  });
}
