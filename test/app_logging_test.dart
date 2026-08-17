import 'package:animix/core/app_logging.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final logs = AppLogBuffer.instance;

  setUp(logs.clear);
  tearDown(logs.clear);

  test('redacts OAuth codes and bearer tokens from exported diagnostics', () {
    logs.recordError(
      Exception(
        'GET /callback?code=secret-code&state=ok '
        'Authorization: Bearer super.secret.token',
      ),
      StackTrace.fromString('access_token=also-secret'),
      source: 'OAuth',
    );

    final exported = logs.exportText();
    expect(exported, isNot(contains('secret-code')));
    expect(exported, isNot(contains('super.secret.token')));
    expect(exported, isNot(contains('also-secret')));
    expect(exported, contains('<redacted>'));
  });

  test('keeps the log buffer bounded', () {
    for (var index = 0; index < AppLogBuffer.maxEntries + 8; index++) {
      logs.info('entry $index');
    }

    expect(logs.entries, hasLength(AppLogBuffer.maxEntries));
    expect(logs.entries.first.message, 'entry 8');
  });
}
