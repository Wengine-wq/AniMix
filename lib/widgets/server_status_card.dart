import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../core/server_status.dart';

/// Live state of the AniMix server with the current notice, e.g. «на
/// ремонте, ждём восстановления». Checks on first build.
class ServerStatusCard extends StatefulWidget {
  const ServerStatusCard({super.key, this.compact = false});

  /// Compact: only shown when something is wrong (login screen).
  final bool compact;

  static Future<void> showSheet(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) => const Padding(
        padding: EdgeInsets.fromLTRB(20, 12, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Grabber(),
            SizedBox(height: 16),
            ServerStatusCard(),
            SizedBox(height: 14),
            Text(
              'Пока сервер недоступен, прогресс копится на устройстве и '
              'отправится сам, когда всё заработает.',
              style: TextStyle(fontSize: 13, height: 1.35),
            ),
          ],
        ),
      ),
    );
  }

  @override
  State<ServerStatusCard> createState() => _ServerStatusCardState();
}

class _ServerStatusCardState extends State<ServerStatusCard> {
  final _service = ServerStatusService.instance;

  @override
  void initState() {
    super.initState();
    _service.refresh();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ServerStatusInfo>(
      valueListenable: _service.status,
      builder: (context, info, _) {
        if (widget.compact && info.state == ServerState.online) {
          return const SizedBox.shrink();
        }
        final (color, icon) = switch (info.state) {
          ServerState.online => (
            const Color(0xFF35D07F),
            CupertinoIcons.checkmark_seal_fill,
          ),
          ServerState.outage => (
            const Color(0xFFFFB547),
            CupertinoIcons.wrench_fill,
          ),
          ServerState.checking => (
            Theme.of(context).colorScheme.onSurfaceVariant,
            CupertinoIcons.antenna_radiowaves_left_right,
          ),
        };
        final checked = info.checkedAt;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          padding: const EdgeInsets.fromLTRB(16, 14, 10, 14),
          decoration: BoxDecoration(
            color: color.withValues(alpha: .10),
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: color.withValues(alpha: .28)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: .18),
                  shape: BoxShape.circle,
                ),
                child: info.state == ServerState.checking
                    ? const CupertinoActivityIndicator(radius: 8)
                    : Icon(icon, size: 18, color: color),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        _PulseDot(color: color),
                        const SizedBox(width: 7),
                        Expanded(
                          child: Text(
                            info.title,
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              fontSize: 15,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      info.message,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.35,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    if (checked != null) ...[
                      const SizedBox(height: 6),
                      Text(
                        'Проверено в ${checked.hour.toString().padLeft(2, '0')}:'
                        '${checked.minute.toString().padLeft(2, '0')}',
                        style: TextStyle(
                          fontSize: 11,
                          color: Theme.of(
                            context,
                          ).colorScheme.onSurfaceVariant.withValues(alpha: .8),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              IconButton(
                tooltip: 'Проверить снова',
                onPressed: info.state == ServerState.checking
                    ? null
                    : _service.refresh,
                icon: const Icon(CupertinoIcons.arrow_clockwise, size: 18),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _PulseDot extends StatefulWidget {
  const _PulseDot({required this.color});

  final Color color;

  @override
  State<_PulseDot> createState() => _PulseDotState();
}

class _PulseDotState extends State<_PulseDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: Tween(begin: .35, end: 1.0).animate(_controller),
    child: Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle),
    ),
  );
}

class _Grabber extends StatelessWidget {
  const _Grabber();

  @override
  Widget build(BuildContext context) => Center(
    child: Container(
      width: 38,
      height: 4,
      decoration: BoxDecoration(
        color: Theme.of(context).dividerColor,
        borderRadius: BorderRadius.circular(2),
      ),
    ),
  );
}
