import 'package:flutter/cupertino.dart';

import 'episode_selection_screen.dart';

class YummyKodikScreen extends StatelessWidget {
  final int animeId;
  final String animeNameRu;
  final String animeNameEn;

  const YummyKodikScreen({
    required this.animeId, 
    required this.animeNameRu, 
    required this.animeNameEn, 
    super.key
  });

  @override
  Widget build(BuildContext context) {
    final displayTitle = animeNameRu.isNotEmpty ? animeNameRu : animeNameEn;
    
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(middle: Text('YummyAnime • Kodik')),
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(40),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(CupertinoIcons.play_rectangle_fill, size: 90, color: Color(0xFFFF5722)),
              const SizedBox(height: 24),
              Text(
                displayTitle,
                style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w700, color: CupertinoColors.white),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 40),
              const Text(
                'Открываем Kodik плеер',
                style: TextStyle(fontSize: 20, color: CupertinoColors.white),
              ),
              const SizedBox(height: 12),
              const Text(
                'Сейчас будет полный экран плеера YummyAnime',
                style: TextStyle(color: CupertinoColors.systemGrey),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 60),
              CupertinoButton.filled(
                onPressed: () => Navigator.push(
                  context,
                  CupertinoPageRoute(
                    builder: (_) => EpisodeSelectionScreen(
                      animeId: animeId,
                      provider: 'yummy_kodik',
                      translationName: 'Kodik',
                      animeNameRu: animeNameRu,
                      animeNameEn: animeNameEn,
                    ),
                  ),
                ),
                child: const Text('Открыть плеер Kodik'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}