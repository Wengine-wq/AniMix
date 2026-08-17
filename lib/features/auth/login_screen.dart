import 'dart:async';
import 'dart:io';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' as services;
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/config.dart';
import '../../core/app_logging.dart';
import '../../core/shikimori_auth_service.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';

class _LoginBackdrop extends StatelessWidget {
  const _LoginBackdrop();

  @override
  Widget build(BuildContext context) => const DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFF17172A), Color(0xFF251633), Color(0xFF11101A)],
      ),
    ),
  );
}

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  bool _busy = false;
  bool _exchangeInFlight = false;
  String? _error;

  @override
  Widget build(BuildContext context) {
    final authService = ref.watch(authServiceProvider);
    final sessionNotice = ref.watch(sessionNoticeProvider);

    return Scaffold(
      backgroundColor: const Color(0xFF11101A),
      body: Stack(
        children: [
          const Positioned.fill(child: _LoginBackdrop()),
          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 400),
                  child: AniMixSurface(
                    radius: 24,
                    elevated: true,
                    blurred: true,
                    padding: const EdgeInsets.all(30),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(23),
                          child: Image.asset(
                            'assets/icon/app_icon.png',
                            width: 104,
                            height: 104,
                            fit: BoxFit.cover,
                            errorBuilder: (_, _, _) => const Icon(
                              CupertinoIcons.play_circle_fill,
                              size: 86,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        const Text(
                          'AniMix',
                          style: TextStyle(
                            fontSize: 48,
                            height: 1.05,
                            letterSpacing: -1.4,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          'Списки, оценки и прогресс — в одном аккаунте',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Colors.white60, fontSize: 16),
                        ),
                        if (sessionNotice != null || _error != null) ...[
                          const SizedBox(height: 20),
                          _LoginNotice(
                            message: _error ?? sessionNotice!,
                            isError: _error != null,
                          ),
                        ],
                        const SizedBox(height: 30),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: _busy
                                ? null
                                : () => _handleLogin(context, ref, authService),
                            icon: _busy
                                ? const SizedBox.square(
                                    dimension: 18,
                                    child: CupertinoActivityIndicator(),
                                  )
                                : const Icon(
                                    CupertinoIcons.person_crop_circle_fill,
                                  ),
                            label: Text(
                              _busy
                                  ? 'Подключаем аккаунт…'
                                  : 'Войти через Shikimori',
                            ),
                            style: FilledButton.styleFrom(
                              minimumSize: const Size.fromHeight(54),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(14),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextButton(
                          onPressed: _busy
                              ? null
                              : () => _showManualCodeDialog(
                                  context,
                                  ref,
                                  authService,
                                ),
                          child: const Text('Ввести код вручную'),
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

  // ===================== ЛОГИКА АВТОРИЗАЦИИ =====================
  Future<void> _handleLogin(
    BuildContext context,
    WidgetRef ref,
    ShikimoriAuthService authService,
  ) async {
    final clientId = Config.shikimoriClientId;
    if (clientId.isEmpty) {
      setState(() => _error = 'Не настроен идентификатор Shikimori.');
      return;
    }
    setState(() => _error = null);

    if (Platform.isIOS || Platform.isAndroid) {
      // 📱 МОБИЛКИ: Встроенный WebView
      final redirectUri = Config.shikimoriRedirectUri;
      final authUrl = Uri.https('shikimori.io', '/oauth/authorize', {
        'client_id': clientId,
        'redirect_uri': redirectUri,
        'response_type': 'code',
        'scope': 'user_rates comments topics',
      }).toString();

      final code = await Navigator.push<String>(
        context,
        CupertinoPageRoute(
          builder: (_) => ShikimoriWebViewScreen(url: authUrl),
        ),
      );

      if (code != null && code.isNotEmpty && context.mounted) {
        await _performTokenExchange(
          context,
          ref,
          authService,
          code,
          redirectUri,
        );
      }
    } else {
      // 💻 ПК (Windows): Запускаем умный локальный сервер
      await _startDesktopAuth(context, ref, authService, clientId);
    }
  }

  // 🔥 МАГИЯ ДЛЯ ПК: ЛОКАЛЬНЫЙ СЕРВЕР ПЕРЕХВАТА ОАУТ-РЕДИРЕКТА
  Future<void> _startDesktopAuth(
    BuildContext context,
    WidgetRef ref,
    ShikimoriAuthService authService,
    String clientId,
  ) async {
    if (mounted) setState(() => _busy = true);
    final redirectUri = Config.shikimoriDesktopRedirectUri;
    final callbackUri = Uri.parse(redirectUri);
    final authUri = Uri.https('shikimori.io', '/oauth/authorize', {
      'client_id': clientId,
      'redirect_uri': redirectUri,
      'response_type': 'code',
      'scope': 'user_rates comments topics',
    });

    HttpServer? server;
    try {
      server = await HttpServer.bind(
        InternetAddress.loopbackIPv4,
        callbackUri.port,
      );
    } catch (e, stackTrace) {
      AppLogBuffer.instance.recordError(
        e,
        stackTrace,
        source: 'Desktop OAuth callback',
      );
      debugPrint('Порт занят, сервер не запущен: $e');
    }

    if (server == null) {
      if (mounted) setState(() => _busy = false);
      await services.Clipboard.setData(
        services.ClipboardData(text: authUri.toString()),
      );
      if (context.mounted) {
        _showManualCodeDialog(
          context,
          ref,
          authService,
          redirectUri: redirectUri,
        );
      }
      return;
    }

    try {
      await launchUrl(authUri, mode: LaunchMode.externalApplication);
    } catch (_) {
      await services.Clipboard.setData(
        services.ClipboardData(text: authUri.toString()),
      );
    }

    if (!context.mounted) return;

    BuildContext? waitingDialogContext;
    var waitingDialogOpen = true;
    final timeout = Timer(const Duration(minutes: 3), () async {
      await server?.close(force: true);
      if (waitingDialogOpen && waitingDialogContext?.mounted == true) {
        Navigator.of(waitingDialogContext!).pop();
      }
      if (mounted) {
        setState(() {
          _busy = false;
          _error = 'Время ожидания истекло. Запустите вход ещё раз.';
        });
      }
    });
    unawaited(
      showCupertinoDialog(
        context: context,
        barrierDismissible: false,
        builder: (ctx) {
          waitingDialogContext = ctx;
          return CupertinoAlertDialog(
            title: const Text('Ожидание браузера'),
            content: const Padding(
              padding: EdgeInsets.only(top: 16.0),
              child: Column(
                children: [
                  CupertinoActivityIndicator(),
                  SizedBox(height: 16),
                  Text(
                    'Завершите вход в браузере — AniMix подхватит аккаунт автоматически.',
                  ),
                ],
              ),
            ),
            actions: [
              CupertinoDialogAction(
                child: const Text('Отмена'),
                onPressed: () async {
                  timeout.cancel();
                  await server?.close(force: true);
                  if (ctx.mounted) Navigator.pop(ctx);
                  if (mounted) setState(() => _busy = false);
                },
              ),
            ],
          );
        },
      ).whenComplete(() => waitingDialogOpen = false),
    );

    await for (HttpRequest request in server) {
      if (request.uri.path == callbackUri.path) {
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
        timeout.cancel();

        if (waitingDialogOpen && waitingDialogContext?.mounted == true) {
          Navigator.of(waitingDialogContext!).pop();
        }
        if (context.mounted && code?.isNotEmpty == true) {
          await _performTokenExchange(
            context,
            ref,
            authService,
            code!,
            redirectUri,
          );
        } else if (mounted) {
          setState(() {
            _busy = false;
            _error = 'Shikimori не вернул код авторизации. Повторите вход.';
          });
        }
        break;
      }
    }
    timeout.cancel();
  }

  // ===================== ОБМЕН КОДА НА ТОКЕНЫ =====================
  Future<void> _performTokenExchange(
    BuildContext context,
    WidgetRef ref,
    ShikimoriAuthService authService,
    String code, [
    String? redirectUri,
  ]) async {
    if (_exchangeInFlight) return;
    setState(() {
      _busy = true;
      _exchangeInFlight = true;
      _error = null;
    });
    try {
      final success = await authService.login(code, redirectUri);
      if (!mounted) return;
      if (success) {
        ref.read(sessionNoticeProvider.notifier).clear();
        ref.read(userDataRevisionProvider.notifier).bump();
        ref.invalidate(currentUserProvider);
        ref.invalidate(isLoggedInProvider);
      } else {
        setState(() {
          _busy = false;
          _exchangeInFlight = false;
          _error =
              'Не удалось авторизоваться. Проверьте соединение и повторите.';
        });
      }
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Shikimori login',
      );
      if (mounted) {
        setState(() {
          _busy = false;
          _exchangeInFlight = false;
          _error = 'Ошибка входа. Подробности сохранены в диагностике.';
        });
      }
    }
  }

  // ===================== ОКНО РУЧНОГО ВВОДА КОДА =====================
  void _showManualCodeDialog(
    BuildContext context,
    WidgetRef ref,
    ShikimoriAuthService authService, {
    String? redirectUri,
  }) {
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
                final clipboardData = await services.Clipboard.getData(
                  services.Clipboard.kTextPlain,
                );
                if (clipboardData?.text != null &&
                    clipboardData!.text!.isNotEmpty) {
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
                _performTokenExchange(
                  context,
                  ref,
                  authService,
                  code,
                  redirectUri,
                );
              }
            },
          ),
        ],
      ),
    );
  }
}

class _LoginNotice extends StatelessWidget {
  const _LoginNotice({required this.message, required this.isError});

  final String message;
  final bool isError;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color:
          (isError ? CupertinoColors.systemRed : CupertinoColors.systemOrange)
              .withValues(alpha: .12),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Row(
      children: [
        Icon(
          isError
              ? CupertinoIcons.exclamationmark_circle_fill
              : CupertinoIcons.info_circle_fill,
          size: 19,
          color: isError
              ? CupertinoColors.systemRed
              : CupertinoColors.systemOrange,
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            message,
            style: const TextStyle(fontSize: 13, height: 1.3),
          ),
        ),
      ],
    ),
  );
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
  bool _completed = false;

  void _complete(String url) {
    if (_completed || !mounted) return;
    _completed = true;
    final uri = Uri.tryParse(url);
    Navigator.of(context).pop(uri?.queryParameters['code'] ?? '');
  }

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(
        const Color(0xFF050507),
      ) // Адаптировано под новый темный фон
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) {
            if (mounted) setState(() => _isLoading = false);
          },
          onUrlChange: (UrlChange change) {
            final url = change.url;
            if (url != null && url.startsWith('https://animix.app/callback')) {
              _complete(url);
            }
          },
          onNavigationRequest: (request) {
            if (request.url.startsWith('https://animix.app/callback')) {
              _complete(request.url);
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
            if (_isLoading)
              const Center(child: CupertinoActivityIndicator(radius: 20)),
          ],
        ),
      ),
    );
  }
}
