import 'dart:async';
import 'dart:collection';

import 'package:flutter/material.dart';

import '../../core/achievement_service.dart';
import 'achievements_screen.dart';

class AchievementToast extends StatefulWidget {
  const AchievementToast({super.key});

  @override
  State<AchievementToast> createState() => _AchievementToastState();
}

class _AchievementToastState extends State<AchievementToast> {
  Achievement? _current;
  Timer? _dismissTimer;
  final Queue<Achievement> _pending = Queue();

  @override
  void initState() {
    super.initState();
    AchievementService.instance.latestUnlock.addListener(_showLatest);
  }

  void _showLatest() {
    final latest = AchievementService.instance.latestUnlock.value;
    if (latest == null) return;
    _pending.add(latest);
    if (_current != null) return;
    _showNext();
  }

  void _showNext() {
    _dismissTimer?.cancel();
    setState(() => _current = _pending.isEmpty ? null : _pending.removeFirst());
    if (_current != null) {
      _dismissTimer = Timer(const Duration(seconds: 5), () {
        if (mounted) _showNext();
      });
    }
  }

  @override
  void dispose() {
    _dismissTimer?.cancel();
    AchievementService.instance.latestUnlock.removeListener(_showLatest);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final achievement = _current;
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Align(
          alignment: Alignment.topRight,
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 320),
            reverseDuration: const Duration(milliseconds: 180),
            transitionBuilder: (child, animation) => FadeTransition(
              opacity: animation,
              child: SlideTransition(
                position:
                    Tween<Offset>(
                      begin: const Offset(0, -.18),
                      end: Offset.zero,
                    ).animate(
                      CurvedAnimation(
                        parent: animation,
                        curve: Curves.easeOutCubic,
                      ),
                    ),
                child: child,
              ),
            ),
            child: achievement == null
                ? const SizedBox.shrink(key: ValueKey('empty'))
                : _AchievementCard(
                    key: ValueKey(achievement.id),
                    achievement: achievement,
                    index: achievements.indexWhere(
                      (item) => item.id == achievement.id,
                    ),
                    color: scheme.primary,
                    surface: scheme.surfaceContainerHigh,
                  ),
          ),
        ),
      ),
    );
  }
}

class _AchievementCard extends StatelessWidget {
  const _AchievementCard({
    super.key,
    required this.achievement,
    required this.index,
    required this.color,
    required this.surface,
  });

  final Achievement achievement;
  final int index;
  final Color color;
  final Color surface;

  @override
  Widget build(BuildContext context) => Material(
    elevation: 12,
    color: surface,
    borderRadius: BorderRadius.circular(18),
    child: Container(
      width: 320,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(18)),
      child: Row(
        children: [
          AchievementIcon(index: index, size: 62),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'ДОСТИЖЕНИЕ ОТКРЫТО',
                  style: TextStyle(
                    color: color,
                    fontSize: 10,
                    letterSpacing: .7,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  achievement.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  achievement.description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
