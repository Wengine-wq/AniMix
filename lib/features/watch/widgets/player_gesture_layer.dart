import 'package:flutter/material.dart';

/// The same gesture surface is used in embedded and fullscreen playback.
class PlayerGestureLayer extends StatelessWidget {
  const PlayerGestureLayer({
    required this.onTap,
    required this.onSeekBackward,
    required this.onSeekForward,
    required this.onTogglePlayback,
    super.key,
  });

  final VoidCallback onTap;
  final VoidCallback onSeekBackward;
  final VoidCallback onSeekForward;
  final VoidCallback onTogglePlayback;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (_, bounds) => GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      onDoubleTapDown: (details) {
        final x = details.localPosition.dx;
        if (x < bounds.maxWidth * .4) {
          onSeekBackward();
        } else if (x > bounds.maxWidth * .6) {
          onSeekForward();
        } else {
          onTogglePlayback();
        }
      },
    ),
  );
}
