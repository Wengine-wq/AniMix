import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_settings.dart';

abstract final class AniMixTheme {
  static const background = Color(0xFF09090C);
  static const elevated = Color(0xFF101014);
  static const surface = Color(0xFF17171C);
  static const surfaceHigh = Color(0xFF202027);
  static const subtleText = Color(0xFFA7A7B1);
  static const divider = Color(0x18FFFFFF);

  static ThemeData material(Color accent, AniMixThemeStyle style) {
    final palette = switch (style) {
      AniMixThemeStyle.graphite => const (
        background: Color(0xFF09090C),
        elevated: Color(0xFF101014),
        surface: Color(0xFF17171C),
        surfaceHigh: Color(0xFF202027),
      ),
      AniMixThemeStyle.midnight => const (
        background: Color(0xFF070A12),
        elevated: Color(0xFF0C1220),
        surface: Color(0xFF121A2A),
        surfaceHigh: Color(0xFF1A263A),
      ),
      AniMixThemeStyle.oled => const (
        background: Color(0xFF000000),
        elevated: Color(0xFF080808),
        surface: Color(0xFF101010),
        surfaceHigh: Color(0xFF191919),
      ),
    };
    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          surface: palette.surface,
        ).copyWith(
          surface: palette.surface,
          surfaceContainer: palette.elevated,
          surfaceContainerHigh: palette.surfaceHigh,
          surfaceContainerHighest: palette.surfaceHigh,
        );
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: palette.background,
      canvasColor: palette.background,
      dividerColor: divider,
      splashFactory: InkSparkle.splashFactory,
      textTheme: const TextTheme(
        bodyLarge: TextStyle(fontSize: 16, height: 1.35, color: Colors.white),
        bodyMedium: TextStyle(fontSize: 14, height: 1.35, color: Colors.white),
        bodySmall: TextStyle(fontSize: 12, height: 1.3, color: subtleText),
        titleLarge: TextStyle(
          fontSize: 24,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.5,
          color: Colors.white,
        ),
        titleMedium: TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w700,
          color: Colors.white,
        ),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleTextStyle: TextStyle(
          color: Colors.white,
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.35,
        ),
      ),
      cardTheme: CardThemeData(
        color: palette.surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: divider),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 68,
        elevation: 0,
        backgroundColor: palette.elevated,
        indicatorColor: accent.withValues(alpha: 0.16),
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            color: states.contains(WidgetState.selected)
                ? Colors.white
                : subtleText,
            fontSize: 11,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w700
                : FontWeight.w500,
          ),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(15),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: palette.surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: divider),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: divider),
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: accent),
      iconTheme: const IconThemeData(color: Colors.white),
    );
  }

  static CupertinoThemeData cupertino(Color accent, AniMixThemeStyle style) =>
      CupertinoThemeData(
        brightness: Brightness.dark,
        primaryColor: accent,
        scaffoldBackgroundColor: switch (style) {
          AniMixThemeStyle.graphite => background,
          AniMixThemeStyle.midnight => const Color(0xFF070A12),
          AniMixThemeStyle.oled => Colors.black,
        },
        barBackgroundColor: switch (style) {
          AniMixThemeStyle.graphite => background.withValues(alpha: 0.96),
          AniMixThemeStyle.midnight => const Color(0xFF070A12),
          AniMixThemeStyle.oled => Colors.black,
        },
        textTheme: const CupertinoTextThemeData(
          textStyle: TextStyle(fontSize: 15, height: 1.35, color: Colors.white),
          navTitleTextStyle: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: Colors.white,
          ),
          navLargeTitleTextStyle: TextStyle(
            fontSize: 30,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.7,
            color: Colors.white,
          ),
          actionTextStyle: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      );
}
