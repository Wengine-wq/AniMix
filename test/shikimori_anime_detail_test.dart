import 'package:flutter_test/flutter_test.dart';

import 'package:animix/models/shikimori_anime_detail.dart';

void main() {
  test('normalizes Russian and API status names to stable keys', () {
    final detail = ShikimoriAnimeDetail.fromJson({
      'id': 1,
      'rates_statuses_stats': [
        {'name': 'Запланировано', 'value': 50068},
        {'name': 'Просмотрено', 'value': 98341},
        {'name': 'Смотрю', 'value': 12661},
        {'name': 'on-hold', 'value': 6754},
        {'name': 'Брошено', 'value': 6109},
      ],
    });

    expect(detail.statusStats['planned'], 50068);
    expect(detail.statusStats['completed'], 98341);
    expect(detail.statusStats['watching'], 12661);
    expect(detail.statusStats['on_hold'], 6754);
    expect(detail.statusStats['dropped'], 6109);
  });

  test('merges duplicate aliases instead of replacing a count', () {
    final detail = ShikimoriAnimeDetail.fromJson({
      'id': 1,
      'rates_statuses_stats': [
        {'name': 'planned', 'value': 4},
        {'name': 'В планах', 'value': 3},
      ],
    });

    expect(detail.statusStats['planned'], 7);
  });

  test('accepts map-shaped fallback stats and numeric strings', () {
    final detail = ShikimoriAnimeDetail.fromJson({
      'id': 1,
      'ratesStatusesStats': {'planned': '42', 'completed': 9},
    });

    expect(detail.statusStats, {'planned': 42, 'completed': 9});
  });
}
