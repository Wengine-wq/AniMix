import 'dart:ui';

import 'package:flutter/foundation.dart';

enum AppLogLevel { info, warning, error }

@immutable
class AppLogEntry {
  const AppLogEntry({
    required this.timestamp,
    required this.level,
    required this.message,
    this.source,
    this.stackTrace,
  });

  final DateTime timestamp;
  final AppLogLevel level;
  final String message;
  final String? source;
  final String? stackTrace;

  String get formatted {
    final levelName = level.name.toUpperCase();
    final origin = source == null ? '' : ' [$source]';
    final stack = stackTrace == null ? '' : '\n$stackTrace';
    return '${timestamp.toIso8601String()} $levelName$origin $message$stack';
  }
}

/// A small, privacy-aware in-memory diagnostic log for errors that users can
/// copy from Settings. It is intentionally not persisted between launches.
class AppLogBuffer extends ChangeNotifier {
  AppLogBuffer._();

  static final AppLogBuffer instance = AppLogBuffer._();
  static const int maxEntries = 250;

  final List<AppLogEntry> _entries = <AppLogEntry>[];
  FlutterExceptionHandler? _previousFlutterErrorHandler;
  ErrorCallback? _previousPlatformErrorHandler;
  bool _installed = false;

  List<AppLogEntry> get entries => List.unmodifiable(_entries);
  bool get isEmpty => _entries.isEmpty;

  void installGlobalHandlers() {
    if (_installed) return;
    _installed = true;
    _previousFlutterErrorHandler = FlutterError.onError;
    _previousPlatformErrorHandler = PlatformDispatcher.instance.onError;

    FlutterError.onError = (details) {
      recordError(
        details.exception,
        details.stack,
        source: details.library ?? 'Flutter',
        context: details.context?.toDescription(),
      );
      _previousFlutterErrorHandler?.call(details);
    };
    PlatformDispatcher.instance.onError = (error, stack) {
      recordError(error, stack, source: 'Platform');
      return _previousPlatformErrorHandler?.call(error, stack) ?? false;
    };
  }

  void info(String message, {String? source}) =>
      _add(AppLogLevel.info, message, source: source);

  void warning(String message, {String? source}) =>
      _add(AppLogLevel.warning, message, source: source);

  void recordError(
    Object error,
    StackTrace? stackTrace, {
    String? source,
    String? context,
  }) {
    final message = context == null ? '$error' : '$context: $error';
    _add(
      AppLogLevel.error,
      message,
      source: source,
      stackTrace: stackTrace?.toString(),
    );
  }

  void clear() {
    if (_entries.isEmpty) return;
    _entries.clear();
    notifyListeners();
  }

  String exportText() {
    if (_entries.isEmpty) return 'AniMix: ошибок не зафиксировано.';
    return [
      'AniMix diagnostics',
      'Generated: ${DateTime.now().toIso8601String()}',
      'Entries: ${_entries.length}',
      '',
      ..._entries.map((entry) => entry.formatted),
    ].join('\n');
  }

  void _add(
    AppLogLevel level,
    String message, {
    String? source,
    String? stackTrace,
  }) {
    _entries.add(
      AppLogEntry(
        timestamp: DateTime.now(),
        level: level,
        message: _sanitize(message),
        source: source == null ? null : _sanitize(source),
        stackTrace: stackTrace == null ? null : _sanitize(stackTrace),
      ),
    );
    if (_entries.length > maxEntries) {
      _entries.removeRange(0, _entries.length - maxEntries);
    }
    notifyListeners();
  }

  static String _sanitize(String value) => value
      .replaceAll(
        RegExp(r'Bearer\s+[A-Za-z0-9._~+/=-]+', caseSensitive: false),
        'Bearer <redacted>',
      )
      .replaceAll(
        RegExp(
          r'([?&](?:code|access_token|refresh_token)=)[^&\s]+',
          caseSensitive: false,
        ),
        r'$1<redacted>',
      )
      .replaceAll(
        RegExp(
          r'("?(?:access_token|refresh_token|client_secret)"?\s*[:=]\s*"?)[^"\s,}]+',
          caseSensitive: false,
        ),
        r'$1<redacted>',
      );
}
