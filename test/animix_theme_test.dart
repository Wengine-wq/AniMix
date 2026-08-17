import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('light and dark AniMix themes expose readable color schemes', () {
    const accent = Color(0xFF8B5CF6);
    final light = AniMixTheme.material(
      accent,
      AniMixThemeStyle.translucent,
      brightness: Brightness.light,
    );
    final dark = AniMixTheme.material(
      accent,
      AniMixThemeStyle.translucent,
      brightness: Brightness.dark,
    );

    expect(light.brightness, Brightness.light);
    expect(
      ThemeData.estimateBrightnessForColor(light.colorScheme.onSurface),
      Brightness.dark,
    );
    expect(light.scaffoldBackgroundColor.computeLuminance(), greaterThan(.75));
    expect(dark.brightness, Brightness.dark);
    expect(dark.scaffoldBackgroundColor.computeLuminance(), lessThan(.03));
    expect(light.extension<AniMixVisualStyle>()?.translucent, isTrue);
  });
}
