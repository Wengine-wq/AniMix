import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_settings.dart';

@immutable
class AniMixVisualStyle extends ThemeExtension<AniMixVisualStyle> {
  const AniMixVisualStyle({required this.translucent});

  final bool translucent;

  @override
  AniMixVisualStyle copyWith({bool? translucent}) =>
      AniMixVisualStyle(translucent: translucent ?? this.translucent);

  @override
  AniMixVisualStyle lerp(AniMixVisualStyle? other, double t) =>
      t < .5 ? this : (other ?? this);
}

/// Shared spatial rhythm for screens and reusable components.
abstract final class AniMixSpacing {
  static const xxs = 4.0;
  static const xs = 8.0;
  static const sm = 12.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
  static const xxl = 44.0;
}

abstract final class AniMixLayout {
  static const contentMaxWidth = 1180.0;
  static const readingMaxWidth = 960.0;
  static const pageInset = AniMixSpacing.lg;
}

abstract final class AniMixTheme {
  static const background = Color(0xFF09090C);
  static const elevated = Color(0xFF0E0E12);
  static const surface = Color(0xFF141418);
  static const surfaceHigh = Color(0xFF1B1B20);
  // Neutral fallback tokens that remain legible in both brightness modes.
  // New widgets should prefer ColorScheme.onSurfaceVariant/outlineVariant.
  static const subtleText = Color(0xFF777984);
  static const divider = Color(0x287A7C86);

  static ThemeData material(
    Color accent,
    AniMixThemeStyle style, {
    Brightness brightness = Brightness.dark,
  }) {
    final dark = brightness == Brightness.dark;
    final palette = dark
        ? switch (style) {
            AniMixThemeStyle.graphite => const (
              background: Color(0xFF09090C),
              elevated: Color(0xFF0E0E12),
              surface: Color(0xFF141418),
              surfaceHigh: Color(0xFF1B1B20),
            ),
            AniMixThemeStyle.midnight => const (
              background: Color(0xFF070A12),
              elevated: Color(0xFF0C1220),
              surface: Color(0xFF121A2A),
              surfaceHigh: Color(0xFF1A263A),
            ),
            AniMixThemeStyle.translucent => const (
              background: Color(0xFF071018),
              elevated: Color(0xC2162230),
              surface: Color(0xA9192633),
              surfaceHigh: Color(0xC2223241),
            ),
            AniMixThemeStyle.oled => const (
              background: Color(0xFF000000),
              elevated: Color(0xFF080808),
              surface: Color(0xFF101010),
              surfaceHigh: Color(0xFF191919),
            ),
          }
        : switch (style) {
            AniMixThemeStyle.graphite => const (
              background: Color(0xFFF5F6FA),
              elevated: Color(0xFFFFFFFF),
              surface: Color(0xFFFFFFFF),
              surfaceHigh: Color(0xFFEAECF2),
            ),
            AniMixThemeStyle.midnight => const (
              background: Color(0xFFF2F6FC),
              elevated: Color(0xFFF8FBFF),
              surface: Color(0xFFFFFFFF),
              surfaceHigh: Color(0xFFE5EDF8),
            ),
            AniMixThemeStyle.translucent => const (
              background: Color(0xFFEFF7FB),
              elevated: Color(0xDFFFFFFF),
              surface: Color(0xCFFFFFFF),
              surfaceHigh: Color(0xE8E8F2F7),
            ),
            AniMixThemeStyle.oled => const (
              background: Color(0xFFF8F8F8),
              elevated: Color(0xFFFFFFFF),
              surface: Color(0xFFFFFFFF),
              surfaceHigh: Color(0xFFECECEC),
            ),
          };
    final foreground = dark ? Colors.white : const Color(0xFF17171C);
    final secondary = dark ? subtleText : const Color(0xFF666873);
    final outline = dark ? divider : const Color(0x16000000);
    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: brightness,
          surface: palette.surface,
        ).copyWith(
          surface: palette.surface,
          surfaceContainer: palette.elevated,
          surfaceContainerHigh: palette.surfaceHigh,
          surfaceContainerHighest: palette.surfaceHigh,
        );
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: palette.background,
      canvasColor: palette.background,
      dividerColor: outline,
      splashFactory: InkSparkle.splashFactory,
      textTheme: TextTheme(
        bodyLarge: TextStyle(fontSize: 16, height: 1.48, color: foreground),
        bodyMedium: TextStyle(fontSize: 14, height: 1.44, color: foreground),
        bodySmall: TextStyle(fontSize: 12, height: 1.4, color: secondary),
        titleLarge: TextStyle(
          fontSize: 24,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.5,
          height: 1.16,
          color: foreground,
        ),
        titleMedium: TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w700,
          height: 1.2,
          color: foreground,
        ),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: Colors.transparent,
        foregroundColor: foreground,
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
        toolbarHeight: 72,
        titleTextStyle: TextStyle(
          color: foreground,
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
          side: BorderSide(color: outline),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 64,
        elevation: 0,
        backgroundColor: palette.elevated,
        indicatorColor: accent.withValues(alpha: 0.16),
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            color: states.contains(WidgetState.selected)
                ? foreground
                : secondary,
            fontSize: 11,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w700
                : FontWeight.w500,
          ),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(50, 50),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: palette.surface,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 18,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: outline),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: outline),
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: accent),
      iconTheme: IconThemeData(color: foreground),
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.windows: FadeUpwardsPageTransitionsBuilder(),
        },
      ),
      extensions: <ThemeExtension<dynamic>>[
        AniMixVisualStyle(translucent: style == AniMixThemeStyle.translucent),
      ],
    );
  }

  static CupertinoThemeData cupertino(
    Color accent,
    AniMixThemeStyle style, {
    Brightness brightness = Brightness.dark,
  }) {
    final dark = brightness == Brightness.dark;
    final foreground = dark ? Colors.white : const Color(0xFF17171C);
    final background = dark
        ? switch (style) {
            AniMixThemeStyle.graphite => AniMixTheme.background,
            AniMixThemeStyle.midnight => const Color(0xFF070A12),
            AniMixThemeStyle.translucent => const Color(0xFF071018),
            AniMixThemeStyle.oled => Colors.black,
          }
        : switch (style) {
            AniMixThemeStyle.graphite => const Color(0xFFF5F6FA),
            AniMixThemeStyle.midnight => const Color(0xFFF2F6FC),
            AniMixThemeStyle.translucent => const Color(0xFFEFF7FB),
            AniMixThemeStyle.oled => const Color(0xFFF8F8F8),
          };
    return CupertinoThemeData(
      brightness: brightness,
      primaryColor: accent,
      scaffoldBackgroundColor: background,
      barBackgroundColor: background.withValues(
        alpha: style == AniMixThemeStyle.translucent ? .78 : .96,
      ),
      textTheme: CupertinoTextThemeData(
        textStyle: TextStyle(fontSize: 15, height: 1.35, color: foreground),
        navTitleTextStyle: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w700,
          color: foreground,
        ),
        navLargeTitleTextStyle: TextStyle(
          fontSize: 30,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.7,
          color: foreground,
        ),
        actionTextStyle: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
      ),
    );
  }

  static bool isTranslucent(BuildContext context) =>
      Theme.of(context).extension<AniMixVisualStyle>()?.translucent ?? false;
}
