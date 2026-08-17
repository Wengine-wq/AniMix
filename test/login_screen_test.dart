import 'package:animix/features/auth/login_screen.dart';
import 'package:animix/providers/auth_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class _ExpiredSessionNotice extends SessionNoticeNotifier {
  @override
  String? build() => 'Сессия Shikimori истекла. Войдите снова.';
}

void main() {
  testWidgets('shows an expired session inline without opening a dialog', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionNoticeProvider.overrideWith(_ExpiredSessionNotice.new),
        ],
        child: const MaterialApp(home: LoginScreen()),
      ),
    );
    await tester.pump();

    expect(
      find.text('Сессия Shikimori истекла. Войдите снова.'),
      findsOneWidget,
    );
    expect(find.byType(Dialog), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
