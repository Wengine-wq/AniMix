import 'dart:io'; // 🔥 ИМПОРТИРУЕМ ВСЕ ДЛЯ СЕРВЕРА
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; 
import 'package:flutter/services.dart' as services;
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:url_launcher/url_launcher.dart'; 

import '../../core/shikimori_auth_service.dart';
import '../../providers/auth_provider.dart';
import '../../main.dart';

class LoginScreen extends HookConsumerWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authService = ref.watch(authServiceProvider);

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(
        middle: Text('AniMix'),
        backgroundColor: Color(0xFF1E1E1E),
      ),
      child: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text(
                  'AniMix',
                  style: TextStyle(fontSize: 64, fontWeight: FontWeight.w900, color: CupertinoColors.white, letterSpacing: -2),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Смотри любимые аниме совершенно бесплатно',
                  style: TextStyle(fontSize: 18, color: CupertinoColors.systemGrey),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 80),

                CupertinoButton.filled(
                  onPressed: () => _handleLogin(context, authService),
                  child: const Text('Войти с помощью Shikimori'),
                ),

                const SizedBox(height: 16),

                CupertinoButton(
                  onPressed: () => _showManualCodeDialog(context, authService),
                  child: const Text('Ввести код авторизации вручную'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ===================== ЛОГИКА АВТОРИЗАЦИИ =====================
  Future<void> _handleLogin(BuildContext context, ShikimoriAuthService authService) async {
    final clientId = dotenv.env['SHIKIMORI_CLIENT_ID'];
    if (clientId == null || clientId.isEmpty) return;

    if (Platform.isIOS || Platform.isAndroid) {
      // 📱 МОБИЛКИ: Встроенный WebView
      final authUrl = Uri.https('shikimori.io', '/oauth/authorize', {
        'client_id': clientId,
        'redirect_uri': 'https://animix.app/callback',
        'response_type': 'code',
        'scope': 'user_rates comments topics',
      }).toString();

      final code = await Navigator.push<String>(
        context,
        CupertinoPageRoute(builder: (_) => ShikimoriWebViewScreen(url: authUrl)),
      );

      if (code != null && code.isNotEmpty && context.mounted) {
        _performTokenExchange(context, authService, code, 'https://animix.app/callback');
      }
    } else {
      // 💻 ПК (Windows): Запускаем умный локальный сервер
      _startDesktopAuth(context, authService, clientId);
    }
  }

  // 🔥 МАГИЯ ДЛЯ ПК: ЛОКАЛЬНЫЙ СЕРВЕР ПЕРЕХВАТА ОАУТ-РЕДИРЕКТА
  Future<void> _startDesktopAuth(BuildContext context, ShikimoriAuthService authService, String clientId) async {
    final redirectUri = 'http://localhost:33333/callback';
    final authUri = Uri.https('shikimori.io', '/oauth/authorize', {
      'client_id': clientId,
      'redirect_uri': redirectUri,
      'response_type': 'code',
      'scope': 'user_rates comments topics',
    });

    HttpServer? server;
    try {
      // Поднимаем микро-сервер на порту 33333
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 33333);
    } catch (e) {
      debugPrint('Порт занят, сервер не запущен: $e');
    }

    if (server == null) {
      // Если сервер не запустился, откатываемся к старому методу с ручным копированием
      await services.Clipboard.setData(services.ClipboardData(text: authUri.toString()));
      if (context.mounted) _showManualCodeDialog(context, authService);
      return;
    }

    // Открываем браузер Оперу/Chrome
    try {
      await launchUrl(authUri, mode: LaunchMode.externalApplication);
    } catch (_) {
      await services.Clipboard.setData(services.ClipboardData(text: authUri.toString()));
    }

    if (!context.mounted) return;

    // Показываем окно ожидания
    showCupertinoDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Ожидание браузера'),
        content: const Padding(
          padding: EdgeInsets.only(top: 16.0),
          child: Column(
            children: [
              CupertinoActivityIndicator(),
              SizedBox(height: 16),
              Text('Мы открыли браузер.\nАвторизуйся там, и приложение само подхватит твой аккаунт! ✨'),
            ],
          ),
        ),
        actions: [
          CupertinoDialogAction(
            child: const Text('Отмена'),
            onPressed: () {
              server?.close(force: true);
              Navigator.pop(ctx);
            },
          ),
        ],
      ),
    );

    // Слушаем ответ от Shikimori
    await for (HttpRequest request in server) {
      if (request.uri.path == '/callback') {
        final code = request.uri.queryParameters['code'];

        // Формируем красивую заглушку для браузера (вместо 404)
        request.response
          ..statusCode = HttpStatus.ok
          ..headers.contentType = ContentType.html
          ..write('''
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>AniMix Auth</title>
              <style>
                body { background: #0F0F0F; color: #FFF; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .card { background: #1E1E1E; padding: 40px; border-radius: 24px; text-align: center; box-shadow: 0 10px 30px rgba(0,0,0,0.5); border: 1px solid rgba(255,87,34,0.3); }
                h1 { color: #FF5722; margin-top: 0; }
                p { font-size: 18px; color: #A0A0A0; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Успешно! 🎉</h1>
                <p>Мы передали данные в приложение.<br>Можешь смело закрыть эту вкладку.</p>
              </div>
              <script>window.close();</script>
            </body>
            </html>
          ''');
        await request.response.close();
        await server.close(force: true);

        if (context.mounted && code != null) {
          Navigator.pop(context); // Закрываем окно ожидания
          _performTokenExchange(context, authService, code, redirectUri); // Логинимся
        }
        break; // Останавливаем цикл
      }
    }
  }

  // ===================== ОБМЕН КОДА НА ТОКЕНЫ =====================
  Future<void> _performTokenExchange(BuildContext context, ShikimoriAuthService authService, String code, [String? redirectUri]) async {
    showCupertinoDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CupertinoActivityIndicator(radius: 20)),
    );

    try {
      final success = await authService.login(code, redirectUri);
      
      if (context.mounted) {
        Navigator.pop(context);
        
        if (success) {
          Navigator.of(context).pushReplacement(
            CupertinoPageRoute(builder: (_) => const MainTabs()),
          );
        } else {
          _showErrorDialog(context, 'Не удалось авторизоваться.');
        }
      }
    } catch (e) {
      if (context.mounted) {
        Navigator.pop(context);
        _showErrorDialog(context, e.toString());
      }
    }
  }

  // ===================== ОКНО РУЧНОГО ВВОДА КОДА =====================
  void _showManualCodeDialog(BuildContext context, ShikimoriAuthService authService) {
    final controller = TextEditingController();

    showCupertinoDialog(
      context: context,
      builder: (dialogContext) => CupertinoAlertDialog(
        title: const Text('Введи код авторизации'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: CupertinoTextField(
                controller: controller,
                placeholder: 'Вставь код из браузера',
                padding: const EdgeInsets.all(12),
                style: const TextStyle(color: CupertinoColors.white),
              ),
            ),
            const SizedBox(height: 12),
            CupertinoButton(
              onPressed: () async {
                final clipboardData = await services.Clipboard.getData(services.Clipboard.kTextPlain);
                if (clipboardData?.text != null && clipboardData!.text!.isNotEmpty) {
                  String text = clipboardData.text!.trim();
                  if (text.contains('code=')) {
                    final uri = Uri.tryParse(text);
                    if (uri != null && uri.queryParameters['code'] != null) {
                      text = uri.queryParameters['code']!;
                    }
                  }
                  controller.text = text;
                }
              },
              child: const Text('📋 Вставить из буфера'),
            ),
          ],
        ),
        actions: [
          CupertinoDialogAction(
            child: const Text('Отмена'),
            onPressed: () => Navigator.pop(dialogContext),
          ),
          CupertinoDialogAction(
            isDefaultAction: true,
            child: const Text('Войти'),
            onPressed: () {
              Navigator.pop(dialogContext);
              final code = controller.text.trim();
              if (code.isNotEmpty) {
                _performTokenExchange(context, authService, code); // Тут пойдет дефолтный redirectUri
              }
            },
          ),
        ],
      ),
    );
  }

  void _showErrorDialog(BuildContext context, String message) {
    showCupertinoDialog(
      context: context,
      builder: (errorContext) => CupertinoAlertDialog(
        title: const Text('Ошибка'),
        content: Text(message),
        actions: [CupertinoDialogAction(child: const Text('OK'), onPressed: () => Navigator.pop(errorContext))],
      ),
    );
  }
}

