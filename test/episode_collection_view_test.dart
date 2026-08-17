import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:animix/features/watch/widgets/episode_collection_view.dart';

void main() {
  testWidgets('episodes stay a vertical list on wide layouts', (tester) async {
    tester.view.physicalSize = const Size(1440, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: EpisodeCollectionView(
            episodes: const [
              EpisodeViewData(
                number: '1',
                title: 'Серия 1',
                downloadId: '1_test_1',
              ),
              EpisodeViewData(
                number: '2',
                title: 'Серия 2',
                downloadId: '1_test_2',
              ),
            ],
            onPlay: (_) {},
            onDownload: (_) {},
          ),
        ),
      ),
    );

    expect(find.byType(ListView), findsOneWidget);
    expect(find.byType(GridView), findsNothing);
    expect(find.text('Серия 1'), findsOneWidget);
    expect(find.text('Серия 2'), findsOneWidget);
  });
}
