import 'package:animix/core/animix_theme.dart';
import 'package:animix/features/downloads/downloads_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('downloads empty state uses the AniMix layout', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await tester.pumpWidget(
      MaterialApp(
        theme: AniMixTheme.material(const Color(0xFF8B5CF6)),
        home: const DownloadsScreen(),
      ),
    );
    await tester.pump();

    expect(find.text('Загрузки'), findsOneWidget);
    expect(find.text('Нет загрузок'), findsOneWidget);
    expect(
      tester.widget<Text>(find.text('Нет загрузок')).style?.decoration,
      TextDecoration.none,
    );
  });
}
