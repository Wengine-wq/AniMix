import 'dart:async';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/local_profile.dart';
import '../../core/secure_storage.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../auth/local_profile_sheet.dart';
import '../auth/login_screen.dart';
import '../catalog/catalog_screen.dart';
import 'anixart_import.dart';

/// Moves public Anixart lists into the AniMix library (account or local
/// profile). Titles already in the library are left as they are.
class AnixartImportScreen extends ConsumerStatefulWidget {
  const AnixartImportScreen({super.key});

  @override
  ConsumerState<AnixartImportScreen> createState() =>
      _AnixartImportScreenState();
}

enum _Stage { search, lists, importing, done }

class _AnixartImportScreenState extends ConsumerState<AnixartImportScreen> {
  final _client = AnixartClient();
  final _nickname = TextEditingController();

  _Stage _stage = _Stage.search;
  bool _busy = false;
  String? _error;
  List<AnixartProfile> _profiles = const [];
  AnixartProfile? _profile;
  Map<AnixartList, int> _counts = const {};
  final Set<AnixartList> _selected = {...AnixartList.values};

  int _total = 0;
  int _processed = 0;
  int _added = 0;
  int _kept = 0;
  final List<String> _notFound = [];
  bool _cancelled = false;

  @override
  void dispose() {
    _cancelled = true;
    _nickname.dispose();
    super.dispose();
  }

  Future<bool> _hasDestination() async {
    final token = await SecureStorage.getAniMixAccessToken();
    return (token != null && token.isNotEmpty) || LocalProfile.exists;
  }

