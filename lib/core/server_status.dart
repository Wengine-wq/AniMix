import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'config.dart';
import 'server_health.dart';

enum ServerState { checking, online, outage }

/// What to tell people about the AniMix server right now.
///
/// The live state comes from a quick health check. The wording can be changed
/// without a release through `status.json` in the GitHub repository, which is
/// served from a host that stays reachable when our cloud is down.
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
    message: 'Это займёт пару секунд.',
  );

  static const defaultOutageTitle = 'Сервер AniMix на ремонте';
  static const defaultOutageMessage =
      'У облака Яндекса авария в дата-центре, ждём восстановления. '
      'Вход в аккаунт и синхронизация временно недоступны, а каталог, '
      'просмотр и загрузки работают без входа.';
}

class ServerStatusService {
  ServerStatusService._();

  static final ServerStatusService instance = ServerStatusService._();
  static const _noticeUrl =
      'https://raw.githubusercontent.com/Wengine-wq/AniMix/main/status.json';

  final ValueNotifier<ServerStatusInfo> status = ValueNotifier(
    ServerStatusInfo.checking,
  );
  final _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 7),
      receiveTimeout: const Duration(seconds: 8),
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
    final results = await Future.wait([_ping(), _notice()]);
    final online = results[0] as bool;
    final notice = results[1] as Map<String, dynamic>?;
    if (online) {
      ServerHealth.instance.markUp();
    } else {
      ServerHealth.instance.markDown();
    }
    final noticeTitle = notice?['title']?.toString().trim();
    final noticeMessage = notice?['message']?.toString().trim();
    final noticeActive = notice?['active'] == true;
    status.value = online
        ? ServerStatusInfo(
            state: ServerState.online,
            title: 'Сервер AniMix работает',
            message: noticeActive && (noticeMessage?.isNotEmpty ?? false)
                ? noticeMessage!
                : 'Вход и синхронизация доступны.',
            checkedAt: DateTime.now(),
          )
        : ServerStatusInfo(
            state: ServerState.outage,
            title: (noticeTitle?.isNotEmpty ?? false)
                ? noticeTitle!
                : ServerStatusInfo.defaultOutageTitle,
            message: (noticeMessage?.isNotEmpty ?? false)
                ? noticeMessage!
                : ServerStatusInfo.defaultOutageMessage,
            checkedAt: DateTime.now(),
          );
  }

  Future<bool> _ping() async {
    try {
      final response = await _dio.get<dynamic>(
        '${Config.animixApiBaseUrl}/health',
        options: Options(validateStatus: (status) => status != null),
      );
      return (response.statusCode ?? 500) < 500;
    } catch (_) {
      return false;
    }
  }

  Future<Map<String, dynamic>?> _notice() async {
    try {
      final response = await _dio.get<dynamic>(
        _noticeUrl,
        options: Options(responseType: ResponseType.plain),
      );
      final data = jsonDecode(response.data.toString());
      if (data is Map) return Map<String, dynamic>.from(data);
      return null;
    } catch (_) {
      return null;
    }
  }
}
