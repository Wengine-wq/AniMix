import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class KodikWebViewScreen extends StatefulWidget {
  final String kodikEmbedUrl;   // полная ссылка на плеер Kodik
  final String episodeTitle;

  const KodikWebViewScreen({
    required this.kodikEmbedUrl,
    required this.episodeTitle,
    super.key,
  });

  @override
  State<KodikWebViewScreen> createState() => _KodikWebViewScreenState();
}

class _KodikWebViewScreenState extends State<KodikWebViewScreen> {
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF000000))
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (url) {
            // Можно добавить JS для скрытия лишних элементов Kodik, если захочешь
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.kodikEmbedUrl));
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        middle: Text(widget.episodeTitle),
        backgroundColor: Colors.black.withValues(alpha: 0.9),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Icon(CupertinoIcons.back, color: CupertinoColors.white),
        ),
      ),
      child: WebViewWidget(controller: _controller),
    );
  }
}