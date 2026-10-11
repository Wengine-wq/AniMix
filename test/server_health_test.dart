import 'package:animix/core/server_health.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('after a failure requests are refused until the next probe', () {
    final health = ServerHealth.instance..markUp();
    expect(health.allowRequest(), isTrue);
    health.markDown();
    expect(health.allowRequest(), isFalse);
    health.retryNow();
    expect(health.allowRequest(), isTrue, reason: 'one probe goes through');
    expect(health.allowRequest(), isFalse);
    health.markUp();
    expect(health.allowRequest(), isTrue);
  });

  test('only network failures and gateway errors count as unreachable', () {
    final options = RequestOptions(path: '/v1/me');
    DioException error(DioExceptionType type, [int? status]) => DioException(
      requestOptions: options,
      type: type,
      response: status == null
          ? null
          : Response(requestOptions: options, statusCode: status),
    );
    expect(
      ServerHealth.isUnreachable(error(DioExceptionType.connectionTimeout)),
      isTrue,
    );
    expect(
      ServerHealth.isUnreachable(error(DioExceptionType.badResponse, 504)),
      isTrue,
    );
    expect(
      ServerHealth.isUnreachable(error(DioExceptionType.badResponse, 401)),
      isFalse,
    );
  });
}
