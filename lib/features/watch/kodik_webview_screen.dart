import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';
import 'package:webview_flutter_wkwebview/webview_flutter_wkwebview.dart';
import 'package:webview_windows/webview_windows.dart';

import '../../core/animix_theme.dart';
import '../../core/config.dart';
import '../../widgets/animix_surface.dart';
import 'services/hls_playlist_service.dart';
import 'services/resolved_stream_cache.dart';
import 'watch_storage.dart';

/// Loads the provider player only as a technical stream resolver. The embedded
/// page is intentionally never presented to the user: playback happens in the
/// native AniMix player after an HLS/MP4 URL has been captured.
class KodikWebViewScreen extends StatefulWidget {
  const KodikWebViewScreen({
    required this.kodikEmbedUrl,
    required this.episodeTitle,
    this.animeTitle,
    this.animeId,
    this.episodeNumber,
    this.posterUrl,
    super.key,
  });

  final String kodikEmbedUrl;
  final String episodeTitle;
  final String? animeTitle;
  final int? animeId;
  final String? episodeNumber;
  final String? posterUrl;

  @override
  State<KodikWebViewScreen> createState() => _KodikWebViewScreenState();
}

class _KodikWebViewScreenState extends State<KodikWebViewScreen> {
  WebViewController? _mobileController;
  final WebviewController _windowsController = WebviewController();
  final HlsPlaylistService _hlsService = HlsPlaylistService();

  bool _windowsReady = false;
  bool _windowsControllerInitialized = false;
  bool _playerInjected = false;
  bool _streamCaptured = false;
  String? _resolverError;
  Timer? _timeout;
  Timer? _captureDebounce;
  String? _bestCapture;
  int _bestCaptureRank = -1;
  final Set<String> _seenCaptures = <String>{};

  bool get _isMobile => Platform.isIOS || Platform.isAndroid;
  bool get _isWindows => Platform.isWindows;

