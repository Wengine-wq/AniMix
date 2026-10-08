import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/animix_auth_service.dart';
import '../../core/achievement_service.dart';
import '../../core/animix_theme.dart';
import '../../core/app_logging.dart';
import '../../core/profile_media_codec.dart';
import '../../core/shikimori_library_import.dart';
import '../../models/shikimori_anime.dart';
import '../../models/shikimori_history.dart';
import '../../models/shikimori_user.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/animix_surface.dart';
import '../../widgets/animix_skeletons.dart';
import '../../widgets/smart_anime_poster.dart';
import '../anime_detail/anime_detail_screen.dart';
import '../auth/login_screen.dart';
import '../watch/watch_storage.dart';
import 'profile_cover_storage.dart';
import 'friends_screen.dart';
import 'achievements_screen.dart';
import 'profile_components.dart';
import 'settings_screen.dart';

final userHistoryProvider = FutureProvider.family
    .autoDispose<List<ShikimoriHistory>, int>((ref, userId) async {
      if (userId <= 0) return const <ShikimoriHistory>[];
      return ref.watch(apiClientProvider).getUserHistory(userId, limit: 60);
    });

final profileUsageProvider = FutureProvider.autoDispose<Map<String, dynamic>>((
  ref,
) async {
  ref.watch(userDataRevisionProvider);
  unawaited(WatchStorage.syncPendingUsageEvents());
  final service = ref.read(animixAuthServiceProvider);
  final remote = await service.getUsageStats();
  final local = await WatchStorage.getLocalUsageStats();
  final pendingActivity = await WatchStorage.getPendingUsageActivity();
  final usage = remote ?? local;
  final currentUser = await ref.read(currentUserProvider.future);
  final library = await service.getLibraryEntries();
  var rows = library ?? const <Map<String, dynamic>>[];
  if (rows.isEmpty && currentUser?.shikimoriLinked == true) {
    final linkedId = int.tryParse(currentUser?.shikimoriUserId ?? '') ?? 0;
    if (linkedId > 0) {
      try {
        final rates = await ref
            .read(apiClientProvider)
            .getUserAnimeRates(linkedId);
        rows = rates
            .map(normalizeShikimoriAnimeRate)
            .whereType<Map<String, dynamic>>()
            .toList(growable: false);
      } catch (error, stackTrace) {
        AppLogBuffer.instance.recordError(
          error,
          stackTrace,
          source: 'Profile watch statistics',
          context: 'Не удалось прочитать связанную библиотеку Shikimori',
        );
      }
    }
  }
  await AchievementService.instance.reconcileLibraryRows(rows);
  final metadataById = <int, ShikimoriAnime>{};
  final completedIds = rows
      .where((row) => row['status'] == 'completed')
      .map((row) => _usageCount(row['shikimori_id']))
      .where((id) => id > 0)
      .toSet()
      .take(250)
      .toList();
  final api = ref.read(apiClientProvider);
  for (var start = 0; start < completedIds.length; start += 50) {
    final ids = completedIds.skip(start).take(50).toList();
    try {
      final anime = await api.getAnimes(
        limit: ids.length,
        filters: {'ids': ids.join(',')},
      );
      for (final item in anime) {
        metadataById[item.id] = item;
      }
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Profile watch statistics',
        context: 'Не удалось получить число эпизодов завершённых тайтлов',
      );
    }
  }
  var libraryEpisodes = 0;
  var estimatedLibrarySeconds = 0;
  for (final row in rows) {
    final id = _usageCount(row['shikimori_id']);
    final status = row['status']?.toString();
    final anime = metadataById[id];
    var episodes = _usageCount(row['episodes_watched']);
    if (episodes == 0 && status == 'completed') {
      episodes = (anime?.episodes ?? 0) > 0 ? anime!.episodes! : 12;
    }
    if (episodes <= 0) continue;
    libraryEpisodes += episodes;
    estimatedLibrarySeconds += (episodes * (anime?.duration ?? 24) * 60)
        .toInt();
  }
  final remoteEpisodes = _usageCount(usage['episodes_watched']);
  final localEpisodes = _usageCount(local['episodes_watched']);
  final estimatedEpisodes = math.max(
    libraryEpisodes,
    math.max(
      currentUser?.episodesWatched ?? 0,
      math.max(remoteEpisodes, localEpisodes),
    ),
  );
  final measuredSeconds = math.max(
    _usageCount(usage['watch_seconds']),
    _usageCount(local['watch_seconds']),
  );
  final episodeBaseline = math.max(
    libraryEpisodes,
    currentUser?.episodesWatched ?? 0,
  );
  return {
    ...usage,
    'pending_activity': currentUser?.isAniMix == true
        ? pendingActivity
        : const <Map<String, dynamic>>[],
    'episodes_watched': estimatedEpisodes,
    // Before session timing existed, library progress retained episode counts
    // but no wall-clock duration. Estimate that historical watch time from
    // anime episode counts and duration, then keep any larger measured total.
    'watch_seconds': math.max(
      measuredSeconds,
      math.max(estimatedLibrarySeconds, episodeBaseline * 24 * 60),
    ),
  };
});

