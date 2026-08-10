import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_theme.dart';
import '../../core/secure_storage.dart';
import '../../models/shikimori_history.dart';
import '../../models/shikimori_user.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../auth/login_screen.dart';
import 'settings_screen.dart';

final userHistoryProvider = FutureProvider.family
    .autoDispose<List<ShikimoriHistory>, int>((ref, userId) async {
      return ref.watch(apiClientProvider).getUserHistory(userId, limit: 60);
    });

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  Future<void> _refresh(WidgetRef ref) async {
    ref.invalidate(currentUserProvider);
    final user = await ref.read(currentUserProvider.future);
    if (user != null) ref.invalidate(userHistoryProvider(user.id));
  }

  Future<void> _logout(BuildContext context, WidgetRef ref) async {
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('Выйти из аккаунта?'),
        content: const Text(
          'Локальные загрузки сохранятся, но списки перестанут синхронизироваться.',
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Отмена'),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Выйти'),
          ),
        ],
      ),
    );
    if (accepted != true) return;
    await SecureStorage.clear();
    ref.invalidate(isLoggedInProvider);
    ref.invalidate(currentUserProvider);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Профиль'),
        actions: [
          IconButton(
            tooltip: 'Настройки',
            onPressed: () => Navigator.push(
              context,
              CupertinoPageRoute<void>(builder: (_) => const SettingsScreen()),
            ),
            icon: const Icon(CupertinoIcons.gear_alt_fill),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: user.when(
        loading: () =>
            const Center(child: CupertinoActivityIndicator(radius: 15)),
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
            onRefresh: () => _refresh(ref),
            child: CustomScrollView(
              physics: const BouncingScrollPhysics(
                parent: AlwaysScrollableScrollPhysics(),
              ),
              slivers: [
                SliverToBoxAdapter(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 920),
                      child: Column(
                        children: [
                          _ProfileHeader(
                            user: value,
                            coverUrl: history.value
                                ?.map((item) => item.anime?.imageUrl)
                                .whereType<String>()
                                .firstOrNull,
                          ),
                          const SizedBox(height: 24),
                          _StatusSummary(user: value),
                          const SizedBox(height: 24),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: Column(
                              children: [
                                _ProfileInfoCard(user: value),
                                const SizedBox(height: 16),
                                _StatisticsCard(user: value),
                              ],
                            ),
                          ),
                          const SizedBox(height: 30),
                        ],
                      ),
                    ),
                  ),
                ),
                SliverToBoxAdapter(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 920),
                      child: history.when(
                        loading: () => const Padding(
                          padding: EdgeInsets.all(28),
                          child: CupertinoActivityIndicator(),
                        ),
                        error: (_, _) => Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                          child: AniMixSurface(
                            padding: const EdgeInsets.all(18),
                            child: Row(
                              children: [
                                const Expanded(
                                  child: Text(
                                    'Не удалось загрузить активность',
                                    style: TextStyle(
                                      color: AniMixTheme.subtleText,
                                    ),
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
                                  _ActivityGraph(items: items),
                                  const SizedBox(height: 28),
                                  _RecentActivity(items: items),
                                ],
                              ),
                      ),
                    ),
                  ),
                ),
                SliverToBoxAdapter(
                  child: Center(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(20, 30, 20, 48),
                      child: OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFFFF5C66),
                          side: const BorderSide(color: Color(0x66FF5C66)),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 28,
                            vertical: 15,
                          ),
                        ),
                        onPressed: () => _logout(context, ref),
                        icon: const Icon(CupertinoIcons.square_arrow_right),
                        label: const Text('Выйти из аккаунта'),
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

