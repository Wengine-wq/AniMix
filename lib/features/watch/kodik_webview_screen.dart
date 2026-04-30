import 'dart:io';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_windows/webview_windows.dart';
import 'package:url_launcher/url_launcher.dart';

import 'watch_storage.dart';

class KodikWebViewScreen extends StatefulWidget {
  final String kodikEmbedUrl;   
  final String episodeTitle;
  final int? animeId;
  final String? episodeNumber;

  const KodikWebViewScreen({
    required this.kodikEmbedUrl,
    required this.episodeTitle,
    this.animeId,
    this.episodeNumber,
    super.key,
  });

  @override
  State<KodikWebViewScreen> createState() => _KodikWebViewScreenState();
}

class _KodikWebViewScreenState extends State<KodikWebViewScreen> {
  WebViewController? _mobileController;
  final _windowsController = WebviewController();
  
  bool _isWindowsInitialized = false;
  bool _isPlayerInjected = false;

  bool get _isMobile => Platform.isIOS || Platform.isAndroid;
  bool get _isWindows => Platform.isWindows;

  final String _adBlockScript = '''
    var initSafeAdBlock = function() {
        window.open = function() { console.log("AdBlock: popup blocked safely"); return null; };
        document.addEventListener('click', function(e) {
            var link = e.target.closest('a');
            if (link && link.target === '_blank') {
                e.preventDefault();
            }
        }, true);
    };
    initSafeAdBlock();
  ''';

  final String _observerScript = '''
    var maxTime = 0;
    var lastTimeMsg = 0;
    window.addEventListener('message', function(e) {
        try {
            var data = e.data;
            if (typeof data === 'string') data = JSON.parse(data);
            
            if (data && data.key === 'kodik_player_video_ended') {
                if (window.AnimeApp) window.AnimeApp.postMessage('watched');
            }
            
            if (data && data.key === 'kodik_player_time_update') {
                if (data.value > maxTime) maxTime = data.value;
                
                var currentSec = Math.floor(data.value);
                if (Math.abs(currentSec - lastTimeMsg) >= 5) {
                    lastTimeMsg = currentSec;
                    if (window.AnimeApp) window.AnimeApp.postMessage('time:' + currentSec);
                }

                if (maxTime > 900) { 
                    if (window.AnimeApp) window.AnimeApp.postMessage('watched');
                }
            }
        } catch(e) {}
    });
  ''';

  @override
  void initState() {
    super.initState();
    if (_isMobile) {
      _initMobileWebview();
    } else if (_isWindows) {
      _initWindowsWebview();
    } else {
      _launchExternalBrowser();
    }
  }

  void _initMobileWebview() {
    _mobileController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF000000))
      // Намеренно не меняем UserAgent, чтобы iOS использовал свой нативный плеер
      ..addJavaScriptChannel('AnimeApp', onMessageReceived: (msg) {
        _handlePlayerMessage(msg.message);
      })
      ..setNavigationDelegate(NavigationDelegate(
        onNavigationRequest: (request) {
          if (request.isMainFrame) {
            final url = request.url.toLowerCase();
            if (url.contains('casino') || url.contains('bet') || url.contains('promo')) {
              return NavigationDecision.prevent;
            }
          }
          return NavigationDecision.navigate;
        },
        onPageFinished: (url) {
          _mobileController!.runJavaScript(_adBlockScript);
          _mobileController!.runJavaScript(_observerScript);
        }
      ));

    // Прямая загрузка с передачей заголовков Referer и Origin для обхода защиты балансера
    _mobileController!.loadRequest(
      Uri.parse(widget.kodikEmbedUrl),
      headers: {
        'Referer': 'https://yani.tv/',
        'Origin': 'https://yani.tv',
      },
    );
  }

  Future<void> _initWindowsWebview() async {
    try {
      await _windowsController.initialize();
      _windowsController.webMessage.listen((msg) {
        _handlePlayerMessage(msg.toString());
      });
      _windowsController.loadingState.listen((state) async {
        if (state == LoadingState.navigationCompleted && !_isPlayerInjected) {
          _isPlayerInjected = true;
          await _windowsController.executeScript('''
            document.body.style.margin = '0';
            document.body.style.backgroundColor = '#000000';
            document.body.style.overflow = 'hidden';
            document.body.innerHTML = '<iframe src="${widget.kodikEmbedUrl}" style="position:fixed; top:0; left:0; width:100%; height:100%; border:none;" allowfullscreen></iframe>';
          ''');
          await _windowsController.executeScript(_adBlockScript);
          await _windowsController.executeScript(_observerScript);
        }
      });
      await _windowsController.loadUrl('https://yani.tv/404'); 
      if (mounted) setState(() => _isWindowsInitialized = true);
    } catch (e) {
      debugPrint('Ошибка Windows webview: $e');
    }
  }

  void _handlePlayerMessage(String msg) {
    if (widget.animeId == null || widget.episodeNumber == null) return;
    if (msg == 'watched') {
      WatchStorage.markEpisodeWatched(widget.animeId!, widget.episodeNumber!);
    } else if (msg.startsWith('time:')) {
      final seconds = int.tryParse(msg.split(':')[1]);
      if (seconds != null) {
        WatchStorage.saveProgress(widget.animeId!, widget.episodeNumber!, Duration(seconds: seconds));
      }
    }
  }

  Future<void> _launchExternalBrowser() async {
    final uri = Uri.tryParse(widget.kodikEmbedUrl);
    if (uri != null && await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  void dispose() {
    if (_isWindows) _windowsController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        middle: Text(widget.episodeTitle),
        backgroundColor: Colors.black.withOpacity(0.9),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Icon(CupertinoIcons.back, color: CupertinoColors.white),
        ),
      ),
      child: SafeArea(
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_isMobile) return WebViewWidget(controller: _mobileController!);
    if (_isWindows) return _isWindowsInitialized ? Webview(_windowsController) : const Center(child: CupertinoActivityIndicator());
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(CupertinoIcons.device_laptop, color: Colors.white, size: 60),
          const SizedBox(height: 20),
          const Text('Открыто во внешнем браузере', style: TextStyle(color: Colors.white, fontSize: 16)),
          const SizedBox(height: 20),
          CupertinoButton.filled(
            onPressed: _launchExternalBrowser, 
            child: const Text('Открыть')
          )
        ],
      ),
    );
  }
}