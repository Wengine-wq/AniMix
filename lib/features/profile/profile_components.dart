import 'dart:io';
import 'dart:math' as math;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../../core/animix_theme.dart';
import '../../models/shikimori_user.dart';
import '../../widgets/animix_surface.dart';

class ProfileHeader extends StatelessWidget {
  const ProfileHeader({
    super.key,
    required this.user,
    this.coverPath,
    this.avatarPath,
    this.coverBusy = false,
    this.editing = false,
    this.nameController,
    this.onChangeCover,
    this.onChangeAvatar,
    this.onDeleteCover,
    this.onDeleteAvatar,
  });

  final ShikimoriUser user;
  final String? coverPath;
  final String? avatarPath;
  final bool coverBusy;
  final bool editing;
  final TextEditingController? nameController;
  final VoidCallback? onChangeCover;
  final VoidCallback? onChangeAvatar;
  final VoidCallback? onDeleteCover;
  final VoidCallback? onDeleteAvatar;

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
                    errorBuilder: (_, _, _) => _remoteCoverOrGradient(),
                  )
                else if (user.bannerUrl?.isNotEmpty == true)
                  CachedNetworkImage(
                    imageUrl: user.bannerUrl!,
                    fit: BoxFit.cover,
                    alignment: Alignment.center,
                    errorWidget: (_, _, _) => const _ProfileGradient(),
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
                if (editing)
                  Positioned(
                    right: 18,
                    top: 18,
                    child: Row(
                      children: [
                        if (onDeleteCover != null) ...[
                          _ProfileMediaButton(
                            icon: CupertinoIcons.delete,
                            label: 'Удалить',
                            onTap: coverBusy ? null : onDeleteCover,
                          ),
                          const SizedBox(width: 8),
                        ],
                        _ProfileMediaButton(
                          icon: CupertinoIcons.photo_fill_on_rectangle_fill,
                          label: 'Сменить фон',
                          busy: coverBusy,
                          onTap: coverBusy ? null : onChangeCover,
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
          Positioned(
            bottom: -50,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Semantics(
                  button: editing,
                  label: editing ? 'Сменить аватар' : 'Аватар пользователя',
                  child: MouseRegion(
                    cursor: coverBusy
                        ? SystemMouseCursors.basic
                        : SystemMouseCursors.click,
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: editing && !coverBusy ? onChangeAvatar : null,
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
                        child: avatarPath != null
                            ? Image.file(
                                File(avatarPath!),
                                fit: BoxFit.cover,
                                gaplessPlayback: true,
                                errorBuilder: (_, _, _) =>
                                    _remoteAvatarOrPlaceholder(),
                              )
                            : user.imageUrl?.isNotEmpty == true
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
                  ),
                ),
                if (editing)
                  Positioned(
                    right: -2,
                    bottom: -2,
                    child: Material(
                      color: Theme.of(context).colorScheme.primary,
                      shape: const CircleBorder(),
                      child: InkWell(
                        customBorder: const CircleBorder(),
                        onTap: coverBusy ? null : onChangeAvatar,
                        child: const Padding(
                          padding: EdgeInsets.all(7),
                          child: Icon(
                            CupertinoIcons.camera_fill,
                            color: Colors.white,
                            size: 16,
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
      const SizedBox(height: 68),
      if (editing)
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: TextField(
              controller: nameController,
              autofocus: true,
              maxLength: 32,
              textAlign: TextAlign.center,
              textInputAction: TextInputAction.done,
              decoration: const InputDecoration(
                labelText: 'Имя в AniMix',
                hintText: 'Как тебя видят другие',
                counterText: '',
                prefixIcon: Icon(CupertinoIcons.person_fill),
              ),
            ),
          ),
        )
      else
        Text(
          user.nickname,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 29,
            letterSpacing: -.7,
            fontWeight: FontWeight.w900,
          ),
        ),
      if (editing && onDeleteAvatar != null) ...[
        const SizedBox(height: 6),
        TextButton.icon(
          onPressed: coverBusy ? null : onDeleteAvatar,
          icon: const Icon(CupertinoIcons.delete, size: 16),
          label: const Text('Удалить аватар'),
        ),
      ],
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
            _onlineText(user.lastOnlineAt, isAniMix: user.isAniMix),
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

  Widget _remoteCoverOrGradient() => user.bannerUrl?.isNotEmpty == true
      ? CachedNetworkImage(
          imageUrl: user.bannerUrl!,
          fit: BoxFit.cover,
          alignment: Alignment.center,
          errorWidget: (_, _, _) => const _ProfileGradient(),
        )
      : const _ProfileGradient();

  Widget _remoteAvatarOrPlaceholder() => user.imageUrl?.isNotEmpty == true
      ? CachedNetworkImage(
          imageUrl: user.imageUrl!,
          fit: BoxFit.cover,
          errorWidget: (_, _, _) =>
              const Icon(CupertinoIcons.person_crop_circle_fill, size: 76),
        )
      : const Icon(CupertinoIcons.person_crop_circle_fill, size: 76);

  static String _onlineText(String? value, {required bool isAniMix}) {
    final date = DateTime.tryParse(value ?? '')?.toLocal();
    if (date == null) return isAniMix ? 'Профиль AniMix' : 'Профиль Shikimori';
    final difference = DateTime.now().difference(date);
    if (difference.inMinutes < 5) return 'сейчас онлайн';
    if (difference.inHours < 1) return '${difference.inMinutes} мин. назад';
    if (difference.inDays < 1) return '${difference.inHours} ч. назад';
    return '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
  }
}

class _ProfileMediaButton extends StatelessWidget {
  const _ProfileMediaButton({
    required this.icon,
    required this.label,
    required this.onTap,
    this.busy = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool busy;

  @override
  Widget build(BuildContext context) => Material(
    color: Colors.black.withValues(alpha: .42),
    borderRadius: BorderRadius.circular(999),
    child: InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (busy)
              const SizedBox.square(
                dimension: 15,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              )
            else
              Icon(icon, size: 16, color: Colors.white),
            const SizedBox(width: 7),
            Text(
              label,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w700,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
    ),
  );
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

class ProfileLibraryOverview extends StatelessWidget {
  const ProfileLibraryOverview({
    required this.user,
    this.ownProfile = true,
    super.key,
  });
  final bool ownProfile;
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final stats = <_LibraryStat>[
      (
        label: ownProfile ? 'Смотрю' : 'Смотрит',
        value: user.watching,
        color: const Color(0xFF52A8FF),
      ),
      (label: 'В планах', value: user.planned, color: const Color(0xFF9B8CFF)),
      (label: 'Завершено', value: user.watched, color: const Color(0xFF35CF83)),
      (label: 'Отложено', value: user.onHold, color: const Color(0xFFFFB547)),
      (label: 'Брошено', value: user.dropped, color: const Color(0xFFFF6574)),
      (
        label: ownProfile ? 'Пересматриваю' : 'Пересматривает',
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
          AniMixSectionHeader(
            title: ownProfile ? 'Моя медиатека' : 'Медиатека',
            subtitle: !ownProfile
                ? 'Коллекция пользователя AniMix'
                : user.isAniMix
                ? 'Статистика вашей библиотеки AniMix'
                : 'Живой срез коллекции Shikimori',
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

class ProfileInfoCard extends StatelessWidget {
  const ProfileInfoCard({required this.user, super.key});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final values = <({String label, String value, IconData icon})>[
      (
        label: 'Просмотрено серий',
        value: '${user.episodesWatched}',
        icon: CupertinoIcons.play_rectangle_fill,
      ),
      if (user.isAniMix)
        (
          label: 'Shikimori',
          value: user.shikimoriLinked ? 'Аккаунт подключён' : 'Не подключён',
          icon: CupertinoIcons.link,
        ),
      if (user.name?.trim().isNotEmpty == true)
        (
          label: user.isAniMix ? 'Имя в AniMix' : 'Имя',
          value: user.name!,
          icon: CupertinoIcons.person_fill,
        ),
      if (user.birthOn?.isNotEmpty == true)
        (
          label: 'Дата рождения',
          value: user.birthOn!,
          icon: CupertinoIcons.gift_fill,
        ),
      if (user.joinedAt?.isNotEmpty == true)
        (
          label: user.isAniMix ? 'В AniMix с' : 'На Shikimori с',
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
          AniMixSectionHeader(
            title: 'О пользователе',
            subtitle: user.isAniMix
                ? 'Публичные данные аккаунта AniMix'
                : 'Данные профиля Shikimori',
            icon: CupertinoIcons.person_crop_circle_fill,
          ),
          const SizedBox(height: AniMixSpacing.lg),
          Column(
            children: [
              for (final item in values) ...[
                Container(
                  width: double.infinity,
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
                    children: [
                      Icon(item.icon, size: 17),
                      const SizedBox(width: 11),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              item.label,
                              style: TextStyle(
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurfaceVariant,
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              item.value,
                              overflow: TextOverflow.ellipsis,
                              maxLines: 2,
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                if (item != values.last) const SizedBox(height: 10),
              ],
            ],
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
