import 'package:flutter/material.dart';
import 'package:skeletonizer/skeletonizer.dart';

import '../core/animix_theme.dart';

/// Shared geometry for initial-content placeholders.
///
/// Keeping these values here prevents every screen from inventing its own
/// slightly different loading language. The skeletons are deliberately only
/// used for the first payload; pagination and user actions retain their local
/// progress affordances.
abstract final class AniMixLoadingTokens {
  static const pageInset = 20.0;
  static const compactInset = 16.0;
  static const sectionGap = 28.0;
  static const itemGap = 12.0;
  static const cardRadius = 20.0;
  static const smallRadius = 14.0;
  static const iconButtonSize = 48.0;
  static const posterRatio = 2 / 3;
  static const shimmerDuration = Duration(milliseconds: 1250);
}

/// Applies one theme-aware shimmer treatment to manually shaped [Bone]s.
///
/// `Skeletonizer.zone` is intentional: these screens provide no fake network
/// data, so only the explicit bones are painted. It also avoids accidental
/// interaction while an initial response is still pending.
class AniMixLoadingFrame extends StatelessWidget {
  const AniMixLoadingFrame({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final base = scheme.surfaceContainerHigh;
    final highlight = Color.alphaBlend(
      Colors.white.withValues(alpha: .14),
      base,
    );
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    return Skeletonizer.zone(
      effect: ShimmerEffect(
        baseColor: base,
        highlightColor: highlight,
        duration: reducedMotion
            ? Duration.zero
            : AniMixLoadingTokens.shimmerDuration,
      ),
      child: ExcludeSemantics(child: child),
    );
  }
}

class AniMixDetailSkeletonScreen extends StatelessWidget {
  const AniMixDetailSkeletonScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
    key: const ValueKey('anime_detail_skeleton'),
    backgroundColor: Theme.of(context).scaffoldBackgroundColor,
    body: AniMixLoadingFrame(
      child: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(
            AniMixLoadingTokens.pageInset,
            8,
            AniMixLoadingTokens.pageInset,
            AniMixLoadingTokens.pageInset,
          ),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 900),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final desktop = constraints.maxWidth >= 700;
                  final posterWidth = desktop
                      ? 240.0
                      : (constraints.maxWidth * .64).clamp(210.0, 300.0);
                  final heroText = _DetailHeroText(desktop: desktop);
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const SizedBox(height: 64),
                      const Align(
                        alignment: Alignment.centerLeft,
                        child: Bone.iconButton(
                          size: AniMixLoadingTokens.iconButtonSize,
                        ),
                      ),
                      const SizedBox(height: AniMixLoadingTokens.itemGap),
                      if (desktop)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _PosterBone(width: posterWidth),
                            const SizedBox(
                              width: AniMixLoadingTokens.sectionGap,
                            ),
                            Expanded(child: heroText),
                          ],
                        )
                      else ...[
                        Align(
                          alignment: Alignment.center,
                          child: _PosterBone(width: posterWidth),
                        ),
                        const SizedBox(height: AniMixLoadingTokens.sectionGap),
                        heroText,
                      ],
                      const SizedBox(height: AniMixLoadingTokens.sectionGap),
                      const _ActionBones(),
                      const SizedBox(height: AniMixLoadingTokens.sectionGap),
                      const _DetailSectionBones(lines: 4),
                      const SizedBox(height: AniMixLoadingTokens.sectionGap),
                      const _DetailSectionBones(lines: 2, showCards: true),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _PosterBone extends StatelessWidget {
  const _PosterBone({required this.width});

  final double width;

  @override
  Widget build(BuildContext context) => Bone(
    width: width,
    height: width / AniMixLoadingTokens.posterRatio,
    borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
  );
}

class _DetailHeroText extends StatelessWidget {
  const _DetailHeroText({required this.desktop});

  final bool desktop;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Bone.multiText(
        lines: desktop ? 3 : 2,
        style: const TextStyle(fontSize: 29, fontWeight: FontWeight.w800),
      ),
      const SizedBox(height: AniMixLoadingTokens.itemGap),
      Bone.text(width: 180, style: const TextStyle(fontSize: 14)),
      const SizedBox(height: AniMixLoadingTokens.itemGap),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: const [
          Bone(width: 64, height: 28, uniRadius: 14),
          Bone(width: 82, height: 28, uniRadius: 14),
          Bone(width: 58, height: 28, uniRadius: 14),
        ],
      ),
    ],
  );
}

