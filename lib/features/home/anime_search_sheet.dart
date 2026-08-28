import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../models/shikimori_anime.dart';
import '../../models/shikimori_genre.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';

class AnimeSearchSheet extends ConsumerStatefulWidget {
  const AnimeSearchSheet({super.key});

  @override
  ConsumerState<AnimeSearchSheet> createState() => _AnimeSearchSheetState();
}

class _AnimeSearchSheetState extends ConsumerState<AnimeSearchSheet> {
  final _queryController = TextEditingController();
  final _scrollController = ScrollController();
  Timer? _debounce;
  List<ShikimoriAnime> _items = const [];
  List<ShikimoriGenre> _genres = const [];
  _AnimeSearchFilters _filters = _AnimeSearchFilters.defaults();
  bool _loading = true;
  bool _loadingMore = false;
  bool _hasMore = true;
  String? _error;
  int _requestGeneration = 0;
  int _page = 1;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    unawaited(_loadGenres());
    unawaited(_search(reset: true));
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _queryController.dispose();
    _scrollController
      ..removeListener(_onScroll)
      ..dispose();
    super.dispose();
  }

  Future<void> _loadGenres() async {
    try {
      final genres = await ref.read(apiClientProvider).getAnimeGenres();
      if (mounted) setState(() => _genres = genres);
    } catch (_) {
      // Search remains fully usable without the optional genre dictionary.
    }
  }

  void _scheduleSearch(String _) {
    _debounce?.cancel();
    _debounce = Timer(
      const Duration(milliseconds: 360),
      () => _search(reset: true),
    );
  }

  void _onScroll() {
    if (_scrollController.position.extentAfter < 560 &&
        !_loading &&
        !_loadingMore &&
        _hasMore) {
      unawaited(_search(reset: false));
    }
  }

  Future<void> _search({required bool reset}) async {
    if (!reset && (_loadingMore || !_hasMore)) return;
    final generation = reset ? ++_requestGeneration : _requestGeneration;
    final requestedPage = reset ? 1 : _page + 1;
    setState(() {
      if (reset) {
        _loading = true;
        _error = null;
      } else {
        _loadingMore = true;
      }
    });
    try {
      final query = _queryController.text.trim();
      final result = await ref
          .read(apiClientProvider)
          .getAnimes(
            page: requestedPage,
            limit: 40,
            filters: _filters.toApiQuery(query),
          );
      if (!mounted || generation != _requestGeneration) return;
      setState(() {
        _page = requestedPage;
        _hasMore = result.length == 40;
        _items = reset ? result : [..._items, ...result];
        _error = null;
      });
    } catch (_) {
      if (!mounted || generation != _requestGeneration) return;
      setState(() {
        if (reset) _items = const [];
        _error = 'Каталог не ответил. Проверь соединение и повтори.';
      });
    } finally {
      if (mounted && generation == _requestGeneration) {
        setState(() {
          _loading = false;
          _loadingMore = false;
        });
      }
    }
  }

  Future<void> _openFilters() async {
    final value = await showModalBottomSheet<_AnimeSearchFilters>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      builder: (_) => FractionallySizedBox(
        heightFactor: .88,
        child: _AnimeFilterPanel(initial: _filters, genres: _genres),
      ),
    );
    if (value == null || !mounted) return;
    setState(() => _filters = value);
    await _search(reset: true);
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 10, 12, 10),
        child: Row(
          children: [
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Поиск и каталог',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Название, жанры, год, рейтинг и формат',
                    style: TextStyle(fontSize: 12),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Закрыть',
              onPressed: () => Navigator.pop(context),
              icon: const Icon(CupertinoIcons.xmark_circle_fill),
            ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: Row(
          children: [
            Expanded(
              child: CupertinoSearchTextField(
                controller: _queryController,
                autofocus: true,
                placeholder: 'Название на русском или английском',
                backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurface,
                ),
                onChanged: _scheduleSearch,
                onSubmitted: (_) => _search(reset: true),
              ),
            ),
            const SizedBox(width: 10),
            Badge(
              isLabelVisible: _filters.activeCount > 0,
              label: Text('${_filters.activeCount}'),
              child: IconButton.filledTonal(
                tooltip: 'Фильтры',
                onPressed: _openFilters,
                icon: const Icon(CupertinoIcons.slider_horizontal_3),
              ),
            ),
          ],
        ),
      ),
      if (_filters.activeCount > 0) ...[
        const SizedBox(height: 10),
        SizedBox(
          height: 34,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 20),
            children: [
              for (final label in _filters.labels(_genres))
                Padding(
                  padding: const EdgeInsets.only(right: 7),
                  child: InputChip(
                    label: Text(label),
                    visualDensity: VisualDensity.compact,
                    onDeleted: () async {
                      setState(
                        () => _filters = _filters.removeLabel(label, _genres),
                      );
                      await _search(reset: true);
                    },
                  ),
                ),
              TextButton(
                onPressed: () async {
                  setState(() => _filters = _AnimeSearchFilters.defaults());
                  await _search(reset: true);
                },
                child: const Text('Сбросить'),
              ),
            ],
          ),
        ),
      ],
      const SizedBox(height: 10),
      Expanded(child: _buildResults()),
    ],
  );

  Widget _buildResults() {
    if (_loading) return const AniMixCatalogSkeleton();
    if (_error != null && _items.isEmpty) {
      return AniMixEmptyState(
        icon: CupertinoIcons.wifi_exclamationmark,
        title: 'Поиск временно недоступен',
        message: _error!,
        actionLabel: 'Повторить',
        onAction: () => _search(reset: true),
      );
    }
    if (_items.isEmpty) {
      return const AniMixEmptyState(
        icon: CupertinoIcons.search,
        title: 'Ничего не найдено',
        message: 'Ослабь фильтры или попробуй другое название.',
      );
    }
    return RefreshIndicator.adaptive(
      onRefresh: () => _search(reset: true),
      child: ListView.separated(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
        itemCount: _items.length + (_loadingMore ? 1 : 0),
        separatorBuilder: (_, _) => const SizedBox(height: 9),
        itemBuilder: (context, index) {
          if (index == _items.length) {
            return const Padding(
              padding: EdgeInsets.all(18),
              child: Center(child: CupertinoActivityIndicator()),
            );
          }
          return _SearchResultRow(anime: _items[index]);
        },
      ),
    );
  }
}

