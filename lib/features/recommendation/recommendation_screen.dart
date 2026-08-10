import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/animix_theme.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';

class RecommendationScreen extends ConsumerStatefulWidget {
  const RecommendationScreen({super.key});

  @override
  ConsumerState<RecommendationScreen> createState() =>
      _RecommendationScreenState();
}

class _RecommendationScreenState extends ConsumerState<RecommendationScreen> {
  static const _rejectedKey = 'recommendation_rejected_ids_v1';
  final List<ShikimoriAnime> _recommendations = [];
  final Set<int> _rejected = {};
  Offset _drag = Offset.zero;
  bool _loading = false;
  bool _saving = false;
  String? _message;
  Object? _loadError;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_rejectedKey);
    if (raw != null) {
      try {
        _rejected.addAll(
          (jsonDecode(raw) as List).map((value) => value as int),
        );
      } catch (_) {}
    }
    await _loadRecommendations(reset: true);
  }

  Future<void> _loadRecommendations({bool reset = false}) async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _loadError = null;
      if (reset) _message = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      final results = <ShikimoriAnime>[];
      for (var attempt = 0; attempt < 2; attempt++) {
        final page = math.Random().nextInt(24) + 1;
        final items = await api.getAnimes(
          page: page,
          limit: 20,
          filters: const {'order': 'random', 'score': 6},
        );
        results.addAll(items);
      }
      if (!mounted) return;
      setState(() {
        if (reset) _recommendations.clear();
        for (final item in results) {
          if (!_rejected.contains(item.id) &&
              !_recommendations.any((existing) => existing.id == item.id)) {
            _recommendations.add(item);
          }
        }
      });
    } catch (error) {
      if (mounted) {
        setState(() => _loadError = error);
        if (_recommendations.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: const Text('Не удалось обновить рекомендации'),
              action: SnackBarAction(
                label: 'Повторить',
                onPressed: () => _loadRecommendations(reset: true),
              ),
            ),
          );
        }
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _reject() async {
    if (_recommendations.isEmpty || _saving) return;
    final anime = _recommendations.first;
    _rejected.add(anime.id);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_rejectedKey, jsonEncode(_rejected.toList()));
    _advance('Пропущено');
  }

  Future<void> _plan() async {
    if (_recommendations.isEmpty || _saving) return;
    setState(() => _saving = true);
    final anime = _recommendations.first;
    try {
      final user = await ref.read(currentUserProvider.future);
      if (user == null) throw StateError('auth');
      await ref
          .read(apiClientProvider)
          .setUserRate(anime.id, 'planned', userId: user.id);
      if (mounted) _advance('Добавлено в планы');
    } catch (_) {
      if (mounted) {
        setState(() {
          _drag = Offset.zero;
          _message = 'Не удалось добавить';
        });
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _advance(String message) {
    setState(() {
      _drag = Offset.zero;
      _message = message;
      if (_recommendations.isNotEmpty) _recommendations.removeAt(0);
    });
    if (_recommendations.length < 5) _loadRecommendations();
    Future<void>.delayed(const Duration(milliseconds: 1300), () {
      if (mounted && _message == message) setState(() => _message = null);
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Theme.of(context).scaffoldBackgroundColor,
    appBar: AppBar(title: const Text('Рекомендации')),
    body: DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Theme.of(context).colorScheme.primary.withValues(alpha: .10),
            const Color(0xFF7C3AED).withValues(alpha: .06),
            Colors.transparent,
          ],
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
        child: Column(
          children: [
            _RecommendationHeader(
              count: _recommendations.length,
              loading: _loading,
              onRefresh: () => _loadRecommendations(reset: true),
            ),
            const SizedBox(height: 14),
            Expanded(child: _buildContent()),
            if (_recommendations.isNotEmpty) ...[
              const SizedBox(height: 12),
              _Controls(
                saving: _saving,
                message: _message,
                onReject: _reject,
                onPlan: _plan,
              ),
            ],
          ],
        ),
      ),
    ),
  );

  Widget _buildContent() {
    if (_loading && _recommendations.isEmpty) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CupertinoActivityIndicator(radius: 15),
            SizedBox(height: 14),
            Text(
              'Загружаем рекомендации…',
              style: TextStyle(color: AniMixTheme.subtleText),
            ),
          ],
        ),
      );
    }
    if (_loadError != null && _recommendations.isEmpty) {
      return AniMixEmptyState(
        icon: CupertinoIcons.wifi_exclamationmark,
        title: 'Не удалось загрузить рекомендации',
        message: 'Проверьте подключение и повторите попытку.',
        actionLabel: 'Повторить',
        onAction: () => _loadRecommendations(reset: true),
      );
    }
    if (_recommendations.isEmpty) {
      return AniMixEmptyState(
        icon: CupertinoIcons.sparkles,
        title: 'Подборка закончилась',
        message: 'Новые варианты появятся после обновления.',
        actionLabel: 'Обновить',
        onAction: () => _loadRecommendations(reset: true),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = math.min(constraints.maxWidth, 520.0);
        final height = math.min(
          constraints.maxHeight,
          constraints.maxWidth >= 700 ? 610.0 : 520.0,
        );
        return Center(
          child: SizedBox(
            width: width,
            height: height,
            child: Stack(
              alignment: Alignment.topCenter,
              children: [
                for (
                  var index = math.min(2, _recommendations.length - 1);
                  index >= 1;
                  index--
                )
                  Transform.translate(
                    offset: Offset(0, index * 14),
                    child: Transform.scale(
                      scale: 1 - index * .045,
                      child: Opacity(
                        opacity: 1 - index * .15,
                        child: _RecommendationCard(
                          anime: _recommendations[index],
                        ),
                      ),
                    ),
                  ),
                GestureDetector(
                  onPanUpdate: (details) =>
                      setState(() => _drag += details.delta),
                  onPanEnd: (_) {
                    if (_drag.dx > 110) {
                      _plan();
                    } else if (_drag.dx < -110) {
                      _reject();
                    } else {
                      setState(() => _drag = Offset.zero);
                    }
                  },
                  child: Transform.translate(
                    offset: Offset(_drag.dx, _drag.dy * .12),
                    child: Transform.rotate(
                      angle: _drag.dx / 900,
                      child: _RecommendationCard(
                        anime: _recommendations.first,
                        drag: _drag.dx,
                        onInfo: () => Navigator.push(
                          context,
                          CupertinoPageRoute<void>(
                            builder: (_) => AnimeDetailScreen(
                              animeId: _recommendations.first.id,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _RecommendationHeader extends StatelessWidget {
  const _RecommendationHeader({
    required this.count,
    required this.loading,
    required this.onRefresh,
  });
  final int count;
  final bool loading;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Icon(
        CupertinoIcons.sparkles,
        color: Theme.of(context).colorScheme.primary,
        size: 19,
      ),
      const SizedBox(width: 8),
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Персональная лента',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 3),
            Text(
              count == 0 ? 'Загрузка списка' : 'Ещё $count · вправо — в планы',
              style: const TextStyle(
                color: AniMixTheme.subtleText,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
      AniMixIconButton(
        icon: CupertinoIcons.refresh,
        tooltip: 'Обновить',
        size: 40,
        onPressed: loading ? null : onRefresh,
      ),
    ],
  );
}

class _RecommendationCard extends StatelessWidget {
  const _RecommendationCard({required this.anime, this.drag = 0, this.onInfo});
  final ShikimoriAnime anime;
  final double drag;
  final VoidCallback? onInfo;

  @override
  Widget build(BuildContext context) {
    final positive = drag >= 0;
    return AniMixSurface(
      radius: 30,
      elevated: true,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(29),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final imageHeight = math.min(
              math.max(constraints.maxHeight * .68, 170.0),
              math.min(400.0, constraints.maxHeight - 72),
            );
            return Column(
              children: [
                SizedBox(
                  height: imageHeight,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      SmartAnimePoster(
                        animeId: anime.id,
                        imageUrl: anime.imageUrl,
                        title: anime.name ?? '',
                        russianTitle: anime.russian,
                      ),
                      const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.center,
                            end: Alignment.bottomCenter,
                            colors: [Colors.transparent, Color(0xB8000000)],
                          ),
                        ),
                      ),
                      Positioned(
                        left: 18,
                        right: 18,
                        bottom: 18,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              anime.russian ?? anime.name ?? 'Без названия',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 23,
                                height: 1.08,
                                fontWeight: FontWeight.w900,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Wrap(
                              spacing: 7,
                              children: [
                                if (anime.kind?.isNotEmpty == true)
                                  AniMixMetadataPill(
                                    label: anime.kind!.toUpperCase(),
                                  ),
                                if ((anime.score ?? 0) > 0)
                                  AniMixMetadataPill(
                                    label:
                                        '★ ${anime.score!.toStringAsFixed(1)}',
                                  ),
                                if ((anime.episodes ?? 0) > 0)
                                  AniMixMetadataPill(
                                    label: '${anime.episodes} эп.',
                                  ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      if (drag.abs() > 10)
                        Positioned(
                          top: 24,
                          left: positive ? 22 : null,
                          right: positive ? null : 22,
                          child: Transform.rotate(
                            angle: positive ? -.12 : .12,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 7,
                              ),
                              decoration: BoxDecoration(
                                border: Border.all(
                                  color: positive
                                      ? CupertinoColors.systemGreen
                                      : CupertinoColors.systemRed,
                                  width: 3,
                                ),
                                borderRadius: BorderRadius.circular(8),
                              ),
                              child: Text(
                                positive ? 'В ПЛАНЫ' : 'ПРОПУСТИТЬ',
                                style: TextStyle(
                                  color: positive
                                      ? CupertinoColors.systemGreen
                                      : CupertinoColors.systemRed,
                                  fontWeight: FontWeight.w900,
                                ),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 18),
                    child: Row(
                      children: [
                        const Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Подобрано по вашей истории',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(fontWeight: FontWeight.w700),
                              ),
                              SizedBox(height: 3),
                              Text(
                                'Shikimori · персональная лента',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: AniMixTheme.subtleText,
                                  fontSize: 11,
                                ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          onPressed: onInfo,
                          icon: Icon(
                            CupertinoIcons.info_circle_fill,
                            color: Theme.of(context).colorScheme.primary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _Controls extends StatelessWidget {
  const _Controls({
    required this.saving,
    required this.message,
    required this.onReject,
    required this.onPlan,
  });
  final bool saving;
  final String? message;
  final VoidCallback onReject;
  final VoidCallback onPlan;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 76,
    child: Stack(
      clipBehavior: Clip.none,
      alignment: Alignment.center,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _ActionCircle(
              icon: CupertinoIcons.xmark,
              color: CupertinoColors.systemRed,
              onTap: saving ? null : onReject,
            ),
            const SizedBox(width: 34),
            _ActionCircle(
              icon: CupertinoIcons.bookmark_fill,
              color: Theme.of(context).colorScheme.primary,
              primary: true,
              onTap: saving ? null : onPlan,
            ),
          ],
        ),
        if (message != null)
          Positioned(top: -34, child: AniMixMetadataPill(label: message!)),
      ],
    ),
  );
}

class _ActionCircle extends StatelessWidget {
  const _ActionCircle({
    required this.icon,
    required this.color,
    required this.onTap,
    this.primary = false,
  });
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;
  final bool primary;

  @override
  Widget build(BuildContext context) => Material(
    color: primary ? color : Theme.of(context).colorScheme.surface,
    shape: CircleBorder(side: BorderSide(color: color.withValues(alpha: .28))),
    elevation: primary ? 6 : 0,
    shadowColor: color.withValues(alpha: .4),
    child: InkWell(
      customBorder: const CircleBorder(),
      onTap: onTap,
      child: SizedBox.square(
        dimension: primary ? 62 : 56,
        child: Icon(
          icon,
          color: primary ? Colors.white : color,
          size: primary ? 27 : 24,
        ),
      ),
    ),
  );
}