int _usageCount(Object? value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  String? _coverPath;
  bool _coverBusy = false;
  bool _editingProfile = false;
  bool _profileSaveBusy = false;
  final TextEditingController _displayNameController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadCover();
  }

  @override
  void dispose() {
    _displayNameController.dispose();
    super.dispose();
  }

  Future<void> _loadCover() async {
    final path = await ProfileCoverStorage.currentPath();
    if (mounted) {
      setState(() => _coverPath = path);
    }
  }

  Future<void> _refresh() async {
    ref.read(animixAuthServiceProvider).invalidateReadCache();
    try {
      final service = ref.read(animixAuthServiceProvider);
      final fresh = await service.getCurrentUser(allowCachedFallback: false);
      if (fresh == null) {
        throw const FormatException('AniMix profile refresh failed.');
      }
      if (!mounted) return;
      ref.read(userDataRevisionProvider.notifier).bump();
      ref.invalidate(currentUserProvider);
      final user = await ref.read(currentUserProvider.future);
      if (!mounted) return;
      if (user != null) {
        final historyUserId = user.id;
        if (historyUserId > 0) {
          ref.invalidate(userHistoryProvider(historyUserId));
        }
      }
      ref.invalidate(profileUsageProvider);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Profile refresh',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Профиль пока недоступен. Попробуйте ещё раз.'),
          ),
        );
      }
    }
  }

  Future<void> _chooseCover() async {
    if (_coverBusy) return;
    setState(() => _coverBusy = true);
    final userFuture = ref.read(currentUserProvider.future);
    try {
      final user = await userFuture;
      if (!mounted) return;
      if (user?.isAniMix == true) {
        await _pickAndUploadAniMixMedia(AniMixProfileMediaKind.banner);
        return;
      }
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
    final userFuture = ref.read(currentUserProvider.future);
    final service = ref.read(animixAuthServiceProvider);
    try {
      final user = await userFuture;
      if (!mounted) return;
      if (user?.isAniMix == true) {
        final updated = await service.deleteProfileMedia(
          AniMixProfileMediaKind.banner,
        );
        if (updated == null) throw StateError('AniMix banner reset failed');
        await ProfileCoverStorage.clearAniMixMedia(isBanner: true);
        if (!mounted) return;
        ref.read(userDataRevisionProvider.notifier).bump();
        ref.invalidate(currentUserProvider);
        return;
      }
      await ProfileCoverStorage.clear();
      if (mounted) setState(() => _coverPath = null);
    } finally {
      if (mounted) setState(() => _coverBusy = false);
    }
  }

  Future<void> _pickAndUploadAniMixMedia(AniMixProfileMediaKind kind) async {
    final service = ref.read(animixAuthServiceProvider);
    final selected = await ProfileCoverStorage.pickImage();
    if (selected == null) return;
    final bytes = await ProfileMediaCodec.encodeForUpload(
      await selected.readAsBytes(),
      isBanner: kind == AniMixProfileMediaKind.banner,
    );
    final updated = await service.uploadProfileMedia(
      kind: kind,
      bytes: bytes,
      contentType: 'image/jpeg',
    );
    if (updated == null) throw StateError('AniMix ${kind.name} upload failed');
    await ProfileCoverStorage.clearAniMixMedia(
      isBanner: kind == AniMixProfileMediaKind.banner,
    );
    if (!mounted) return;
    ref.read(userDataRevisionProvider.notifier).bump();
    ref.invalidate(currentUserProvider);
    await ref.read(currentUserProvider.future);
  }

  Future<void> _deleteAniMixMedia(AniMixProfileMediaKind kind) async {
    if (_coverBusy) return;
    setState(() => _coverBusy = true);
    final service = ref.read(animixAuthServiceProvider);
    try {
      final updated = await service.deleteProfileMedia(kind);
      if (updated == null) {
        throw StateError('AniMix ${kind.name} delete failed');
      }
      await ProfileCoverStorage.clearAniMixMedia(
        isBanner: kind == AniMixProfileMediaKind.banner,
      );
      if (!mounted) return;
      ref.read(userDataRevisionProvider.notifier).bump();
      ref.invalidate(currentUserProvider);
      await ref.read(currentUserProvider.future);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix profile media',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось удалить изображение.')),
        );
      }
    } finally {
      if (mounted) setState(() => _coverBusy = false);
    }
  }

  Future<void> _chooseAvatar() async {
    if (_coverBusy) return;
    final userFuture = ref.read(currentUserProvider.future);
    final user = await userFuture;
    if (!mounted) return;
    if (user?.isAniMix != true) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Аватар Shikimori изменяется в Shikimori.'),
          ),
        );
      }
      return;
    }
    setState(() => _coverBusy = true);
    try {
      await _pickAndUploadAniMixMedia(AniMixProfileMediaKind.avatar);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix avatar',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Не удалось обновить аватар. Проверьте сеть и повторите.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _coverBusy = false);
    }
  }

  void _startProfileEditing(ShikimoriUser user) {
    _displayNameController.text = user.nickname;
    setState(() => _editingProfile = true);
  }

  void _cancelProfileEditing() {
    if (_profileSaveBusy) return;
    setState(() => _editingProfile = false);
  }

  Future<void> _saveProfile(ShikimoriUser user) async {
    final name = _displayNameController.text.trim();
    if (name.isEmpty || name.length > 32 || _profileSaveBusy) return;
    if (name == user.nickname) {
      setState(() => _editingProfile = false);
      return;
    }
    setState(() => _profileSaveBusy = true);
    final service = ref.read(animixAuthServiceProvider);
    try {
      final updated = await service.updateProfile(displayName: name);
      if (updated == null) throw StateError('AniMix profile update failed');
      if (!mounted) return;
      ref.read(userDataRevisionProvider.notifier).bump();
      ref.invalidate(currentUserProvider);
      await ref.read(currentUserProvider.future);
      if (mounted) setState(() => _editingProfile = false);
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'AniMix profile edit',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Не удалось сохранить профиль.')),
        );
      }
    } finally {
      if (mounted) setState(() => _profileSaveBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final loadedUser = user.value;
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Профиль'),
        actions: [
          if (loadedUser?.isAniMix == true)
            if (_editingProfile) ...[
              IconButton(
                tooltip: 'Отменить',
                onPressed: _profileSaveBusy ? null : _cancelProfileEditing,
                icon: const Icon(CupertinoIcons.xmark_circle_fill),
              ),
              IconButton(
                tooltip: 'Сохранить',
                onPressed: _profileSaveBusy
                    ? null
                    : () => _saveProfile(loadedUser!),
                icon: _profileSaveBusy
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CupertinoActivityIndicator(),
                      )
                    : const Icon(CupertinoIcons.check_mark_circled_solid),
              ),
            ] else
              IconButton(
                tooltip: 'Редактировать профиль',
                onPressed: _coverBusy
                    ? null
                    : () => _startProfileEditing(loadedUser!),
                icon: const Icon(CupertinoIcons.pencil_circle_fill),
              ),
          if (loadedUser?.isAniMix == true)
            IconButton(
              tooltip: 'Друзья',
              onPressed: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(builder: (_) => const FriendsScreen()),
              ),
              icon: const Icon(CupertinoIcons.person_2_fill),
            ),
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
          message: 'Профиль временно недоступен. Проверьте сеть и повторите.',
          actionLabel: 'Повторить',
          onAction: () => ref.invalidate(currentUserProvider),
        ),
        data: (value) {
          if (value == null) {
            return AniMixEmptyState(
              icon: CupertinoIcons.person_crop_circle,
              title: 'Профиль Shikimori',
              message:
                  'Войдите, чтобы сохранить прогресс, оценки и собственный профиль.',
              actionLabel: 'Войти',
              onAction: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(builder: (_) => const LoginScreen()),
              ),
            );
          }
          final historyUserId = value.id;
          final history = ref.watch(userHistoryProvider(historyUserId));
          final usage = ref.watch(profileUsageProvider);
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
                          ProfileHeader(
                            user: value,
                            coverPath: value.isAniMix ? null : _coverPath,
                            avatarPath: null,
                            coverBusy: _coverBusy,
                            editing: _editingProfile,
                            nameController: _displayNameController,
                            onChangeCover: _chooseCover,
                            onChangeAvatar: _chooseAvatar,
                            onDeleteCover: value.bannerUrl?.isNotEmpty == true
                                ? _resetCover
                                : null,
                            onDeleteAvatar: value.avatarUrl?.isNotEmpty == true
                                ? () => _deleteAniMixMedia(
                                    AniMixProfileMediaKind.avatar,
                                  )
                                : null,
                          ),
                          const SizedBox(height: AniMixSpacing.xl),
                          Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: AniMixLayout.pageInset,
                            ),
                            child: Column(
                              children: [
                                ProfileLibraryOverview(user: value),
                                const SizedBox(height: AniMixSpacing.lg),
                                usage.when(
                                  data: (stats) =>
                                      _ProfileUsageCard(stats: stats),
                                  loading: () =>
                                      const AniMixProfileActivitySkeleton(),
                                  error: (_, _) => const SizedBox.shrink(),
                                ),
                                const SizedBox(height: AniMixSpacing.lg),
                                ValueListenableBuilder<int>(
                                  valueListenable:
                                      AchievementService.instance.revision,
                                  builder: (context, _, _) =>
                                      FutureBuilder<Map<String, DateTime>>(
                                        future: AchievementService.instance
                                            .unlockedSnapshot(),
                                        builder: (context, snapshot) => AniMixSurface(
                                          onTap: () => Navigator.push(
                                            context,
                                            CupertinoPageRoute<void>(
                                              builder: (_) =>
                                                  const AchievementsScreen(),
                                            ),
                                          ),
                                          child: Row(
                                            children: [
                                              const AchievementIcon(
                                                index: 4,
                                                size: 56,
                                              ),
                                              const SizedBox(width: 12),
                                              Expanded(
                                                child: Column(
                                                  crossAxisAlignment:
                                                      CrossAxisAlignment.start,
                                                  children: [
                                                    const Text(
                                                      'Достижения',
                                                      style: TextStyle(
                                                        fontWeight:
                                                            FontWeight.w800,
                                                        fontSize: 17,
                                                      ),
                                                    ),
                                                    Text(
                                                      '${snapshot.data?.length ?? 0} из ${achievements.length} открыто · только на этом устройстве',
                                                      style: TextStyle(
                                                        color: Theme.of(context)
                                                            .colorScheme
                                                            .onSurfaceVariant,
                                                        fontSize: 12,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                              ),
                                              const Icon(
                                                CupertinoIcons.chevron_right,
                                                size: 16,
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                ),
                                const SizedBox(height: AniMixSpacing.lg),
                                ProfileInfoCard(user: value),
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

class _ProfileUsageCard extends StatelessWidget {
  const _ProfileUsageCard({required this.stats});
  final Map<String, dynamic> stats;

  @override
  Widget build(BuildContext context) {
    final rawActivity = stats['activity'];
    final activity = rawActivity is List
        ? rawActivity
              .whereType<Map>()
              .map((item) => Map<String, dynamic>.from(item))
              .toList()
        : <Map<String, dynamic>>[];
    final dailySeconds = <DateTime, int>{};
    final activeDays = <DateTime>{};
    for (final item in activity) {
      final rawTime = item['created_at'];
      final timestamp = rawTime is num
          ? rawTime.toInt()
          : int.tryParse('$rawTime') ?? 0;
      final date = DateTime.fromMillisecondsSinceEpoch(
        timestamp > 100000000000 ? timestamp : timestamp * 1000,
      ).toLocal();
      final day = DateTime(date.year, date.month, date.day);
      if (timestamp <= 0) continue;
      activeDays.add(day);
      if (item['action'] != 'watch_seconds') continue;
      final rawMetadata = item['metadata'];
      final metadata = rawMetadata is Map
          ? rawMetadata
          : const <String, dynamic>{};
      final seconds = metadata['seconds'] is num
          ? (metadata['seconds'] as num).toInt()
          : int.tryParse('${metadata['seconds'] ?? 0}') ?? 0;
      dailySeconds[day] = (dailySeconds[day] ?? 0) + seconds;
    }
    // Queued offline events belong to this AniMix account and count even
    // before the server has acknowledged them.
    final pendingActivity = stats['pending_activity'];
    if (pendingActivity is List) {
      for (final raw in pendingActivity.whereType<Map>()) {
        final timestamp = _usageInt(raw['created_at']);
        if (timestamp <= 0) continue;
        final date = DateTime.fromMillisecondsSinceEpoch(
          timestamp > 100000000000 ? timestamp : timestamp * 1000,
        ).toLocal();
        final day = DateTime(date.year, date.month, date.day);
        activeDays.add(day);
        if (raw['action'] != 'watch_seconds') continue;
        final metadata = raw['metadata'];
        final seconds = metadata is Map ? _usageInt(metadata['seconds']) : 0;
        if (seconds <= 0) continue;
        dailySeconds[day] = (dailySeconds[day] ?? 0) + seconds;
      }
    }
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final todayActive = activeDays.contains(today);
    var streak = 0;
    var cursor = todayActive ? today : today.subtract(const Duration(days: 1));
    while (activeDays.contains(cursor)) {
      streak++;
      cursor = cursor.subtract(const Duration(days: 1));
    }
    final latestActivityDay = activeDays.fold<DateTime?>(
      null,
      (latest, day) => latest == null || day.isAfter(latest) ? day : latest,
    );
    final lastWatchAt = stats['last_watch_at'];
    final lastWatchTimestamp = lastWatchAt is num
        ? lastWatchAt.toInt()
        : int.tryParse('$lastWatchAt') ?? 0;
    final lastWatchDay = lastWatchTimestamp > 0
        ? DateTime.fromMillisecondsSinceEpoch(
            lastWatchTimestamp > 100000000000
                ? lastWatchTimestamp
                : lastWatchTimestamp * 1000,
          ).toLocal()
        : null;
    final latestDay =
        latestActivityDay ??
        (lastWatchDay == null
            ? null
            : DateTime(
                lastWatchDay.year,
                lastWatchDay.month,
                lastWatchDay.day,
              ));
    final afkDays = latestDay == null
        ? null
        : today.difference(latestDay).inDays;
    final watchSeconds = _usageInt(stats['watch_seconds']);
    final episodes = _usageInt(stats['episodes_watched']);
    final days = List.generate(
      14,
      (index) => today.subtract(Duration(days: 13 - index)),
    );
    final maxSeconds = days.fold<int>(
      1,
      (max, day) => math.max(max, dailySeconds[day] ?? 0),
    );

    return AniMixSurface(
      elevated: true,
      padding: const EdgeInsets.all(AniMixSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AniMixSectionHeader(
            title: 'Статистика просмотра',
            subtitle: 'Серии из библиотеки; часы рассчитаны по хронометражу',
            icon: CupertinoIcons.play_rectangle_fill,
          ),
          const SizedBox(height: AniMixSpacing.lg),
          Row(
            children: [
              _UsageValue(
                value: _usageHours(watchSeconds),
                label: 'часов контента ≈',
              ),
              _UsageValue(value: '$episodes', label: 'серий просмотрено'),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              _UsageValue(value: '$streak', label: 'дней подряд'),
              _UsageValue(
                value: afkDays?.toString() ?? '—',
                label: 'дней без активности',
              ),
            ],
          ),
          const SizedBox(height: AniMixSpacing.lg),
          SizedBox(
            height: 48,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                for (final day in days)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: Tooltip(
                        message:
                            '${day.day}.${day.month}: '
                            '${activeDays.contains(day) ? 'активность AniMix, ' : ''}'
                            '${_usageHours(dailySeconds[day] ?? 0)} ч просмотра',
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: !activeDays.contains(day)
                                ? Theme.of(
                                    context,
                                  ).colorScheme.surfaceContainerHighest
                                : Theme.of(
                                    context,
                                  ).colorScheme.primary.withValues(
                                    alpha:
                                        .3 +
                                        .7 *
                                            ((dailySeconds[day] ?? 0) /
                                                    maxSeconds)
                                                .clamp(0, 1),
                                  ),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: SizedBox(
                            height: !activeDays.contains(day)
                                ? 5
                                : 9 +
                                      39 *
                                          ((dailySeconds[day] ?? 0) /
                                                  maxSeconds)
                                              .clamp(0, 1),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'Просмотр по дням · последние 14 дней',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  static int _usageInt(Object? value) =>
      value is num ? value.toInt() : int.tryParse('$value') ?? 0;

  static String _usageHours(int seconds) =>
      (seconds / 3600).toStringAsFixed(seconds >= 36000 ? 0 : 1);
}

class _UsageValue extends StatelessWidget {
  const _UsageValue({required this.value, required this.label});
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: Theme.of(
            context,
          ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
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
