import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import '../../core/animix_theme.dart';
import '../../widgets/animix_surface.dart';
import 'episode_selection_screen.dart';
import 'yummy_kodik_screen.dart';

/// Picks the catalog to watch from. Two equal rows in one grouped list, so
/// both sources always line up regardless of text length or window width.
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
    final scheme = Theme.of(context).colorScheme;
    return AniMixPage(
      title: 'Источник',
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 36),
            children: [
              Text(
                _title,
                style: const TextStyle(
                  fontSize: 24,
                  height: 1.15,
                  letterSpacing: -.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Выберите каталог. Дальше — озвучка, серия и качество.',
                style: TextStyle(color: scheme.onSurfaceVariant, height: 1.4),
              ),
              const SizedBox(height: 22),
              AniMixSurface(
                child: Column(
                  children: [
                    _ProviderRow(
                      icon: CupertinoIcons.play_rectangle_fill,
                      title: 'YummyAnime',
                      badge: 'Рекомендуем',
                      subtitle: 'Больше озвучек · поток Kodik без рекламы',
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
                    const Divider(height: 1, indent: 68),
                    _ProviderRow(
                      icon: CupertinoIcons.film_fill,
                      title: 'AniLiberty',
                      subtitle: 'Своя озвучка · выбор качества · загрузки',
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
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ProviderRow extends StatelessWidget {
  const _ProviderRow({
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
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 12, 16),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(AniMixRadius.md - 4),
              ),
              child: Icon(icon, size: 20),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (badge != null)
                        Text(
                          badge!,
                          style: TextStyle(
                            color: scheme.primary,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 3),
                  Text(
                    subtitle,
                    style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontSize: 13,
                      height: 1.35,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              CupertinoIcons.chevron_forward,
              size: 16,
              color: scheme.onSurfaceVariant,
            ),
          ],
        ),
      ),
    );
  }
}
