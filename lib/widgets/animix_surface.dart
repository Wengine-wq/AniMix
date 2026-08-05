import 'package:flutter/material.dart';

import '../core/animix_theme.dart';

class AniMixSurface extends StatelessWidget {
  const AniMixSurface({
    required this.child,
    this.padding = EdgeInsets.zero,
    this.radius = 22,
    this.onTap,
    this.selected = false,
    this.elevated = false,
    super.key,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  final VoidCallback? onTap;
  final bool selected;
  final bool elevated;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    final content = AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      padding: padding,
      decoration: BoxDecoration(
        color: selected
            ? Color.alphaBlend(
                accent.withValues(alpha: 0.12),
                elevated ? AniMixTheme.surfaceHigh : AniMixTheme.surface,
              )
            : elevated
            ? AniMixTheme.surfaceHigh
            : AniMixTheme.surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(
          color: selected
              ? accent.withValues(alpha: 0.38)
              : AniMixTheme.divider,
        ),
        boxShadow: elevated
            ? const [
                BoxShadow(
                  color: Color(0x33000000),
                  blurRadius: 20,
                  offset: Offset(0, 8),
                ),
              ]
            : null,
      ),
      child: child,
    );
    if (onTap == null) return content;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(radius),
        onTap: onTap,
        child: content,
      ),
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
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFF111117), AniMixTheme.background],
          stops: [0, 0.38],
        ),
      ),
      child: Scaffold(
        backgroundColor: Colors.transparent,
        appBar: AppBar(
          title: Text(title),
          leading: leading,
          actions: actions,
          toolbarHeight: 64,
        ),
        body: SafeArea(
          top: false,
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1180),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}
