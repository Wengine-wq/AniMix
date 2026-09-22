import 'package:animix/features/watch/services/anime_skip_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds intro and credits sections using the next timestamp', () {
    final segments = AnimeSkipService.buildSegments(
      [
        {
          'id': 'canon',
          'at': 0,
          'type': {'name': 'Canon'},
        },
        {
          'id': 'intro',
          'at': 20,
          'type': {'name': 'Intro'},
        },
        {
          'id': 'story',
          'at': 110,
          'type': {'name': 'Canon'},
        },
        {
          'id': 'credits',
          'at': 1200,
          'type': {'name': 'Credits'},
        },
        {
          'id': 'preview',
          'at': 1290,
          'type': {'name': 'Preview'},
        },
      ],
      videoDuration: const Duration(seconds: 1320),
      baseDurationSeconds: 1320,
    );

    expect(segments, hasLength(2));
    expect(segments.first.kind, AnimeSkipSegmentKind.opening);
    expect(segments.first.start, const Duration(seconds: 20));
    expect(segments.first.end, const Duration(seconds: 110));
    expect(segments.last.kind, AnimeSkipSegmentKind.ending);
    expect(segments.last.start, const Duration(seconds: 1200));
    expect(segments.last.end, const Duration(seconds: 1290));
  });

  test('applies the documented release duration offset', () {
    final segments = AnimeSkipService.buildSegments(
      [
        {
          'id': 'intro',
          'at': 15,
          'type': {'name': 'Mixed Intro'},
        },
        {
          'id': 'canon',
          'at': 105,
          'type': {'name': 'Canon'},
        },
      ],
      videoDuration: const Duration(seconds: 1220),
      baseDurationSeconds: 1200,
    );

    expect(segments.single.start, const Duration(seconds: 35));
    expect(segments.single.end, const Duration(seconds: 125));
  });
}
