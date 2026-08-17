import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:motor/motor.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/app_logging.dart';
import '../../core/animix_motion.dart';
import '../../core/animix_theme.dart';
import '../../models/shikimori_anime.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
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
      } catch (error, stackTrace) {
        AppLogBuffer.instance.recordError(
          error,
          stackTrace,
          source: 'Recommendations',
          context: 'Повреждён локальный список пропущенных аниме',
        );
      }
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
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Recommendations',
        context: 'Не удалось загрузить рекомендации',
      );
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

  Future<bool> _reject({bool advance = true}) async {
    if (_recommendations.isEmpty || _saving) return false;
    setState(() => _saving = true);
    final anime = _recommendations.first;
    try {
      _rejected.add(anime.id);
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_rejectedKey, jsonEncode(_rejected.toList()));
      if (advance && mounted) _advance('Пропущено');
      return true;
    } catch (error, stackTrace) {
      _rejected.remove(anime.id);
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Recommendations',
        context: 'Не удалось сохранить пропущенное аниме ${anime.id}',
      );
      if (mounted) setState(() => _message = 'Не удалось сохранить выбор');
      return false;
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<bool> _plan({bool advance = true}) async {
    if (_recommendations.isEmpty || _saving) return false;
    setState(() => _saving = true);
    final anime = _recommendations.first;
    try {
      final user = await ref.read(currentUserProvider.future);
      if (user == null) throw StateError('auth');
      await ref
          .read(apiClientProvider)
          .setUserRate(anime.id, 'planned', userId: user.id);
      if (mounted && advance) _advance('Добавлено в планы');
      return true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Recommendations',
        context: 'Не удалось добавить аниме ${anime.id} в планы',
      );
      if (mounted) {
        setState(() => _message = 'Не удалось добавить');
      }
      return false;
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _advance(String message) {
    setState(() {
      _message = message;
      if (_recommendations.isNotEmpty) _recommendations.removeAt(0);
    });
    if (_recommendations.length < 5) _loadRecommendations();
    Future<void>.delayed(const Duration(milliseconds: 1300), () {
      if (mounted && _message == message) setState(() => _message = null);
    });
  }

  String get _contentStateKey {
    if (_loading && _recommendations.isEmpty) return 'loading';
    if (_loadError != null && _recommendations.isEmpty) return 'error';
    if (_recommendations.isEmpty) return 'empty';
    return 'content';
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
            Expanded(
              child: AniMixFadeThrough(
                stateKey: _contentStateKey,
                child: _buildContent(),
              ),
            ),
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
      return const AniMixRecommendationSkeleton();
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
                _RecommendationSwipeCard(
                  key: ValueKey('recommendation_${_recommendations.first.id}'),
                  anime: _recommendations.first,
                  saving: _saving,
                  onPlan: () => _plan(advance: false),
                  onReject: () => _reject(advance: false),
                  onAdvance: _advance,
                  onInfo: () => Navigator.push(
                    context,
                    CupertinoPageRoute<void>(
                      builder: (_) =>
                          AnimeDetailScreen(animeId: _recommendations.first.id),
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

class _RecommendationSwipeCard extends StatefulWidget {
  const _RecommendationSwipeCard({
    required this.anime,
    required this.saving,
    required this.onPlan,
    required this.onReject,
    required this.onAdvance,
    required this.onInfo,
    super.key,
  });

  final ShikimoriAnime anime;
  final bool saving;
  final Future<bool> Function() onPlan;
  final Future<bool> Function() onReject;
  final ValueChanged<String> onAdvance;
  final VoidCallback onInfo;

  @override
  State<_RecommendationSwipeCard> createState() =>
      _RecommendationSwipeCardState();
}

class _RecommendationSwipeCardState extends State<_RecommendationSwipeCard> {
  static const _minimumCommitDistance = 96.0;
  static const _commitWidthFactor = .22;
  static const _commitVelocity = 720.0;
  static const _maximumVerticalTravel = 24.0;
  static const _maximumRotation = .13;

  Offset _target = Offset.zero;
  bool _busy = false;
  bool _armed = false;

  bool get _enabled => !_busy && !widget.saving;

  double _threshold(double width) =>
      math.max(_minimumCommitDistance, width * _commitWidthFactor);

  void _updateDrag(DragUpdateDetails details) {
    if (!_enabled) return;
    final width = context.size?.width ?? 390;
    final next = _target + details.delta;
    final target = Offset(
      next.dx.clamp(-width, width),
      next.dy.clamp(-_maximumVerticalTravel, _maximumVerticalTravel),
    );
    final armed = target.dx.abs() >= _threshold(width);
    if (armed && !_armed && Theme.of(context).platform == TargetPlatform.iOS) {
      HapticFeedback.selectionClick();
    }
    setState(() {
      _target = target;
      _armed = armed;
    });
  }

  Future<void> _finishDrag(DragEndDetails details) async {
    if (!_enabled) return;
    final width = context.size?.width ?? 390;
    final velocity = details.velocity.pixelsPerSecond.dx;
    final distanceCommit = _target.dx.abs() >= _threshold(width);
    final velocityCommit = velocity.abs() >= _commitVelocity;
    if (!distanceCommit && !velocityCommit) {
      _reset();
      return;
    }
    final plan = velocityCommit ? velocity > 0 : _target.dx > 0;
    await _commit(plan: plan, width: width);
  }

  Future<void> _commit({required bool plan, required double width}) async {
    setState(() {
      _busy = true;
      _armed = true;
      _target = Offset(plan ? _threshold(width) : -_threshold(width), 0);
    });
    final accepted = await (plan ? widget.onPlan() : widget.onReject());
    if (!mounted) return;
    if (!accepted) {
      _reset();
      return;
    }
    setState(() => _target = Offset(plan ? width * 1.25 : -width * 1.25, 0));
    await Future<void>.delayed(
      AniMixMotion.resolve(context, const Duration(milliseconds: 240)),
    );
    if (!mounted) return;
    widget.onAdvance(plan ? 'Добавлено в планы' : 'Пропущено');
  }

  void _reset() {
    if (!mounted) return;
    setState(() {
      _target = Offset.zero;
      _busy = false;
      _armed = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final reduced = AniMixMotion.isReduced(context);
    return LayoutBuilder(
      builder: (context, constraints) => MotionBuilder<Offset>(
        value: _target,
        from: Offset.zero,
        active: !reduced,
        motion: reduced
            ? const Motion.none()
            : const CupertinoMotion.interactive(
                duration: AniMixMotion.gesture,
                extraBounce: -.04,
              ),
        converter: const OffsetMotionConverter(),
        builder: (context, offset, _) {
          final width = math.max(constraints.maxWidth, 1.0);
          final progress = (offset.dx.abs() / _threshold(width))
              .clamp(0.0, 1.0)
              .toDouble();
          final plan = offset.dx >= 0;
          return Stack(
            fit: StackFit.expand,
            children: [
              IgnorePointer(
                child: AnimatedOpacity(
                  opacity: progress,
                  duration: AniMixMotion.resolve(context, AniMixMotion.press),
                  child: _SwipeBackground(plan: plan),
                ),
              ),
              Transform.translate(
                offset: offset,
                child: Transform.rotate(
                  angle:
                      (offset.dx / width).clamp(-1.0, 1.0) * _maximumRotation,
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onHorizontalDragUpdate: _enabled ? _updateDrag : null,
                    onHorizontalDragEnd: _enabled ? _finishDrag : null,
                    child: _RecommendationCard(
                      anime: widget.anime,
                      onInfo: widget.onInfo,
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _RecommendationCard extends StatelessWidget {
  const _RecommendationCard({required this.anime, this.onInfo});
  final ShikimoriAnime anime;
  final VoidCallback? onInfo;

  @override
  Widget build(BuildContext context) {
    return AniMixSurface(
      radius: 30,
      elevated: true,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(29),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final imageHeight = math.min(
              math.max(constraints.maxHeight * .72, 190.0),
              math.min(430.0, constraints.maxHeight - 104),
            );
            return Column(
              children: [
                SizedBox(
                  height: imageHeight,
                  child: ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainer,
                    child: SmartAnimePoster(
                      animeId: anime.id,
                      imageUrl: anime.imageUrl,
                      title: anime.name ?? '',
                      russianTitle: anime.russian,
                      fit: BoxFit.contain,
                      alignment: Alignment.topCenter,
                    ),
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(18, 12, 10, 12),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                anime.russian ?? anime.name ?? 'Без названия',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 19,
                                  height: 1.08,
                                  fontWeight: FontWeight.w900,
                                ),
                              ),
                              const SizedBox(height: 7),
                              Wrap(
                                spacing: 6,
                                runSpacing: 6,
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
                        IconButton(
                          key: const ValueKey('recommendation_info'),
                          tooltip: 'Подробнее',
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

class _SwipeBackground extends StatelessWidget {
  const _SwipeBackground({required this.plan});

  final bool plan;

  @override
  Widget build(BuildContext context) {
    final color = plan
        ? Theme.of(context).colorScheme.primary
        : CupertinoColors.systemRed;
    return Container(
      key: ValueKey(plan ? 'swipe_plan' : 'swipe_skip'),
      alignment: plan ? Alignment.centerLeft : Alignment.centerRight,
      padding: const EdgeInsets.symmetric(horizontal: 28),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .14),
        borderRadius: BorderRadius.circular(30),
        border: Border.all(color: color.withValues(alpha: .34)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            plan ? CupertinoIcons.bookmark_fill : CupertinoIcons.xmark,
            color: color,
            size: 30,
          ),
          const SizedBox(height: 6),
          Text(
            plan ? 'В ПЛАНЫ' : 'ПРОПУСТИТЬ',
            style: TextStyle(
              color: color,
              fontSize: 11,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
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
        Positioned(
          top: -34,
          child: AniMixFadeThrough(
            stateKey: message ?? 'no-message',
            duration: AniMixMotion.selection,
            child: message == null
                ? const SizedBox.shrink()
                : AniMixMetadataPill(label: message!),
          ),
        ),
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
