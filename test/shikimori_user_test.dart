import 'package:animix/models/shikimori_user.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ShikimoriUser scores', () {
    test('sums anime score distribution from the current API shape', () {
      final user = ShikimoriUser.fromJson({
        'id': 7,
        'nickname': 'tester',
        'stats': {
          'scores': {
            'anime': [
              {'name': 10, 'value': 12},
              {'name': 9, 'value': 8},
              {'name': 8, 'value': 5},
            ],
            'manga': [
              {'name': 10, 'value': 99},
            ],
          },
        },
      });

      expect(user.scores, 25);
    });

    test('reads on-hold anime as a separate library status', () {
      final user = ShikimoriUser.fromJson({
        'id': 7,
        'nickname': 'tester',
        'stats': {
          'statuses': {
            'anime': [
              {'grouped_id': 'on_hold', 'size': '11'},
            ],
          },
        },
      });

      expect(user.onHold, 11);
    });

    test('supports a top-level distribution for API compatibility', () {
      final user = ShikimoriUser.fromJson({
        'id': 7,
        'nickname': 'tester',
        'stats': {
          'scores': [
            {'name': 10, 'value': 4},
            {'name': 7, 'size': 3},
          ],
        },
      });

      expect(user.scores, 7);
    });
  });

  group('AniMix profile timestamps', () {
    test('parses unix seconds instead of treating them as a huge year', () {
      final user = ShikimoriUser.localFromAniMixJson({
        'display_name': 'Tester',
        'created_at': 1787510000,
        'shikimori_linked': true,
        'shikimori_user_id': '42',
        'stats': <String, dynamic>{},
      });

      expect(DateTime.parse(user.joinedAt!).year, lessThan(2100));
      expect(user.shikimoriLinked, isTrue);
      expect(user.shikimoriUserId, '42');
    });

    test('normalizes legacy milliseconds and microseconds identically', () {
      ShikimoriUser parse(int value) => ShikimoriUser.localFromAniMixJson({
        'display_name': 'Tester',
        'created_at': value,
        'stats': <String, dynamic>{},
      });

      expect(parse(1700000000000).joinedAt, parse(1700000000000000).joinedAt);
      expect(DateTime.parse(parse(1700000000000000).joinedAt!).year, 2023);
    });
  });
}