  static const String _bootstrapScript = r'''
    (function() {
      if (window.__animixInterceptorInstalled) return;
      window.__animixInterceptorInstalled = true;

      function post(type, value) {
        try {
          var message = { type: type, value: value || '' };
          if (window.AnimeApp) window.AnimeApp.postMessage(JSON.stringify(message));
          else if (window.chrome && window.chrome.webview) {
            window.chrome.webview.postMessage(message);
          }
        } catch (_) {}
      }

      function normalize(value) {
        if (typeof value !== 'string') return '';
        return value.replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
      }

      var reported = {};
      function send(value, source) {
        try {
          if (!value) return;
          var url = normalize(typeof value === 'string' ? value : (value.url || value.src || ''));
          if (!url) return;
          if (url.indexOf('[') !== 0) {
            try { url = new URL(url, document.baseURI).href; } catch (_) {}
          }
          if ((/\.(m3u8|mp4)(\?|$)/i.test(url) || /^\[[^\]]+\]https?:/i.test(url)) &&
              !/(trash|advert|promo|vast|preroll|banner|casino|bet|adsbygoogle|mradx|adfox|adriver)/i.test(url)) {
            if (reported[url]) return;
            reported[url] = true;
            post('stream', url);
            post('log', (source || 'scan') + ': ' + url.substring(0, 180));
          }
        } catch (_) {}
      }

      function scanText(text, source) {
        try {
          if (!text || typeof text !== 'string') return;
          var playerJs = text.match(/\[(?:2160|1440|1080|720|480|360)p?\](?:https?:)?\\?\/\\?\/[^"'\\s]+(?:,\s*\[[^\]]+\](?:https?:)?\\?\/\\?\/[^"'\\s]+)*/i);
          if (playerJs) send(playerJs[0], source + '-playerjs');
          var urls = text.match(/(?:https?:)?\\?\/\\?\/[^"'<>\\s]+\.(?:m3u8|mp4)(?:\?[^"'<>\\s]*)?/ig) || [];
          urls.forEach(function(url) { send(url, source + '-body'); });
        } catch (_) {}
      }

      window.open = function() { return null; };
      document.addEventListener('click', function(event) {
        var link = event.target && event.target.closest && event.target.closest('a');
        if (link && (link.target === '_blank' || /advert|promo|casino|bet/i.test(link.href))) {
          event.preventDefault();
          event.stopImmediatePropagation();
        }
      }, true);

      var originalFetch = window.fetch;
      if (originalFetch) {
        window.fetch = function() {
          var request = arguments[0];
          send(typeof request === 'string' ? request : request && request.url, 'fetch-request');
          return originalFetch.apply(this, arguments).then(function(response) {
            send(response && response.url, 'fetch-response');
            try {
              response.clone().text().then(function(text) { scanText(text, 'fetch'); }).catch(function() {});
            } catch (_) {}
            return response;
          });
        };
      }

      var originalOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method, url) {
        send(url, 'xhr-request');
        this.addEventListener('load', function() {
          try {
            send(this.responseURL, 'xhr-response');
            if (!this.responseType || this.responseType === 'text') scanText(this.responseText, 'xhr');
          } catch (_) {}
        });
        return originalOpen.apply(this, arguments);
      };

      if (window.PerformanceObserver) {
        try {
          new PerformanceObserver(function(list) {
            list.getEntries().forEach(function(entry) { send(entry.name, 'performance'); });
          }).observe({entryTypes: ['resource']});
        } catch (_) {}
      }

      try {
        new MutationObserver(function(mutations) {
          mutations.forEach(function(mutation) {
            var node = mutation.target;
            send(node && (node.currentSrc || node.src), 'mutation');
          });
        }).observe(document.documentElement, {
          attributes: true,
          attributeFilter: ['src'],
          childList: true,
          subtree: true
        });
      } catch (_) {}

      function scan() {
        try {
          performance.getEntriesByType('resource').forEach(function(entry) { send(entry.name, 'resource'); });

          var configs = [
            window.PlayerjsConfig && window.PlayerjsConfig.file,
            window.playerjsConfig && window.playerjsConfig.file,
            window.playerConfig && (window.playerConfig.file || window.playerConfig.src)
          ];
          configs.forEach(function(file) { send(file, 'player-config'); });

          document.querySelectorAll('video, source').forEach(function(node) {
            send(node.currentSrc || node.src, 'media-element');
            if (node.tagName === 'VIDEO') {
              node.muted = true;
              var promise = node.play();
              if (promise && promise.catch) promise.catch(function() {});
            }
          });

          var selectors = [
            '.play_button', '.play-button', '.fp-ui', '.fp-play', '.play-btn',
            '.pjs-play', '#play', '.vjs-big-play-button',
            '.plyr__control--overlaid', '[data-player-play]'
          ];
          for (var index = 0; index < selectors.length; index++) {
            var button = document.querySelector(selectors[index]);
            if (button && button.click) button.click();
          }
        } catch (_) {}
      }
      scan();
      setInterval(scan, 500);

      var lastSecond = 0;
      window.addEventListener('message', function(event) {
        try {
          var data = event.data;
          if (typeof data === 'string') data = JSON.parse(data);
          if (data && data.key === 'kodik_player_video_ended') post('watched', '');
          if (data && data.key === 'kodik_player_time_update') {
            var second = Math.floor(data.value || 0);
            if (Math.abs(second - lastSecond) >= 5) {
              lastSecond = second;
              post('time', String(second));
            }
          }
        } catch (_) {}
      });

      post('log', 'interceptor-ready ' + location.href);
    })();
  ''';

  @override
  void initState() {
    super.initState();
    _startResolver();
  }

