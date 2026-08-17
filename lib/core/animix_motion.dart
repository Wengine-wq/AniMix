import 'package:animations/animations.dart';
import 'package:flutter/material.dart';

/// Shared motion language for AniMix.
///
/// Durations live here so screens do not slowly grow their own collection of
/// unrelated magic numbers. Every helper also respects the platform's reduced
/// motion preference.
abstract final class AniMixMotion {
  static const press = Duration(milliseconds: 110);
  static const selection = Duration(milliseconds: 180);
  static const content = Duration(milliseconds: 220);
  static const route = Duration(milliseconds: 280);
  static const gesture = Duration(milliseconds: 360);

  static const standardCurve = Curves.easeOutCubic;
  static const exitCurve = Curves.easeInCubic;

  static bool isReduced(BuildContext context) =>
      MediaQuery.maybeOf(context)?.disableAnimations ?? false;

  static Duration resolve(BuildContext context, Duration duration) =>
      isReduced(context) ? Duration.zero : duration;
}

/// A restrained transition for replacing one piece of content with another.
///
/// This is intended for loading/content/error and compact layout changes. It
/// should not wrap infinite lists or long-lived navigation stacks.
class AniMixFadeThrough extends StatelessWidget {
  const AniMixFadeThrough({
    required this.stateKey,
    required this.child,
    this.duration = AniMixMotion.content,
    this.fillColor = Colors.transparent,
    super.key,
  });

  final Object stateKey;
  final Widget child;
  final Duration duration;
  final Color fillColor;

  @override
  Widget build(BuildContext context) {
    if (AniMixMotion.isReduced(context)) {
      return KeyedSubtree(key: ValueKey(stateKey), child: child);
    }
    return PageTransitionSwitcher(
      duration: duration,
      reverse: false,
      transitionBuilder: (child, primary, secondary) => FadeThroughTransition(
        animation: primary,
        secondaryAnimation: secondary,
        fillColor: fillColor,
        child: child,
      ),
      child: KeyedSubtree(key: ValueKey(stateKey), child: child),
    );
  }
}