class _ActionBones extends StatelessWidget {
  const _ActionBones();

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: AniMixLoadingTokens.itemGap,
    runSpacing: AniMixLoadingTokens.itemGap,
    children: const [
      Bone.button(width: 164, height: 50, type: BoneButtonType.filled),
      Bone.button(width: 126, height: 50, type: BoneButtonType.outlined),
      Bone.iconButton(size: 50, uniRadius: AniMixLoadingTokens.smallRadius),
    ],
  );
}

class _DetailSectionBones extends StatelessWidget {
  const _DetailSectionBones({required this.lines, this.showCards = false});

  final int lines;
  final bool showCards;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Bone.text(width: 172, style: const TextStyle(fontSize: 21)),
      const SizedBox(height: AniMixLoadingTokens.itemGap),
      Bone.multiText(lines: lines, style: const TextStyle(fontSize: 14)),
      if (showCards) ...[
        const SizedBox(height: AniMixLoadingTokens.itemGap),
        SizedBox(
          height: 118,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: 3,
            separatorBuilder: (_, _) =>
                const SizedBox(width: AniMixLoadingTokens.itemGap),
            itemBuilder: (_, _) => const Bone(
              width: 164,
              height: 118,
              uniRadius: AniMixLoadingTokens.smallRadius,
            ),
          ),
        ),
      ],
    ],
  );
}

class AniMixCatalogSkeleton extends StatelessWidget {
  const AniMixCatalogSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: LayoutBuilder(
      builder: (context, constraints) {
        final list = constraints.maxWidth < 620;
        return list
            ? const _CatalogListSkeleton()
            : const _CatalogGridSkeleton();
      },
    ),
  );
}

class _CatalogListSkeleton extends StatelessWidget {
  const _CatalogListSkeleton();

  @override
  Widget build(BuildContext context) => ListView.separated(
    key: const ValueKey('catalog_list_skeleton'),
    physics: const NeverScrollableScrollPhysics(),
    padding: const EdgeInsets.fromLTRB(
      AniMixLoadingTokens.pageInset,
      8,
      AniMixLoadingTokens.pageInset,
      40,
    ),
    itemCount: 5,
    separatorBuilder: (_, _) =>
        const SizedBox(height: AniMixLoadingTokens.itemGap),
    itemBuilder: (_, _) => const _CatalogRowBone(),
  );
}

class _CatalogRowBone extends StatelessWidget {
  const _CatalogRowBone();

  @override
  Widget build(BuildContext context) => Container(
    height: 114,
    padding: const EdgeInsets.all(10),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surface,
      borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
      border: Border.all(color: AniMixTheme.divider),
    ),
    child: const Row(
      children: [
        Bone(width: 64, height: 94, uniRadius: AniMixLoadingTokens.smallRadius),
        SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Bone.multiText(lines: 2, style: TextStyle(fontSize: 15)),
              SizedBox(height: 10),
              Bone(width: 112, height: 24, uniRadius: 12),
            ],
          ),
        ),
      ],
    ),
  );
}

class _CatalogGridSkeleton extends StatelessWidget {
  const _CatalogGridSkeleton();

  @override
  Widget build(BuildContext context) => GridView.builder(
    key: const ValueKey('catalog_grid_skeleton'),
    physics: const NeverScrollableScrollPhysics(),
    padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
    itemCount: 8,
    gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
      maxCrossAxisExtent: 210,
      childAspectRatio: .56,
      crossAxisSpacing: 14,
      mainAxisSpacing: 20,
    ),
    itemBuilder: (_, _) => const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Bone(
            width: double.infinity,
            uniRadius: AniMixLoadingTokens.cardRadius,
          ),
        ),
        SizedBox(height: 8),
        Bone.multiText(lines: 2, style: TextStyle(fontSize: 14)),
        SizedBox(height: 6),
        Bone(width: 42, height: 10, uniRadius: 5),
      ],
    ),
  );
}

