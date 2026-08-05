import 'package:flutter/material.dart';

import '../../core/animix_theme.dart';
import '../../widgets/animix_surface.dart';
import 'episode_selection_screen.dart';
import 'yummy_kodik_screen.dart';

class WatchProviderSelectionScreen extends StatelessWidget {
  const WatchProviderSelectionScreen({
    required this.animeId,
    required this.animeNameRu,
    required this.animeNameEn,
    super.key,
  });

  final int animeId;
  final String animeNameRu;
  final String animeNameEn;

  String get _title => animeNameRu.isNotEmpty ? animeNameRu : animeNameEn;

  @override
  Widget build(BuildContext context) {
    return AniMixPage(
      title: 'Источник просмотра',
      child: LayoutBuilder(
        builder: (context, constraints) {
          final wide = constraints.maxWidth >= 760;
          final providers = [
            _ProviderCard(
              icon: Icons.play_circle_fill_rounded,
              title: 'YummyAnime + Kodik',
              subtitle: 'Озвучки и серии • прямой HLS без рекламного iframe',
              badge: 'Рекомендуется',
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) => YummyAnimeScreen(
                    animeId: animeId,
                    animeNameRu: animeNameRu,
                    animeNameEn: animeNameEn,
                  ),
                ),
              ),
            ),
            _ProviderCard(
              icon: Icons.video_library_rounded,
              title: 'AniLiberty',
              subtitle: 'Прямые источники • качества • офлайн-загрузка',
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) => EpisodeSelectionScreen(
                    animeId: animeId,
                    provider: 'anilibria',
                    translationName: 'AniLiberty',
                    animeNameRu: animeNameRu,
                    animeNameEn: animeNameEn,
                  ),
                ),
              ),
            ),
          ];
          return SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(
              wide ? 30 : 20,
              28,
              wide ? 30 : 20,
              36,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Смотреть «$_title»',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Выберите каталог. После этого AniMix предложит озвучку, серию и качество.',
                  style: TextStyle(color: AniMixTheme.subtleText, height: 1.45),
                ),
                const SizedBox(height: 28),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (
                        var index = 0;
                        index < providers.length;
                        index++
                      ) ...[
                        if (index > 0) const SizedBox(width: 14),
                        Expanded(child: providers[index]),
                      ],
                    ],
                  )
                else
                  Column(
                    children: [
                      for (
                        var index = 0;
                        index < providers.length;
                        index++
                      ) ...[
                        if (index > 0) const SizedBox(height: 12),
                        providers[index],
                      ],
                    ],
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _ProviderCard extends StatelessWidget {
  const _ProviderCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.badge,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final String? badge;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return AniMixSurface(
      onTap: onTap,
      padding: const EdgeInsets.all(22),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 58,
            height: 58,
            decoration: BoxDecoration(
              color: accent.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(18),
            ),
            child: Icon(icon, color: accent, size: 30),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (badge != null) ...[
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 9,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: accent.withValues(alpha: 0.14),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      badge!,
                      style: TextStyle(
                        color: accent,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                ],
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AniMixTheme.subtleText,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
          const Padding(
            padding: EdgeInsets.only(top: 18),
            child: Icon(Icons.chevron_right_rounded, color: Colors.white38),
          ),
        ],
      ),
    );
  }
}
