import 'package:animix/features/watch/widgets/player_gesture_layer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late int backward;
  late int forward;
  late int playback;
  late int singleTap;

  Future<Rect> pumpLayer(WidgetTester tester) async {
    backward = 0;
    forward = 0;
    playback = 0;
    singleTap = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 300,
            height: 180,
            child: PlayerGestureLayer(
              onTap: () => singleTap++,
              onSeekBackward: () => backward++,
              onSeekForward: () => forward++,
              onTogglePlayback: () => playback++,
            ),
          ),
        ),
      ),
    );
    return tester.getRect(find.byType(PlayerGestureLayer));
  }

  Offset at(Rect box, double fraction) =>
      Offset(box.left + box.width * fraction, box.center.dy);

  testWidgets('single tap fires immediately without double-tap delay', (
    tester,
  ) async {
    final box = await pumpLayer(tester);
    await tester.tapAt(at(box, .5));
    await tester.pump();
    expect(singleTap, 1);
    await tester.pump(PlayerGestureLayer.multiTapWindow);
  });

  testWidgets('double taps seek on the corresponding side', (tester) async {
    final box = await pumpLayer(tester);
    Future<void> doubleTapAt(double fraction) async {
      final point = at(box, fraction);
      await tester.tapAt(point);
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tapAt(point);
      await tester.pump(const Duration(milliseconds: 400));
    }

    await doubleTapAt(.15);
    await doubleTapAt(.85);
    await doubleTapAt(.5);

    expect(backward, 1);
    expect(forward, 1);
    expect(playback, 1);
    // The first tap of each pair toggles, the second restores the state.
    expect(singleTap.isEven, isTrue);
  });

  testWidgets('quick repeated taps on a side keep seeking', (tester) async {
    final box = await pumpLayer(tester);
    final point = at(box, .9);
    for (var i = 0; i < 4; i++) {
      await tester.tapAt(point);
      await tester.pump(const Duration(milliseconds: 80));
    }
    await tester.pump(const Duration(milliseconds: 400));
    expect(forward, 3);
    expect(singleTap, 2);
  });
}
