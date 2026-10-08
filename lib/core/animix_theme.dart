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

/// Corner radii. Quiet UI keeps a small set so shapes read as one family.
abstract final class AniMixRadius {
  static const sm = 8.0;
  static const md = 14.0;
  static const lg = 18.0;
  static const xl = 24.0;
}

abstract final class AniMixLayout {
  static const contentMaxWidth = 1180.0;
  static const readingMaxWidth = 960.0;
  static const pageInset = AniMixSpacing.lg;
}

abstract final class AniMixTheme {
  static const background = Color(0xFF0C0C0F);
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
              background: Color(0xFF0C0C0F),
              elevated: Color(0xFF121216),
              surface: Color(0xFF16161A),
              surfaceHigh: Color(0xFF1F1F24),
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
              background: Color(0xFFF7F7F9),
              elevated: Color(0xFFFFFFFF),
              surface: Color(0xFFFFFFFF),
              surfaceHigh: Color(0xFFEFEFF3),
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
    final foreground = dark ? const Color(0xFFF4F4F6) : const Color(0xFF16161B);
    final secondary = dark ? const Color(0xFF8B8C96) : const Color(0xFF6B6D78);
    // Quiet UI: hairlines are a last resort, surfaces separate by tone.
    final outline = dark ? const Color(0x14FFFFFF) : const Color(0x0F000000);
    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: brightness,
          surface: palette.surface,
        ).copyWith(
          primary: accent,
          surface: palette.surface,
          surfaceContainerLowest: palette.background,
          surfaceContainerLow: palette.elevated,
          surfaceContainer: palette.elevated,
          surfaceContainerHigh: palette.surfaceHigh,
          surfaceContainerHighest: palette.surfaceHigh,
          onSurface: foreground,
          onSurfaceVariant: secondary,
          outline: outline,
          outlineVariant: outline,
        );
    final radius = BorderRadius.circular(AniMixRadius.md);
    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: palette.background,
      canvasColor: palette.background,
      dividerColor: outline,
      splashFactory: InkRipple.splashFactory,
      hoverColor: foreground.withValues(alpha: .04),
      highlightColor: foreground.withValues(alpha: .03),
      splashColor: foreground.withValues(alpha: .06),
      textTheme: TextTheme(
        displaySmall: TextStyle(
          fontSize: 34,
          fontWeight: FontWeight.w700,
          letterSpacing: -1.1,
          height: 1.08,
          color: foreground,
        ),
        headlineSmall: TextStyle(
          fontSize: 26,
          fontWeight: FontWeight.w700,
          letterSpacing: -.7,
          height: 1.12,
          color: foreground,
        ),
        bodyLarge: TextStyle(fontSize: 16, height: 1.5, color: foreground),
        bodyMedium: TextStyle(fontSize: 14, height: 1.46, color: foreground),
        bodySmall: TextStyle(fontSize: 12, height: 1.4, color: secondary),
        titleLarge: TextStyle(
          fontSize: 22,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.5,
          height: 1.16,
          color: foreground,
        ),
        titleMedium: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: -.15,
          height: 1.22,
          color: foreground,
        ),
        titleSmall: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w600,
          height: 1.25,
          color: foreground,
        ),
        labelLarge: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        labelMedium: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w500,
          color: secondary,
        ),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        foregroundColor: foreground,
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
        toolbarHeight: 64,
        titleTextStyle: TextStyle(
          color: foreground,
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.4,
        ),
      ),
      cardTheme: CardThemeData(
        color: palette.surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AniMixRadius.lg),
        ),
      ),
      dividerTheme: DividerThemeData(color: outline, thickness: 1, space: 1),
      listTileTheme: ListTileThemeData(
        shape: RoundedRectangleBorder(borderRadius: radius),
        iconColor: secondary,
        subtitleTextStyle: TextStyle(fontSize: 12.5, color: secondary),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: palette.surfaceHigh,
        selectedColor: accent.withValues(alpha: dark ? .22 : .14),
        side: BorderSide.none,
        shape: const StadiumBorder(),
        labelStyle: TextStyle(
          color: foreground,
          fontSize: 13,
          fontWeight: FontWeight.w500,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        showCheckmark: false,
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 64,
        elevation: 0,
        backgroundColor: palette.elevated,
        indicatorColor: accent.withValues(alpha: 0.14),
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            color: states.contains(WidgetState.selected)
                ? foreground
                : secondary,
            fontSize: 11,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w600
                : FontWeight.w500,
          ),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48),
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 20),
          shape: RoundedRectangleBorder(borderRadius: radius),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          foregroundColor: foreground,
          backgroundColor: palette.surfaceHigh,
          side: BorderSide.none,
          shape: RoundedRectangleBorder(borderRadius: radius),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: radius),
          textStyle: const TextStyle(fontWeight: FontWeight.w600),
        ),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: SegmentedButton.styleFrom(
          side: BorderSide.none,
          backgroundColor: palette.surfaceHigh,
          selectedBackgroundColor: accent.withValues(alpha: dark ? .24 : .16),
          selectedForegroundColor: foreground,
          shape: RoundedRectangleBorder(borderRadius: radius),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: palette.surfaceHigh,
        hintStyle: TextStyle(color: secondary),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
        border: OutlineInputBorder(
          borderRadius: radius,
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: radius,
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: radius,
          borderSide: BorderSide(color: accent.withValues(alpha: .6)),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: palette.elevated,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(AniMixRadius.xl),
          ),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: palette.elevated,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AniMixRadius.xl),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        elevation: 0,
        backgroundColor: palette.surfaceHigh,
        contentTextStyle: TextStyle(color: foreground),
        shape: RoundedRectangleBorder(borderRadius: radius),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: palette.surfaceHigh,
        surfaceTintColor: Colors.transparent,
        elevation: 2,
        shape: RoundedRectangleBorder(borderRadius: radius),
      ),
      tooltipTheme: TooltipThemeData(
        waitDuration: const Duration(milliseconds: 500),
        decoration: BoxDecoration(
          color: palette.surfaceHigh,
          borderRadius: BorderRadius.circular(AniMixRadius.sm),
        ),
        textStyle: TextStyle(color: foreground, fontSize: 12),
      ),
      switchTheme: SwitchThemeData(
        trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
        thumbColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.selected) ? Colors.white : secondary,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? accent
              : palette.surfaceHigh,
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: accent,
        linearTrackColor: palette.surfaceHigh,
      ),
      iconTheme: IconThemeData(color: foreground),
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.windows: FadeForwardsPageTransitionsBuilder(),
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
            AniMixThemeStyle.graphite => const Color(0xFFF7F7F9),
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
