import 'package:flutter/cupertino.dart';
import 'package:cached_network_image/cached_network_image.dart';

import 'episode_selection_screen.dart';

class WatchProviderSelectionScreen extends StatelessWidget {
  final int animeId;
  final String animeNameRu;
  final String animeNameEn;

  const WatchProviderSelectionScreen({
    required this.animeId,
    required this.animeNameRu,
    required this.animeNameEn,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    // Для отображения используем лучшее доступное название
    final displayTitle = animeNameRu.isNotEmpty ? animeNameRu : animeNameEn;

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Выбор плеера'),
        backgroundColor: Color(0xFF1E1E1E),
      ),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Где хочешь смотреть?', 
                  style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: CupertinoColors.white)),
              const SizedBox(height: 8),
              Text(displayTitle, style: const TextStyle(fontSize: 17, color: CupertinoColors.systemGrey)),
              const SizedBox(height: 40),

              GestureDetector(
                onTap: () => Navigator.push(
                  context,
                  CupertinoPageRoute(
                    builder: (_) => EpisodeSelectionScreen(
                      animeId: animeId,
                      provider: 'anilibria',
                      translationName: 'Anilibria (основная)',
                      animeNameRu: animeNameRu,
                      animeNameEn: animeNameEn,
                    ),
                  ),
                ),
                child: Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E1E),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: const Color(0xFFFF5722).withValues(alpha: 0.3), width: 2),
                  ),
                  child: Row(
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(12),
                        child: CachedNetworkImage(
                          imageUrl: 'https://anilibria.tv/favicon.ico',
                          width: 56,
                          height: 56,
                          fit: BoxFit.cover,
                        ),
                      ),
                      const SizedBox(width: 20),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Anilibria', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w700, color: CupertinoColors.white)),
                            SizedBox(height: 4),
                            Text('Встроенный плеер • все озвучки', 
                                style: TextStyle(fontSize: 15, color: CupertinoColors.systemGrey)),
                          ],
                        ),
                      ),
                      const Icon(CupertinoIcons.chevron_right, color: CupertinoColors.systemGrey3),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}