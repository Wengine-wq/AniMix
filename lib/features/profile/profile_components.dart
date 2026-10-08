import 'dart:io';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../../core/achievement_service.dart';
import '../../core/animix_theme.dart';
import '../../models/shikimori_user.dart';
import '../../widgets/animix_surface.dart';
import 'achievements_screen.dart';

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
            height: MediaQuery.sizeOf(context).width >= 700 ? 210 : 168,
            margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            clipBehavior: Clip.antiAlias,
            decoration: const BoxDecoration(
              borderRadius: BorderRadius.all(Radius.circular(AniMixRadius.xl)),
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
                if (coverPath != null || user.bannerUrl?.isNotEmpty == true)
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
            left: 32,
            bottom: -42,
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
                        width: 92,
                        height: 92,
                        decoration: BoxDecoration(
                          color: Theme.of(context).scaffoldBackgroundColor,
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: Theme.of(context).scaffoldBackgroundColor,
                            width: 4,
                          ),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x26000000),
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
      // Name sits beside the overlapping avatar, left-aligned like a social
      // profile, instead of a centered stack under the cover.
      Padding(
        padding: const EdgeInsets.fromLTRB(32 + 92 + 14, 10, 20, 0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (!editing)
              Text(
                user.nickname,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 22,
                  height: 1.15,
                  letterSpacing: -.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
            const SizedBox(height: 4),
            _onlineRow(context),
          ],
        ),
      ),
      if (editing) ...[
        const SizedBox(height: 16),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: TextField(
            controller: nameController,
            autofocus: true,
            maxLength: 32,
            textInputAction: TextInputAction.done,
            decoration: const InputDecoration(
              labelText: 'Имя в AniMix',
              hintText: 'Как тебя видят другие',
              counterText: '',
              prefixIcon: Icon(CupertinoIcons.person_fill),
            ),
          ),
        ),
        if (onDeleteAvatar != null)
          Align(
            alignment: Alignment.centerLeft,
            child: Padding(
              padding: const EdgeInsets.only(left: 12, top: 4),
              child: TextButton.icon(
                onPressed: coverBusy ? null : onDeleteAvatar,
                icon: const Icon(CupertinoIcons.delete, size: 16),
                label: const Text('Удалить аватар'),
              ),
            ),
          ),
      ],
      const SizedBox(height: 8),
    ],
  );

  Widget _onlineRow(BuildContext context) => Row(
    children: [
      if (_onlineText(user.lastOnlineAt, isAniMix: user.isAniMix) ==
          'сейчас онлайн') ...[
        Container(
          width: 7,
          height: 7,
          decoration: const BoxDecoration(
            color: CupertinoColors.systemGreen,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 7),
      ],
      Text(
        _onlineText(user.lastOnlineAt, isAniMix: user.isAniMix),
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
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

/// Default cover when the user has none: a quiet tonal wash rather than a
/// loud gradient, so the avatar and name carry the header.
class _ProfileGradient extends StatelessWidget {
  const _ProfileGradient();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color.alphaBlend(
              scheme.primary.withValues(alpha: .18),
              scheme.surfaceContainerHigh,
            ),
            scheme.surfaceContainer,
          ],
        ),
      ),
    );
  }
}

typedef _LibraryStat = ({String label, int value, Color color});

/// Library at a glance: three headline numbers, one proportional bar and a
/// two-column legend. Replaces a tall donut chart that was mostly empty space.
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
    final scheme = Theme.of(context).colorScheme;
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
    final completion = total == 0 ? 0 : (user.watched / total * 100).round();
    return AniMixSurface(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            ownProfile ? 'Медиатека' : 'Медиатека пользователя',
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              _Headline(value: '$total', label: 'в коллекции'),
              _Headline(value: '$completion%', label: 'завершено'),
              _Headline(value: '${user.scores}', label: 'оценено'),
            ],
          ),
          const SizedBox(height: 16),
          Semantics(
            label: 'Всего $total аниме, завершено ${user.watched}',
            child: ClipRRect(
              borderRadius: BorderRadius.circular(99),
              child: SizedBox(
                height: 8,
                child: total == 0
                    ? ColoredBox(color: scheme.surfaceContainerHigh)
                    : Row(
                        children: [
                          for (final stat in stats.where((s) => s.value > 0))
                            Expanded(
                              flex: stat.value,
                              child: Padding(
                                padding: const EdgeInsets.only(right: 2),
                                child: ColoredBox(color: stat.color),
                              ),
                            ),
                        ],
                      ),
              ),
            ),
          ),
          const SizedBox(height: 14),
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 520 ? 3 : 2;
              final width =
                  (constraints.maxWidth - (columns - 1) * 12) / columns;
              return Wrap(
                spacing: 12,
                runSpacing: 10,
                children: [
                  for (final stat in stats)
                    SizedBox(
                      width: width,
                      child: Row(
                        children: [
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(
                              color: stat.color,
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              stat.label,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: scheme.onSurfaceVariant,
                                fontSize: 13,
                              ),
                            ),
                          ),
                          Text(
                            '${stat.value}',
                            style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _Headline extends StatelessWidget {
  const _Headline({required this.value, required this.label});
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: const TextStyle(
            fontSize: 24,
            height: 1.1,
            letterSpacing: -.6,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            fontSize: 12,
          ),
        ),
      ],
    ),
  );
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
                                fontWeight: FontWeight.w700,
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

/// Achievements as a compact strip of earned badges that opens the gallery.
/// Used on the own profile and on other users' public profiles.
class AchievementStrip extends StatelessWidget {
  const AchievementStrip({
    required this.unlocked,
    required this.onTap,
    this.caption,
    super.key,
  });

  final Map<String, DateTime> unlocked;
  final VoidCallback onTap;
  final String? caption;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final earned = [
      for (var i = 0; i < achievements.length; i++)
        if (unlocked.containsKey(achievements[i].id)) i,
    ];
    return AniMixSurface(
      onTap: onTap,
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Достижения',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                ),
              ),
              Text(
                '${earned.length} из ${achievements.length}',
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
              const SizedBox(width: 4),
              Icon(
                CupertinoIcons.chevron_forward,
                size: 15,
                color: scheme.onSurfaceVariant,
              ),
            ],
          ),
          if (caption != null) ...[
            const SizedBox(height: 2),
            Text(
              caption!,
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
            ),
          ],
          const SizedBox(height: 12),
          if (earned.isEmpty)
            Text(
              'Пока ничего не открыто.',
              style: TextStyle(color: scheme.onSurfaceVariant),
            )
          else
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final index in earned.take(12))
                  AchievementIcon(index: index, size: 44),
              ],
            ),
        ],
      ),
    );
  }
}
