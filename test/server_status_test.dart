import 'package:animix/core/server_status.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const timeout = Duration(seconds: 10);

  test('a redirect to Google means the server is up', () {
    final info = ServerStatusInfo.fromProbe(statusCode: 302, timeout: timeout);
    expect(info.state, ServerState.online);
  });

  test('gateway errors and timeouts are described as an outage', () {
    final gateway = ServerStatusInfo.fromProbe(
      statusCode: 504,
      timeout: timeout,
    );
    expect(gateway.state, ServerState.outage);
    expect(gateway.message, contains('504'));

    final silent = ServerStatusInfo.fromProbe(
      error: DioExceptionType.connectionTimeout,
      timeout: timeout,
    );
    expect(silent.state, ServerState.outage);
    expect(silent.message, contains('10 секунд'));
  });
}
