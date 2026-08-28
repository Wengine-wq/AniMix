import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/app_logging.dart';
import '../../core/animix_auth_service.dart';
import '../../core/config.dart';
import '../../core/secure_storage.dart';
import '../../core/shikimori_library_import.dart';
import '../../core/shikimori_oauth_flow.dart';
import '../../models/shikimori_user.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../auth/login_screen.dart';
import '../catalog/catalog_screen.dart';

class ShikimoriIntegrationScreen extends ConsumerStatefulWidget {
  const ShikimoriIntegrationScreen({super.key});

  @override
  ConsumerState<ShikimoriIntegrationScreen> createState() =>
      _ShikimoriIntegrationScreenState();
}

class _ShikimoriIntegrationScreenState
    extends ConsumerState<ShikimoriIntegrationScreen> {
  bool _busy = false;
  bool _checkingSession = true;
  ShikimoriUser? _shikimoriUser;

  @override
  void initState() {
    super.initState();
    unawaited(_checkShikimoriSession());
  }

  Future<void> _checkShikimoriSession() async {
    final token = await SecureStorage.getAccessToken();
    if (token == null || token.isEmpty) {
      if (mounted) setState(() => _checkingSession = false);
      return;
    }
    try {
      final api = ref.read(apiClientProvider)..invalidateAuthenticationCache();
      final user = await api.getCurrentUser();
      final currentToken = await SecureStorage.getAccessToken();
      if (currentToken != token) return;
      if (mounted) {
        setState(() {
          _shikimoriUser = user;
          _checkingSession = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _shikimoriUser = null;
          _checkingSession = false;
        });
      }
    }
  }

  Future<String?> _requestShikimoriCode({required bool clearSession}) async {
    final state = ShikimoriOAuthFlow.createState();
    final url = ShikimoriOAuthFlow.authorizationUri(
      clientId: Config.shikimoriClientId,
      state: state,
    ).toString();
    if (!mounted) return null;
    if (Platform.isWindows) {
      return Navigator.push<String>(
        context,
        MaterialPageRoute<String>(
          builder: (_) => ShikimoriWindowsWebViewScreen(
            url: url,
            expectedState: state,
            clearSession: clearSession,
          ),
        ),
      );
    }
    return Navigator.push<String>(
      context,
      CupertinoPageRoute<String>(
        builder: (_) => ShikimoriWebViewScreen(
          url: url,
          expectedState: state,
          clearSession: clearSession,
        ),
      ),
    );
  }

  Future<ShikimoriUser?> _requireShikimoriUser({
    bool forceLogin = false,
  }) async {
    final api = ref.read(apiClientProvider);
    if (forceLogin) {
      await SecureStorage.clearShikimori();
      api.invalidateAuthenticationCache();
    } else {
      final token = await SecureStorage.getAccessToken();
      if (token != null && token.isNotEmpty) {
        try {
          api.invalidateAuthenticationCache();
          final user = await api.getCurrentUser();
          if (mounted) {
            setState(() {
              _shikimoriUser = user;
              _checkingSession = false;
            });
          }
          return user;
        } catch (_) {
          // A rejected token is cleared by ShikimoriApiClient. Network errors
          // keep it intact and must not silently turn into an OAuth loop.
          final remaining = await SecureStorage.getAccessToken();
          if (remaining != null && remaining.isNotEmpty) rethrow;
        }
      }
    }

    final code = await _requestShikimoriCode(clearSession: true);
    if (code == null || code.isEmpty) return null;
    final connected = await ref.read(authServiceProvider).login(code);
    if (!connected) {
      throw StateError('Shikimori OAuth token exchange failed');
    }
    api.invalidateAuthenticationCache();
    ref.read(sessionNoticeProvider.notifier).clear();
    final user = await api.getCurrentUser();
    if (mounted) {
      setState(() {
        _shikimoriUser = user;
        _checkingSession = false;
      });
    }
    return user;
  }

  Future<void> _loginShikimori({required bool force}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _requireShikimoriUser(forceLogin: force);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Shikimori integration login',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Вход в Shikimori не завершён. Проверь диагностику.'),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _disconnectShikimori() async {
    await ref.read(authServiceProvider).logout();
    ref.read(apiClientProvider).invalidateAuthenticationCache();
    ref.read(sessionNoticeProvider.notifier).clear();
    if (mounted) {
      setState(() {
        _shikimoriUser = null;
        _checkingSession = false;
      });
    }
  }

  Future<void> _import() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final animixToken = await SecureStorage.getAniMixAccessToken();
      if (animixToken == null || animixToken.isEmpty) {
        throw const AniMixApiException(
          operation: 'Shikimori library import',
          errorCode: 'missing_animix_session',
        );
      }
      if (Config.shikimoriClientId.isEmpty) {
        throw const FormatException('Shikimori client id is unavailable.');
      }
      final api = ref.read(apiClientProvider);
      final shikimoriUser = await _requireShikimoriUser();
      if (shikimoriUser == null) return;
      final rates = await api.getUserAnimeRates(shikimoriUser.id);
      final entries = rates
          .map(normalizeShikimoriAnimeRate)
          .whereType<Map<String, dynamic>>()
          .toList(growable: false);
      if (entries.isEmpty) {
        throw const FormatException('Shikimori library is empty.');
      }
      final result = await ref
          .read(animixAuthServiceProvider)
          .importShikimoriLibrary(
            shikimoriUserId: shikimoriUser.id,
            entries: entries,
          );
      ref.read(userDataRevisionProvider.notifier).bump();
      ref.invalidate(currentUserProvider);
      ref.invalidate(bookmarksProvider);
      await ref.read(currentUserProvider.future);
      if (!mounted) return;
      final message = result.skipped > 0
          ? 'Перенесено ${result.imported} тайтлов, пропущено ${result.skipped} повреждённых записей.'
          : 'Перенесено ${result.imported} тайтлов в AniMix.';
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Shikimori library import',
      );
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_importErrorMessage(error))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _importErrorMessage(Object error) {
    if (error is! AniMixApiException) {
      return 'Перенос не завершён. Подробности сохранены в диагностике.';
    }
    return switch (error.errorCode) {
      'missing_animix_session' || 'unauthorized' =>
        'Сессия AniMix истекла. Сначала войдите через Google ещё раз.',
      'shikimori_account_already_linked' =>
        'Этот Shikimori уже привязан к другому аккаунту AniMix.',
      'shikimori_relink_not_supported' =>
        'К профилю уже привязан другой аккаунт Shikimori.',
      'invalid_shikimori_import' =>
        'Shikimori вернул данные библиотеки в неподдерживаемом формате.',
      _ =>
        'Сервер отклонил перенос (${error.errorCode}). Код сохранён в диагностике.',
    };
  }

  @override
  Widget build(BuildContext context) {
    final profile = ref.watch(currentUserProvider);
    return AniMixPage(
      title: 'Shikimori',
      child: profile.when(
        loading: () => const Center(child: CupertinoActivityIndicator()),
        error: (_, _) => AniMixEmptyState(
          icon: CupertinoIcons.wifi_exclamationmark,
          title: 'Профиль недоступен',
          message: 'Не удалось проверить состояние интеграции.',
          actionLabel: 'Повторить',
          onAction: () => ref.invalidate(currentUserProvider),
        ),
        data: (user) {
          if (user == null || !user.isAniMix) {
            return const AniMixEmptyState(
              icon: CupertinoIcons.person_crop_circle_badge_exclam,
              title: 'Сначала войдите в AniMix',
              message: 'Shikimori подключается к основному аккаунту AniMix.',
            );
          }
          final linked = user.shikimoriLinked;
          final total =
              user.watched +
              user.watching +
              user.planned +
              user.onHold +
              user.dropped +
              user.rewatched;
          return ListView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 72),
            children: [
              AniMixSurface(
                padding: const EdgeInsets.all(18),
                child: _checkingSession
                    ? const Row(
                        children: [
                          CupertinoActivityIndicator(),
                          SizedBox(width: 12),
                          Text('Проверяем сессию Shikimori…'),
                        ],
                      )
                    : Row(
                        children: [
                          Icon(
                            _shikimoriUser == null
                                ? CupertinoIcons.person_crop_circle_badge_plus
                                : CupertinoIcons.check_mark_circled_solid,
                            color: _shikimoriUser == null
                                ? Theme.of(context).colorScheme.primary
                                : CupertinoColors.systemGreen,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  _shikimoriUser?.nickname ??
                                      'Shikimori не подключён',
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w800,
                                  ),
                                ),
                                Text(
                                  _shikimoriUser == null
                                      ? 'Войдите перед переносом библиотеки'
                                      : 'OAuth-сессия активна',
                                  style: TextStyle(
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.onSurfaceVariant,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          if (_shikimoriUser == null)
                            TextButton(
                              onPressed: _busy
                                  ? null
                                  : () => _loginShikimori(force: false),
                              child: const Text('Войти'),
                            )
                          else
                            PopupMenuButton<String>(
                              enabled: !_busy,
                              onSelected: (value) {
                                if (value == 'relogin') {
                                  _loginShikimori(force: true);
                                } else if (value == 'disconnect') {
                                  _disconnectShikimori();
                                }
                              },
                              itemBuilder: (_) => const [
                                PopupMenuItem(
                                  value: 'relogin',
                                  child: Text('Войти заново'),
                                ),
                                PopupMenuItem(
                                  value: 'disconnect',
                                  child: Text('Отключить'),
                                ),
                              ],
                            ),
                        ],
                      ),
              ),
              const SizedBox(height: 18),
              AniMixSurface(
                elevated: true,
                padding: const EdgeInsets.all(22),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      linked
                          ? CupertinoIcons.check_mark_circled_solid
                          : CupertinoIcons.arrow_down_circle_fill,
                      size: 38,
                      color: linked
                          ? CupertinoColors.systemGreen
                          : Theme.of(context).colorScheme.primary,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      linked
                          ? 'Библиотека уже в AniMix'
                          : 'Одноразовый перенос библиотеки',
                      style: const TextStyle(
                        fontSize: 23,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      linked
                          ? '$total тайтлов хранятся в вашем аккаунте AniMix. Закладки больше не зависят от сессии Shikimori.'
                          : 'Мы скопируем статусы, оценки и просмотренные серии. После этого источником закладок станет AniMix.',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        height: 1.45,
                      ),
                    ),
                    if (!linked) ...[
                      const SizedBox(height: 22),
                      FilledButton.icon(
                        onPressed: _busy ? null : _import,
                        icon: _busy
                            ? const SizedBox.square(
                                dimension: 18,
                                child: CupertinoActivityIndicator(),
                              )
                            : const Icon(CupertinoIcons.link),
                        label: Text(
                          _busy ? 'Переносим…' : 'Подключить и перенести',
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 18),
              const AniMixSurface(
                padding: EdgeInsets.all(18),
                child: Text(
                  'AniMix хранит только идентификаторы тайтлов, статусы, оценки и прогресс. Описания и обложки по-прежнему загружаются из внешнего каталога.',
                  style: TextStyle(height: 1.45),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
