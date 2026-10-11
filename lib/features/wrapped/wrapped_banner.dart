import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/animix_theme.dart';
import '../../core/app_settings.dart';
import 'wrapped_schedule.dart';
import 'wrapped_screen.dart';

/// Home banner: from 1 November it counts down to «Итоги года», and once
/// they open it leads straight into the story.
class WrappedBanner extends StatefulWidget {
  const WrappedBanner({super.key, this.debugNow});

  /// Screenshot harness only: pretend the clock shows this moment.
  @visibleForTesting
  final DateTime? debugNow;

  @override
  State<WrappedBanner> createState() => _WrappedBannerState();
}

class _WrappedBannerState extends State<WrappedBanner> {
  late WrappedSchedule _schedule = _now();

  WrappedSchedule _now() {
    final debugNow = widget.debugNow;
    return debugNow == null
        ? WrappedSchedule.current()
        : WrappedSchedule.at(debugNow);
  }

  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    AppSettingsController.instance.addListener(_refresh);
    _syncTicker();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    AppSettingsController.instance.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    final next = _now();
    if (!mounted) return;
    setState(() => _schedule = next);
    _syncTicker();
  }

  void _syncTicker() {
    final counting = _schedule.phase == WrappedPhase.teaser;
    if (counting && _ticker == null) {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
    } else if (!counting) {
      _ticker?.cancel();
      _ticker = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final schedule = _schedule;
    if (schedule.phase == WrappedPhase.hidden) return const SizedBox.shrink();
    final open = schedule.phase == WrappedPhase.open;
    final accent = AppSettingsController.instance.accentColor;
    final hsl = HSLColor.fromColor(accent);
    final left = schedule.untilOpen(widget.debugNow ?? DateTime.now());
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AniMixLayout.pageInset,
        AniMixSpacing.sm,
        AniMixLayout.pageInset,
        AniMixSpacing.md,
      ),
      child: Semantics(
        button: true,
        label: open
            ? 'Итоги ${schedule.year} готовы'
            : 'Итоги ${schedule.year} откроются через ${left.inDays} дней',
        child: GestureDetector(
          onTap: open
              ? () => WrappedScreen.open(context, schedule.year)
              : () => _explain(context, schedule),
          child: Container(
            padding: const EdgeInsets.fromLTRB(18, 16, 16, 16),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(22),
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  hsl.withLightness(0.38).toColor(),
                  hsl
                      .withHue((hsl.hue + 45) % 360)
                      .withLightness(0.2)
                      .toColor(),
                ],
              ),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        open
                            ? 'Твои итоги ${schedule.year} готовы'
                            : 'Готовимся к итогам ${schedule.year}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        open
                            ? 'Часы, жанры, марафоны и тайтл года.'
                            : 'Всё, что посмотришь до 20 декабря, попадёт в итоги.',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.78),
                          fontSize: 13,
                          height: 1.3,
                        ),
                      ),
                      if (!open) ...[
                        const SizedBox(height: 12),
                        _Countdown(left: left),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.white.withValues(alpha: open ? 1 : 0.16),
                  ),
                  child: Icon(
                    open ? CupertinoIcons.play_fill : CupertinoIcons.sparkles,
                    color: open ? Colors.black : Colors.white,
                    size: 20,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _explain(BuildContext context, WrappedSchedule schedule) {
    showCupertinoDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (context) => CupertinoAlertDialog(
        title: Text('Итоги ${schedule.year}'),
        content: const Padding(
          padding: EdgeInsets.only(top: 8),
          child: Text(
            '20 декабря откроется история твоего года в AniMix: '
            'сколько часов ты смотрел, любимые жанры, самый длинный '
            'марафон и тайтл года. Каждая серия до этого дня попадёт в итоги.',
          ),
        ),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.pop(context),
            child: const Text('Жду'),
          ),
        ],
      ),
    );
  }
}

class _Countdown extends StatelessWidget {
  const _Countdown({required this.left});

  final Duration left;

  @override
  Widget build(BuildContext context) {
    final parts = [
      (left.inDays, 'дн'),
      (left.inHours % 24, 'ч'),
      (left.inMinutes % 60, 'мин'),
      (left.inSeconds % 60, 'сек'),
    ];
    return Row(
      children: [
        for (var i = 0; i < parts.length; i++) ...[
          if (i > 0) const SizedBox(width: 6),
          Container(
            constraints: const BoxConstraints(minWidth: 50),
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.22),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Column(
              children: [
                Text(
                  parts[i].$1.toString().padLeft(i == 0 ? 1 : 2, '0'),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    height: 1.1,
                    fontWeight: FontWeight.w800,
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
                Text(
                  parts[i].$2,
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.65),
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}