class _SearchResultRow extends StatelessWidget {
  const _SearchResultRow({required this.anime});

  final ShikimoriAnime anime;

  @override
  Widget build(BuildContext context) => Material(
    color: Theme.of(context).colorScheme.surfaceContainer.withValues(alpha: .7),
    borderRadius: BorderRadius.circular(18),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: () => Navigator.push(
        context,
        CupertinoPageRoute<void>(
          builder: (_) => AnimeDetailScreen(animeId: anime.id),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: SizedBox(
                width: 62,
                height: 88,
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: anime.imageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                ),
              ),
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    anime.russian ?? anime.name ?? 'Без названия',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if (anime.name?.isNotEmpty == true) ...[
                    const SizedBox(height: 4),
                    Text(
                      anime.name!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 7,
                    runSpacing: 5,
                    children: [
                      if ((anime.score ?? 0) > 0)
                        _ResultPill('★ ${anime.score!.toStringAsFixed(1)}'),
                      if (anime.year != null) _ResultPill('${anime.year}'),
                      if (anime.kind?.isNotEmpty == true)
                        _ResultPill(anime.kind!.toUpperCase()),
                      if (anime.status?.isNotEmpty == true)
                        _ResultPill(
                          anime.status == 'ongoing' ? 'Выходит' : 'Вышло',
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const Icon(CupertinoIcons.chevron_right, size: 16),
          ],
        ),
      ),
    ),
  );
}

class _ResultPill extends StatelessWidget {
  const _ResultPill(this.label);
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.primary.withValues(alpha: .11),
      borderRadius: BorderRadius.circular(99),
    ),
    child: Text(
      label,
      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
    ),
  );
}

class _AnimeFilterPanel extends StatefulWidget {
  const _AnimeFilterPanel({required this.initial, required this.genres});

  final _AnimeSearchFilters initial;
  final List<ShikimoriGenre> genres;

  @override
  State<_AnimeFilterPanel> createState() => _AnimeFilterPanelState();
}

