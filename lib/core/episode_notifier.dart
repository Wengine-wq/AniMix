import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' show DartPluginRegistrant;

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:workmanager/workmanager.dart';

import '../models/shikimori_anime.dart';
import 'app_logging.dart';
import 'config.dart';

/// Android runs this in a background isolate about once an hour.
@pragma('vm:entry-point')
void episodeCheckDispatcher() {
  Workmanager().executeTask((task, _) async {
    WidgetsFlutterBinding.ensureInitialized();
    DartPluginRegistrant.ensureInitialized();
    await EpisodeNotifier.instance.check(force: true);
    return true;
  });
}

/// A title the viewer follows and the last episode count we told them about.
class FollowedOngoing {
  const FollowedOngoing({
    required this.id,
    required this.title,
    required this.aired,
  });

  final int id;
  final String title;
  final int aired;

  Map<String, Object> toJson() => {'title': title, 'aired': aired};
}

/// The result of comparing fresh Shikimori data with what we last saw.
class NewEpisode {
  const NewEpisode({
    required this.id,
    required this.title,
    required this.from,
    required this.to,
  });

  final int id;
  final String title;
  final int from;
  final int to;

  String get body => to - from == 1
      ? 'Вышла $to серия. Можно смотреть!'
      : 'Вышли серии ${from + 1}–$to. Можно смотреть!';
}

/// Notifies about new episodes of ongoing titles in the «Смотрю» list.
///
/// The app keeps a snapshot of followed ongoings (id, title, aired count);
/// the check only needs the public Shikimori API, so it works from the
/// Android background task without the account session.
class EpisodeNotifier {
  EpisodeNotifier._();

  static final EpisodeNotifier instance = EpisodeNotifier._();

  static const _followedKey = 'episode_notifier_followed_v1';
  static const _lastCheckKey = 'episode_notifier_last_check_v1';
  static const enabledKey = 'episode_notifier_enabled_v1';
  static const _taskName = 'animix-new-episodes';
  static const _checkEvery = Duration(minutes: 45);

  final _plugin = FlutterLocalNotificationsPlugin();
  bool _ready = false;
  Timer? _foregroundTimer;
  AppLifecycleListener? _lifecycle;
  void Function(int animeId)? _onOpen;

  static bool get supported =>
      !kIsWeb &&
      (Platform.isAndroid || Platform.isWindows || Platform.isIOS) &&
      !Platform.environment.containsKey('FLUTTER_TEST');

  /// Pure comparison, kept separate so it is easy to test.
  static List<NewEpisode> diff(
    Map<int, FollowedOngoing> followed,
    Iterable<ShikimoriAnime> fresh,
  ) {
    final result = <NewEpisode>[];
    for (final anime in fresh) {
      final known = followed[anime.id];
      final aired = anime.episodesAired ?? 0;
      if (known == null || aired <= known.aired) continue;
      result.add(
        NewEpisode(
          id: anime.id,
          title: known.title,
          from: known.aired,
          to: aired,
        ),
      );
    }
    return result;
  }

