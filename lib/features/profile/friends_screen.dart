import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_auth_service.dart';
import '../../core/achievement_service.dart';
import '../../core/animix_theme.dart';
import '../../models/shikimori_anime.dart';
import '../../models/shikimori_user.dart';
import 'profile_components.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../anime_detail/anime_detail_screen.dart';

class FriendsScreen extends ConsumerStatefulWidget {
  const FriendsScreen({super.key});

  @override
  ConsumerState<FriendsScreen> createState() => _FriendsScreenState();
}

class _FriendsScreenState extends ConsumerState<FriendsScreen> {
  final _search = TextEditingController();
  List<Map<String, dynamic>> _friends = [];
  List<Map<String, dynamic>> _results = [];
  bool _busy = true;
  String? _error;
  int _searchGeneration = 0;
  final Set<String> _accepting = {};

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _searchGeneration++;
    _search.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool force = false}) async {
    if (force) ref.read(animixAuthServiceProvider).invalidateReadCache();
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final friends = await ref.read(animixAuthServiceProvider).getFriends();
      if (mounted) setState(() => _friends = friends);
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'Не удалось загрузить друзей. Повторите попытку.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _find(String value) async {
    final generation = ++_searchGeneration;
    if (value.trim().length < 2) {
      setState(() => _results = []);
      return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted || generation != _searchGeneration) return;
    try {
      final results = await ref
          .read(animixAuthServiceProvider)
          .searchAniMixUsers(value.trim());
      if (mounted && generation == _searchGeneration) {
        setState(() {
          _results = results;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted && generation == _searchGeneration) {
        setState(() => _error = 'Поиск временно недоступен.');
      }
    }
  }

  Future<void> _open(Map<String, dynamic> user) async {
    await Navigator.push(
      context,
      CupertinoPageRoute<void>(
        builder: (_) => PublicProfileScreen(userId: user['id'].toString()),
      ),
    );
    if (mounted) await _refresh();
  }

  Future<void> _accept(Map<String, dynamic> user) async {
    final id = user['id']?.toString() ?? '';
    if (id.isEmpty || !_accepting.add(id)) return;
    setState(() {});
    try {
      final status = await ref.read(animixAuthServiceProvider).addFriend(id);
      if (status != 'friends') {
        throw StateError('Unexpected friendship state: $status');
      }
      unawaited(AchievementService.instance.friendAccepted());
      if (!mounted) return;
      setState(() {
        _friends = [
          for (final item in _friends)
            if (item['id']?.toString() == id)
              {...item, 'status': 'friends'}
            else
              item,
        ];
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${user['display_name'] ?? 'Пользователь'} теперь в друзьях',
          ),
        ),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Не удалось принять заявку. Попробуйте ещё раз.'),
          ),
        );
      }
    } finally {
      _accepting.remove(id);
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final accepted = _friends
        .where((user) => user['status'] == 'friends')
        .toList();
    final incoming = _friends
        .where((user) => user['status'] == 'incoming')
        .toList();
    final outgoing = _friends
        .where((user) => user['status'] == 'outgoing')
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Друзья'),
        actions: [
          IconButton(
            onPressed: () => _refresh(force: true),
            tooltip: 'Обновить',
            icon: const Icon(CupertinoIcons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator.adaptive(
        onRefresh: () => _refresh(force: true),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            TextField(
              controller: _search,
              onChanged: _find,
              decoration: const InputDecoration(
                prefixIcon: Icon(CupertinoIcons.search),
                hintText: 'Найти по имени AniMix',
                helperText: 'Введите минимум 2 символа',
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (_search.text.trim().length >= 2) ...[
              const SizedBox(height: 22),
              const Text(
                'Результаты поиска',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              if (_results.isEmpty)
                const ListTile(title: Text('Никого не найдено')),
              for (final user in _results)
                _UserTile(user: user, onTap: () => _open(user)),
            ],
            const SizedBox(height: 22),
            if (_busy)
              const Center(child: CircularProgressIndicator.adaptive()),
            if (!_busy) ...[
              if (incoming.isNotEmpty) ...[
                const Text(
                  'Заявки вам',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                for (final user in incoming)
                  _UserTile(
                    user: user,
                    subtitle: 'Хочет добавить вас',
                    onTap: () => _open(user),
                    trailing: FilledButton.tonal(
                      onPressed: _accepting.contains(user['id']?.toString())
                          ? null
                          : () => _accept(user),
                      child: Text(
                        _accepting.contains(user['id']?.toString())
                            ? 'Принимаем…'
                            : 'Принять',
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
              ],
              const Text(
                'Мои друзья',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              if (accepted.isEmpty)
                const ListTile(
                  title: Text('Пока никого нет'),
                  subtitle: Text(
                    'Найдите пользователя по имени и отправьте заявку.',
                  ),
                ),
              for (final user in accepted)
                _UserTile(user: user, onTap: () => _open(user)),
              if (outgoing.isNotEmpty) ...[
                const SizedBox(height: 16),
                const Text(
                  'Отправленные заявки',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                for (final user in outgoing)
                  _UserTile(
                    user: user,
                    subtitle: 'Ожидает ответа',
                    onTap: () => _open(user),
                  ),
              ],
            ],
          ],
        ),
      ),
    );
  }
}

class _UserTile extends StatelessWidget {
  const _UserTile({
    required this.user,
    required this.onTap,
    this.subtitle,
    this.trailing,
  });
  final Map<String, dynamic> user;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final avatar = user['avatar_url']?.toString() ?? '';
    return ListTile(
      onTap: onTap,
      leading: CircleAvatar(
        backgroundImage: avatar.isEmpty
            ? null
            : CachedNetworkImageProvider(avatar),
        child: avatar.isEmpty ? const Icon(CupertinoIcons.person_fill) : null,
      ),
      title: Text(user['display_name']?.toString() ?? 'Пользователь AniMix'),
      subtitle: subtitle == null ? null : Text(subtitle!),
      trailing: trailing ?? const Icon(CupertinoIcons.chevron_right, size: 16),
    );
  }
}

class PublicProfileScreen extends ConsumerStatefulWidget {
  const PublicProfileScreen({required this.userId, super.key});
  final String userId;

  @override
  ConsumerState<PublicProfileScreen> createState() =>
      _PublicProfileScreenState();
}

class _PublicProfileScreenState extends ConsumerState<PublicProfileScreen> {
  Map<String, dynamic>? _user;
  List<Map<String, dynamic>>? _library;
  Map<int, ShikimoriAnime> _anime = {};
  String _friendStatus = 'none';
  bool _private = false;
  String? _libraryError;
  bool _busy = true;
  bool _actionBusy = false;
  bool _libraryLoading = false;
  int _loadGeneration = 0;
  static const _pageSize = 30;
  int _visibleEntries = _pageSize;
  bool _moreBusy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool force = false}) async {
    if (force) ref.read(animixAuthServiceProvider).invalidateReadCache();
    final generation = ++_loadGeneration;
    setState(() {
      _busy = true;
      _error = null;
      _library = null;
      _libraryError = null;
      _libraryLoading = true;
      _visibleEntries = _pageSize;
      _moreBusy = false;
    });
    final service = ref.read(animixAuthServiceProvider);
    try {
      final values = await Future.wait<Object>([
        service.getPublicProfile(widget.userId),
        service.getFriendStatus(widget.userId),
      ]);
      if (!mounted || generation != _loadGeneration) return;
      final user = values[0] as Map<String, dynamic>;
      final status = values[1] as String;
      setState(() {
        _user = user;
        _friendStatus = status;
        _busy = false;
      });
      List<Map<String, dynamic>>? library;
      var private = false;
      String? libraryError;
      try {
        library = await service.getPublicLibrary(widget.userId);
      } on AniMixApiException catch (error) {
        if (error.errorCode == 'library_private') {
          private = true;
        } else {
          libraryError = 'Библиотека временно недоступна.';
        }
      } catch (_) {
        libraryError = 'Библиотека временно недоступна.';
      }
      final anime = <int, ShikimoriAnime>{};
      final ids = (library ?? const <Map<String, dynamic>>[])
          .take(_pageSize)
          .map((entry) => int.tryParse('${entry['shikimori_id']}') ?? 0)
          .where((id) => id > 0)
          .toSet()
          .toList();
      for (var i = 0; i < ids.length; i += 50) {
        try {
          final group = ids.skip(i).take(50).toList();
          final items = await ref
              .read(apiClientProvider)
              .getAnimes(
                limit: group.length,
                filters: {'ids': group.join(',')},
              );
          for (final item in items) {
            anime[item.id] = item;
          }
        } catch (_) {
          /* Numeric IDs remain available if metadata fails. */
        }
      }
      if (mounted && generation == _loadGeneration) {
        setState(() {
          _library = library;
          _private = private;
          _libraryError = libraryError;
          _anime = anime;
        });
      }
    } catch (_) {
      if (mounted && generation == _loadGeneration) {
        setState(() => _error = 'Не удалось загрузить профиль.');
      }
    } finally {
      if (mounted && generation == _loadGeneration) {
        setState(() {
          _busy = false;
          _libraryLoading = false;
        });
      }
    }
  }

  Future<void> _changeFriend() async {
    setState(() => _actionBusy = true);
    try {
      final service = ref.read(animixAuthServiceProvider);
      String status;
      if (_friendStatus == 'none' || _friendStatus == 'incoming') {
        status = await service.addFriend(widget.userId);
        if (status == 'friends') {
          unawaited(AchievementService.instance.friendAccepted());
        }
      } else {
        await service.removeFriend(widget.userId);
        status = 'none';
      }
      if (mounted) setState(() => _friendStatus = status);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось изменить список друзей.')),
        );
      }
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _loadMore() async {
    if (_moreBusy || _library == null) return;
    final generation = _loadGeneration;
    setState(() => _moreBusy = true);
    try {
      final ids = _library!
          .skip(_visibleEntries)
          .take(_pageSize)
          .map((entry) => int.tryParse('${entry['shikimori_id']}') ?? 0)
          .where((id) => id > 0)
          .toList();
      if (ids.isNotEmpty) {
        final items = await ref
            .read(apiClientProvider)
            .getAnimes(limit: ids.length, filters: {'ids': ids.join(',')});
        if (!mounted || generation != _loadGeneration) return;
        for (final item in items) {
          _anime[item.id] = item;
        }
      }
    } catch (_) {
      // Entries remain accessible by ID when catalog metadata is unavailable.
    } finally {
      if (mounted && generation == _loadGeneration) {
        setState(() {
          _visibleEntries += _pageSize;
          _moreBusy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = _user;
    final friendLabel = switch (_friendStatus) {
      'incoming' => 'Принять заявку',
      'outgoing' => 'Отменить заявку',
      'friends' => 'Удалить из друзей',
      _ => 'Добавить в друзья',
    };
    return Scaffold(
      appBar: AppBar(
        title: const Text('Профиль AniMix'),
        actions: [
          IconButton(
            onPressed: () => _load(force: true),
            tooltip: 'Обновить',
            icon: const Icon(CupertinoIcons.refresh),
          ),
        ],
      ),
      body: _busy
          ? const Center(child: CircularProgressIndicator.adaptive())
          : _error != null
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(_error!),
                  TextButton(
                    onPressed: () => _load(force: true),
                    child: const Text('Повторить'),
                  ),
                ],
              ),
            )
          : RefreshIndicator.adaptive(
              onRefresh: () => _load(force: true),
              child: ListView(
                padding: const EdgeInsets.only(bottom: 48),
                children: [
                  Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(
                        maxWidth: AniMixLayout.readingMaxWidth,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          ProfileHeader(
                            user: ShikimoriUser.localFromAniMixJson(user!),
                          ),
                          const SizedBox(height: 5),
                          Text(
                            _friendStatus == 'friends'
                                ? 'Вы друзья в AniMix'
                                : _friendStatus == 'incoming'
                                ? 'Этот пользователь отправил вам заявку'
                                : _friendStatus == 'outgoing'
                                ? 'Ожидаем ответ на вашу заявку'
                                : 'Профиль участника AniMix',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              color: Theme.of(
                                context,
                              ).colorScheme.onSurfaceVariant,
                            ),
                          ),
                          const SizedBox(height: 16),
                          Center(
                            child: FilledButton.tonalIcon(
                              onPressed: _actionBusy ? null : _changeFriend,
                              icon: Icon(
                                _friendStatus == 'incoming'
                                    ? CupertinoIcons.check_mark_circled_solid
                                    : CupertinoIcons.person_badge_plus,
                              ),
                              label: Text(friendLabel),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(20, 28, 20, 0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                ProfileLibraryOverview(
                                  user: ShikimoriUser.localFromAniMixJson(user),
                                  ownProfile: false,
                                ),
                                const SizedBox(height: AniMixSpacing.lg),
                                ProfileInfoCard(
                                  user: ShikimoriUser.localFromAniMixJson(user),
                                ),
                                const SizedBox(height: AniMixSpacing.lg),
                                const AniMixSectionHeader(
                                  title: 'Библиотека',
                                  subtitle:
                                      'Доступна, если обмен открыт у вас обоих',
                                  icon: CupertinoIcons.book_fill,
                                ),
                                const SizedBox(height: 12),
                                AniMixSurface(
                                  child: Column(
                                    children: [
                                      if (_libraryLoading)
                                        const Padding(
                                          padding: EdgeInsets.all(24),
                                          child: LinearProgressIndicator(),
                                        ),
                                      if (!_libraryLoading && _private)
                                        const ListTile(
                                          leading: Icon(
                                            CupertinoIcons.lock_fill,
                                          ),
                                          title: Text('Библиотека закрыта'),
                                          subtitle: Text(
                                            'Один из вас отключил обмен в настройках приватности.',
                                          ),
                                        ),
                                      if (_libraryError != null)
                                        ListTile(title: Text(_libraryError!)),
                                      if (!_libraryLoading &&
                                          !_private &&
                                          _libraryError == null &&
                                          (_library?.isEmpty ?? true))
                                        const ListTile(
                                          title: Text(
                                            'Библиотека пока пустая.',
                                          ),
                                        ),
                                      if (!_private)
                                        for (final entry
                                            in (_library ??
                                                    const <
                                                      Map<String, dynamic>
                                                    >[])
                                                .take(_visibleEntries))
                                          _libraryTile(entry),
                                      if (!_private &&
                                          (_library?.length ?? 0) >
                                              _visibleEntries)
                                        Padding(
                                          padding: const EdgeInsets.all(12),
                                          child: TextButton.icon(
                                            onPressed: _moreBusy
                                                ? null
                                                : _loadMore,
                                            icon: const Icon(
                                              CupertinoIcons.chevron_down,
                                            ),
                                            label: Text(
                                              _moreBusy
                                                  ? 'Загружаем…'
                                                  : 'Показать ещё',
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _libraryTile(Map<String, dynamic> entry) {
    final id = int.tryParse('${entry['shikimori_id']}') ?? 0;
    final anime = _anime[id];
    final title = anime?.russian?.isNotEmpty == true
        ? anime!.russian!
        : anime?.name?.isNotEmpty == true
        ? anime!.name!
        : 'Аниме #$id';
    const statuses = {
      'planned': 'В планах',
      'watching': 'Смотрит',
      'completed': 'Просмотрено',
      'on_hold': 'Отложено',
      'dropped': 'Брошено',
      'rewatching': 'Пересматривает',
    };
    final status =
        statuses[entry['status']] ?? entry['status']?.toString() ?? '';
    final episodes = int.tryParse('${entry['episodes_watched']}') ?? 0;
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: SizedBox(
          width: 38,
          height: 54,
          child: anime?.imageUrl?.isNotEmpty == true
              ? CachedNetworkImage(
                  imageUrl: anime!.imageUrl!,
                  fit: BoxFit.cover,
                  errorWidget: (_, _, _) => const Icon(CupertinoIcons.film),
                )
              : const Icon(CupertinoIcons.film),
        ),
      ),
      title: Text(title, maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: Text('$status · $episodes серий'),
      trailing: const Icon(CupertinoIcons.chevron_right, size: 16),
      onTap: id <= 0
          ? null
          : () => Navigator.push(
              context,
              CupertinoPageRoute<void>(
                builder: (_) => AnimeDetailScreen(animeId: id),
              ),
            ),
    );
  }
}
