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
}