  Future<bool> _init() async {
    if (_ready) return true;
    if (!supported) return false;
    try {
      await _plugin.initialize(
        settings: const InitializationSettings(
          android: AndroidInitializationSettings('@drawable/ic_stat_animix'),
          iOS: DarwinInitializationSettings(
            requestAlertPermission: false,
            requestBadgePermission: false,
            requestSoundPermission: false,
          ),
          windows: WindowsInitializationSettings(
            appName: 'AniMix',
            appUserModelId: 'AniMix.AniMix.Desktop',
            guid: '6f2b9d1e-3c4a-4f7e-9b8d-2a1c5e7f9d30',
          ),
        ),
        onDidReceiveNotificationResponse: (response) {
          final id = int.tryParse(response.payload ?? '') ?? 0;
          if (id > 0) _onOpen?.call(id);
        },
      );
      _ready = true;
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Уведомления о сериях',
        context: 'Не удалось инициализировать уведомления',
      );
    }
    return _ready;
  }

  /// Called once from the running app: wires taps, background checks and a
  /// calm foreground timer.
  Future<void> start({required void Function(int animeId) onOpen}) async {
    _onOpen = onOpen;
    if (!await _init()) return;
    final launch = await _plugin.getNotificationAppLaunchDetails();
    final payload = launch?.didNotificationLaunchApp == true
        ? int.tryParse(launch?.notificationResponse?.payload ?? '')
        : null;
    if (payload != null && payload > 0) {
      scheduleMicrotask(() => onOpen(payload));
    }
    await _syncBackground();
    _foregroundTimer ??= Timer.periodic(_checkEvery, (_) => check());
    _lifecycle ??= AppLifecycleListener(onResume: () => unawaited(check()));
  }

  Future<bool> isEnabled() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(enabledKey) ?? true;
  }

  Future<void> setEnabled(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(enabledKey, value);
    if (value) await requestPermission();
    await _syncBackground();
  }

  Future<void> requestPermission() async {
    if (!await _init()) return;
    await _plugin
        .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin
        >()
        ?.requestNotificationsPermission();
    await _plugin
        .resolvePlatformSpecificImplementation<
          IOSFlutterLocalNotificationsPlugin
        >()
        ?.requestPermissions(alert: true, badge: true, sound: true);
  }

  Future<void> _syncBackground() async {
    if (kIsWeb || !Platform.isAndroid) return;
    try {
      await Workmanager().initialize(episodeCheckDispatcher);
      if (await isEnabled()) {
        await Workmanager().registerPeriodicTask(
          _taskName,
          _taskName,
          frequency: const Duration(hours: 1),
          constraints: Constraints(networkType: NetworkType.connected),
          existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
        );
      } else {
        await Workmanager().cancelByUniqueName(_taskName);
      }
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Уведомления о сериях',
        context: 'Фоновая проверка не запланирована',
      );
    }
  }

  Future<Map<int, FollowedOngoing>> _followed() async {
    final prefs = await SharedPreferences.getInstance();
    try {
      final raw = jsonDecode(prefs.getString(_followedKey) ?? '{}');
      if (raw is! Map) return {};
      final result = <int, FollowedOngoing>{};
      for (final entry in raw.entries) {
        final id = int.tryParse('${entry.key}') ?? 0;
        final value = entry.value;
        if (id <= 0 || value is! Map) continue;
        result[id] = FollowedOngoing(
          id: id,
          title: '${value['title'] ?? ''}',
          aired: int.tryParse('${value['aired']}') ?? 0,
        );
      }
      return result;
    } catch (_) {
      return {};
    }
  }

  Future<void> _save(Map<int, FollowedOngoing> followed) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _followedKey,
      jsonEncode({
        for (final item in followed.values) '${item.id}': item.toJson(),
      }),
    );
  }

  /// Replaces the followed list with the ongoing titles the viewer is
  /// watching. New titles start from their current count, so nothing old is
  /// announced; known titles keep their count until [check] reports them.
  Future<void> follow(Iterable<ShikimoriAnime> watching) async {
    final current = await _followed();
    final next = <int, FollowedOngoing>{};
    for (final anime in watching) {
      // Metadata failed to load: keep what we knew instead of resetting it.
      if (anime.status == null) {
        final known = current[anime.id];
        if (known != null) next[anime.id] = known;
        continue;
      }
      if (anime.status != 'ongoing') continue;
      final title = (anime.russian?.trim().isNotEmpty ?? false)
          ? anime.russian!.trim()
          : (anime.name ?? 'Аниме');
      final known = current[anime.id];
      final aired = anime.episodesAired ?? 0;
      next[anime.id] = FollowedOngoing(
        id: anime.id,
        title: title,
        aired: known?.aired ?? aired,
      );
    }
    await _save(next);
    unawaited(check());
  }

  /// Fetches fresh episode counts and shows a notification per title.
  Future<void> check({bool force = false}) async {
    if (!supported || !await isEnabled()) return;
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now().millisecondsSinceEpoch;
    final last = prefs.getInt(_lastCheckKey) ?? 0;
    if (!force && now - last < const Duration(minutes: 20).inMilliseconds) {
      return;
    }
    final followed = await _followed();
    if (followed.isEmpty) return;
    await prefs.setInt(_lastCheckKey, now);
    final fresh = <ShikimoriAnime>[];
    final dio = Dio(
      BaseOptions(
        baseUrl: Config.shikimoriApiBaseUrl,
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 20),
        headers: const {
          'User-Agent': 'AniMix/2.5 (episode notifications)',
          'Accept': 'application/json',
        },
      ),
    );
    final ids = followed.keys.toList();
    try {
      for (var start = 0; start < ids.length; start += 50) {
        final chunk = ids.skip(start).take(50).join(',');
        final response = await dio.get<dynamic>(
          '/api/animes',
          queryParameters: {'ids': chunk, 'limit': 50},
        );
        final data = response.data;
        if (data is! List) continue;
        fresh.addAll(
          data.whereType<Map>().map(
            (item) => ShikimoriAnime.fromJson(Map<String, dynamic>.from(item)),
          ),
        );
      }
    } catch (error, stackTrace) {
      AppLogBuffer.instance.recordError(
        error,
        stackTrace,
        source: 'Уведомления о сериях',
        context: 'Shikimori не ответил',
      );
      return;
    }
    final updates = diff(followed, fresh);
    if (updates.isEmpty) return;
    if (await _init()) {
      for (final update in updates.take(6)) {
        await _plugin.show(
          id: update.id,
          title: update.title,
          body: update.body,
          payload: '${update.id}',
          notificationDetails: const NotificationDetails(
            android: AndroidNotificationDetails(
              'new_episodes',
              'Новые серии',
              channelDescription: 'Онгоинги из списка «Смотрю»',
              importance: Importance.high,
              priority: Priority.high,
            ),
            iOS: DarwinNotificationDetails(),
            windows: WindowsNotificationDetails(),
          ),
        );
      }
    }
    for (final update in updates) {
      final known = followed[update.id]!;
      followed[update.id] = FollowedOngoing(
        id: known.id,
        title: known.title,
        aired: update.to,
      );
    }
    await _save(followed);
  }
}
