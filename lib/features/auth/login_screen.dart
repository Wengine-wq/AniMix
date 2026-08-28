import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_windows/webview_windows.dart';

import '../../core/config.dart';
import '../../core/app_logging.dart';
import '../../core/shikimori_oauth_flow.dart';
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
  String? _error;

  @override
  Widget build(BuildContext context) {
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
                        _buildAniMixAuthPanel(),
                        const SizedBox(height: 16),
                        Text(
                          'Shikimori подключается отдельно в настройках после входа в AniMix.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Theme.of(
                              context,
                            ).colorScheme.onSurfaceVariant,
                            fontSize: 12,
                            height: 1.35,
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

  Widget _buildAniMixAuthPanel() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FilledButton.icon(
          onPressed: _busy ? null : _handleAniMixGoogleLogin,
          icon: _busy
              ? const SizedBox.square(
                  dimension: 18,
                  child: CupertinoActivityIndicator(),
                )
              : const Icon(CupertinoIcons.person_crop_circle_fill),
          label: Text(_busy ? 'Открываем Google…' : 'Войти через Google'),
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
        ),
        const SizedBox(height: 10),
        Text(
          'Google подтверждает email, а AniMix хранит только собственный профиль и сессию.',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            fontSize: 12,
            height: 1.35,
          ),
        ),
      ],
    );
  }

  Future<void> _handleAniMixGoogleLogin() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final result = await ref.read(animixAuthServiceProvider).loginWithGoogle();
    if (!mounted) return;
    if (result.success) {
      setState(() => _busy = false);
      ref.read(sessionNoticeProvider.notifier).clear();
      ref.read(userDataRevisionProvider.notifier).bump();
      ref.invalidate(currentUserProvider);
      ref.read(authSessionSignalProvider.notifier).signedIn();
    } else {
      setState(() {
        _busy = false;
        _error = result.errorMessage;
      });
    }
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
  final String expectedState;
  final bool clearSession;
  const ShikimoriWebViewScreen({
    required this.url,
    required this.expectedState,
    this.clearSession = false,
    super.key,
  });

  @override
  State<ShikimoriWebViewScreen> createState() => _ShikimoriWebViewScreenState();
}

class _ShikimoriWebViewScreenState extends State<ShikimoriWebViewScreen> {
  late final WebViewController _controller;
  bool _isLoading = true;
  bool _completed = false;
  String? _callbackError;

  bool _tryComplete(String url) {
    if (_completed || !mounted) return _completed;
    final result = ShikimoriOAuthFlow.parseCallback(
      url,
      expectedState: widget.expectedState,
    );
    if (result == null) return false;
    if (!result.isSuccess) {
      setState(() {
        _isLoading = false;
        _callbackError = result.error ?? 'Неизвестная ошибка OAuth.';
      });
      return true;
    }
    _completed = true;
    Navigator.of(context).pop(result.code);
    return true;
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
            if (url != null) _tryComplete(url);
          },
          onNavigationRequest: (request) {
            if (_tryComplete(request.url)) {
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      );
    unawaited(_loadAuthorization());
  }

  Future<void> _loadAuthorization() async {
    try {
      if (widget.clearSession) {
        await WebViewCookieManager().clearCookies();
        await _controller.clearCache();
      }
      await _controller.loadRequest(Uri.parse(widget.url));
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Mobile OAuth WebView',
      );
      if (mounted) {
        setState(() {
          _isLoading = false;
          _callbackError = 'Не удалось открыть авторизацию Shikimori.';
        });
      }
    }
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
            if (_callbackError != null)
              _OAuthErrorOverlay(
                message: _callbackError!,
                onClose: () => Navigator.pop(context),
              ),
          ],
        ),
      ),
    );
  }
}

class ShikimoriWindowsWebViewScreen extends StatefulWidget {
  const ShikimoriWindowsWebViewScreen({
    required this.url,
    required this.expectedState,
    this.clearSession = false,
    super.key,
  });

  final String url;
  final String expectedState;
  final bool clearSession;

  @override
  State<ShikimoriWindowsWebViewScreen> createState() =>
      _ShikimoriWindowsWebViewScreenState();
}

class _ShikimoriWindowsWebViewScreenState
    extends State<ShikimoriWindowsWebViewScreen> {
  final WebviewController _controller = WebviewController();
  StreamSubscription<String>? _urlSubscription;
  StreamSubscription<WebErrorStatus>? _errorSubscription;
  bool _ready = false;
  bool _completed = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      await _controller.initialize();
      if (widget.clearSession) {
        await _controller.clearCookies();
        await _controller.clearCache();
      }
      await _controller.setUserAgent(Config.browserUserAgent);
      await _controller.setBackgroundColor(const Color(0xFF050507));
      await _controller.setPopupWindowPolicy(WebviewPopupWindowPolicy.deny);
      _urlSubscription = _controller.url.listen(_tryComplete);
      _errorSubscription = _controller.onLoadError.listen((error) {
        if (mounted && !_completed) {
          setState(() => _error = 'WebView2 не открыл страницу: $error');
        }
      });
      await _controller.loadUrl(widget.url);
      if (mounted) setState(() => _ready = true);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Windows OAuth WebView',
      );
      if (mounted) {
        setState(() => _error = 'Не удалось запустить WebView2: $error');
      }
    }
  }

  Future<void> _tryComplete(String url) async {
    if (_completed || !mounted) return;
    final result = ShikimoriOAuthFlow.parseCallback(
      url,
      expectedState: widget.expectedState,
    );
    if (result == null) return;
    _completed = true;
    await _controller.stop();
    if (!mounted) return;
    if (result.isSuccess) {
      Navigator.pop(context, result.code);
    } else {
      setState(() => _error = result.error ?? 'Неизвестная ошибка OAuth.');
    }
  }

  @override
  void dispose() {
    _urlSubscription?.cancel();
    _errorSubscription?.cancel();
    if (_controller.value.isInitialized) unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFF050507),
    appBar: AppBar(
      backgroundColor: const Color(0xFF050507),
      foregroundColor: Colors.white,
      title: const Text('Авторизация Shikimori'),
    ),
    body: Stack(
      children: [
        if (_ready) Positioned.fill(child: Webview(_controller)),
        if (!_ready && _error == null)
          const Center(child: CupertinoActivityIndicator(radius: 20)),
        if (_error != null)
          _OAuthErrorOverlay(
            message: _error!,
            onClose: () => Navigator.pop(context),
          ),
      ],
    ),
  );
}

class _OAuthErrorOverlay extends StatelessWidget {
  const _OAuthErrorOverlay({required this.message, required this.onClose});

  final String message;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) => ColoredBox(
    color: const Color(0xF2050507),
    child: Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              CupertinoIcons.exclamationmark_triangle_fill,
              color: CupertinoColors.systemRed,
              size: 46,
            ),
            const SizedBox(height: 16),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 20),
            FilledButton(onPressed: onClose, child: const Text('Закрыть')),
          ],
        ),
      ),
    ),
  );
}
