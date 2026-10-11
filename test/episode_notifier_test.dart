import 'package:animix/core/episode_notifier.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:flutter_test/flutter_test.dart';

ShikimoriAnime _anime(int id, int aired) => ShikimoriAnime.fromJson({
  'id': id,
  'name': 'Anime $id',
  'status': 'ongoing',
  'episodes_aired': aired,
});

void main() {
  test('reports only titles whose aired count grew', () {
    final followed = {
      1: const FollowedOngoing(id: 1, title: 'Фрирен', aired: 6),
      2: const FollowedOngoing(id: 2, title: 'Магическая битва', aired: 10),
      3: const FollowedOngoing(id: 3, title: 'Ванпанчмен', aired: 0),
    };
    final updates = EpisodeNotifier.diff(followed, [
      _anime(1, 7),
      _anime(2, 10),
      _anime(3, 2),
      _anime(4, 12),
    ]);
    expect(updates.map((u) => u.id), [1, 3]);
    expect(updates.first.body, 'Вышла 7 серия. Можно смотреть!');
    expect(updates.last.body, 'Вышли серии 1–2. Можно смотреть!');
  });
}
