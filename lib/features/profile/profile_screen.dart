import 'dart:io';
import 'dart:math' as math;

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/app_logging.dart';
import '../../models/shikimori_history.dart';
import '../../models/shikimori_user.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../auth/login_screen.dart';
import 'profile_cover_storage.dart';
import 'settings_screen.dart';

final userHistoryProvider = FutureProvider.family
    .autoDispose<List<ShikimoriHistory>, int>((ref, userId) async {
      return ref.watch(apiClientProvider).getUserHistory(userId, limit: 60);
    });

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  String? _coverPath;
  bool _coverBusy = false;

  @override
  void initState() {
    super.initState();
    _loadCover();
  }

  Future<void> _loadCover() async {
    final path = await ProfileCoverStorage.currentPath();
    if (mounted) setState(() => _coverPath = path);
  }

  Future<void> _refresh() async {
    ref.invalidate(currentUserProvider);
    final user = await ref.read(currentUserProvider.future);
    if (user != null) ref.invalidate(userHistoryProvider(user.id));
  }

  Future<void> _chooseCover() async {
    if (_coverBusy) return;
    setState(() => _coverBusy = true);
    try {
      final path = await ProfileCoverStorage.chooseAndSave();
      if (path != null && mounted) setState(() => _coverPath = path);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Profile cover',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось сохранить фон профиля')),
        );
      }
    } finally {
      if (mounted) setState(() => _coverBusy = false);
    }
  }

  Future<void> _resetCover() async {
    if (_coverBusy) return;
    setState(() => _coverBusy = true);
    try {
      await ProfileCoverStorage.clear();
      if (mounted) setState(() => _coverPath = null);
    } finally {
      if (mounted) setState(() => _coverBusy = false);
    }
  }

  Future<void> _showCoverMenu() async {
    final action = await showModalBottomSheet<_CoverAction>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                leading: const Icon(CupertinoIcons.photo_on_rectangle),
                title: const Text('Выбрать изображение'),
                subtitle: const Text('JPG, PNG или WebP с устройства'),
                onTap: () => Navigator.pop(sheetContext, _CoverAction.choose),
              ),
              if (_coverPath != null)
                ListTile(
                  leading: const Icon(CupertinoIcons.arrow_counterclockwise),
                  title: const Text('Вернуть фон AniMix'),
                  onTap: () => Navigator.pop(sheetContext, _CoverAction.reset),
                ),
            ],
          ),
        ),
      ),
    );
    if (!mounted) return;
    switch (action) {
      case _CoverAction.choose:
        await _chooseCover();
      case _CoverAction.reset:
        await _resetCover();
      case null:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Профиль'),
        actions: [
          IconButton(
            tooltip: 'Настройки',
            onPressed: () async {
              await Navigator.push(
                context,
                CupertinoPageRoute<void>(
                  builder: (_) => const SettingsScreen(),
                ),
              );
              if (mounted) await _refresh();
            },
            icon: const Icon(CupertinoIcons.gear_alt_fill),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: user.when(
        loading: () => const AniMixProfileSkeleton(),
        error: (_, _) => AniMixEmptyState(
          icon: CupertinoIcons.exclamationmark_triangle,
          title: 'Не удалось загрузить профиль',
          message: 'Shikimori временно не отвечает.',
          actionLabel: 'Повторить',
          onAction: () => ref.invalidate(currentUserProvider),
        ),
        data: (value) {
          if (value == null) {
            return AniMixEmptyState(
              icon: CupertinoIcons.person_crop_circle,
              title: 'Профиль Shikimori',
              message:
                  'Войдите, чтобы синхронизировать закладки, прогресс и историю.',
              actionLabel: 'Войти',
              onAction: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(builder: (_) => const LoginScreen()),
              ),
            );
          }
          final history = ref.watch(userHistoryProvider(value.id));
          return RefreshIndicator.adaptive(
            onRefresh: _refresh,
            child: CustomScrollView(
              physics: const BouncingScrollPhysics(
                parent: AlwaysScrollableScrollPhysics(),
              ),
              slivers: [
                SliverToBoxAdapter(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(
                        maxWidth: AniMixLayout.readingMaxWidth,
                      ),
                      child: Column(
                        children: [
                          _ProfileHeader(
                            user: value,
                            coverPath: _coverPath,
                            coverBusy: _coverBusy,
                            onChangeCover: _showCoverMenu,
                          ),
                          const SizedBox(height: AniMixSpacing.xl),
                          Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: AniMixLayout.pageInset,
                            ),
                            child: Column(
                              children: [
                                _LibraryOverview(user: value),
                                const SizedBox(height: AniMixSpacing.lg),
                                _ProfileInfoCard(user: value),
                              ],
                            ),
                          ),
                          const SizedBox(height: AniMixSpacing.xl),
                          history.when(
                            loading: () =>
                                const AniMixProfileActivitySkeleton(),
                            error: (_, _) => Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: AniMixLayout.pageInset,
                              ),
                              child: AniMixSurface(
                                padding: const EdgeInsets.all(AniMixSpacing.lg),
                                child: Row(
                                  children: [
                                    const Expanded(
                                      child: Text(
                                        'Не удалось загрузить активность',
                                      ),
                                    ),
                                    TextButton.icon(
                                      onPressed: () => ref.invalidate(
                                        userHistoryProvider(value.id),
                                      ),
                                      icon: const Icon(CupertinoIcons.refresh),
                                      label: const Text('Повторить'),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            data: (items) => items.isEmpty
                                ? const SizedBox.shrink()
                                : Column(
                                    children: [
                                      _ActivityRhythm(items: items),
                                      const SizedBox(height: AniMixSpacing.xl),
                                      _RecentActivityCarousel(items: items),
                                    ],
                                  ),
                          ),
                          const SizedBox(height: 64),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

enum _CoverAction { choose, reset }

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({
    required this.user,
    required this.coverPath,
    required this.coverBusy,
    required this.onChangeCover,
  });

  final ShikimoriUser user;
  final String? coverPath;
  final bool coverBusy;
  final VoidCallback onChangeCover;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.bottomCenter,
        children: [
          Container(
            width: double.infinity,
            height: MediaQuery.sizeOf(context).width >= 700 ? 250 : 220,
            clipBehavior: Clip.antiAlias,
            decoration: const BoxDecoration(
              borderRadius: BorderRadius.only(
                bottomLeft: Radius.circular(32),
                bottomRight: Radius.circular(32),
              ),
            ),
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (coverPath != null)
                  Image.file(
                    File(coverPath!),
                    fit: BoxFit.cover,
                    alignment: Alignment.center,
                    gaplessPlayback: true,
                    errorBuilder: (_, _, _) => const _ProfileGradient(),
                  )
                else
                  const _ProfileGradient(),
                DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Colors.black.withValues(alpha: .04),
                        Colors.black.withValues(alpha: .72),
                      ],
                    ),
                  ),
                ),
                Positioned(
                  right: 18,
                  top: 18,
                  child: Material(
                    color: Colors.black.withValues(alpha: .42),
                    borderRadius: BorderRadius.circular(999),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(999),
                      onTap: coverBusy ? null : onChangeCover,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 13,
                          vertical: 9,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (coverBusy)
                              const SizedBox.square(
                                dimension: 15,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            else
                              const Icon(
                                CupertinoIcons.photo_fill_on_rectangle_fill,
                                size: 16,
                                color: Colors.white,
                              ),
                            const SizedBox(width: 7),
                            const Text(
                              'Фон',
                              style: TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            bottom: -50,
            child: Container(
              width: 104,
              height: 104,
              decoration: BoxDecoration(
                color: Theme.of(context).scaffoldBackgroundColor,
                shape: BoxShape.circle,
                border: Border.all(
                  color: Theme.of(context).scaffoldBackgroundColor,
                  width: 5,
                ),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x42000000),
                    blurRadius: 22,
                    offset: Offset(0, 9),
                  ),
                ],
              ),
              clipBehavior: Clip.antiAlias,
              child: user.imageUrl?.isNotEmpty == true
                  ? CachedNetworkImage(
                      imageUrl: user.imageUrl!,
                      fit: BoxFit.cover,
                      errorWidget: (_, _, _) => const Icon(
                        CupertinoIcons.person_crop_circle_fill,
                        size: 76,
                      ),
                    )
                  : const Icon(
                      CupertinoIcons.person_crop_circle_fill,
                      size: 76,
                    ),
            ),
          ),
        ],
      ),
      const SizedBox(height: 68),
      Text(
        user.nickname,
        style: const TextStyle(
          fontSize: 29,
          letterSpacing: -.7,
          fontWeight: FontWeight.w900,
        ),
      ),
      const SizedBox(height: 7),
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: const BoxDecoration(
              color: CupertinoColors.systemGreen,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 7),
          Text(
            _onlineText(user.lastOnlineAt),
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    ],
  );

  static String _onlineText(String? value) {
    final date = DateTime.tryParse(value ?? '')?.toLocal();
    if (date == null) return 'Профиль Shikimori';
    final difference = DateTime.now().difference(date);
    if (difference.inMinutes < 5) return 'сейчас онлайн';
    if (difference.inHours < 1) return '${difference.inMinutes} мин. назад';
    if (difference.inDays < 1) return '${difference.inHours} ч. назад';
    return '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
  }
}

class _ProfileGradient extends StatelessWidget {
  const _ProfileGradient();

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color.lerp(accent, const Color(0xFF0A1020), .38)!,
            const Color(0xFF3B2C76),
            Color.lerp(accent, const Color(0xFF09090C), .78)!,
          ],
        ),
      ),
      child: CustomPaint(painter: _BackdropOrbitsPainter(accent)),
    );
  }
}

