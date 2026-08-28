import 'package:animix/core/shikimori_library_import.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('normalizes current v2 anime rate', () {
    final result = normalizeShikimoriAnimeRate({
      'target_id': 5114,
      'target_type': 'Anime',
      'status': 'completed',
      'score': 10,
      'episodes': 64,
    });

    expect(result, {
      'shikimori_id': 5114,
      'status': 'completed',
      'score': 10,
      'episodes_watched': 64,
    });
  });

  test('normalizes legacy rate whose id is nested under anime', () {
    final result = normalizeShikimoriAnimeRate({
      'anime': {'id': 9253},
      'status': 'rewatching',
      'score': '8',
      'episodes': '12',
    });

    expect(result, {
      'shikimori_id': 9253,
      'status': 'rewatching',
      'score': 8,
      'episodes_watched': 12,
    });
  });

  test('rejects manga and malformed status rows', () {
    expect(
      normalizeShikimoriAnimeRate({
        'target_id': 1,
        'status': 'mysterious_status',
      }),
      isNull,
    );
    expect(normalizeShikimoriAnimeRate({'status': 'planned'}), isNull);
  });
}
