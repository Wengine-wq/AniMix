import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_auth_service.dart';
import '../../core/animix_theme.dart';
import '../../models/shikimori_anime.dart';
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

  Future<void> _refresh() async {
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
            onPressed: _refresh,
            tooltip: 'Обновить',
            icon: const Icon(CupertinoIcons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator.adaptive(
        onRefresh: _refresh,
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
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final service = ref.read(animixAuthServiceProvider);
    try {
      final user = await service.getPublicProfile(widget.userId);
      final status = await service.getFriendStatus(widget.userId);
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
      }
      final anime = <int, ShikimoriAnime>{};
      final ids = (library ?? const <Map<String, dynamic>>[])
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
      if (mounted) {
        setState(() {
          _user = user;
          _friendStatus = status;
          _library = library;
          _private = private;
          _libraryError = libraryError;
          _anime = anime;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Не удалось загрузить профиль.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _changeFriend() async {
    setState(() => _actionBusy = true);
    try {
      final service = ref.read(animixAuthServiceProvider);
      String status;
      if (_friendStatus == 'none' || _friendStatus == 'incoming') {
        status = await service.addFriend(widget.userId);
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

  @override
  Widget build(BuildContext context) {
    final user = _user;
    final stats = user?['stats'] is Map
        ? user!['stats'] as Map
        : const <String, dynamic>{};
    final avatar = user?['avatar_url']?.toString() ?? '';
    final banner = user?['banner_url']?.toString() ?? '';
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
            onPressed: _load,
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
                  TextButton(onPressed: _load, child: const Text('Повторить')),
                ],
              ),
            )
          : RefreshIndicator.adaptive(
              onRefresh: _load,
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
                          Stack(
                            clipBehavior: Clip.none,
                            alignment: Alignment.bottomCenter,
                            children: [
                              Container(
                                height: 220,
                                clipBehavior: Clip.antiAlias,
                                decoration: const BoxDecoration(
                                  borderRadius: BorderRadius.vertical(
                                    bottom: Radius.circular(30),
                                  ),
                                ),
                                child: Stack(
                                  fit: StackFit.expand,
                                  children: [
                                    if (banner.isNotEmpty)
                                      CachedNetworkImage(
                                        imageUrl: banner,
                                        fit: BoxFit.cover,
                                        errorWidget: (_, _, _) =>
                                            const _PublicProfileBackdrop(),
                                      )
                                    else
                                      const _PublicProfileBackdrop(),
                                    const DecoratedBox(
                                      decoration: BoxDecoration(
                                        gradient: LinearGradient(
                                          begin: Alignment.topCenter,
                                          end: Alignment.bottomCenter,
                                          colors: [
                                            Colors.transparent,
                                            Color(0x99000000),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              Positioned(
                                bottom: -48,
                                child: Container(
                                  width: 104,
                                  height: 104,
                                  padding: const EdgeInsets.all(5),
                                  decoration: BoxDecoration(
                                    color: Theme.of(
                                      context,
                                    ).scaffoldBackgroundColor,
                                    shape: BoxShape.circle,
                                  ),
                                  child: CircleAvatar(
                                    backgroundImage: avatar.isEmpty
                                        ? null
                                        : CachedNetworkImageProvider(avatar),
                                    child: avatar.isEmpty
                                        ? const Icon(
                                            CupertinoIcons.person_fill,
                                            size: 44,
                                          )
                                        : null,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 62),
                          Text(
                            user?['display_name']?.toString() ??
                                'Пользователь AniMix',
                            textAlign: TextAlign.center,
                            style: Theme.of(context).textTheme.headlineSmall
                                ?.copyWith(fontWeight: FontWeight.w900),
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
                                AniMixSurface(
                                  elevated: true,
                                  padding: const EdgeInsets.all(
                                    AniMixSpacing.lg,
                                  ),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      const AniMixSectionHeader(
                                        title: 'Медиатека',
                                        subtitle:
                                            'Что пользователь добавил в AniMix',
                                        icon:
                                            CupertinoIcons.rectangle_stack_fill,
                                      ),
                                      const SizedBox(height: 18),
                                      Wrap(
                                        spacing: 12,
                                        runSpacing: 12,
                                        children: [
                                          _PublicStat(
                                            value: stats['total'],
                                            label: 'тайтлов',
                                          ),
                                          _PublicStat(
                                            value: stats['completed'],
                                            label: 'просмотрено',
                                          ),
                                          _PublicStat(
                                            value: stats['watching'],
                                            label: 'смотрит',
                                          ),
                                          _PublicStat(
                                            value: stats['planned'],
                                            label: 'в планах',
                                          ),
                                          _PublicStat(
                                            value: stats['episodes_watched'],
                                            label: 'серий',
                                          ),
                                          _PublicStat(
                                            value: stats['scores'],
                                            label: 'оценок',
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(height: AniMixSpacing.lg),
                                AniMixSurface(
                                  padding: const EdgeInsets.all(
                                    AniMixSpacing.lg,
                                  ),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      const AniMixSectionHeader(
                                        title: 'О пользователе',
                                        subtitle: 'Публичные данные аккаунта',
                                        icon: CupertinoIcons
                                            .person_crop_circle_fill,
                                      ),
                                      const SizedBox(height: 14),
                                      _ProfileDetail(
                                        icon: CupertinoIcons.calendar,
                                        label: 'В AniMix с',
                                        value: _publicDate(user?['created_at']),
                                      ),
                                      const SizedBox(height: 10),
                                      _ProfileDetail(
                                        icon: CupertinoIcons.link,
                                        label: 'Shikimori',
                                        value: user?['shikimori_linked'] == true
                                            ? 'Аккаунт подключён'
                                            : 'Не подключён',
                                      ),
                                    ],
                                  ),
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
                                      if (_private)
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
                                      if (!_private &&
                                          _libraryError == null &&
                                          (_library?.isEmpty ?? true))
                                        const ListTile(
                                          title: Text(
                                            'Библиотека пока пустая.',
                                          ),
                                        ),
                                      if (!_private)
                                        for (final entry
                                            in _library ??
                                                const <Map<String, dynamic>>[])
                                          _libraryTile(entry),
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

  String _publicDate(Object? value) {
    final date = DateTime.tryParse(value?.toString() ?? '')?.toLocal();
    if (date == null) return 'Дата неизвестна';
    return '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
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
      title: Text(title),
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

class _PublicProfileBackdrop extends StatelessWidget {
  const _PublicProfileBackdrop();

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Theme.of(context).colorScheme.primary.withValues(alpha: .7),
          const Color(0xFF3B2C76),
          const Color(0xFF0A1020),
        ],
      ),
    ),
  );
}

class _PublicStat extends StatelessWidget {
  const _PublicStat({required this.value, required this.label});
  final Object? value;
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    width: 126,
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${value ?? 0}',
          style: Theme.of(
            context,
          ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
        ),
        Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    ),
  );
}

class _ProfileDetail extends StatelessWidget {
  const _ProfileDetail({
    required this.icon,
    required this.label,
    required this.value,
  });
  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(15),
    ),
    child: Row(
      children: [
        Icon(icon, size: 18),
        const SizedBox(width: 12),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            Text(value, style: const TextStyle(fontWeight: FontWeight.w800)),
          ],
        ),
      ],
    ),
  );
}
