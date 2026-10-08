import 'dart:ui';

import 'package:flutter/material.dart';

import '../core/animix_theme.dart';

/// A quiet tonal panel: no outline and no hover lift. Surfaces separate from
/// the page by tone only; `elevated` steps one tone up (plus a faint shadow on
/// light themes), `selected` tints with the accent.
class AniMixSurface extends StatelessWidget {
  const AniMixSurface({
    required this.child,
    this.padding = EdgeInsets.zero,
    this.radius = AniMixRadius.lg,
    this.onTap,
    this.selected = false,
    this.elevated = false,
    this.blurred = false,
    super.key,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  final VoidCallback? onTap;
  final bool selected;
  final bool elevated;

  /// Enables a real backdrop blur for transient/floating chrome only.
  ///
  /// Content cards deliberately keep this disabled: multiple backdrop filters
  /// in scrolling lists are both visually muddy and expensive to repaint.
  final bool blurred;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final translucent = AniMixTheme.isTranslucent(context);
    final base = elevated ? scheme.surfaceContainerHigh : scheme.surface;
    var color = selected
        ? Color.alphaBlend(scheme.primary.withValues(alpha: .12), base)
        : base;
    if (translucent) color = color.withValues(alpha: elevated ? .82 : .7);
    final borderRadius = BorderRadius.circular(radius);

    Widget panel = Material(
      color: color,
      borderRadius: borderRadius,
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? Padding(padding: padding, child: child)
          : InkWell(
              onTap: onTap,
              child: Padding(padding: padding, child: child),
            ),
    );
    if (translucent && blurred) {
      panel = ClipRRect(
        borderRadius: borderRadius,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14),
          child: panel,
        ),
      );
    }
    if (elevated && theme.brightness == Brightness.light) {
      panel = DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: borderRadius,
          boxShadow: const [
            BoxShadow(
              color: Color(0x0D000000),
              blurRadius: 18,
              offset: Offset(0, 6),
            ),
          ],
        ),
        child: panel,
      );
    }
    return panel;
  }
}

class AniMixPage extends StatelessWidget {
  const AniMixPage({
    required this.title,
    required this.child,
    this.actions = const [],
    this.leading,
    super.key,
  });

  final String title;
  final Widget child;
  final List<Widget> actions;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final translucent = AniMixTheme.isTranslucent(context);
    // Quiet UI: a flat page. Only the translucent style keeps a soft wash
    // so its glass surfaces have something to show through.
    return DecoratedBox(
      decoration: BoxDecoration(
        color: theme.scaffoldBackgroundColor,
        gradient: translucent
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color.alphaBlend(
                    theme.colorScheme.primary.withValues(alpha: .12),
                    theme.scaffoldBackgroundColor,
                  ),
                  theme.scaffoldBackgroundColor,
                ],
                stops: const [0, .5],
              )
            : null,
      ),
      child: Scaffold(
        backgroundColor: Colors.transparent,
        appBar: AppBar(
          title: Text(title),
          leading: leading,
          actions: [
            ...actions,
            const SizedBox(width: AniMixSpacing.xs),
          ],
        ),
        body: SafeArea(
          top: false,
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                maxWidth: AniMixLayout.contentMaxWidth,
              ),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}

class AniMixIconButton extends StatelessWidget {
  const AniMixIconButton({
    required this.icon,
    required this.onPressed,
    this.tooltip,
    this.size = 48,
    super.key,
  });

  final IconData icon;
  final VoidCallback? onPressed;
  final String? tooltip;
  final double size;

  @override
  Widget build(BuildContext context) {
    final button = Material(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onPressed,
        child: SizedBox.square(
          dimension: size,
          child: Icon(
            icon,
            size: size * .44,
            color: Theme.of(context).colorScheme.onSurface,
          ),
        ),
      ),
    );
    return tooltip == null ? button : Tooltip(message: tooltip!, child: button);
  }
}

class AniMixMetadataPill extends StatelessWidget {
  const AniMixMetadataPill({
    required this.label,
    this.icon,
    this.accent = false,
    super.key,
  });

  final String label;
  final IconData? icon;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    final color = accent
        ? Theme.of(context).colorScheme.primary
        : Theme.of(context).colorScheme.onSurface;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: accent
            ? color.withValues(alpha: .14)
            : Theme.of(context).colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 13, color: color),
            const SizedBox(width: 5),
          ],
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 12,
              height: 1,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

class AniMixSectionHeader extends StatelessWidget {
  const AniMixSectionHeader({
    required this.title,
    this.subtitle,
    this.icon,
    this.trailing,
    super.key,
  });

  final String title;
  final String? subtitle;
  /// Kept for API compatibility; quiet headers are text-only.
  final IconData? icon;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.end,
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurface,
                fontSize: 20,
                letterSpacing: -.4,
                fontWeight: FontWeight.w700,
                height: 1.18,
              ),
            ),
            if (subtitle?.isNotEmpty == true) ...[
              const SizedBox(height: AniMixSpacing.xxs),
              Text(
                subtitle!,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 13,
                  height: 1.35,
                ),
              ),
            ],
          ],
        ),
      ),
      ?trailing,
    ],
  );
}

class AniMixEmptyState extends StatelessWidget {
  const AniMixEmptyState({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
    super.key,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 380),
      child: Padding(
        padding: const EdgeInsets.all(AniMixSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              icon,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              size: 34,
            ),
            const SizedBox(height: AniMixSpacing.md),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: AniMixSpacing.sm),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                height: 1.45,
              ),
            ),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: AniMixSpacing.lg),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    ),
  );
}