class _BackdropOrbitsPainter extends CustomPainter {
  const _BackdropOrbitsPainter(this.accent);
  final Color accent;

  @override
  void paint(Canvas canvas, Size size) {
    final line = Paint()
      ..color = Colors.white.withValues(alpha: .10)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2;
    final glow = Paint()
      ..shader =
          RadialGradient(
            colors: [accent.withValues(alpha: .30), Colors.transparent],
          ).createShader(
            Rect.fromCircle(
              center: Offset(size.width * .18, size.height * .16),
              radius: size.width * .34,
            ),
          );
    canvas.drawRect(Offset.zero & size, glow);
    for (final factor in [.34, .52, .74]) {
      canvas.drawOval(
        Rect.fromCenter(
          center: Offset(size.width * .76, size.height * .28),
          width: size.width * factor,
          height: size.height * factor,
        ),
        line,
      );
    }
  }

  @override
  bool shouldRepaint(_BackdropOrbitsPainter oldDelegate) =>
      oldDelegate.accent != accent;
}

typedef _LibraryStat = ({String label, int value, Color color});

class _LibraryOverview extends StatelessWidget {
  const _LibraryOverview({required this.user});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final stats = <_LibraryStat>[
      (label: 'Смотрю', value: user.watching, color: const Color(0xFF52A8FF)),
      (label: 'В планах', value: user.planned, color: const Color(0xFF9B8CFF)),
      (label: 'Завершено', value: user.watched, color: const Color(0xFF35CF83)),
      (label: 'Отложено', value: user.onHold, color: const Color(0xFFFFB547)),
      (label: 'Брошено', value: user.dropped, color: const Color(0xFFFF6574)),
      (
        label: 'Пересматриваю',
        value: user.rewatched,
        color: const Color(0xFFD274FF),
      ),
    ];
    final total = stats.fold<int>(0, (sum, item) => sum + item.value);
    final completion = total == 0 ? 0.0 : user.watched / total;
    return AniMixSurface(
      elevated: true,
      padding: const EdgeInsets.all(AniMixSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AniMixSectionHeader(
            title: 'Моя медиатека',
            subtitle: 'Живой срез коллекции Shikimori',
            icon: CupertinoIcons.chart_pie_fill,
          ),
          const SizedBox(height: AniMixSpacing.xl),
          LayoutBuilder(
            builder: (context, constraints) {
              final chart = _LibraryDonut(
                stats: stats,
                total: total,
                completed: user.watched,
              );
              final legend = _LibraryLegend(
                stats: stats,
                total: total,
                scoreCount: user.scores,
                completion: completion,
              );
              if (constraints.maxWidth >= 620) {
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    SizedBox(width: 230, child: chart),
                    const SizedBox(width: 34),
                    Expanded(child: legend),
                  ],
                );
              }
              return Column(
                children: [
                  chart,
                  const SizedBox(height: AniMixSpacing.xl),
                  legend,
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _LibraryDonut extends StatelessWidget {
  const _LibraryDonut({
    required this.stats,
    required this.total,
    required this.completed,
  });
  final List<_LibraryStat> stats;
  final int total;
  final int completed;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Всего $total аниме, завершено $completed',
    child: SizedBox(
      width: 210,
      height: 210,
      child: Stack(
        alignment: Alignment.center,
        children: [
          TweenAnimationBuilder<double>(
            duration: const Duration(milliseconds: 720),
            curve: Curves.easeOutCubic,
            tween: Tween(begin: 0, end: 1),
            builder: (_, progress, _) => CustomPaint(
              size: const Size.square(210),
              painter: _DonutPainter(
                stats: stats,
                total: total,
                progress: progress,
                trackColor: Theme.of(context).colorScheme.surfaceContainerHigh,
              ),
            ),
          ),
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '$total',
                style: const TextStyle(
                  fontSize: 38,
                  height: 1,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -1.5,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'в коллекции',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}

class _DonutPainter extends CustomPainter {
  const _DonutPainter({
    required this.stats,
    required this.total,
    required this.progress,
    required this.trackColor,
  });
  final List<_LibraryStat> stats;
  final int total;
  final double progress;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    const stroke = 16.0;
    final arcRect = rect.deflate(stroke / 2);
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..color = trackColor;
    canvas.drawArc(arcRect, 0, math.pi * 2, false, track);
    if (total <= 0) return;
    var start = -math.pi / 2;
    const gap = .035;
    for (final stat in stats.where((item) => item.value > 0)) {
      final sweep = math.pi * 2 * stat.value / total * progress;
      final paint = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..color = stat.color;
      canvas.drawArc(
        arcRect,
        start + gap,
        math.max(0, sweep - gap * 2),
        false,
        paint,
      );
      start += sweep;
    }
  }

  @override
  bool shouldRepaint(_DonutPainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.total != total ||
      oldDelegate.trackColor != trackColor;
}

class _LibraryLegend extends StatelessWidget {
  const _LibraryLegend({
    required this.stats,
    required this.total,
    required this.scoreCount,
    required this.completion,
  });
  final List<_LibraryStat> stats;
  final int total;
  final int scoreCount;
  final double completion;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final stat in stats) ...[
        _LibraryStatRow(stat: stat, total: total),
        if (stat != stats.last) const SizedBox(height: 12),
      ],
      const SizedBox(height: 20),
      Row(
        children: [
          Expanded(
            child: _InsightPill(
              value: '${(completion * 100).round()}%',
              label: 'завершено',
              icon: CupertinoIcons.check_mark_circled_solid,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: _InsightPill(
              value: '$scoreCount',
              label: 'оценено',
              icon: CupertinoIcons.star_fill,
            ),
          ),
        ],
      ),
    ],
  );
}

class _LibraryStatRow extends StatelessWidget {
  const _LibraryStatRow({required this.stat, required this.total});
  final _LibraryStat stat;
  final int total;

  @override
  Widget build(BuildContext context) {
    final fraction = total == 0 ? 0.0 : stat.value / total;
    return Row(
      children: [
        Container(
          width: 4,
          height: 28,
          decoration: BoxDecoration(
            color: stat.color,
            borderRadius: BorderRadius.circular(99),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      stat.label,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                  Text(
                    '${stat.value}',
                    style: const TextStyle(fontWeight: FontWeight.w900),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              ClipRRect(
                borderRadius: BorderRadius.circular(99),
                child: LinearProgressIndicator(
                  value: fraction,
                  minHeight: 4,
                  color: stat.color,
                  backgroundColor: stat.color.withValues(alpha: .12),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _InsightPill extends StatelessWidget {
  const _InsightPill({
    required this.value,
    required this.label,
    required this.icon,
  });
  final String value;
  final String label;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 12),
      decoration: BoxDecoration(
        color: accent.withValues(alpha: .08),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Icon(icon, color: accent, size: 18),
          const SizedBox(width: 9),
          Flexible(
            child: RichText(
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              text: TextSpan(
                style: DefaultTextStyle.of(context).style,
                children: [
                  TextSpan(
                    text: '$value ',
                    style: const TextStyle(fontWeight: FontWeight.w900),
                  ),
                  TextSpan(
                    text: label,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontSize: 11,
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
}

class _ProfileInfoCard extends StatelessWidget {
  const _ProfileInfoCard({required this.user});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final values = <({String label, String value, IconData icon})>[
      if (user.name?.trim().isNotEmpty == true)
        (label: 'Имя', value: user.name!, icon: CupertinoIcons.person_fill),
      if (user.birthOn?.isNotEmpty == true)
        (
          label: 'Дата рождения',
          value: user.birthOn!,
          icon: CupertinoIcons.gift_fill,
        ),
      if (user.joinedAt?.isNotEmpty == true)
        (
          label: 'На Shikimori с',
          value: _shortDate(user.joinedAt!),
          icon: CupertinoIcons.calendar,
        ),
    ];
    if (values.isEmpty) return const SizedBox.shrink();
    return AniMixSurface(
      padding: const EdgeInsets.all(AniMixSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AniMixSectionHeader(
            title: 'О пользователе',
            subtitle: 'Данные профиля Shikimori',
            icon: CupertinoIcons.person_crop_circle_fill,
          ),
          const SizedBox(height: AniMixSpacing.lg),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: values
                .map(
                  (item) => Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 13,
                      vertical: 11,
                    ),
                    decoration: BoxDecoration(
                      color: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHigh.withValues(alpha: .62),
                      borderRadius: BorderRadius.circular(15),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(item.icon, size: 16),
                        const SizedBox(width: 8),
                        Text(
                          '${item.label}: ',
                          style: TextStyle(
                            color: Theme.of(
                              context,
                            ).colorScheme.onSurfaceVariant,
                          ),
                        ),
                        Text(
                          item.value,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                      ],
                    ),
                  ),
                )
                .toList(),
          ),
        ],
      ),
    );
  }

  static String _shortDate(String value) {
    final date = DateTime.tryParse(value)?.toLocal();
    return date == null
        ? value
        : '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
  }
}

class _ActivityRhythm extends StatelessWidget {
  const _ActivityRhythm({required this.items});
  final List<ShikimoriHistory> items;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final counts = <DateTime, int>{};
    for (final item in items) {
      final value = DateTime.tryParse(item.createdAt)?.toLocal();
      if (value == null) continue;
      final date = DateTime(value.year, value.month, value.day);
      counts[date] = (counts[date] ?? 0) + 1;
    }
    final days = List.generate(14, (index) {
      final value = DateTime(
        now.year,
        now.month,
        now.day,
      ).subtract(Duration(days: 13 - index));
      return (date: value, count: counts[value] ?? 0);
    });
    final activeDays = days.where((day) => day.count > 0).length;
    final actions = days.fold<int>(0, (sum, day) => sum + day.count);
    var streak = 0;
    for (final day in days.reversed) {
      if (day.count == 0) break;
      streak++;
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AniMixLayout.pageInset),
      child: AniMixSurface(
        padding: const EdgeInsets.all(AniMixSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const AniMixSectionHeader(
              title: 'Ритм просмотра',
              subtitle: 'Что происходило за последние 14 дней',
              icon: CupertinoIcons.waveform_path_ecg,
            ),
            const SizedBox(height: AniMixSpacing.lg),
            Row(
              children: [
                Expanded(
                  child: _RhythmMetric(value: '$actions', label: 'действий'),
                ),
                Expanded(
                  child: _RhythmMetric(
                    value: '$activeDays',
                    label: 'активных дней',
                  ),
                ),
                Expanded(
                  child: _RhythmMetric(value: '$streak', label: 'дней подряд'),
                ),
              ],
            ),
            const SizedBox(height: AniMixSpacing.lg),
            Semantics(
              label: '$actions действий за 14 дней, активных дней $activeDays',
              child: SizedBox(
                height: 142,
                width: double.infinity,
                child: TweenAnimationBuilder<double>(
                  duration: const Duration(milliseconds: 680),
                  curve: Curves.easeOutCubic,
                  tween: Tween(begin: 0, end: 1),
                  builder: (_, progress, _) => CustomPaint(
                    painter: _ActivityWavePainter(
                      values: days.map((day) => day.count).toList(),
                      accent: Theme.of(context).colorScheme.primary,
                      grid: Theme.of(context).colorScheme.outlineVariant,
                      progress: progress,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children:
                  [
                        Text(_dayLabel(days.first.date)),
                        Text(_dayLabel(days[6].date)),
                        const Text('Сегодня'),
                      ]
                      .map(
                        (label) => DefaultTextStyle.merge(
                          style: TextStyle(
                            color: Theme.of(
                              context,
                            ).colorScheme.onSurfaceVariant,
                            fontSize: 10,
                          ),
                          child: label,
                        ),
                      )
                      .toList(),
            ),
          ],
        ),
      ),
    );
  }

  static String _dayLabel(DateTime date) =>
      '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}';
}

class _RhythmMetric extends StatelessWidget {
  const _RhythmMetric({required this.value, required this.label});
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        value,
        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900),
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
}

class _ActivityWavePainter extends CustomPainter {
  const _ActivityWavePainter({
    required this.values,
    required this.accent,
    required this.grid,
    required this.progress,
  });
  final List<int> values;
  final Color accent;
  final Color grid;
  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    final chart = Rect.fromLTWH(2, 4, size.width - 4, size.height - 8);
    final gridPaint = Paint()
      ..color = grid.withValues(alpha: .38)
      ..strokeWidth = 1;
    for (var index = 0; index < 4; index++) {
      final y = chart.top + chart.height * index / 3;
      canvas.drawLine(Offset(chart.left, y), Offset(chart.right, y), gridPaint);
    }
    if (values.isEmpty) return;
    final maxValue = math.max(1, values.reduce(math.max));
    final points = <Offset>[];
    for (var index = 0; index < values.length; index++) {
      final x = chart.left + chart.width * index / (values.length - 1);
      final normalized = values[index] / maxValue * progress;
      final y = chart.bottom - normalized * chart.height * .82;
      points.add(Offset(x, y));
    }
    final line = Path()..moveTo(points.first.dx, points.first.dy);
    for (var index = 1; index < points.length; index++) {
      final previous = points[index - 1];
      final current = points[index];
      final controlX = (previous.dx + current.dx) / 2;
      line.cubicTo(
        controlX,
        previous.dy,
        controlX,
        current.dy,
        current.dx,
        current.dy,
      );
    }
    final fill = Path.from(line)
      ..lineTo(points.last.dx, chart.bottom)
      ..lineTo(points.first.dx, chart.bottom)
      ..close();
    canvas.drawPath(
      fill,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            accent.withValues(alpha: .27),
            accent.withValues(alpha: .015),
          ],
        ).createShader(chart),
    );
    canvas.drawPath(
      line,
      Paint()
        ..color = accent
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..strokeCap = StrokeCap.round,
    );
    final dot = Paint()..color = accent;
    for (var index = 0; index < points.length; index++) {
      if (values[index] > 0) canvas.drawCircle(points[index], 3.5, dot);
    }
  }

  @override
  bool shouldRepaint(_ActivityWavePainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.values != values ||
      oldDelegate.accent != accent;
}

class _RecentActivityCarousel extends StatelessWidget {
  const _RecentActivityCarousel({required this.items});
  final List<ShikimoriHistory> items;

  @override
  Widget build(BuildContext context) {
    final visible = items.take(16).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: AniMixLayout.pageInset),
          child: AniMixSectionHeader(
            title: 'Последние штрихи',
            subtitle:
                'Листай в сторону — профиль больше не бесконечная ведомость',
            icon: CupertinoIcons.time_solid,
          ),
        ),
        const SizedBox(height: AniMixSpacing.md),
        SizedBox(
          height: 246,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final width =
                  (constraints.maxWidth *
                          (constraints.maxWidth >= 760 ? .34 : .76))
                      .clamp(230.0, 310.0);
              return ListView.separated(
                scrollDirection: Axis.horizontal,
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.symmetric(
                  horizontal: AniMixLayout.pageInset,
                ),
                itemCount: visible.length,
                separatorBuilder: (_, _) => const SizedBox(width: 12),
                itemBuilder: (context, index) => SizedBox(
                  width: width,
                  child: _HistoryCard(item: visible[index]),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _HistoryCard extends StatelessWidget {
  const _HistoryCard({required this.item});
  final ShikimoriHistory item;

  @override
  Widget build(BuildContext context) {
    final anime = item.anime;
    final date = DateTime.tryParse(item.createdAt)?.toLocal();
    return AniMixSurface(
      elevated: true,
      radius: 22,
      onTap: anime == null
          ? null
          : () => Navigator.push(
              context,
              CupertinoPageRoute<void>(
                builder: (_) => AnimeDetailScreen(animeId: anime.id),
              ),
            ),
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (anime != null)
            SmartAnimePoster(
              animeId: anime.id,
              imageUrl: anime.imageUrl,
              title: anime.name ?? '',
              russianTitle: anime.russian,
            )
          else
            ColoredBox(
              color: Theme.of(context).colorScheme.surfaceContainerHigh,
            ),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                stops: [0, .36, 1],
                colors: [
                  Color(0x14000000),
                  Color(0x8F000000),
                  Color(0xF5000000),
                ],
              ),
            ),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 16,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (date != null)
                  Text(
                    _relativeTime(date),
                    style: const TextStyle(
                      color: Colors.white70,
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                const SizedBox(height: 7),
                Text(
                  anime?.russian ?? anime?.name ?? item.description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    height: 1.15,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 7),
                Text(
                  item.description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 11,
                    height: 1.25,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static String _relativeTime(DateTime value) {
    final difference = DateTime.now().difference(value);
    if (difference.inMinutes < 1) return 'только что';
    if (difference.inHours < 1) return '${difference.inMinutes} мин. назад';
    if (difference.inDays < 1) return '${difference.inHours} ч. назад';
    if (difference.inDays < 7) return '${difference.inDays} дн. назад';
    return '${value.day.toString().padLeft(2, '0')}.${value.month.toString().padLeft(2, '0')}';
  }
}
