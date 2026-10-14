import 'package:animix/features/import/anixart_import.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:flutter_test/flutter_test.dart';

ShikimoriAnime _anime(
  String name,
  String russian,
  String aired,
  int eps, {
  String kind = 'tv',
}) => ShikimoriAnime.fromJson({
  'id': 1,
  'name': name,
  'russian': russian,
  'aired_on': aired,
  'episodes': eps,
  'kind': kind,
});

AnixartRelease _release(
  String original,
  String ru,
  int year,
  int eps, {
  AnixartList list = AnixartList.completed,
  bool movie = false,
}) => AnixartRelease(
  id: 1,
  list: list,
  titleRu: ru,
  titleOriginal: original,
  year: year,
  episodesTotal: eps,
  isMovie: movie,
  lastViewEpisode: 3,
);

void main() {
  test('names match ignoring case and punctuation', () {
    final release = _release('Toradora!', 'Торадора!', 2008, 25);
    expect(
      AnixartMatcher.score(
        release,
        _anime('Toradora!', 'Торадора!', '2008-10-02', 25),
      ),
      greaterThanOrEqualTo(6),
    );
    expect(
      AnixartMatcher.score(
        _release('Yahari Ore no Seishun', '', 2013, 13),
        _anime('Yahari Ore no Seishun.', '', '2013-04-05', 13),
      ),
      greaterThanOrEqualTo(4),
    );
  });

  test('a different title only scores on year and format', () {
    final release = _release('Clannad', 'Кланнад', 2007, 23);
    expect(
      AnixartMatcher.score(release, _anime('Air', 'Воздух', '2005-01-06', 13)),
      lessThan(4),
    );
  });

  test('episodes to record depend on the list', () {
    expect(_release('a', '', 2020, 12).episodesWatched, 12);
    expect(
      _release('a', '', 2020, 12, list: AnixartList.watching).episodesWatched,
      3,
    );
  });
}
