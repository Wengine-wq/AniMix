import 'dart:io'; 
import 'dart:ui'; // Для ImageFilter
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; 
import 'package:flutter/services.dart' as services;
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:url_launcher/url_launcher.dart'; 
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart'; // 🔥 Подключаем Liquid Glass

import '../../core/shikimori_auth_service.dart';
import '../../providers/auth_provider.dart';
import '../../main.dart';

class LoginScreen extends HookConsumerWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authService = ref.watch(authServiceProvider);

    return Scaffold(
      backgroundColor: const Color(0xFF050507),
      body: Stack(
        children: [
          // 1. АТМОСФЕРНЫЙ ФОН С ПРЕЛОМЛЕНИЕМ
          _buildAnimatedBackground(),

          // 2. СЛОЙ LIQUID GLASS ДЛЯ ВСЕГО ЭКРАНА
          AdaptiveLiquidGlassLayer(
            settings: const LiquidGlassSettings(
              blur: 40.0,
              thickness: 25.0,
              chromaticAberration: 0.15,
              refractiveIndex: 1.4,
            ),
            child: SafeArea(
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 420), // Идеально для Windows / iPad
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // ЛОГОТИП И ЗАГОЛОВОК
                        const Icon(CupertinoIcons.play_circle_fill, size: 72, color: Colors.white),
                        const SizedBox(height: 16),
                        const Text(
                          'AniMix',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 56, 
                            fontWeight: FontWeight.w900, 
                            color: Colors.white, 
                            letterSpacing: -2,
                            height: 1.1,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Открой мир аниме заново.\nСмотри любимые тайтлы без ограничений.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 17, 
                            color: Colors.white.withValues(alpha: 0.6),
                            fontWeight: FontWeight.w500,
                            height: 1.3,
                          ),
                        ),
                        const SizedBox(height: 64),

                        // 🔥 КНОПКА ВОЙТИ (LIQUID GLASS)
                        GlassButton(
                          onTap: () => _handleLogin(context, ref, authService), // Передаем ref
                          quality: GlassQuality.premium,
                          shape: const LiquidRoundedSuperellipse(borderRadius: 24),
                          settings: const LiquidGlassSettings(
                            glassColor: Color(0x668B5CF6), // Фирменный фиолетовый тинт
                            blur: 15.0,
                            specularSharpness: GlassSpecularSharpness.sharp,
                          ),
                          icon: const Padding(
                            padding: EdgeInsets.symmetric(vertical: 20),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(CupertinoIcons.person_crop_circle_fill, color: Colors.white, size: 24),
                                SizedBox(width: 12),
                                Text(
                                  'Войти через Shikimori',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 18,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: -0.5,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),

                        const SizedBox(height: 16),

                        // ВТОРИЧНАЯ КНОПКА (РУЧНОЙ ВВОД)
                        GlassButton(
                          onTap: () => _showManualCodeDialog(context, ref, authService), // Передаем ref
                          quality: GlassQuality.standard,
                          shape: const LiquidRoundedSuperellipse(borderRadius: 24),
                          settings: const LiquidGlassSettings(
                            glassColor: Color(0x1AFFFFFF), // Легкий светлый тинт
                            blur: 20.0,
                          ),
                          icon: const Padding(
                            padding: EdgeInsets.symmetric(vertical: 18),
                            child: Text(
                              'Ввести код вручную',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: Colors.white70,
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ===================== АТМОСФЕРНЫЙ ФОН =====================
  Widget _buildAnimatedBackground() {
    return Stack(
      children: [
        Positioned(
          top: -100,
          right: -50,
          child: Container(
            width: 300,
            height: 300,
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              color: Color(0x408B5CF6), // Фиолетовое свечение
            ),
          ),
        ),
        Positioned(
          bottom: -50,
          left: -100,
          child: Container(
            width: 400,
            height: 400,
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              color: Color(0x30FF2D55), // Розовое свечение (iOS стиль)
            ),
          ),
        ),
        // Размываем пятна для эффекта объемного тумана
        BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 80, sigmaY: 80),
          child: Container(color: Colors.transparent),
        ),
      ],
    );
  }

  // ===================== ЛОГИКА АВТОРИЗАЦИИ =====================
  Future<void> _handleLogin(BuildContext context, WidgetRef ref, ShikimoriAuthService authService) async {
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
        _performTokenExchange(context, ref, authService, code, 'https://animix.app/callback');
      }
    } else {
      // 💻 ПК (Windows): Запускаем умный локальный сервер
      _startDesktopAuth(context, ref, authService, clientId);
    }
  }

  // 🔥 МАГИЯ ДЛЯ ПК: ЛОКАЛЬНЫЙ СЕРВЕР ПЕРЕХВАТА ОАУТ-РЕДИРЕКТА
  Future<void> _startDesktopAuth(BuildContext context, WidgetRef ref, ShikimoriAuthService authService, String clientId) async {
    final redirectUri = 'http://localhost:33333/callback';
    final authUri = Uri.https('shikimori.io', '/oauth/authorize', {
      'client_id': clientId,
      'redirect_uri': redirectUri,
      'response_type': 'code',
      'scope': 'user_rates comments topics',
    });

    HttpServer? server;
    try {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 33333);
    } catch (e) {
      debugPrint('Порт занят, сервер не запущен: $e');
    }

    if (server == null) {
      await services.Clipboard.setData(services.ClipboardData(text: authUri.toString()));
      if (context.mounted) _showManualCodeDialog(context, ref, authService);
      return;
    }

    try {
      await launchUrl(authUri, mode: LaunchMode.externalApplication);
    } catch (_) {
      await services.Clipboard.setData(services.ClipboardData(text: authUri.toString()));
    }

    if (!context.mounted) return;

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

    await for (HttpRequest request in server) {
      if (request.uri.path == '/callback') {
        final code = request.uri.queryParameters['code'];

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
                .card { background: #1E1E1E; padding: 40px; border-radius: 24px; text-align: center; box-shadow: 0 10px 30px rgba(0,0,0,0.5); border: 1px solid rgba(139, 92, 246, 0.5); }
                h1 { color: #8B5CF6; margin-top: 0; }
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
          Navigator.pop(context); 
          _performTokenExchange(context, ref, authService, code, redirectUri); 
        }
        break; 
      }
    }
  }

  // ===================== ОБМЕН КОДА НА ТОКЕНЫ =====================
  Future<void> _performTokenExchange(BuildContext context, WidgetRef ref, ShikimoriAuthService authService, String code, [String? redirectUri]) async {
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
          // Инвалидируем провайдер (ref теперь передан и доступен)
          ref.invalidate(isLoggedInProvider);
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
  void _showManualCodeDialog(BuildContext context, WidgetRef ref, ShikimoriAuthService authService) {
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
                _performTokenExchange(context, ref, authService, code); 
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
      ..setBackgroundColor(const Color(0xFF050507)) // Адаптировано под новый темный фон
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
      backgroundColor: const Color(0xFF050507),
      // Убираем глухой фон у бара, делаем его нативным и прозрачным
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Авторизация', style: TextStyle(color: Colors.white)),
        backgroundColor: Color(0xCC050507),
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