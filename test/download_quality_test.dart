import 'package:animix/core/app_settings.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const labels = ['Авто', '1080p', '720p', '480p', '360p'];

  test('ask never auto-picks', () {
    expect(AniMixDownloadQuality.ask.pick(labels), isNull);
  });

  test('fixed heights pick that height or the nearest lower one', () {
    expect(AniMixDownloadQuality.best.pick(labels), '1080p');
    expect(AniMixDownloadQuality.p720.pick(labels), '720p');
    expect(AniMixDownloadQuality.p720.pick(['1080p', '480p']), '480p');
    expect(AniMixDownloadQuality.p480.pick(['1080p', '720p']), '720p');
    expect(AniMixDownloadQuality.smallest.pick(labels), '360p');
  });

  test('adaptive-only sources still download', () {
    expect(AniMixDownloadQuality.p720.pick(['Авто']), 'Авто');
  });
}
