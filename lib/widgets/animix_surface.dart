import 'dart:ui';

import 'package:flutter/material.dart';

import '../core/animix_theme.dart';

class AniMixSurface extends StatefulWidget {
  const AniMixSurface({
    required this.child,
    this.padding = EdgeInsets.zero,
    this.radius = 20,
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
  State<AniMixSurface> createState() => _AniMixSurfaceState();
}

class _AniMixSurfaceState extends State<AniMixSurface> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    final scheme = Theme.of(context).colorScheme;
    final translucent = AniMixTheme.isTranslucent(context);
    final surfaceColor = widget.selected
        ? Color.alphaBlend(
            accent.withValues(alpha: 0.12),
            widget.elevated ? scheme.surfaceContainerHigh : scheme.surface,
          )
        : widget.elevated
        ? scheme.surfaceContainerHigh
        : scheme.surface;
    final decorated = AnimatedContainer(
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOutCubic,
      padding: widget.padding,
      transform: Matrix4.translationValues(0, _hovered ? -3 : 0, 0),
      transformAlignment: Alignment.center,
      decoration: BoxDecoration(
        color: translucent
            ? surfaceColor.withValues(alpha: widget.elevated ? .82 : .72)
            : surfaceColor,
        borderRadius: BorderRadius.circular(widget.radius),
        border: Border.all(
          color: widget.selected
              ? accent.withValues(alpha: 0.30)
              : translucent
              ? scheme.onSurface.withValues(alpha: widget.elevated ? .12 : .065)
              : scheme.outlineVariant.withValues(alpha: .42),
        ),
        boxShadow: widget.elevated || _hovered
            ? [
                BoxShadow(
                  color: Colors.black.withValues(
                    alpha: Theme.of(context).brightness == Brightness.dark
                        ? (_hovered ? .24 : .13)
                        : (_hovered ? .15 : .08),
                  ),
                  blurRadius: _hovered ? 34 : 24,
                  offset: Offset(0, _hovered ? 13 : 8),
                ),
                if (translucent)
                  BoxShadow(
                    color: accent.withValues(alpha: _hovered ? .12 : .06),
                    blurRadius: _hovered ? 30 : 20,
                    offset: const Offset(0, 8),
                  ),
              ]
            : null,
      ),
      child: widget.child,
    );
    final content = ClipRRect(
      borderRadius: BorderRadius.circular(widget.radius),
      child: translucent && (widget.blurred || widget.elevated)
          ? BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 13, sigmaY: 13),
              child: decorated,
            )
          : decorated,
    );
    final interactive = widget.onTap == null
        ? content
        : Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(widget.radius),
              onTap: widget.onTap,
              child: content,
            ),
          );
    return MouseRegion(
      cursor: widget.onTap == null
          ? MouseCursor.defer
          : SystemMouseCursors.click,
      onEnter: (_) {
        if ((widget.onTap != null || widget.elevated || translucent) &&
            mounted) {
          setState(() => _hovered = true);
        }
      },
      onExit: (_) {
        if (_hovered && mounted) setState(() => _hovered = false);
      },
      child: interactive,
    );
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
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: translucent ? Alignment.topLeft : Alignment.topCenter,
          end: translucent ? Alignment.bottomRight : Alignment.bottomCenter,
          colors: translucent
              ? [
                  Color.alphaBlend(
                    theme.colorScheme.primary.withValues(alpha: .16),
                    theme.scaffoldBackgroundColor,
                  ),
                  Color.alphaBlend(
                    theme.colorScheme.tertiary.withValues(alpha: .07),
                    theme.scaffoldBackgroundColor,
                  ),
                  theme.scaffoldBackgroundColor,
                ]
              : [
                  Color.alphaBlend(
                    theme.colorScheme.primary.withValues(alpha: .025),
                    theme.colorScheme.surfaceContainer,
                  ),
                  theme.scaffoldBackgroundColor,
                ],
          stops: translucent ? const [0, .42, 1] : const [0, 0.38],
        ),
      ),
      child: Scaffold(
        backgroundColor: Colors.transparent,
        appBar: AppBar(
          title: Text(title),
          leading: leading,
          actions: actions,
          toolbarHeight: 72,
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
      shape: const CircleBorder(side: BorderSide(color: Color(0x26FFFFFF))),
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
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
      decoration: BoxDecoration(
        color: accent
            ? color.withValues(alpha: .12)
            : Theme.of(context).colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: accent
              ? color.withValues(alpha: .42)
              : Theme.of(
                  context,
                ).colorScheme.outlineVariant.withValues(alpha: .55),
        ),
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
              fontWeight: FontWeight.w700,
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
  final IconData? icon;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.end,
    children: [
      if (icon != null) ...[
        Icon(icon, color: Theme.of(context).colorScheme.primary, size: 22),
        const SizedBox(width: AniMixSpacing.sm),
      ],
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurface,
                fontSize: 21,
                letterSpacing: -.45,
                fontWeight: FontWeight.w800,
                height: 1.18,
              ),
            ),
            if (subtitle?.isNotEmpty == true) ...[
              const SizedBox(height: AniMixSpacing.xs),
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
            Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                color: Theme.of(
                  context,
                ).colorScheme.primary.withValues(alpha: .12),
                shape: BoxShape.circle,
              ),
              child: Icon(
                icon,
                color: Theme.of(context).colorScheme.primary,
                size: 25,
              ),
            ),
            const SizedBox(height: AniMixSpacing.lg),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
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
