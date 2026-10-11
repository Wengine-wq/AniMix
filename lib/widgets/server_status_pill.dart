import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../core/server_health.dart';
import 'server_status_card.dart';

/// A quiet notice while the AniMix server is unreachable. The catalog,
/// watching and downloads keep working; only account features wait.
class ServerStatusPill extends StatelessWidget {
  const ServerStatusPill({super.key, this.bottomGap = 16});

  /// Space above the bottom edge, so the pill clears the tab bar.
  final double bottomGap;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: ServerHealth.instance.down,
      builder: (context, down, _) => AnimatedSwitcher(
        duration: const Duration(milliseconds: 250),
        child: !down
            ? const SizedBox.shrink()
            : SafeArea(
                top: false,
                child: Padding(
                  padding: EdgeInsets.only(bottom: bottomGap),
                  child: Center(
                    child: Tooltip(
                      message:
                          'Каталог, просмотр и загрузки работают. Профиль, списки и достижения синхронизируются, когда сервер вернётся.',
                      child: Material(
                        color: const Color(0xE6302A12),
                        shape: const StadiumBorder(
                          side: BorderSide(color: Color(0x66FFC547)),
                        ),
                        child: InkWell(
                          customBorder: const StadiumBorder(),
                          onTap: () => ServerStatusCard.showSheet(context),
                          child: const Padding(
                            padding: EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 6,
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  CupertinoIcons.cloud_bolt_fill,
                                  size: 14,
                                  color: Color(0xFFFFC547),
                                ),
                                SizedBox(width: 6),
                                Text(
                                  'Сервер AniMix на ремонте · подробнее',
                                  style: TextStyle(
                                    color: Color(0xFFFFE3A3),
                                    fontSize: 12,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
      ),
    );
  }
}