  Future<void> _search() async {
    final query = _nickname.text.trim();
    if (query.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final profiles = await _client.searchProfiles(query);
      if (!mounted) return;
      setState(() {
        _profiles = profiles;
        if (profiles.isEmpty) _error = 'Профиль «$query» в Anixart не найден.';
      });
    } on AnixartUnavailable {
      if (mounted) setState(() => _error = 'Anixart сейчас не отвечает.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _choose(AnixartProfile profile) async {
    setState(() {
      _busy = true;
      _error = null;
      _profile = profile;
    });
    try {
      final counts = await _client.listCounts(profile.id);
      if (!mounted) return;
      setState(() {
        _counts = counts;
        _stage = _Stage.lists;
        _selected
          ..clear()
          ..addAll(counts.entries.where((e) => e.value > 0).map((e) => e.key));
      });
    } on AnixartUnavailable {
      if (mounted) setState(() => _error = 'Anixart сейчас не отвечает.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _import() async {
    final profile = _profile;
    if (profile == null) return;
    if (!await _hasDestination()) {
      if (mounted) await _askForDestination();
      if (!await _hasDestination()) return;
    }
    final lists = AnixartList.values.where(_selected.contains).toList();
    setState(() {
      _stage = _Stage.importing;
      _total = lists.fold(0, (sum, list) => sum + (_counts[list] ?? 0));
      _processed = 0;
      _added = 0;
      _kept = 0;
      _notFound.clear();
    });
    final service = ref.read(animixAuthServiceProvider);
    final matcher = AnixartMatcher(ref.read(apiClientProvider));
    final existing = {
      for (final entry in await service.getLibraryEntries() ?? const [])
        int.tryParse('${entry['shikimori_id']}') ?? 0,
    };
    try {
      for (final list in lists) {
        await for (final release in _client.releases(profile.id, list)) {
          if (_cancelled) return;
          final anime = await matcher.match(release).catchError((_) => null);
          if (anime == null) {
            _notFound.add(release.displayTitle);
          } else if (existing.contains(anime.id)) {
            _kept++;
          } else {
            final saved = await service.saveLibraryEntryWithResult(
              animeId: anime.id,
              status: list.status,
              score: 0,
              episodesWatched: release.episodesWatched,
            );
            if (saved.success) {
              _added++;
              existing.add(anime.id);
            } else {
              _notFound.add(release.displayTitle);
            }
          }
          if (!mounted) return;
          setState(() => _processed++);
          // Shikimori allows about five requests a second.
          await Future<void>.delayed(const Duration(milliseconds: 250));
        }
      }
    } on AnixartUnavailable {
      if (mounted) {
        setState(() => _error = 'Anixart перестал отвечать, перенос прерван.');
      }
    }
    ref.read(userDataRevisionProvider.notifier).bump();
    ref.invalidate(bookmarksProvider);
    ref.invalidate(currentUserProvider);
    if (mounted) setState(() => _stage = _Stage.done);
  }

  Future<void> _askForDestination() async {
    final choice = await showCupertinoDialog<String>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('Куда перенести списки?'),
        content: const Text(
          'Создайте локальный профиль (всё останется на устройстве) или войдите в аккаунт AniMix.',
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, 'local'),
            child: const Text('Локальный профиль'),
          ),
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, 'account'),
            child: const Text('Войти в аккаунт'),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(context),
            child: const Text('Отмена'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    if (choice == 'local') {
      await LocalProfileSheet.show(context);
    } else if (choice == 'account') {
      await Navigator.push(
        context,
        CupertinoPageRoute<void>(builder: (_) => const LoginScreen()),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return AniMixPage(
      title: 'Перенос из Anixart',
      child: ListView(
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
        children: switch (_stage) {
          _Stage.search => _searchStage(),
          _Stage.lists => _listsStage(),
          _Stage.importing => _progressStage(),
          _Stage.done => _doneStage(),
        },
      ),
    );
  }

  Widget _note(String text) => Padding(
    padding: const EdgeInsets.only(top: 12),
    child: Text(
      text,
      style: const TextStyle(
        color: AniMixTheme.subtleText,
        fontSize: 12,
        height: 1.4,
      ),
    ),
  );

  List<Widget> _searchStage() => [
    const Text(
      'Найдите свой профиль Anixart по нику. Мы прочитаем открытые списки и найдём эти тайтлы в каталоге AniMix.',
      style: TextStyle(height: 1.4),
    ),
    const SizedBox(height: 16),
    Row(
      children: [
        Expanded(
          child: TextField(
            controller: _nickname,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _search(),
            decoration: InputDecoration(
              hintText: 'Ник в Anixart',
              prefixIcon: const Icon(CupertinoIcons.search),
              filled: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(14),
                borderSide: BorderSide.none,
              ),
            ),
          ),
        ),
        const SizedBox(width: 10),
        FilledButton(
          onPressed: _busy ? null : _search,
          style: FilledButton.styleFrom(minimumSize: const Size(0, 52)),
          child: _busy
              ? const CupertinoActivityIndicator()
              : const Text('Найти'),
        ),
      ],
    ),
    if (_error != null) _note(_error!),
    const SizedBox(height: 16),
    for (final profile in _profiles.take(12))
      Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: AniMixSurface(
          onTap: _busy ? null : () => _choose(profile),
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              CircleAvatar(
                radius: 20,
                backgroundImage: profile.avatar == null
                    ? null
                    : CachedNetworkImageProvider(profile.avatar!),
                child: profile.avatar == null
                    ? const Icon(CupertinoIcons.person_fill)
                    : null,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  profile.login,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              const Icon(CupertinoIcons.chevron_right, size: 16),
            ],
          ),
        ),
      ),
    _note(
      'Пароль от Anixart не нужен: читаются только открытые списки. Если списки скрыты, временно откройте их в настройках приватности Anixart.',
    ),
  ];

  List<Widget> _listsStage() {
    final total = AnixartList.values
        .where(_selected.contains)
        .fold<int>(0, (sum, list) => sum + (_counts[list] ?? 0));
    final empty = _counts.values.every((count) => count == 0);
    return [
      Text(
        _profile?.login ?? '',
        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
      ),
      const SizedBox(height: 12),
      if (empty)
        _note(
          'Списки пустые или скрыты. Откройте их в настройках приватности Anixart и попробуйте снова.',
        )
      else
        AniMixSurface(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Column(
            children: [
              for (final list in AnixartList.values)
                CheckboxListTile.adaptive(
                  value: _selected.contains(list),
                  onChanged: (_counts[list] ?? 0) == 0
                      ? null
                      : (value) => setState(
                          () => value == true
                              ? _selected.add(list)
                              : _selected.remove(list),
                        ),
                  title: Text(list.label),
                  secondary: Text('${_counts[list] ?? 0}'),
                ),
            ],
          ),
        ),
      const SizedBox(height: 18),
      FilledButton(
        onPressed: total == 0 ? null : _import,
        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
        child: Text('Перенести $total тайтлов'),
      ),
      TextButton(
        onPressed: () => setState(() => _stage = _Stage.search),
        child: const Text('Другой профиль'),
      ),
      _note(
        'Тайтлы, которые уже есть в ваших списках AniMix, не меняются. Оценки из Anixart не переносятся: они там скрыты.',
      ),
    ];
  }

  List<Widget> _progressStage() => [
    const SizedBox(height: 24),
    const Text(
      'Переносим списки…',
      style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
    ),
    const SizedBox(height: 16),
    ClipRRect(
      borderRadius: BorderRadius.circular(4),
      child: LinearProgressIndicator(
        minHeight: 6,
        value: _total == 0 ? null : _processed / _total,
      ),
    ),
    const SizedBox(height: 12),
    Text('$_processed из $_total · добавлено $_added'),
    _note('Ищем каждый тайтл в каталоге, это займёт немного времени.'),
  ];

  List<Widget> _doneStage() => [
    const SizedBox(height: 24),
    const Icon(
      CupertinoIcons.checkmark_seal_fill,
      size: 44,
      color: CupertinoColors.systemGreen,
    ),
    const SizedBox(height: 12),
    Text(
      'Добавлено $_added тайтлов',
      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
    ),
    const SizedBox(height: 6),
    Text(
      [
        if (_kept > 0) '$_kept уже были в списках',
        if (_notFound.isNotEmpty) '${_notFound.length} не нашлись в каталоге',
      ].join(' · '),
      style: const TextStyle(color: AniMixTheme.subtleText),
    ),
    if (_error != null) _note(_error!),
    if (_notFound.isNotEmpty) ...[
      const SizedBox(height: 18),
      AniMixSurface(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Не нашли автоматически',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            for (final title in _notFound.take(40))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text('• $title'),
              ),
            if (_notFound.length > 40) Text('и ещё ${_notFound.length - 40}'),
          ],
        ),
      ),
    ],
    const SizedBox(height: 18),
    FilledButton(
      onPressed: () => Navigator.of(context).maybePop(),
      style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
      child: const Text('Готово'),
    ),
  ];
}
