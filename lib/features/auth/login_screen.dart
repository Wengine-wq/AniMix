import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_windows/webview_windows.dart';

import '../../core/app_logging.dart';
import '../../core/config.dart';
import '../../core/shikimori_oauth_flow.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/server_status_card.dart';
import '../profile/shikimori_integration_screen.dart';

/// Slow drifting accent glows behind the sign-in screen.
class _LoginBackdrop extends StatefulWidget {
  const _LoginBackdrop();

  @override
  State<_LoginBackdrop> createState() => _LoginBackdropState();
}

class _LoginBackdropState extends State<_LoginBackdrop>
    with SingleTickerProviderStateMixin {
  late final AnimationController _drift = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 18),
  )..repeat();

  @override
  void dispose() {
    _drift.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = theme.colorScheme.primary;
    final hsl = HSLColor.fromColor(accent);
    final second = hsl.withHue((hsl.hue + 50) % 360).toColor();
    final dark = theme.brightness == Brightness.dark;
    return RepaintBoundary(
      child: AnimatedBuilder(
        animation: _drift,
        builder: (context, _) {
          final t = _drift.value * 2 * math.pi;
          return DecoratedBox(
            decoration: BoxDecoration(color: theme.scaffoldBackgroundColor),
            child: Stack(
              fit: StackFit.expand,
              children: [
                _Glow(
                  alignment: Alignment(
                    -0.8 + 0.25 * math.sin(t),
                    -0.9 + 0.15 * math.cos(t),
                  ),
                  color: accent.withValues(alpha: dark ? .34 : .22),
                ),
                _Glow(
                  alignment: Alignment(
                    0.9 + 0.2 * math.cos(t),
                    -0.2 + 0.25 * math.sin(t),
                  ),
                  color: second.withValues(alpha: dark ? .22 : .14),
                ),
                _Glow(
                  alignment: Alignment(
                    -0.4 + 0.2 * math.cos(t + 1),
                    1.0 + 0.1 * math.sin(t),
                  ),
                  color: accent.withValues(alpha: dark ? .16 : .10),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({required this.alignment, required this.color});

  final Alignment alignment;
  final Color color;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      gradient: RadialGradient(
        center: alignment,
        radius: 0.9,
        colors: [color, color.withValues(alpha: 0)],
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

  static const _features = [
    (CupertinoIcons.play_rectangle_fill, 'Плеер без рекламы'),
    (CupertinoIcons.arrow_down_circle_fill, 'Офлайн-загрузки'),
    (CupertinoIcons.bell_fill, 'Новые серии'),
    (CupertinoIcons.sparkles, 'Итоги года'),
  ];

  @override
  Widget build(BuildContext context) {
    final sessionNotice = ref.watch(sessionNoticeProvider);
    final notice = _error ?? sessionNotice;
    return Scaffold(
      body: Stack(
        children: [
          const Positioned.fill(child: _LoginBackdrop()),
          SafeArea(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final wide = constraints.maxWidth >= 860;
                final hero = _Hero(features: _features, wide: wide);
                final panel = _buildPanel(notice, isError: _error != null);
                if (wide) {
                  return Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 1040),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 48),
                        child: Row(
                          children: [
                            Expanded(child: hero),
                            const SizedBox(width: 56),
                            SizedBox(
                              width: 400,
                              child: SingleChildScrollView(child: panel),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                }
                return SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      minHeight: constraints.maxHeight - 52,
                    ),
                    child: Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 440),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [hero, const SizedBox(height: 36), panel],
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPanel(String? notice, {required bool isError}) {
    final scheme = Theme.of(context).colorScheme;
    final guest = ref.watch(guestModeProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const ServerStatusCard(compact: true),
        if (notice != null) ...[
          const SizedBox(height: 12),
          _LoginNotice(message: notice, isError: isError),
        ],
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: _busy ? null : _handleAniMixGoogleLogin,
          icon: _busy
              ? const SizedBox.square(
                  dimension: 18,
                  child: CupertinoActivityIndicator(),
                )
              : const Icon(CupertinoIcons.person_crop_circle_fill, size: 20),
          label: Text(_busy ? 'Открываем Google…' : 'Войти через Google'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(54),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
            textStyle: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        if (!guest) ...[
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: _busy ? null : _continueAsGuest,
            style: OutlinedButton.styleFrom(
              minimumSize: const Size.fromHeight(54),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              side: BorderSide(color: scheme.outlineVariant),
              textStyle: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
            child: const Text('Продолжить без входа'),
          ),
        ],
        const SizedBox(height: 6),
        TextButton.icon(
          onPressed: _busy ? null : _openShikimori,
          icon: const Icon(CupertinoIcons.chat_bubble_2_fill, size: 17),
          label: const Text('Войти в Shikimori для комментариев'),
          style: TextButton.styleFrom(minimumSize: const Size.fromHeight(44)),
        ),
        const SizedBox(height: 14),
        Text(
          'Без входа работают каталог, просмотр и загрузки, прогресс хранится '
          'на устройстве. Аккаунт AniMix синхронизирует списки, профиль и '
          'достижения между устройствами.',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: scheme.onSurfaceVariant,
            fontSize: 12,
            height: 1.4,
          ),
        ),
      ],
    );
  }

  void _continueAsGuest() {
    ref.read(guestModeProvider.notifier).set(true);
    if (Navigator.of(context).canPop()) Navigator.of(context).pop();
  }

  /// Shikimori is only for comments here; it does not touch AniMix data.
  void _openShikimori() {
    final navigator = Navigator.of(context);
    navigator.push(
      CupertinoPageRoute<void>(
        builder: (_) => const ShikimoriIntegrationScreen(),
      ),
    );
    if (!ref.read(guestModeProvider)) {
      ref.read(guestModeProvider.notifier).set(true);
    }
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
      await ref.read(guestModeProvider.notifier).set(false);
      // Opened from a guest screen: go back to the app.
      if (mounted && Navigator.of(context).canPop()) {
        Navigator.of(context).pop();
      }
    } else {
      setState(() {
        _busy = false;
        _error = result.errorMessage;
      });
    }
  }
}

class _Hero extends StatelessWidget {
  const _Hero({required this.features, required this.wide});

  final List<(IconData, String)> features;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final align = wide ? CrossAxisAlignment.start : CrossAxisAlignment.center;
    final textAlign = wide ? TextAlign.start : TextAlign.center;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: align,
      children: [
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(26),
            boxShadow: [
              BoxShadow(
                color: scheme.primary.withValues(alpha: .45),
                blurRadius: 44,
                offset: const Offset(0, 14),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(26),
            child: Image.asset(
              'assets/icon/app_icon.png',
              width: wide ? 104 : 92,
              height: wide ? 104 : 92,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) =>
                  const Icon(CupertinoIcons.play_circle_fill, size: 86),
            ),
          ),
        ),
        SizedBox(height: wide ? 28 : 22),
        Text(
          'AniMix',
          textAlign: textAlign,
          style: TextStyle(
            fontSize: wide ? 64 : 48,
            height: 1,
            letterSpacing: -2,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 12),
        Text(
          'Смотри аниме, веди списки\nи подводи итоги года. Бесплатно.',
          textAlign: textAlign,
          style: TextStyle(
            color: scheme.onSurfaceVariant,
            fontSize: wide ? 20 : 17,
            height: 1.35,
          ),
        ),
        SizedBox(height: wide ? 28 : 22),
        Wrap(
          alignment: wide ? WrapAlignment.start : WrapAlignment.center,
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final (icon, label) in features)
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHighest.withValues(alpha: .55),
                  borderRadius: BorderRadius.circular(99),
                  border: Border.all(
                    color: scheme.outlineVariant.withValues(alpha: .5),
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(icon, size: 15, color: scheme.primary),
                    const SizedBox(width: 6),
                    Text(
                      label,
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ],
    );
  }
}

class _LoginNotice extends StatelessWidget {
  const _LoginNotice({required this.message, required this.isError});

  final String message;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final color = isError
        ? CupertinoColors.systemRed
        : CupertinoColors.systemOrange;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .12),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Icon(
            isError
                ? CupertinoIcons.exclamationmark_circle_fill
                : CupertinoIcons.info_circle_fill,
            size: 19,
            color: color,
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