  Future<void> _startResolver() async {
    final cached = await ResolvedStreamCache.get(_normalizedUrl);
    if (!mounted) return;
    if (cached != null && cached.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _finishWithSources(cached);
      });
      return;
    }
    _armTimeout();
    if (_isMobile) {
      await _initMobileWebView();
    } else if (_isWindows) {
      _initWindowsWebView();
    } else {
      setState(() {
        _resolverError =
            'Перехват потока поддерживается на Android, iOS и Windows.';
      });
    }
  }

  void _armTimeout() {
    _timeout?.cancel();
    _timeout = Timer(const Duration(seconds: 28), () {
      if (mounted && !_streamCaptured) {
        setState(
          () => _resolverError =
              'Плеер не отдал прямую ссылку. Проверьте соединение и повторите попытку.',
        );
      }
    });
  }

  Future<void> _initMobileWebView() async {
    final PlatformWebViewControllerCreationParams params;
    if (Platform.isIOS) {
      params = WebKitWebViewControllerCreationParams(
        allowsInlineMediaPlayback: true,
        mediaTypesRequiringUserAction: const <PlaybackMediaTypes>{},
      );
    } else {
      params = const PlatformWebViewControllerCreationParams();
    }
    final controller = WebViewController.fromPlatformCreationParams(params);
    _mobileController = controller
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.transparent)
      ..addJavaScriptChannel(
        'AnimeApp',
        onMessageReceived: (message) => _handlePlayerMessage(message.message),
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) {
            if (!request.isMainFrame) return NavigationDecision.navigate;
            final target = request.url.toLowerCase();
            final originalHost = Uri.tryParse(widget.kodikEmbedUrl)?.host;
            final targetHost = Uri.tryParse(request.url)?.host;
            if (targetHost != null &&
                originalHost != null &&
                targetHost != originalHost &&
                !target.contains('.m3u8')) {
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
          onPageStarted: (_) =>
              _mobileController?.runJavaScript(_bootstrapScript),
          onPageFinished: (_) =>
              _mobileController?.runJavaScript(_bootstrapScript),
          onWebResourceError: (error) {
            if (error.isForMainFrame == true && mounted) {
              setState(
                () => _resolverError = 'Не удалось открыть источник видео.',
              );
            }
          },
        ),
      );
    await controller.setUserAgent(
      Platform.isIOS ? Config.appleBrowserUserAgent : Config.browserUserAgent,
    );
    if (controller.platform is AndroidWebViewController) {
      await (controller.platform as AndroidWebViewController)
          .setMediaPlaybackRequiresUserGesture(false);
    }
    await controller.loadRequest(
      Uri.parse(_normalizedUrl),
      headers: Config.providerMediaHeaders,
    );
  }

  Future<void> _initWindowsWebView() async {
    try {
      await _windowsController.initialize();
      _windowsControllerInitialized = true;
      await _windowsController.setUserAgent(Config.browserUserAgent);
      await _windowsController.addScriptToExecuteOnDocumentCreated(
        _bootstrapScript,
      );
      _windowsController.webMessage.listen(_handlePlayerMessage);
      _windowsController.loadingState.listen((state) async {
        if (state != LoadingState.navigationCompleted || _playerInjected) {
          return;
        }
        _playerInjected = true;
        final encodedUrl = jsonEncode(_normalizedUrl);
        await _windowsController.executeScript('''
          window.location.replace($encodedUrl);
        ''');
      });
      await _windowsController.loadUrl('${Config.yummyWebOrigin}/404');
      if (mounted) setState(() => _windowsReady = true);
    } catch (error) {
      if (mounted) {
        setState(() => _resolverError = 'WebView2 недоступен: $error');
      }
    }
  }

  String get _normalizedUrl => widget.kodikEmbedUrl.startsWith('//')
      ? 'https:${widget.kodikEmbedUrl}'
      : widget.kodikEmbedUrl;

  void _handlePlayerMessage(dynamic rawMessage) {
    dynamic message = rawMessage;
    if (message is String) {
      final trimmed = message.trim();
      try {
        message = jsonDecode(trimmed);
      } catch (_) {
        message = trimmed;
      }
    }
    if (message is Map) {
      final type = message['type']?.toString();
      final value = message['value']?.toString() ?? '';
      if (type == 'stream') {
        _queueCapturedStream(value);
        return;
      }
      if (type == 'log') {
        debugPrint('[AniMix resolver] $value');
        return;
      }
      if (type == 'watched') {
        _markWatched();
        return;
      }
      if (type == 'time') {
        _saveWatchTime(value);
        return;
      }
    }

    final text = message.toString().replaceAll(RegExp(r'''^["']|["']$'''), '');
    if (text.startsWith('stream:')) {
      _queueCapturedStream(text.substring('stream:'.length));
      return;
    }
    if (widget.animeId == null || widget.episodeNumber == null) return;
    if (text == 'watched') {
      _markWatched();
    } else if (text.startsWith('time:')) {
      _saveWatchTime(text.substring('time:'.length));
    }
  }

  void _markWatched() {
    if (widget.animeId != null && widget.episodeNumber != null) {
      WatchStorage.markEpisodeWatched(widget.animeId!, widget.episodeNumber!);
    }
  }

  void _saveWatchTime(String rawSeconds) {
    final seconds = int.tryParse(rawSeconds);
    if (seconds != null &&
        widget.animeId != null &&
        widget.episodeNumber != null) {
      WatchStorage.saveProgress(
        widget.animeId!,
        widget.episodeNumber!,
        Duration(seconds: seconds),
      );
    }
  }

  void _queueCapturedStream(String value) {
    if (_streamCaptured || !mounted || value.isEmpty) return;
    if (!_seenCaptures.add(value)) return;
    final rank = _captureRank(value);
    if (rank < 0) return;
    if (rank > _bestCaptureRank) {
      _bestCaptureRank = rank;
      _bestCapture = value;
    }
    _captureDebounce?.cancel();
    _captureDebounce = Timer(
      Duration(milliseconds: rank >= 400 ? 150 : 850),
      _resolveBestCapture,
    );
  }

  static int _captureRank(String value) {
    final lower = value.toLowerCase();
    if (RegExp(
      r'trash|advert|promo|vast|preroll|banner|casino|adsbygoogle|mradx|adfox|adriver',
    ).hasMatch(lower)) {
      return -1;
    }
    if (RegExp(r'^\[[^\]]+\](?:https?:)?//').hasMatch(value)) return 400;
    if (lower.contains('master') && lower.contains('.m3u8')) return 350;
    if (lower.contains('.m3u8')) return 300;
    if (lower.contains('.mp4')) return 200;
    return -1;
  }

  Future<void> _resolveBestCapture() async {
    final streamValue = _bestCapture;
    if (streamValue == null) return;
    await _handleCapturedStream(streamValue);
  }

  Future<void> _handleCapturedStream(String streamValue) async {
    if (_streamCaptured || !mounted) return;
    _streamCaptured = true;
    try {
      final sources = await _hlsService.resolveQualities(streamValue);
      if (!mounted) return;
      if (sources.isEmpty) {
        _streamCaptured = false;
        _bestCapture = null;
        _bestCaptureRank = -1;
        return;
      }
      _timeout?.cancel();
      await ResolvedStreamCache.put(_normalizedUrl, sources);
      if (!mounted) return;
      _finishWithSources(sources);
    } catch (error) {
      _streamCaptured = false;
      _bestCapture = null;
      _bestCaptureRank = -1;
      _seenCaptures.clear();
      debugPrint('[AniMix resolver] candidate rejected: $error');
    }
  }

  void _finishWithSources(Map<String, String> sources) {
    if (!mounted) return;
    Navigator.of(context).pop<Map<String, String>>(sources);
  }

  Future<void> _retry() async {
    setState(() {
      _resolverError = null;
      _streamCaptured = false;
      _bestCapture = null;
      _bestCaptureRank = -1;
      _seenCaptures.clear();
    });
    await ResolvedStreamCache.invalidate(_normalizedUrl);
    _armTimeout();
    if (_isMobile) {
      await _mobileController?.loadRequest(
        Uri.parse(_normalizedUrl),
        headers: Config.providerMediaHeaders,
      );
    } else if (_isWindows && _windowsReady) {
      await _windowsController.executeScript(
        'window.location.replace(${jsonEncode(_normalizedUrl)});',
      );
    }
  }

  @override
  void dispose() {
    _timeout?.cancel();
    _captureDebounce?.cancel();
    if (_isWindows && _windowsControllerInitialized) {
      _windowsController.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return AniMixPage(
      title: widget.episodeTitle,
      child: Stack(
        children: [
          // Keep WebView alive and painted so media requests are not throttled,
          // but never expose provider UI or advertisements.
          Positioned.fill(
            child: IgnorePointer(
              child: Opacity(opacity: 0.01, child: _webView()),
            ),
          ),
          Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 460),
                child: AniMixSurface(
                  padding: const EdgeInsets.all(30),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 70,
                        height: 70,
                        decoration: BoxDecoration(
                          color: accent.withValues(alpha: 0.16),
                          shape: BoxShape.circle,
                        ),
                        child: _resolverError == null
                            ? Padding(
                                padding: const EdgeInsets.all(18),
                                child: CircularProgressIndicator(
                                  color: accent,
                                  strokeWidth: 3,
                                ),
                              )
                            : const Icon(Icons.link_off_rounded, size: 32),
                      ),
                      const SizedBox(height: 22),
                      Text(
                        _resolverError == null
                            ? 'Подготавливаем прямой поток'
                            : 'Не удалось получить видео',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 21,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 9),
                      Text(
                        _resolverError ??
                            'AniMix перехватывает HLS-ссылку и откроет её без рекламного iframe.',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: AniMixTheme.subtleText,
                          height: 1.45,
                        ),
                      ),
                      if (_resolverError != null) ...[
                        const SizedBox(height: 22),
                        FilledButton.icon(
                          onPressed: _retry,
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Повторить'),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _webView() {
    if (_isMobile && _mobileController != null) {
      return WebViewWidget(controller: _mobileController!);
    }
    if (_isWindows && _windowsReady) return Webview(_windowsController);
    return const SizedBox.expand();
  }
}
