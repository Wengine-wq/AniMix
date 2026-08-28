import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/config.dart';
import '../../models/shikimori_anime.dart';
import '../../models/shikimori_anime_detail.dart';
import '../../models/shikimori_user.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_media_viewer.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_network_image.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/smart_anime_poster.dart';
import '../data/comments_screen.dart';
import '../watch/watch_provider_selection_screen.dart';

class AnimeDetailScreen extends StatefulHookConsumerWidget {
  const AnimeDetailScreen({required this.animeId, super.key});

  final int animeId;

  @override
  ConsumerState<AnimeDetailScreen> createState() => _AnimeDetailScreenState();
}

class _AnimeDetailScreenState extends ConsumerState<AnimeDetailScreen> {
  ShikimoriAnimeDetail? _anime;
  ShikimoriUser? _currentUser;
  String? _error;
  String? _currentStatus;
  int _currentScore = 0;
  int _watchedEpisodes = 0;
  bool _descriptionExpanded = false;
  bool _loading = true;

  List<String> _screenshots = const [];
  List<Map<String, dynamic>> _related = const [];
  List<ShikimoriAnime> _similar = const [];
  int _watching = 0;
  int _planned = 0;
  int _completed = 0;
  String? _duration;
  String? _rating;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = ref.read(apiClientProvider);
    try {
      final detail = await api.getAnimeDetail(widget.animeId);
      if (!mounted) return;
      setState(() {
        _anime = detail;
        _duration = detail.duration?.toString();
        _rating = detail.rating;
        _watching = detail.statusStats['watching'] ?? 0;
        _planned = detail.statusStats['planned'] ?? 0;
        _completed = detail.statusStats['completed'] ?? 0;
        _loading = false;
      });

      await Future.wait<void>([
        api
            .getAnimeScreenshots(widget.animeId)
            .then((value) {
              if (mounted) setState(() => _screenshots = value);
            })
            .catchError((_) {}),
        api
            .getRelatedAnimes(widget.animeId)
            .then((value) {
              if (mounted) setState(() => _related = value);
            })
            .catchError((_) {}),
        api
            .getSimilarAnimes(widget.animeId)
            .then((value) {
              if (mounted) setState(() => _similar = value);
            })
            .catchError((_) {}),
        _loadUserRate(),
      ]);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error =
            'Не удалось загрузить аниме. Проверьте подключение и повторите.';
      });
    }
  }

  Future<void> _loadUserRate() async {
    final api = ref.read(apiClientProvider);
    try {
      final user = await ref.read(currentUserProvider.future);
      if (user == null) return;
      if (user.isAniMix) {
        final entry = await ref
            .read(animixAuthServiceProvider)
            .getLibraryEntry(widget.animeId);
        if (mounted) {
          setState(() {
            _currentUser = user;
            _currentStatus = entry?['status']?.toString();
            _currentScore = _entryInt(entry, 'score');
            _watchedEpisodes = _entryInt(entry, 'episodes_watched');
          });
        }
        return;
      }
      final rate = await api.getUserRate(widget.animeId, userId: user.id);
      if (!mounted) return;
      setState(() {
        _currentUser = user;
        _currentStatus = rate?['status'] as String?;
        _currentScore = rate?['score'] as int? ?? 0;
        _watchedEpisodes = rate?['episodes'] as int? ?? 0;
      });
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const AniMixDetailSkeletonScreen();
    }
    if (_anime == null) {
      return Scaffold(
        appBar: AppBar(),
        body: AniMixEmptyState(
          icon: CupertinoIcons.exclamationmark_triangle,
          title: 'Не удалось открыть аниме',
          message: _error ?? 'Данные временно недоступны.',
          actionLabel: 'Повторить',
          onAction: () {
            setState(() {
              _loading = true;
              _error = null;
            });
            _load();
          },
        ),
      );
    }

    final anime = _anime!;
    final poster = _validImageUrl(anime.imageUrl);
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: Stack(
        children: [
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: 570,
            child: _AmbientBackdrop(
              animeId: widget.animeId,
              imageUrl: poster,
              title: anime.name ?? '',
              russianTitle: anime.russian,
            ),
          ),
          SafeArea(
            bottom: false,
            child: SingleChildScrollView(
              physics: const BouncingScrollPhysics(
                parent: AlwaysScrollableScrollPhysics(),
              ),
              padding: const EdgeInsets.only(bottom: 72),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 900),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        final desktop = constraints.maxWidth >= 700;
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(height: desktop ? 76 : 72),
                            _buildHero(anime, poster, desktop),
                            const SizedBox(height: 28),
                            _buildActions(anime, desktop),
                            if (_currentUser != null &&
                                (_currentStatus == 'watching' ||
                                    _currentStatus == 'rewatching') &&
                                (anime.episodes ?? 0) > 0) ...[
                              const SizedBox(height: 18),
                              _buildProgress(anime),
                            ],
                            const SizedBox(height: 34),
                            _buildAbout(anime),
                            if (_screenshots.isNotEmpty) ...[
                              const SizedBox(height: 34),
                              AniMixSectionHeader(
                                title: 'Кадры из аниме',
                                subtitle: '${_screenshots.length} изображений',
                              ),
                              const SizedBox(height: 14),
                              _buildScreenshots(),
                            ],
                            if (_related.isNotEmpty) ...[
                              const SizedBox(height: 34),
                              const AniMixSectionHeader(
                                title: 'Хронология и франшиза',
                                subtitle: 'Связанные истории',
                              ),
                              const SizedBox(height: 14),
                              _buildRelated(),
                            ],
                            if (_similar.isNotEmpty) ...[
                              const SizedBox(height: 34),
                              const AniMixSectionHeader(
                                title: 'Вам может понравиться',
                                subtitle: 'Популярное рядом',
                              ),
                              const SizedBox(height: 14),
                              _buildSimilar(),
                            ],
                          ],
                        );
                      },
                    ),
                  ),
                ),
              ),
            ),
          ),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  AniMixIconButton(
                    icon: CupertinoIcons.back,
                    tooltip: 'Назад',
                    onPressed: () => Navigator.maybePop(context),
                  ),
                  AniMixIconButton(
                    icon: CupertinoIcons.share,
                    tooltip: 'Открыть на Shikimori',
                    onPressed: () => launchUrl(
                      Uri.parse(
                        'https://shikimori.io/animes/${widget.animeId}',
                      ),
                      mode: LaunchMode.externalApplication,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHero(ShikimoriAnimeDetail anime, String poster, bool desktop) {
    final posterWidget = _PosterFrame(
      width: desktop ? 270 : 256,
      height: desktop ? 405 : 384,
      child: SmartAnimePoster(
        animeId: widget.animeId,
        imageUrl: poster,
        title: anime.name ?? '',
        russianTitle: anime.russian,
      ),
    );
    final identity = _buildIdentity(anime);
    if (desktop) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          posterWidget,
          const SizedBox(width: 34),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: identity,
            ),
          ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(child: posterWidget),
        const SizedBox(height: 28),
        identity,
      ],
    );
  }

  Widget _buildIdentity(ShikimoriAnimeDetail anime) {
    final title = anime.russian?.trim().isNotEmpty == true
        ? anime.russian!
        : anime.name ?? 'Без названия';
    final original = anime.name != title ? anime.name : anime.english;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 12,
          runSpacing: 10,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            AniMixMetadataPill(label: _statusLabel(anime.status), accent: true),
            if ((anime.score ?? 0) > 0)
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(
                    CupertinoIcons.star_fill,
                    color: Color(0xFFFFC638),
                    size: 19,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    anime.score!.toStringAsFixed(1),
                    style: const TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            if (_currentScore > 0)
              AniMixMetadataPill(label: 'Ваша оценка · $_currentScore'),
          ],
        ),
        const SizedBox(height: 18),
        Text(
          title,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurface,
            fontSize: 34,
            height: 1.06,
            letterSpacing: -1.2,
            fontWeight: FontWeight.w900,
          ),
        ),
        if (original?.trim().isNotEmpty == true) ...[
          const SizedBox(height: 9),
          Text(
            original!,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              fontSize: 16,
              height: 1.3,
            ),
          ),
        ],
        const SizedBox(height: 18),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            if (anime.kind?.isNotEmpty == true)
              AniMixMetadataPill(label: _kindLabel(anime.kind)),
            if (anime.episodes != null)
              AniMixMetadataPill(label: '${anime.episodes} эп.'),
            if (_duration?.isNotEmpty == true)
              AniMixMetadataPill(label: '$_duration мин.'),
            if (_rating?.isNotEmpty == true)
              AniMixMetadataPill(label: _rating!.toUpperCase()),
          ],
        ),
      ],
    );
  }

  Widget _buildActions(ShikimoriAnimeDetail anime, bool desktop) {
    final play = FilledButton.icon(
      onPressed: () => Navigator.push(
        context,
        CupertinoPageRoute<void>(
          builder: (_) => WatchProviderSelectionScreen(
            animeId: anime.id,
            animeNameRu: anime.russian ?? '',
            animeNameEn: anime.name ?? '',
          ),
        ),
      ),
      icon: const Icon(CupertinoIcons.play_fill),
      label: const Text('Смотреть'),
      style: FilledButton.styleFrom(
        minimumSize: const Size(190, 54),
        shape: const StadiumBorder(),
      ),
    );
    final status = OutlinedButton.icon(
      onPressed: _currentUser == null ? null : _showStatusSheet,
      icon: Icon(
        _currentStatus == null ? CupertinoIcons.add : CupertinoIcons.check_mark,
      ),
      label: Text(_statusActionLabel(_currentStatus)),
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(150, 52),
        foregroundColor: Theme.of(context).colorScheme.onSurface,
        side: BorderSide(
          color: Theme.of(
            context,
          ).colorScheme.outlineVariant.withValues(alpha: .65),
        ),
        shape: const StadiumBorder(),
      ),
    );
    final comments = OutlinedButton.icon(
      onPressed: anime.topicId == null
          ? null
          : () => Navigator.push(
              context,
              CupertinoPageRoute<void>(
                builder: (_) => CommentsScreen(topicId: anime.topicId!),
              ),
            ),
      icon: const Icon(CupertinoIcons.chat_bubble),
      label: const Text('Отзывы'),
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(130, 52),
        foregroundColor: Theme.of(context).colorScheme.onSurface,
        side: BorderSide(
          color: Theme.of(
            context,
          ).colorScheme.outlineVariant.withValues(alpha: .65),
        ),
        shape: const StadiumBorder(),
      ),
    );
    if (desktop) {
      return Wrap(
        spacing: 10,
        runSpacing: 10,
        children: [play, status, comments],
      );
    }
    return Column(
      children: [
        SizedBox(width: double.infinity, child: play),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: status),
            const SizedBox(width: 10),
            Expanded(child: comments),
          ],
        ),
      ],
    );
  }

  Widget _buildAbout(ShikimoriAnimeDetail anime) {
    final description = anime.description?.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const AniMixSectionHeader(
          title: 'Об аниме',
          subtitle: 'Кратко и по делу',
        ),
        const SizedBox(height: 14),
        AniMixSurface(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (description?.isNotEmpty == true) ...[
                Text(
                  description!,
                  maxLines: _descriptionExpanded ? null : 6,
                  overflow: _descriptionExpanded
                      ? TextOverflow.visible
                      : TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFD1D1D8),
                    fontSize: 15,
                    height: 1.55,
                  ),
                ),
                if (description.length > 280)
                  CupertinoButton(
                    padding: const EdgeInsets.only(top: 10),
                    onPressed: () => setState(
                      () => _descriptionExpanded = !_descriptionExpanded,
                    ),
                    child: Text(
                      _descriptionExpanded ? 'Свернуть' : 'Подробнее',
                    ),
                  ),
                const SizedBox(height: 18),
              ],
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: anime.genres
                    .map((genre) => AniMixMetadataPill(label: genre))
                    .toList(),
              ),
              const SizedBox(height: 20),
              const Divider(height: 1),
              const SizedBox(height: 20),
              _InfoGrid(
                items: [
                  (
                    'Эпизоды',
                    '${anime.episodesAired ?? 0} / ${anime.episodes ?? '?'}',
                  ),
                  ('Статус', _statusLabel(anime.status)),
                  ('Премьера', anime.airedOn ?? '—'),
                  ('Финал', anime.releasedOn ?? '—'),
                  (
                    'Студия',
                    anime.studios.isEmpty ? '—' : anime.studios.join(', '),
                  ),
                  ('Формат', _kindLabel(anime.kind)),
                ],
              ),
              if (_watching + _planned + _completed > 0) ...[
                const SizedBox(height: 22),
                const Divider(height: 1),
                const SizedBox(height: 18),
                Row(
                  children: [
                    Expanded(
                      child: _Stat(label: 'Смотрят', value: _watching),
                    ),
                    Expanded(
                      child: _Stat(label: 'В планах', value: _planned),
                    ),
                    Expanded(
                      child: _Stat(label: 'Завершили', value: _completed),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildProgress(ShikimoriAnimeDetail anime) {
    final total = anime.episodes ?? 0;
    final watched = _watchedEpisodes.clamp(0, total);
    final progress = total == 0 ? 0.0 : watched / total;
    return AniMixSurface(
      padding: const EdgeInsets.all(18),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text(
                      'Прогресс просмотра',
                      style: TextStyle(fontWeight: FontWeight.w800),
                    ),
                    const Spacer(),
                    Text(
                      '$watched / $total',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.primary,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    minHeight: 7,
                    value: progress,
                    backgroundColor: Theme.of(
                      context,
                    ).colorScheme.surfaceContainerHigh,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 14),
          IconButton.filledTonal(
            tooltip: 'Уменьшить',
            onPressed: watched <= 0 ? null : () => _updateProgress(watched - 1),
            icon: const Icon(CupertinoIcons.minus),
          ),
          const SizedBox(width: 7),
          IconButton.filled(
            tooltip: 'Следующая серия просмотрена',
            onPressed: watched >= total
                ? null
                : () => _updateProgress(watched + 1),
            icon: const Icon(CupertinoIcons.plus),
          ),
        ],
      ),
    );
  }

  Widget _buildScreenshots() {
    return SizedBox(
      height: 128,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        itemCount: _screenshots.length,
        separatorBuilder: (_, _) => const SizedBox(width: 12),
        itemBuilder: (context, index) => GestureDetector(
          onTap: () => showAniMixMediaViewer(
            context,
            images: _screenshots,
            initialIndex: index,
            title: 'Скриншот',
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(15),
            child: AspectRatio(
              aspectRatio: 16 / 9,
              child: AniMixNetworkImage(
                imageUrl: _screenshots[index],
                fit: BoxFit.cover,
                placeholder: (context, _) => ColoredBox(
                  color: Theme.of(context).colorScheme.surface,
                  child: const Center(child: CupertinoActivityIndicator()),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildRelated() {
    final items = _related.where((item) => item['anime'] is Map).toList();
    return SizedBox(
      height: 238,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        itemCount: items.length,
        separatorBuilder: (_, _) => const SizedBox(width: 12),
        itemBuilder: (context, index) {
          final data = Map<String, dynamic>.from(items[index]['anime'] as Map);
          final item = ShikimoriAnime.fromJson(data);
          return _AnimeMiniCard(anime: item);
        },
      ),
    );
  }

  Widget _buildSimilar() => SizedBox(
    height: 238,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      physics: const BouncingScrollPhysics(),
      itemCount: _similar.length,
      separatorBuilder: (_, _) => const SizedBox(width: 12),
      itemBuilder: (context, index) => _AnimeMiniCard(anime: _similar[index]),
    ),
  );

  Future<void> _showStatusSheet() async {
    var status = _currentStatus ?? 'planned';
    var score = _currentScore;
    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(26)),
      ),
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
            20,
            12,
            20,
            20 + MediaQuery.viewInsetsOf(context).bottom,
          ),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 38,
                    height: 5,
                    decoration: BoxDecoration(
                      color: Colors.white24,
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                const Text(
                  'Мой список',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 14),
                for (final option in const [
                  ('planned', 'В планах', CupertinoIcons.bookmark),
                  ('watching', 'Смотрю', CupertinoIcons.play_circle),
                  (
                    'completed',
                    'Просмотрено',
                    CupertinoIcons.check_mark_circled,
                  ),
                  ('on_hold', 'Отложено', CupertinoIcons.pause_circle),
                  ('dropped', 'Брошено', CupertinoIcons.xmark_circle),
                ])
                  ListTile(
                    onTap: () => setSheetState(() => status = option.$1),
                    leading: Icon(option.$3),
                    title: Text(option.$2),
                    contentPadding: EdgeInsets.zero,
                    trailing: Icon(
                      status == option.$1
                          ? CupertinoIcons.check_mark_circled_solid
                          : CupertinoIcons.circle,
                      color: status == option.$1
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                const SizedBox(height: 10),
                Text('Оценка: ${score == 0 ? 'нет' : score}'),
                Slider(
                  min: 0,
                  max: 10,
                  divisions: 10,
                  value: score.toDouble(),
                  label: score == 0 ? 'нет' : '$score',
                  onChanged: (value) =>
                      setSheetState(() => score = value.round()),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: () {
                      Navigator.pop(sheetContext);
                      _updateUserRate(status, score);
                    },
                    child: const Text('Сохранить'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _updateUserRate(String status, int score) async {
    final user = _currentUser;
    if (user == null) return;
    if (user.isAniMix) {
      final previousStatus = _currentStatus;
      final previousScore = _currentScore;
      setState(() {
        _currentStatus = status;
        _currentScore = score;
      });
      final saved = await ref
          .read(animixAuthServiceProvider)
          .saveLibraryEntry(
            animeId: widget.animeId,
            status: status,
            score: score,
            episodesWatched: _watchedEpisodes,
          );
      if (saved) {
        ref.read(userDataRevisionProvider.notifier).bump();
        ref.invalidate(currentUserProvider);
        return;
      }
      if (mounted) {
        setState(() {
          _currentStatus = previousStatus;
          _currentScore = previousScore;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Не удалось сохранить изменения AniMix'),
          ),
        );
      }
      return;
    }
    final previousStatus = _currentStatus;
    final previousScore = _currentScore;
    setState(() {
      _currentStatus = status;
      _currentScore = score;
    });
    try {
      await ref
          .read(apiClientProvider)
          .setUserRate(
            widget.animeId,
            status,
            score: score,
            episodes: _watchedEpisodes,
            userId: user.id,
          );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _currentStatus = previousStatus;
        _currentScore = previousScore;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось сохранить изменения')),
      );
    }
  }

  Future<void> _updateProgress(int episodes) async {
    final user = _currentUser;
    final status = _currentStatus;
    if (user == null || status == null) return;
    final previous = _watchedEpisodes;
    setState(() => _watchedEpisodes = episodes);
    try {
      if (user.isAniMix) {
        final saved = await ref
            .read(animixAuthServiceProvider)
            .saveLibraryEntry(
              animeId: widget.animeId,
              status: status,
              score: _currentScore,
              episodesWatched: episodes,
            );
        if (!saved) throw StateError('AniMix progress save failed');
        ref.read(userDataRevisionProvider.notifier).bump();
        ref.invalidate(currentUserProvider);
        return;
      }
      await ref
          .read(apiClientProvider)
          .setUserRate(
            widget.animeId,
            status,
            score: _currentScore,
            episodes: episodes,
            userId: user.id,
          );
    } catch (_) {
      if (!mounted) return;
      setState(() => _watchedEpisodes = previous);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось обновить прогресс')),
      );
    }
  }

  static int _entryInt(Map<String, dynamic>? entry, String key) {
    final value = entry?[key];
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  String _validImageUrl(String? path) {
    if (path == null || path.isEmpty) return '';
    return Config.proxiedImageUrl(path);
  }

  String _statusLabel(String? status) => switch (status) {
    'ongoing' => 'Выходит',
    'released' => 'Вышло',
    'anons' => 'Анонс',
    _ => status?.isNotEmpty == true ? status! : 'Неизвестно',
  };

  String _kindLabel(String? kind) => switch (kind) {
    'tv' => 'TV-сериал',
    'movie' => 'Фильм',
    'ova' => 'OVA',
    'ona' => 'ONA',
    'special' => 'Спешл',
    _ => kind?.toUpperCase() ?? '—',
  };

  String _statusActionLabel(String? status) => switch (status) {
    'watching' => 'Смотрю',
    'completed' => 'Просмотрено',
    'on_hold' => 'Отложено',
    'dropped' => 'Брошено',
    'planned' => 'В планах',
    _ => 'В список',
  };
}

class _AmbientBackdrop extends StatelessWidget {
  const _AmbientBackdrop({
    required this.animeId,
    required this.imageUrl,
    required this.title,
    required this.russianTitle,
  });

  final int animeId;
  final String imageUrl;
  final String title;
  final String? russianTitle;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: [
          Transform.scale(
            scale: 1.12,
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
              child: Opacity(
                opacity: 0.34,
                child: SmartAnimePoster(
                  animeId: animeId,
                  imageUrl: imageUrl,
                  title: title,
                  russianTitle: russianTitle,
                  fit: BoxFit.cover,
                  alignment: Alignment.topCenter,
                ),
              ),
            ),
          ),
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Color(0x4D09090C),
                  Color(0xCC09090C),
                  Theme.of(context).scaffoldBackgroundColor,
                ],
                stops: [0, 0.62, 1],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PosterFrame extends StatelessWidget {
  const _PosterFrame({
    required this.width,
    required this.height,
    required this.child,
  });

  final double width;
  final double height;
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: height,
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(24),
      border: Border.all(color: const Color(0x26FFFFFF)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x66000000),
          blurRadius: 30,
          offset: Offset(0, 16),
        ),
      ],
    ),
    clipBehavior: Clip.antiAlias,
    child: child,
  );
}

class _InfoGrid extends StatelessWidget {
  const _InfoGrid({required this.items});

  final List<(String, String)> items;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final columns = constraints.maxWidth >= 640 ? 3 : 2;
      final width = (constraints.maxWidth - (columns - 1) * 18) / columns;
      return Wrap(
        spacing: 18,
        runSpacing: 18,
        children: items
            .map(
              (item) => SizedBox(
                width: width,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.$1,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      item.$2,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ],
                ),
              ),
            )
            .toList(),
      );
    },
  );
}

class _Stat extends StatelessWidget {
  const _Stat({required this.label, required this.value});

  final String label;
  final int value;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text(
        _compact(value),
        style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
      ),
      const SizedBox(height: 3),
      Text(
        label,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontSize: 11,
        ),
      ),
    ],
  );

  static String _compact(int number) {
    if (number >= 1000000) return '${(number / 1000000).toStringAsFixed(1)}M';
    if (number >= 1000) return '${(number / 1000).toStringAsFixed(1)}K';
    return '$number';
  }
}

class _AnimeMiniCard extends StatelessWidget {
  const _AnimeMiniCard({required this.anime});

  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 132,
    child: GestureDetector(
      onTap: () => Navigator.push(
        context,
        CupertinoPageRoute<void>(
          builder: (_) => AnimeDetailScreen(animeId: anime.id),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(17),
            child: SizedBox(
              width: 132,
              height: 184,
              child: SmartAnimePoster(
                animeId: anime.id,
                imageUrl: anime.imageUrl,
                title: anime.name ?? '',
                russianTitle: anime.russian,
              ),
            ),
          ),
          const SizedBox(height: 9),
          Text(
            anime.russian ?? anime.name ?? 'Без названия',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 13,
              height: 1.15,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    ),
  );
}