class _AnimeFilterPanelState extends State<_AnimeFilterPanel> {
  late _AnimeSearchFilters _value = widget.initial;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 12, 8),
        child: Row(
          children: [
            const Expanded(
              child: Text(
                'Фильтры каталога',
                style: TextStyle(fontSize: 23, fontWeight: FontWeight.w900),
              ),
            ),
            TextButton(
              onPressed: () =>
                  setState(() => _value = _AnimeSearchFilters.defaults()),
              child: const Text('Сбросить'),
            ),
          ],
        ),
      ),
      Expanded(
        child: ListView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
          children: [
            _FilterSection(
              title: 'Формат',
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children:
                    {
                          'all': 'Любой',
                          'tv': 'TV',
                          'movie': 'Фильм',
                          'ova': 'OVA',
                          'ona': 'ONA',
                          'special': 'Спецвыпуск',
                          'music': 'Клип',
                        }.entries
                        .map(
                          (entry) => ChoiceChip(
                            label: Text(entry.value),
                            selected: _value.kind == entry.key,
                            onSelected: (_) => setState(
                              () => _value = _value.copyWith(kind: entry.key),
                            ),
                          ),
                        )
                        .toList(),
              ),
            ),
            _FilterSection(
              title: 'Статус',
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children:
                    {
                          'all': 'Любой',
                          'ongoing': 'Выходит',
                          'released': 'Завершён',
                          'anons': 'Анонсирован',
                        }.entries
                        .map(
                          (entry) => ChoiceChip(
                            label: Text(entry.value),
                            selected: _value.status == entry.key,
                            onSelected: (_) => setState(
                              () => _value = _value.copyWith(status: entry.key),
                            ),
                          ),
                        )
                        .toList(),
              ),
            ),
            _FilterSection(
              title: 'Сортировка',
              child: DropdownButtonFormField<String>(
                initialValue: _value.order,
                decoration: const InputDecoration(
                  prefixIcon: Icon(CupertinoIcons.sort_down),
                ),
                items: const [
                  DropdownMenuItem(
                    value: 'popularity',
                    child: Text('По популярности'),
                  ),
                  DropdownMenuItem(value: 'ranked', child: Text('По рейтингу')),
                  DropdownMenuItem(
                    value: 'aired_on',
                    child: Text('Сначала новые'),
                  ),
                  DropdownMenuItem(value: 'name', child: Text('По названию')),
                ],
                onChanged: (value) {
                  if (value != null) {
                    setState(() => _value = _value.copyWith(order: value));
                  }
                },
              ),
            ),
            _FilterSection(
              title: 'Возрастной рейтинг',
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children:
                    {
                          'all': 'Любой',
                          'g': 'G',
                          'pg': 'PG',
                          'pg_13': 'PG-13',
                          'r': 'R-17',
                          'r_plus': 'R+',
                          'rx': 'Rx',
                        }.entries
                        .map(
                          (entry) => ChoiceChip(
                            label: Text(entry.value),
                            selected: _value.rating == entry.key,
                            onSelected: (_) => setState(
                              () => _value = _value.copyWith(rating: entry.key),
                            ),
                          ),
                        )
                        .toList(),
              ),
            ),
            _FilterSection(
              title: _value.minScore == 0
                  ? 'Минимальная оценка: любая'
                  : 'Минимальная оценка: ${_value.minScore}',
              child: Slider(
                value: _value.minScore.toDouble(),
                min: 0,
                max: 9,
                divisions: 9,
                label: _value.minScore == 0 ? 'Любая' : '${_value.minScore}',
                onChanged: (value) => setState(
                  () => _value = _value.copyWith(minScore: value.round()),
                ),
              ),
            ),
            _FilterSection(
              title: 'Годы: ${_value.startYear}–${_value.endYear}',
              child: RangeSlider(
                values: RangeValues(
                  _value.startYear.toDouble(),
                  _value.endYear.toDouble(),
                ),
                min: 1960,
                max: (DateTime.now().year + 2).toDouble(),
                divisions: DateTime.now().year + 2 - 1960,
                labels: RangeLabels('${_value.startYear}', '${_value.endYear}'),
                onChanged: (range) => setState(
                  () => _value = _value.copyWith(
                    startYear: range.start.round(),
                    endYear: range.end.round(),
                  ),
                ),
              ),
            ),
            _FilterSection(
              title:
                  'Жанры${_value.genreIds.isEmpty ? '' : ' · ${_value.genreIds.length}'}',
              child: widget.genres.isEmpty
                  ? const Center(child: CupertinoActivityIndicator())
                  : Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: widget.genres.map((genre) {
                        final selected = _value.genreIds.contains(genre.id);
                        return FilterChip(
                          label: Text(genre.label),
                          selected: selected,
                          onSelected: (_) {
                            final ids = {..._value.genreIds};
                            selected ? ids.remove(genre.id) : ids.add(genre.id);
                            setState(
                              () => _value = _value.copyWith(genreIds: ids),
                            );
                          },
                        );
                      }).toList(),
                    ),
            ),
          ],
        ),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 14),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Отмена'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: FilledButton.icon(
                  onPressed: () => Navigator.pop(context, _value),
                  icon: const Icon(CupertinoIcons.check_mark),
                  label: const Text('Применить'),
                ),
              ),
            ],
          ),
        ),
      ),
    ],
  );
}

