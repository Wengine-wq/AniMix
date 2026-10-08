import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/achievement_service.dart';

class AchievementIcon extends StatelessWidget {
  const AchievementIcon({
    super.key,
    required this.index,
    this.size = 68,
    this.locked = false,
  });

  final int index;
  final double size;
  final bool locked;

  static final Future<ui.Image> _classicAtlas = _loadAtlas('atlas.png');
  static final Future<ui.Image> _titleAtlas = _loadAtlas('titles_atlas.png');

  static Future<ui.Image> _loadAtlas(String name) async {
    final bytes = await rootBundle.load('assets/achievements/$name');
    final codec = await ui.instantiateImageCodec(bytes.buffer.asUint8List());
    final frame = await codec.getNextFrame();
    codec.dispose();
    return frame.image;
  }

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: FutureBuilder<ui.Image>(
      future: index < 10 ? _classicAtlas : _titleAtlas,
      builder: (context, snapshot) {
        if (!snapshot.hasData) return const SizedBox.shrink();
        return Opacity(
          opacity: locked ? .36 : 1,
          child: CustomPaint(
            painter: _AtlasCellPainter(snapshot.data!, index % 10),
          ),
        );
      },
    ),
  );
}

class _AtlasCellPainter extends CustomPainter {
  const _AtlasCellPainter(this.atlas, this.index);
  final ui.Image atlas;
  final int index;

  @override
  void paint(Canvas canvas, Size size) {
    final cellWidth = atlas.width / 5;
    final cellHeight = atlas.height / 2;
    final source = Rect.fromLTWH(
      (index % 5) * cellWidth,
      (index ~/ 5) * cellHeight,
      cellWidth,
      cellHeight,
    );
    canvas.drawImageRect(
      atlas,
      source,
      Offset.zero & size,
      Paint()..filterQuality = FilterQuality.high,
    );
  }

  @override
  bool shouldRepaint(_AtlasCellPainter oldDelegate) =>
      oldDelegate.atlas != atlas || oldDelegate.index != index;
}

class AchievementsScreen extends StatelessWidget {
  const AchievementsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Достижения')),
      body: ValueListenableBuilder<int>(
        valueListenable: AchievementService.instance.revision,
        builder: (context, _, _) => FutureBuilder<Map<String, DateTime>>(
          future: AchievementService.instance.unlockedSnapshot(),
          builder: (context, snapshot) {
            final earned = snapshot.data ?? const <String, DateTime>{};
            return FutureBuilder<Map<String, String>>(
              future: AchievementService.instance.titleProgressSnapshot(),
              builder: (context, progressSnapshot) => LayoutBuilder(
                builder: (context, limits) {
                  final columns = limits.maxWidth >= 900
                      ? 3
                      : limits.maxWidth >= 620
                      ? 2
                      : 1;
                  final width =
                      (limits.maxWidth - 32 - (columns - 1) * 12) / columns;
                  Widget badgeGrid(int start, int end) => Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      for (var i = start; i < end; i++)
                        SizedBox(
                          width: width,
                          child: _AchievementCard(
                            achievement: achievements[i],
                            index: i,
                            unlockedAt: earned[achievements[i].id],
                            progress:
                                progressSnapshot.data?[achievements[i].id],
                          ),
                        ),
                    ],
                  );
                  return ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      Text(
                        '${earned.length} из ${achievements.length} открыто',
                        style: Theme.of(context).textTheme.headlineSmall
                            ?.copyWith(fontWeight: FontWeight.w800),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Награды живут только на этом устройстве и не синхронизируются с сервером.',
                        style: TextStyle(color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 20),
                      Text(
                        'Основные',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 12),
                      badgeGrid(0, 10),
                      const SizedBox(height: 28),
                      Text(
                        'Испытания по тайтлам',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Нужны отмеченные просмотром серии в AniMix. Одного статуса «Просмотрено» недостаточно.',
                        style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 12),
                      badgeGrid(10, achievements.length),
                    ],
                  );
                },
              ),
            );
          },
        ),
      ),
    );
  }
}

class _AchievementCard extends StatelessWidget {
  const _AchievementCard({
    required this.achievement,
    required this.index,
    required this.unlockedAt,
    this.progress,
  });
  final Achievement achievement;
  final int index;
  final DateTime? unlockedAt;
  final String? progress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final locked = unlockedAt == null;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: locked
              ? scheme.outlineVariant
              : scheme.primary.withValues(alpha: .55),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            AchievementIcon(index: index, size: 72, locked: locked),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    achievement.title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 15,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    locked ? achievement.hint : achievement.description,
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                  if (locked && progress != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      progress!,
                      style: TextStyle(
                        fontSize: 11,
                        color: scheme.primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                  if (!locked) ...[
                    const SizedBox(height: 6),
                    Text(
                      'Открыто ${unlockedAt!.day.toString().padLeft(2, '0')}.${unlockedAt!.month.toString().padLeft(2, '0')}.${unlockedAt!.year}',
                      style: TextStyle(fontSize: 11, color: scheme.primary),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
