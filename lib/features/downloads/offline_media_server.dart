import 'dart:async';
import 'dart:io';

/// Exposes downloaded HLS packages over loopback HTTP.
///
/// AVPlayer does not reliably open a raw local `.m3u8` file. Serving the same
/// package from 127.0.0.1 keeps every segment offline while making playback
/// behave like regular HLS on iOS, Android and desktop players.
class OfflineMediaServer {
  OfflineMediaServer._();

  static final OfflineMediaServer instance = OfflineMediaServer._();

  final Map<String, Directory> _roots = <String, Directory>{};
  HttpServer? _server;

  Future<Uri> serve(String episodeId, File manifest) async {
    final server = await _ensureStarted();
    final token = _safeToken(episodeId);
    _roots[token] = manifest.parent;
    return Uri(
      scheme: 'http',
      host: InternetAddress.loopbackIPv4.address,
      port: server.port,
      pathSegments: <String>[token, manifest.uri.pathSegments.last],
    );
  }

  Future<HttpServer> _ensureStarted() async {
    final current = _server;
    if (current != null) return current;
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server = server;
    unawaited(server.forEach(_handleRequest));
    return server;
  }

  Future<void> close() async {
    final server = _server;
    _server = null;
    _roots.clear();
    await server?.close(force: true);
  }

  Future<void> _handleRequest(HttpRequest request) async {
    try {
      final segments = request.uri.pathSegments;
      if (segments.length != 2 || segments.any(_unsafeSegment)) {
        request.response.statusCode = HttpStatus.badRequest;
        await request.response.close();
        return;
      }
      final root = _roots[segments.first];
      if (root == null) {
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
        return;
      }
      final file = File(
        '${root.path}${Platform.pathSeparator}${segments.last}',
      );
      if (!await file.exists()) {
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
        return;
      }
      await _sendFile(request, file);
    } catch (_) {
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }

  Future<void> _sendFile(HttpRequest request, File file) async {
    final length = await file.length();
    final response = request.response;
    response.headers
      ..set(HttpHeaders.acceptRangesHeader, 'bytes')
      ..contentType = _contentType(file.path);

    var start = 0;
    var end = length - 1;
    final range = request.headers.value(HttpHeaders.rangeHeader);
    final match = range == null
        ? null
        : RegExp(r'^bytes=(\d*)-(\d*)$').firstMatch(range);
    if (match != null) {
      final requestedStart = int.tryParse(match.group(1) ?? '');
      final requestedEnd = int.tryParse(match.group(2) ?? '');
      if (requestedStart != null) start = requestedStart;
      if (requestedEnd != null) end = requestedEnd;
      if (start < 0 || start >= length || end < start) {
        response
          ..statusCode = HttpStatus.requestedRangeNotSatisfiable
          ..headers.set(HttpHeaders.contentRangeHeader, 'bytes */$length');
        await response.close();
        return;
      }
      end = end.clamp(start, length - 1);
      response
        ..statusCode = HttpStatus.partialContent
        ..headers.set(
          HttpHeaders.contentRangeHeader,
          'bytes $start-$end/$length',
        );
    }
    response.contentLength = end - start + 1;
    if (request.method == 'HEAD') {
      await response.close();
      return;
    }
    await file.openRead(start, end + 1).pipe(response);
  }

  static bool _unsafeSegment(String value) =>
      value.isEmpty || value == '.' || value == '..' || value.contains('\\');

  static String _safeToken(String value) =>
      value.replaceAll(RegExp(r'[^a-zA-Z0-9_-]'), '_');

  static ContentType _contentType(String path) {
    final lower = path.toLowerCase();
    if (lower.endsWith('.m3u8')) {
      return ContentType('application', 'vnd.apple.mpegurl', charset: 'utf-8');
    }
    if (lower.endsWith('.ts')) return ContentType('video', 'mp2t');
    if (lower.endsWith('.mp4') || lower.endsWith('.m4s')) {
      return ContentType('video', 'mp4');
    }
    if (lower.endsWith('.key')) {
      return ContentType.binary;
    }
    return ContentType.binary;
  }
}