class _ActivityGraph extends StatelessWidget {
  const _ActivityGraph({required this.items});
  final List<ShikimoriHistory> items;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final counts = <String, int>{};
    for (final item in items) {
      final date = DateTime.tryParse(item.createdAt)?.toLocal();
      if (date == null) continue;
      final key = '${date.year}-${date.month}-${date.day}';
      counts[key] = (counts[key] ?? 0) + 1;
    }
    final days = List.generate(56, (index) {
      final date = DateTime(
        now.year,
        now.month,
        now.day,
      ).subtract(Duration(days: 55 - index));
      return (date, counts['${date.year}-${date.month}-${date.day}'] ?? 0);
    });
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: AniMixSurface(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const AniMixSectionHeader(
              title: 'Активность',
              subtitle: 'Последние восемь недель',
              icon: CupertinoIcons.chart_bar_square_fill,
            ),
            const SizedBox(height: 18),
            LayoutBuilder(
              builder: (context, constraints) {
                const spacing = 5.0;
                final size = ((constraints.maxWidth - spacing * 13) / 14).clamp(
                  9.0,
                  19.0,
                );
                final accent = Theme.of(context).colorScheme.primary;
                return Wrap(
                  spacing: spacing,
                  runSpacing: spacing,
                  children: days.map((day) {
                    final alpha = day.$2 == 0
                        ? .08
                        : day.$2 == 1
                        ? .28
                        : day.$2 == 2
                        ? .55
                        : .9;
                    return Tooltip(
                      message:
                          '${day.$1.day.toString().padLeft(2, '0')}.${day.$1.month.toString().padLeft(2, '0')}: ${day.$2}',
                      child: Container(
                        width: size,
                        height: size,
                        decoration: BoxDecoration(
                          color: day.$2 == 0
                              ? Colors.white.withValues(alpha: alpha)
                              : accent.withValues(alpha: alpha),
                          borderRadius: BorderRadius.circular(size * .28),
                        ),
                      ),
                    );
                  }).toList(),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({required this.user, required this.coverUrl});
  final ShikimoriUser user;
  final String? coverUrl;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.bottomCenter,
        children: [
          Container(
            width: double.infinity,
            height: 230,
            clipBehavior: Clip.antiAlias,
            decoration: const BoxDecoration(
              borderRadius: BorderRadius.only(
                bottomLeft: Radius.circular(30),
                bottomRight: Radius.circular(30),
              ),
            ),
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (coverUrl?.isNotEmpty == true)
                  CachedNetworkImage(
                    imageUrl: coverUrl!,
                    fit: BoxFit.cover,
                    alignment: Alignment.topCenter,
                    errorWidget: (_, _, _) => const _ProfileGradient(),
                  )
                else
                  const _ProfileGradient(),
                const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Colors.transparent, Color(0x8A000000)],
                    ),
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            bottom: -52,
            child: Container(
              width: 108,
              height: 108,
              decoration: BoxDecoration(
                color: Theme.of(context).scaffoldBackgroundColor,
                shape: BoxShape.circle,
                border: Border.all(
                  color: Theme.of(context).scaffoldBackgroundColor,
                  width: 5,
                ),
                boxShadow: const [
                  BoxShadow(
                    color: Color(0x52000000),
                    blurRadius: 18,
                    offset: Offset(0, 7),
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
                        size: 82,
                        color: Colors.white38,
                      ),
                    )
                  : const Icon(
                      CupertinoIcons.person_crop_circle_fill,
                      size: 82,
                      color: Colors.white38,
                    ),
            ),
          ),
        ],
      ),
      const SizedBox(height: 64),
      Text(
        user.nickname,
        style: const TextStyle(
          fontSize: 28,
          letterSpacing: -.6,
          fontWeight: FontWeight.w800,
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
          const SizedBox(width: 6),
          Text(
            _onlineText(user.lastOnlineAt),
            style: const TextStyle(
              color: AniMixTheme.subtleText,
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
    final now = DateTime.now();
    final difference = now.difference(date);
    if (difference.inMinutes < 5) return 'сейчас онлайн';
    if (difference.inHours < 1) return '${difference.inMinutes} мин. назад';
    if (difference.inDays < 1) return '${difference.inHours} ч. назад';
    return '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
  }
}

class _ProfileGradient extends StatelessWidget {
  const _ProfileGradient();

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Theme.of(context).colorScheme.primary.withValues(alpha: .9),
          const Color(0xFF7C3AED).withValues(alpha: .72),
          const Color(0xFF4338CA).withValues(alpha: .58),
        ],
      ),
    ),
  );
}

class _StatusSummary extends StatelessWidget {
  const _StatusSummary({required this.user});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final stats = [
      ('Смотрю', user.watching, CupertinoColors.systemBlue),
      ('В планах', user.planned, CupertinoColors.systemGrey),
      ('Просмотрено', user.watched, CupertinoColors.systemGreen),
      ('Брошено', user.dropped, CupertinoColors.systemRed),
      ('Пересмотрено', user.rewatched, CupertinoColors.systemPurple),
    ].where((item) => item.$2 > 0).toList();
    return SizedBox(
      height: 84,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: stats.length,
        separatorBuilder: (_, _) => const SizedBox(width: 10),
        itemBuilder: (context, index) {
          final item = stats[index];
          return Container(
            width: 126,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: item.$3.withValues(alpha: .12),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${item.$2}',
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  item.$1,
                  maxLines: 1,
                  style: const TextStyle(
                    color: AniMixTheme.subtleText,
                    fontSize: 11,
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

class _ProfileInfoCard extends StatelessWidget {
  const _ProfileInfoCard({required this.user});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final values = <(String, String)>[
      if (user.name?.trim().isNotEmpty == true) ('Имя', user.name!),
      if (user.birthOn?.isNotEmpty == true) ('Дата рождения', user.birthOn!),
      if (user.joinedAt?.isNotEmpty == true)
        ('На Shikimori с', _shortDate(user.joinedAt!)),
      ('Оценок', '${user.scores}'),
    ];
    return AniMixSurface(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AniMixSectionHeader(
            title: 'Профиль',
            subtitle: 'Личная информация',
            icon: Icons.badge_outlined,
          ),
          const SizedBox(height: 20),
          LayoutBuilder(
            builder: (context, constraints) {
              final width = (constraints.maxWidth - 14) / 2;
              return Wrap(
                spacing: 14,
                runSpacing: 16,
                children: values
                    .map(
                      (item) => SizedBox(
                        width: width,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              item.$1,
                              style: const TextStyle(
                                color: AniMixTheme.subtleText,
                                fontSize: 11,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              item.$2,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                    )
                    .toList(),
              );
            },
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

class _StatisticsCard extends StatelessWidget {
  const _StatisticsCard({required this.user});
  final ShikimoriUser user;

  @override
  Widget build(BuildContext context) {
    final stats = [
      ('Смотрю', user.watching, CupertinoColors.systemBlue),
      ('В планах', user.planned, CupertinoColors.systemGrey),
      ('Просмотрено', user.watched, CupertinoColors.systemGreen),
      ('Брошено', user.dropped, CupertinoColors.systemRed),
      ('Пересмотрено', user.rewatched, CupertinoColors.systemPurple),
    ];
    final total = stats.fold<int>(0, (sum, item) => sum + item.$2);
    return AniMixSurface(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AniMixSectionHeader(
            title: 'Статистика',
            subtitle: 'Вся медиатека',
            icon: CupertinoIcons.chart_pie_fill,
          ),
          const SizedBox(height: 20),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: SizedBox(
              height: 12,
              child: Row(
                children: [
                  for (final item in stats)
                    if (item.$2 > 0)
                      Expanded(
                        flex: item.$2,
                        child: ColoredBox(color: item.$3),
                      ),
                  if (total == 0)
                    const Expanded(child: ColoredBox(color: Colors.white12)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 18),
          Wrap(
            spacing: 18,
            runSpacing: 12,
            children: stats
                .where((item) => item.$2 > 0)
                .map(
                  (item) => Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 8,
                        height: 8,
                        decoration: BoxDecoration(
                          color: item.$3,
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 7),
                      Text(
                        '${item.$1} ${item.$2}',
                        style: const TextStyle(
                          color: AniMixTheme.subtleText,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                )
                .toList(),
          ),
        ],
      ),
    );
  }
}

class _RecentActivity extends StatelessWidget {
  const _RecentActivity({required this.items});
  final List<ShikimoriHistory> items;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const AniMixSectionHeader(
          title: 'Недавняя активность',
          subtitle: 'Последние изменения',
          icon: CupertinoIcons.time_solid,
        ),
        const SizedBox(height: 14),
        for (final item in items.take(12)) ...[
          _HistoryRow(item: item),
          const SizedBox(height: 9),
        ],
      ],
    ),
  );
}

class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.item});
  final ShikimoriHistory item;

  @override
  Widget build(BuildContext context) {
    final anime = item.anime;
    return AniMixSurface(
      radius: 18,
      onTap: anime == null
          ? null
          : () => Navigator.push(
              context,
              CupertinoPageRoute<void>(
                builder: (_) => AnimeDetailScreen(animeId: anime.id),
              ),
            ),
      padding: const EdgeInsets.all(10),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: SizedBox(
              width: 52,
              height: 72,
              child: anime == null
                  ? ColoredBox(
                      color: Theme.of(context).colorScheme.surfaceContainerHigh,
                      child: const Icon(CupertinoIcons.sparkles),
                    )
                  : SmartAnimePoster(
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
                  item.description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                if (anime != null) ...[
                  const SizedBox(height: 5),
                  Text(
                    anime.russian ?? anime.name ?? '',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AniMixTheme.subtleText,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const Icon(
            CupertinoIcons.chevron_right,
            color: Colors.white24,
            size: 14,
          ),
        ],
      ),
    );
  }
}