class _FilterSection extends StatelessWidget {
  const _FilterSection({required this.title, required this.child});
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 10),
        child,
      ],
    ),
  );
}

class _AnimeSearchFilters {
  const _AnimeSearchFilters({
    required this.kind,
    required this.status,
    required this.rating,
    required this.order,
    required this.minScore,
    required this.startYear,
    required this.endYear,
    required this.genreIds,
  });

  factory _AnimeSearchFilters.defaults() => _AnimeSearchFilters(
    kind: 'all',
    status: 'all',
    rating: 'all',
    order: 'popularity',
    minScore: 0,
    startYear: 1960,
    endYear: DateTime.now().year + 2,
    genreIds: const {},
  );

  final String kind;
  final String status;
  final String rating;
  final String order;
  final int minScore;
  final int startYear;
  final int endYear;
  final Set<int> genreIds;

  int get activeCount =>
      (kind == 'all' ? 0 : 1) +
      (status == 'all' ? 0 : 1) +
      (rating == 'all' ? 0 : 1) +
      (order == 'popularity' ? 0 : 1) +
      (minScore == 0 ? 0 : 1) +
      ((startYear == 1960 && endYear == DateTime.now().year + 2) ? 0 : 1) +
      genreIds.length;

  Map<String, dynamic> toApiQuery(String query) => {
    if (query.isNotEmpty) 'search': query,
    if (kind != 'all') 'kind': kind,
    if (status != 'all') 'status': status,
    if (rating != 'all') 'rating': rating,
    if (minScore > 0) 'score': minScore,
    if (genreIds.isNotEmpty) 'genre': genreIds.join(','),
    if (startYear != 1960 || endYear != DateTime.now().year + 2)
      'season': startYear == endYear ? '$startYear' : '${startYear}_$endYear',
    'order': query.isNotEmpty && order == 'popularity' ? 'ranked' : order,
  };

  List<String> labels(List<ShikimoriGenre> genres) {
    final labels = <String>[];
    if (kind != 'all') labels.add('Формат: ${kind.toUpperCase()}');
    if (status != 'all') labels.add('Статус: $status');
    if (rating != 'all') labels.add('Рейтинг: $rating');
    if (order != 'popularity') labels.add('Сортировка: $order');
    if (minScore > 0) labels.add('Оценка от $minScore');
    if (startYear != 1960 || endYear != DateTime.now().year + 2) {
      labels.add('$startYear–$endYear');
    }
    for (final id in genreIds) {
      final genre = genres.where((item) => item.id == id).firstOrNull;
      labels.add(genre?.label ?? 'Жанр #$id');
    }
    return labels;
  }

  _AnimeSearchFilters removeLabel(String label, List<ShikimoriGenre> genres) {
    if (label.startsWith('Формат:')) return copyWith(kind: 'all');
    if (label.startsWith('Статус:')) return copyWith(status: 'all');
    if (label.startsWith('Рейтинг:')) return copyWith(rating: 'all');
    if (label.startsWith('Сортировка:')) return copyWith(order: 'popularity');
    if (label.startsWith('Оценка')) return copyWith(minScore: 0);
    if (label.contains('–') && RegExp(r'^\d{4}').hasMatch(label)) {
      return copyWith(startYear: 1960, endYear: DateTime.now().year + 2);
    }
    final matching = genres.where((genre) => genre.label == label).firstOrNull;
    if (matching != null) {
      final ids = {...genreIds}..remove(matching.id);
      return copyWith(genreIds: ids);
    }
    return this;
  }

  _AnimeSearchFilters copyWith({
    String? kind,
    String? status,
    String? rating,
    String? order,
    int? minScore,
    int? startYear,
    int? endYear,
    Set<int>? genreIds,
  }) => _AnimeSearchFilters(
    kind: kind ?? this.kind,
    status: status ?? this.status,
    rating: rating ?? this.rating,
    order: order ?? this.order,
    minScore: minScore ?? this.minScore,
    startYear: startYear ?? this.startYear,
    endYear: endYear ?? this.endYear,
    genreIds: Set.unmodifiable(genreIds ?? this.genreIds),
  );
}