// ===================== ВСТРОЕННЫЙ БРАУЗЕР ДЛЯ МОБИЛОК =====================
class ShikimoriWebViewScreen extends StatefulWidget {
  final String url;
  const ShikimoriWebViewScreen({required this.url, super.key});

  @override
  State<ShikimoriWebViewScreen> createState() => _ShikimoriWebViewScreenState();
}

class _ShikimoriWebViewScreenState extends State<ShikimoriWebViewScreen> {
  late final WebViewController _controller;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF0F0F0F))
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) {
            if (mounted) setState(() => _isLoading = false);
          },
          onUrlChange: (UrlChange change) {
            final url = change.url;
            if (url != null && url.startsWith('https://animix.app/callback')) {
              final uri = Uri.parse(url);
              final code = uri.queryParameters['code'];
              Navigator.pop(context, code ?? '');
            }
          },
          onNavigationRequest: (request) {
            if (request.url.startsWith('https://animix.app/callback')) {
              final uri = Uri.parse(request.url);
              Navigator.pop(context, uri.queryParameters['code'] ?? '');
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.url));
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Авторизация'),
        backgroundColor: Color(0xFF1E1E1E),
        previousPageTitle: 'Назад',
      ),
      child: SafeArea(
        child: Stack(
          children: [
            WebViewWidget(controller: _controller),
            if (_isLoading) const Center(child: CupertinoActivityIndicator(radius: 20)),
          ],
        ),
      ),
    );
  }
}