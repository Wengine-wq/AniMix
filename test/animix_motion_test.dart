import 'package:animix/core/animix_motion.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('fade-through becomes immediate when reduced motion is enabled', (
    tester,
  ) async {
    Widget app(String value) => MaterialApp(
      home: MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: Scaffold(
          body: AniMixFadeThrough(stateKey: value, child: Text(value)),
        ),
      ),
    );

    await tester.pumpWidget(app('Загрузка'));
    expect(find.text('Загрузка'), findsOneWidget);

    await tester.pumpWidget(app('Готово'));
    await tester.pump();

    expect(find.text('Загрузка'), findsNothing);
    expect(find.text('Готово'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('normal fade-through keeps outgoing content during transition', (
    tester,
  ) async {
    Widget app(String value) => MaterialApp(
      home: Scaffold(
        body: AniMixFadeThrough(stateKey: value, child: Text(value)),
      ),
    );

    await tester.pumpWidget(app('До'));
    await tester.pumpWidget(app('После'));
    await tester.pump(const Duration(milliseconds: 80));

    expect(find.text('После'), findsOneWidget);
    expect(find.text('До'), findsOneWidget);

    await tester.pumpAndSettle();
    expect(find.text('До'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
