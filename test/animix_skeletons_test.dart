import 'package:animix/core/animix_theme.dart';
import 'package:animix/core/app_settings.dart';
import 'package:animix/widgets/animix_skeletons.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:skeletonizer/skeletonizer.dart';

void main() {
  final theme = AniMixTheme.material(
    const Color(0xFF8B5CF6),
    AniMixThemeStyle.graphite,
  );

  Future<void> pumpSkeleton(
    WidgetTester tester,
    Widget child, {
    Size size = const Size(390, 844),
    bool disableAnimations = false,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: theme,
        home: MediaQuery(
          data: MediaQueryData(
            size: size,
            disableAnimations: disableAnimations,
          ),
          child: child,
        ),
      ),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
  }

  testWidgets('detail skeleton has a shaped initial layout on a phone', (
    tester,
  ) async {
    await pumpSkeleton(tester, const AniMixDetailSkeletonScreen());

    expect(find.byKey(const ValueKey('anime_detail_skeleton')), findsOneWidget);
    expect(
      find.byWidgetPredicate((widget) => widget is Skeletonizer),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('catalog switches between list and grid skeletons responsively', (
    tester,
  ) async {
    await pumpSkeleton(tester, const Scaffold(body: AniMixCatalogSkeleton()));
    expect(find.byKey(const ValueKey('catalog_list_skeleton')), findsOneWidget);

    await pumpSkeleton(
      tester,
      const Scaffold(body: AniMixCatalogSkeleton()),
      size: const Size(1000, 720),
    );
    expect(find.byKey(const ValueKey('catalog_grid_skeleton')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'profile and comment skeletons remain finite with reduced motion',
    (tester) async {
      await pumpSkeleton(
        tester,
        const Scaffold(body: AniMixProfileSkeleton()),
        disableAnimations: true,
      );
      expect(find.byKey(const ValueKey('profile_skeleton')), findsOneWidget);

      await tester.pumpWidget(
        MaterialApp(
          theme: theme,
          home: const Scaffold(body: AniMixCommentsSkeleton()),
        ),
      );
      await tester.pump();
      expect(find.byKey(const ValueKey('comments_skeleton')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('home skeleton exposes the real page hierarchy', (tester) async {
    await pumpSkeleton(
      tester,
      const Scaffold(body: SingleChildScrollView(child: AniMixHomeSkeleton())),
    );

    expect(find.byKey(const ValueKey('home_skeleton')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('recommendation and episode skeletons stay finite', (
    tester,
  ) async {
    await pumpSkeleton(
      tester,
      const Scaffold(body: AniMixRecommendationSkeleton()),
    );
    expect(
      find.byKey(const ValueKey('recommendation_skeleton')),
      findsOneWidget,
    );

    await pumpSkeleton(
      tester,
      const Scaffold(body: AniMixEpisodeListSkeleton()),
    );
    expect(find.byKey(const ValueKey('episode_list_skeleton')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
