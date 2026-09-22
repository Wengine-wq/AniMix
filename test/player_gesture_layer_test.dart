import 'package:animix/features/watch/widgets/player_gesture_layer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('double taps seek on the corresponding side', (tester) async {
    var backward = 0;
    var forward = 0;
    var playback = 0;
    var singleTap = 0;
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

    final box = tester.getRect(find.byType(PlayerGestureLayer));
    Future<void> doubleTapAt(double fraction) async {
      final point = Offset(box.left + box.width * fraction, box.center.dy);
      await tester.tapAt(point);
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tapAt(point);
      await tester.pumpAndSettle();
    }

    await doubleTapAt(.15);
    await doubleTapAt(.85);
    await doubleTapAt(.5);

    expect(backward, 1);
    expect(forward, 1);
    expect(playback, 1);
    expect(singleTap, 0);
  });
}
