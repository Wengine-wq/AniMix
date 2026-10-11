import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'config.dart';
import 'server_health.dart';

enum ServerState { checking, online, outage }

/// What the AniMix server is doing right now, judged only by how it answers
/// the same request the app sends when signing in.
class ServerStatusInfo {
  const ServerStatusInfo({
    required this.state,
    required this.title,
    required this.message,
    this.checkedAt,
  });

  final ServerState state;
  final String title;
  final String message;
  final DateTime? checkedAt;

  static const checking = ServerStatusInfo(
    state: ServerState.checking,
    title: 'Проверяем сервер AniMix',
    message: 'Отправляем запрос на вход, это займёт пару секунд.',
  );

  static const _stillWorks = 'Каталог, просмотр и загрузки работают без входа.';

  /// Turns the result of the sign-in probe into words for people.
  static ServerStatusInfo fromProbe({
    int? statusCode,
    DioExceptionType? error,
    required Duration timeout,
    DateTime? at,
  }) {
    final checkedAt = at ?? DateTime.now();
    if (statusCode != null && statusCode < 500) {
      return ServerStatusInfo(
        state: ServerState.online,
        title: 'Сервер AniMix работает',
        message: 'Вход и синхронизация доступны.',
        checkedAt: checkedAt,
      );
    }
    if (statusCode != null) {
      return ServerStatusInfo(
        state: ServerState.outage,
        title: 'Сервер AniMix на ремонте',
        message:
            'На попытку входа облако Яндекса ответило ошибкой $statusCode. '
            'Ждём восстановления. $_stillWorks',
        checkedAt: checkedAt,
      );
    }
    final seconds = timeout.inSeconds;
    final message = switch (error) {
      DioExceptionType.connectionTimeout ||
      DioExceptionType.receiveTimeout ||
      DioExceptionType.sendTimeout =>
        'Облако Яндекса не ответило на попытку входа за $seconds секунд. '
            'Похоже на аварию, ждём восстановления. $_stillWorks',
      _ =>
        'Не удалось подключиться к серверу AniMix. Если интернет есть, значит '
            'облако Яндекса недоступно, ждём восстановления. $_stillWorks',
    };
    return ServerStatusInfo(
      state: ServerState.outage,
      title: 'Сервер AniMix на ремонте',
      message: message,
      checkedAt: checkedAt,
    );
  }
}

class ServerStatusService {
  ServerStatusService._();

  static final ServerStatusService instance = ServerStatusService._();
  static const _timeout = Duration(seconds: 10);

  final ValueNotifier<ServerStatusInfo> status = ValueNotifier(
    ServerStatusInfo.checking,
  );
  final _dio = Dio(
    BaseOptions(
      connectTimeout: _timeout,
      receiveTimeout: _timeout,
      followRedirects: false,
    ),
  );
  Future<void>? _inFlight;

  Future<void> refresh() {
    // Widget tests must not leave network timers behind.
    if (Platform.environment.containsKey('FLUTTER_TEST')) {
      return Future.value();
    }
    return _inFlight ??= _refresh().whenComplete(() => _inFlight = null);
  }

  Future<void> _refresh() async {
    if (status.value.state != ServerState.online) {
      status.value = ServerStatusInfo.checking;
    }
    ServerStatusInfo info;
    try {
      // The first step of signing in: a healthy server redirects to Google.
      final response = await _dio.get<dynamic>(
        '${Config.animixApiBaseUrl}/v1/auth/google/start',
        queryParameters: {'return_uri': Config.animixOAuthCallbackUri},
        options: Options(validateStatus: (status) => status != null),
      );
      info = ServerStatusInfo.fromProbe(
        statusCode: response.statusCode,
        timeout: _timeout,
      );
    } on DioException catch (error) {
      info = ServerStatusInfo.fromProbe(
        statusCode: error.response?.statusCode,
        error: error.type,
        timeout: _timeout,
      );
    } catch (_) {
      info = ServerStatusInfo.fromProbe(timeout: _timeout);
    }
    if (info.state == ServerState.online) {
      ServerHealth.instance.markUp();
    } else {
      ServerHealth.instance.markDown();
    }
    status.value = info;
  }
}
