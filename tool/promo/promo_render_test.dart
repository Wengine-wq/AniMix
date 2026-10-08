// Renders the promo video frame by frame.
//   Stills for review:  PROMO_STILLS=1.5,5,9 flutter test tool/promo --update-goldens
//   All frames:         flutter test tool/promo --update-goldens
// then: ffmpeg -framerate 30 -i tool/promo/frames/f%04d.png ... (see README.md)
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'promo.dart';

const _fps = 30;

Future<void> _loadFont(String family, List<String> paths) async {
  final loader = FontLoader(family);
  for (final path in paths) {
    final bytes = File(path).readAsBytesSync();
    loader.addFont(Future.value(ByteData.view(bytes.buffer)));
  }
  await loader.load();
}

Future<ui.Image> _decodeAsset(String path) async {
  final data = await rootBundle.load(path);
  final codec = await ui.instantiateImageCodec(data.buffer.asUint8List());
  return (await codec.getNextFrame()).image;
}

void main() {
  final flutterRoot =
      Platform.environment['FLUTTER_ROOT'] ?? 'E:/flittersdk/flutter';
  final fonts = '$flutterRoot/bin/cache/artifacts/material_fonts';

  testWidgets('render promo', (tester) async {
    await tester.runAsync(() async {
      await _loadFont('Roboto', [
        '$fonts/roboto-regular.ttf',
        '$fonts/roboto-medium.ttf',
        '$fonts/roboto-bold.ttf',
        '$fonts/roboto-black.ttf',
      ]);
      await _loadFont('MaterialIcons', ['$fonts/materialicons-regular.otf']);
    });
    final assets = (await tester.runAsync(
      () async => PromoAssets(
        logo: await _decodeAsset('assets/icon/app_icon.png'),
        atlas: await _decodeAsset('assets/achievements/atlas.png'),
      ),
    ))!;

    tester.view.physicalSize = kPromoSize;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    const key = ValueKey('promo');
    Future<void> shoot(double t, String name) async {
      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(size: kPromoSize),
          child: Directionality(
            textDirection: TextDirection.ltr,
            child: RepaintBoundary(
              key: key,
              child: Promo(t: t, assets: assets),
            ),
          ),
        ),
      );
      await expectLater(find.byKey(key), matchesGoldenFile(name));
    }

    final stills = Platform.environment['PROMO_STILLS'];
    if (stills != null) {
      for (final value in stills.split(',')) {
        final t = double.parse(value);
        await shoot(t, 'stills/t_${value.replaceAll('.', '_')}.png');
      }
      return;
    }
    final total = (kPromoDuration * _fps).ceil();
    final from = int.tryParse(Platform.environment['PROMO_FROM'] ?? '') ?? 0;
    final to = int.tryParse(Platform.environment['PROMO_TO'] ?? '') ?? total;
    for (var frame = from; frame < to; frame++) {
      await shoot(
        frame / _fps,
        'frames/f${frame.toString().padLeft(4, '0')}.png',
      );
    }
  }, timeout: const Timeout(Duration(hours: 1)));
}