class AniMixProfileSkeleton extends StatelessWidget {
  const AniMixProfileSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: CustomScrollView(
      key: const ValueKey('profile_skeleton'),
      physics: const NeverScrollableScrollPhysics(),
      slivers: [
        SliverToBoxAdapter(
          child: Column(
            children: [
              const SizedBox(height: 20),
              const Bone.circle(size: 88),
              const SizedBox(height: 14),
              Bone.text(width: 154, style: const TextStyle(fontSize: 22)),
              const SizedBox(height: 8),
              Bone.text(width: 96, style: const TextStyle(fontSize: 12)),
              const SizedBox(height: AniMixLoadingTokens.sectionGap),
              const Padding(
                padding: EdgeInsets.symmetric(
                  horizontal: AniMixLoadingTokens.pageInset,
                ),
                child: _ProfileSummaryBones(),
              ),
              const SizedBox(height: AniMixLoadingTokens.itemGap),
              const Padding(
                padding: EdgeInsets.symmetric(
                  horizontal: AniMixLoadingTokens.pageInset,
                ),
                child: _ProfileCardBones(),
              ),
              const SizedBox(height: AniMixLoadingTokens.sectionGap),
              const AniMixProfileActivitySkeleton(),
            ],
          ),
        ),
      ],
    ),
  );
}

class _ProfileSummaryBones extends StatelessWidget {
  const _ProfileSummaryBones();

  @override
  Widget build(BuildContext context) => Container(
    height: 100,
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surface,
      borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
      border: Border.all(color: AniMixTheme.divider),
    ),
    padding: const EdgeInsets.all(AniMixLoadingTokens.compactInset),
    child: const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Bone.text(width: 110, style: TextStyle(fontSize: 15)),
        Spacer(),
        Bone(width: double.infinity, height: 10, uniRadius: 5),
      ],
    ),
  );
}

class _ProfileCardBones extends StatelessWidget {
  const _ProfileCardBones();

  @override
  Widget build(BuildContext context) => Container(
    height: 156,
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surface,
      borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
      border: Border.all(color: AniMixTheme.divider),
    ),
    padding: const EdgeInsets.all(AniMixLoadingTokens.compactInset),
    child: const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Bone.text(width: 142, style: TextStyle(fontSize: 18)),
        SizedBox(height: AniMixLoadingTokens.itemGap),
        Bone.multiText(lines: 3, style: TextStyle(fontSize: 13)),
      ],
    ),
  );
}

class AniMixProfileActivitySkeleton extends StatelessWidget {
  const AniMixProfileActivitySkeleton({super.key});

  @override
  Widget build(BuildContext context) =>
      AniMixLoadingFrame(child: _ProfileActivitySkeletonBody());
}

class _ProfileActivitySkeletonBody extends StatelessWidget {
  const _ProfileActivitySkeletonBody();

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(
      horizontal: AniMixLoadingTokens.pageInset,
    ),
    child: Container(
      height: 172,
      padding: const EdgeInsets.all(AniMixLoadingTokens.compactInset),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
        border: Border.all(color: AniMixTheme.divider),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Bone.text(width: 126, style: TextStyle(fontSize: 18)),
          const SizedBox(height: AniMixLoadingTokens.itemGap),
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                const columns = 14;
                final size =
                    ((constraints.maxWidth - ((columns - 1) * 6)) / columns)
                        .clamp(8.0, 18.0);
                return Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: List.generate(
                    42,
                    (_) => Bone.square(size: size, uniRadius: 4),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    ),
  );
}

class AniMixCommentsSkeleton extends StatelessWidget {
  const AniMixCommentsSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: ListView.separated(
      key: const ValueKey('comments_skeleton'),
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(
        AniMixLoadingTokens.compactInset,
        8,
        AniMixLoadingTokens.compactInset,
        28,
      ),
      itemCount: 4,
      separatorBuilder: (_, _) =>
          const SizedBox(height: AniMixLoadingTokens.itemGap),
      itemBuilder: (_, index) => _CommentBone(reply: index == 2),
    ),
  );
}

