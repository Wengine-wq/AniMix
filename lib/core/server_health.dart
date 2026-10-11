import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// Tracks whether the AniMix server is reachable.
///
/// When the cloud is down every request used to hang for 15–20 seconds and
/// screens waited one after another. After a network failure the server is
/// marked unreachable and requests fail at once; every [_probeEvery] a single
/// request is let through to see whether it is back.
class ServerHealth {
  ServerHealth._();

  static final ServerHealth instance = ServerHealth._();
  static const _probeEvery = Duration(seconds: 45);

  /// True while the server is believed to be unreachable.
  final ValueNotifier<bool> down = ValueNotifier(false);
  DateTime? _nextProbe;

  bool get isDown => down.value;

  /// Whether a request may go out now. While down, lets one probe through
  /// per interval.
  bool allowRequest() {
    if (!down.value) return true;
    final now = DateTime.now();
    if (_nextProbe == null || now.isAfter(_nextProbe!)) {
      _nextProbe = now.add(_probeEvery);
      return true;
    }
    return false;
  }

  void markDown() {
    _nextProbe = DateTime.now().add(_probeEvery);
    down.value = true;
  }

  void markUp() {
    _nextProbe = null;
    down.value = false;
  }

  /// Lets the next request through right away (pull-to-refresh, «Повторить»).
  void retryNow() => _nextProbe = null;

  static bool isUnreachable(DioException error) => switch (error.type) {
    DioExceptionType.connectionTimeout ||
    DioExceptionType.connectionError ||
    DioExceptionType.sendTimeout => true,
    DioExceptionType.receiveTimeout => true,
    DioExceptionType.badResponse =>
      // Gateway errors from the cloud itself, not answers from our code.
      const {502, 503, 504}.contains(error.response?.statusCode),
    _ => false,
  };

  /// Interceptor for the AniMix API client.
  Interceptor get interceptor => InterceptorsWrapper(
    onRequest: (options, handler) {
      if (allowRequest()) return handler.next(options);
      handler.reject(
        DioException(
          requestOptions: options,
          type: DioExceptionType.connectionError,
          message: 'Сервер AniMix недоступен',
        ),
      );
    },
    onResponse: (response, handler) {
      if (!const {502, 503, 504}.contains(response.statusCode)) markUp();
      handler.next(response);
    },
    onError: (error, handler) {
      if (isUnreachable(error)) {
        markDown();
      } else if (error.response != null) {
        markUp();
      }
      handler.next(error);
    },
  );
}
