import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/features/downloads/downloads_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('downloads empty state uses the AniMix layout', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await tester.pumpWidget(
      MaterialApp(
        theme: AniMixTheme.material(
          const Color(0xFF8B5CF6),
          AniMixThemeStyle.graphite,
        ),
        home: const DownloadsScreen(),
      ),
    );
    await tester.pump();

    expect(find.text('Загрузки'), findsOneWidget);
    expect(find.text('Нет загрузок'), findsOneWidget);
    // Rendered inside a Material page, so no debug underline can appear.
    expect(
      find.ancestor(
        of: find.text('Нет загрузок'),
        matching: find.byType(Scaffold),
      ),
      findsWidgets,
    );
  });
}