class AniMixHomeSkeleton extends StatelessWidget {
  const AniMixHomeSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1180),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AniMixLoadingTokens.pageInset,
            0,
            AniMixLoadingTokens.pageInset,
            40,
          ),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final desktop = constraints.maxWidth >= 760;
              final heroHeight = desktop ? 330.0 : 208.0;
              final shortcutWidth =
                  (constraints.maxWidth - (desktop ? 30 : 10)) /
                  (desktop ? 4 : 2);
              return Column(
                key: const ValueKey('home_skeleton'),
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Bone(
                      width: desktop ? 760 : double.infinity,
                      height: heroHeight,
                      uniRadius: 28,
                    ),
                  ),
                  const SizedBox(height: 22),
                  Wrap(
                    spacing: 10,
                    runSpacing: 10,
                    children: List.generate(
                      4,
                      (_) => Bone(
                        width: shortcutWidth,
                        height: 66,
                        uniRadius: AniMixLoadingTokens.cardRadius,
                      ),
                    ),
                  ),
                  const SizedBox(height: AniMixLoadingTokens.sectionGap),
                  const _HomePosterRailSkeleton(),
                  const SizedBox(height: AniMixLoadingTokens.sectionGap),
                  const _HomePosterRailSkeleton(),
                ],
              );
            },
          ),
        ),
      ),
    ),
  );
}

class _HomePosterRailSkeleton extends StatelessWidget {
  const _HomePosterRailSkeleton();

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Bone.text(width: 184, style: TextStyle(fontSize: 21)),
      const SizedBox(height: AniMixLoadingTokens.itemGap),
      SizedBox(
        height: 260,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: 6,
          separatorBuilder: (_, _) =>
              const SizedBox(width: AniMixLoadingTokens.itemGap),
          itemBuilder: (_, _) => const SizedBox(
            width: 150,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Bone(
                    width: 150,
                    uniRadius: AniMixLoadingTokens.cardRadius,
                  ),
                ),
                SizedBox(height: 8),
                Bone.multiText(lines: 2, style: TextStyle(fontSize: 14)),
              ],
            ),
          ),
        ),
      ),
    ],
  );
}

class AniMixRecommendationSkeleton extends StatelessWidget {
  const AniMixRecommendationSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            key: const ValueKey('recommendation_skeleton'),
            children: [
              Expanded(child: Bone(width: double.infinity, uniRadius: 30)),
              const SizedBox(height: 18),
              const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Bone.circle(size: 56),
                  SizedBox(width: 34),
                  Bone.circle(size: 62),
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class AniMixEpisodeListSkeleton extends StatelessWidget {
  const AniMixEpisodeListSkeleton({super.key});

  @override
  Widget build(BuildContext context) => AniMixLoadingFrame(
    child: ListView.separated(
      key: const ValueKey('episode_list_skeleton'),
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(
        AniMixLoadingTokens.compactInset,
        8,
        AniMixLoadingTokens.compactInset,
        28,
      ),
      itemCount: 7,
      separatorBuilder: (_, _) =>
          const SizedBox(height: AniMixLoadingTokens.itemGap),
      itemBuilder: (_, _) => Container(
        height: 72,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface,
          borderRadius: BorderRadius.circular(AniMixLoadingTokens.smallRadius),
          border: Border.all(color: AniMixTheme.divider),
        ),
        child: const Row(
          children: [
            Bone.circle(size: 38),
            SizedBox(width: 12),
            Expanded(
              child: Bone.multiText(lines: 2, style: TextStyle(fontSize: 14)),
            ),
            SizedBox(width: 12),
            Bone.iconButton(size: 36),
          ],
        ),
      ),
    ),
  );
}

class _CommentBone extends StatelessWidget {
  const _CommentBone({required this.reply});

  final bool reply;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(left: reply ? 18 : 0),
    child: Container(
      padding: const EdgeInsets.all(AniMixLoadingTokens.compactInset),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(AniMixLoadingTokens.cardRadius),
        border: Border.all(color: AniMixTheme.divider),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Bone.circle(size: 36),
              SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Bone.text(width: 116, style: TextStyle(fontSize: 13)),
                    SizedBox(height: 5),
                    Bone.text(width: 68, style: TextStyle(fontSize: 10)),
                  ],
                ),
              ),
            ],
          ),
          SizedBox(height: 14),
          Bone.multiText(lines: 3, style: TextStyle(fontSize: 13)),
          SizedBox(height: 12),
          Bone(width: 74, height: 13, uniRadius: 7),
        ],
      ),
    ),
  );
}
