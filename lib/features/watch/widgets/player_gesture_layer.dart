import 'dart:async';

import 'package:flutter/material.dart';

/// The same gesture surface is used in embedded and fullscreen playback.
///
/// A plain [GestureDetector] with both `onTap` and `onDoubleTap` holds every
/// single tap for ~300 ms while it waits for a possible second tap, which made
/// showing the controls feel sluggish. Here a single tap fires immediately.
/// When a second tap follows quickly the gesture becomes a double tap: [onTap]
/// is invoked once more so a toggle (show/hide controls) cancels out, and the
/// side action runs. Further quick taps on a side keep seeking, YouTube-style.
class PlayerGestureLayer extends StatefulWidget {
  const PlayerGestureLayer({
    required this.onTap,
    required this.onSeekBackward,
    required this.onSeekForward,
    required this.onTogglePlayback,
    super.key,
  });

  /// Must be a toggle: it is called a second time when a tap turns into a
  /// double tap, restoring the previous state.
  final VoidCallback onTap;
  final VoidCallback onSeekBackward;
  final VoidCallback onSeekForward;
  final VoidCallback onTogglePlayback;

  static const Duration multiTapWindow = Duration(milliseconds: 280);
  static const double _maxTapSlop = 48;

  @override
  State<PlayerGestureLayer> createState() => _PlayerGestureLayerState();
}

class _PlayerGestureLayerState extends State<PlayerGestureLayer> {
  Timer? _streakTimer;
  Offset? _lastTapPosition;
  int _streak = 0;

  @override
  void dispose() {
    _streakTimer?.cancel();
    super.dispose();
  }

  void _handleTap(Offset position, double width) {
    final previous = _lastTapPosition;
    final continuesStreak =
        _streak > 0 &&
        previous != null &&
        (position - previous).distance <= PlayerGestureLayer._maxTapSlop * 2;
    _lastTapPosition = position;
    _streakTimer?.cancel();
    _streakTimer = Timer(PlayerGestureLayer.multiTapWindow, () {
      _streak = 0;
      _lastTapPosition = null;
    });

    if (!continuesStreak) {
      _streak = 1;
      widget.onTap();
      return;
    }

    _streak++;
    final x = position.dx;
    final zone = x < width * .4
        ? -1
        : x > width * .6
        ? 1
        : 0;
    if (_streak == 2) widget.onTap();
    if (zone < 0) {
      widget.onSeekBackward();
    } else if (zone > 0) {
      widget.onSeekForward();
    } else if (_streak == 2) {
      widget.onTogglePlayback();
    }
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (_, bounds) => GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (details) => _handleTap(details.localPosition, bounds.maxWidth),
    ),
  );
}
