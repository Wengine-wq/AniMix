import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:intl/intl.dart';
import 'package:intl/date_symbol_data_local.dart'; // 🔥 ДОБАВЛЕН ИМПОРТ ДЛЯ ИНИЦИАЛИЗАЦИИ ЛОКАЛИ

import '../../providers/user_provider.dart';
import '../../models/shikimori_history.dart';
import '../../widgets/smart_anime_poster.dart';
import '../../core/app_settings.dart';
import 'settings_screen.dart';

// 🔥 Импортируем наш глобальный нативный класс стекла
import '../../main.dart';

// Цветовая палитра "Premium Violet"
Color get _accentColor => AppSettingsController.instance.accentColor;
Color get _accentLight =>
    Color.lerp(_accentColor, Colors.white, 0.24) ?? _accentColor;
const Color _bgColor = Color(0xFF09090B);

// Провайдер истории с привязкой к ID
final userHistoryProvider = FutureProvider.family
    .autoDispose<List<ShikimoriHistory>, int>((ref, userId) async {
      final api = ref.watch(apiClientProvider);
      return api.getUserHistory(userId, limit: 100);
    });

// =====================================================================
// ЭКРАН ПРОФИЛЯ
// =====================================================================
class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  Future<void> _onRefresh() async {
    ref.invalidate(currentUserProvider);
    // Принудительно ждем обновления юзера, чтобы инвалидировать его историю
    final user = await ref.read(currentUserProvider.future);
    if (user != null) {
      ref.invalidate(userHistoryProvider(user.id));
    }
  }

  @override
  Widget build(BuildContext context) {
    final userAsync = ref.watch(currentUserProvider);

    return Scaffold(
      backgroundColor: _bgColor,
      body: Stack(
        children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color.alphaBlend(
                      _accentColor.withValues(alpha: 0.08),
                      const Color(0xFF111117),
                    ),
                    _bgColor,
                    _bgColor,
                  ],
                  stops: const [0, 0.3, 1],
                ),
              ),
            ),
          ),

          // 2. ОСНОВНОЙ КОНТЕНТ
          userAsync.when(
            loading: () =>
                const Center(child: CupertinoActivityIndicator(radius: 16)),
            error: (error, stack) {
              // Изящная защита от 429 ошибки (Rate Limit)
              final isRateLimit = error.toString().contains('429');

              return Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: _PremiumGlass(
                    padding: const EdgeInsets.all(28),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: Colors.redAccent.withValues(alpha: 0.15),
                            shape: BoxShape.circle,
                          ),
                          child: Icon(
                            isRateLimit
                                ? CupertinoIcons.timer
                                : CupertinoIcons.exclamationmark_triangle_fill,
                            color: Colors.redAccent,
                            size: 48,
                          ),
                        ),
                        const SizedBox(height: 20),
                        Text(
                          isRateLimit
                              ? 'Слишком много запросов'
                              : 'Ошибка загрузки',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 0.5,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          isRateLimit
                              ? 'Shikimori временно ограничил доступ.\nПожалуйста, подождите минуту.'
                              : 'Не удалось загрузить данные профиля.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.7),
                            fontSize: 15,
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 28),

                        // 🔥 ЗАМЕНИЛИ GestureDetector НА _BouncingButton
                        _BouncingButton(
                          onTap: () => ref.refresh(currentUserProvider),
                          child: Container(
                            width: double.infinity,
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                colors: [_accentColor, Color(0xFF6D28D9)],
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                              ),
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: const Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  CupertinoIcons.refresh,
                                  color: Colors.white,
                                  size: 20,
                                ),
                                SizedBox(width: 8),
                                Text(
                                  'Обновить',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            },
            data: (user) {
              if (user == null) {
                return const Center(
                  child: Text(
                    'Нет данных',
                    style: TextStyle(color: Colors.white),
                  ),
                );
              }

              // Загружаем историю только если юзер загрузился
              final historyAsync = ref.watch(userHistoryProvider(user.id));

              return CustomScrollView(
                physics: const BouncingScrollPhysics(
                  parent: AlwaysScrollableScrollPhysics(),
                ),
                slivers: [
                  CupertinoSliverRefreshControl(onRefresh: _onRefresh),

                  SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.only(
                        top: MediaQuery.of(context).padding.top + 20,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                const Text(
                                  'Профиль',
                                  style: TextStyle(
                                    fontSize: 34,
                                    fontWeight: FontWeight.w900,
                                    color: Colors.white,
                                    letterSpacing: -1.2,
                                  ),
                                ),

                                // 🔥 АНИМИРОВАННАЯ СТЕКЛЯННАЯ КНОПКА НАСТРОЕК
                                _BouncingButton(
                                  onTap: () => Navigator.push(
                                    context,
                                    CupertinoPageRoute(
                                      builder: (_) => const SettingsScreen(),
                                    ),
                                  ),
                                  child: const _PremiumGlass(
                                    borderRadius:
                                        100.0, // Делает кнопку идеально круглой
                                    padding: EdgeInsets.all(12),
                                    child: Icon(
                                      CupertinoIcons.gear_alt_fill,
                                      color: Colors.white,
                                      size: 24,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 32),
                          Center(child: _buildProfileHeader(user)),
                          const SizedBox(height: 32),

                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: _buildLibraryProgressBar(user),
                          ),
                          const SizedBox(height: 16),

                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: _buildStatsGrid(user),
                          ),
                          const SizedBox(height: 32),
                        ],
                      ),
                    ),
                  ),

                  SliverToBoxAdapter(
                    child: historyAsync.when(
                      data: (history) {
                        if (history.isEmpty) return const SizedBox();
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 20,
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text(
                                    'Активность',
                                    style: TextStyle(
                                      fontSize: 22,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.white,
                                      letterSpacing: -0.5,
                                    ),
                                  ),
                                  const SizedBox(height: 16),
                                  _ActivityChartPremium(history: history),
                                  const SizedBox(height: 32),
                                  const Text(
                                    'Недавняя история',
                                    style: TextStyle(
                                      fontSize: 22,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.white,
                                      letterSpacing: -0.5,
                                    ),
                                  ),
                                  const SizedBox(height: 16),
                                ],
                              ),
                            ),
                            // Оригинальный горизонтальный скролл истории
                            SizedBox(
                              height: 180,
                              child: ListView.builder(
                                scrollDirection: Axis.horizontal,
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 20,
                                ),
                                physics: const BouncingScrollPhysics(),
                                itemCount: history.length > 15
                                    ? 15
                                    : history.length,
                                itemBuilder: (context, index) =>
                                    _HistoryCard(history[index]),
                              ),
                            ),
                            const SizedBox(height: 120), // Отступ под навбар
                          ],
                        );
                      },
                      loading: () => const Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: CupertinoActivityIndicator()),
                      ),
                      error: (_, _) => const SizedBox(),
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

  Widget _buildProfileHeader(dynamic user) {
    return Column(
      children: [
        Container(
          width: 120,
          height: 120,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            boxShadow: [
              BoxShadow(
                color: _accentColor.withValues(alpha: 0.3),
                blurRadius: 30,
                spreadRadius: 5,
              ),
            ],
            border: Border.all(
              color: Colors.white.withValues(alpha: 0.1),
              width: 2,
            ),
          ),
          child: ClipOval(
            child: CachedNetworkImage(
              imageUrl: user.avatarUrl ?? '',
              fit: BoxFit.cover,
              errorWidget: (_, _, _) => const Icon(
                CupertinoIcons.person_solid,
                size: 60,
                color: Colors.grey,
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          user.nickname ?? 'Без имени',
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
            color: Colors.white,
            letterSpacing: -0.5,
          ),
        ),
        const SizedBox(height: 4),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color: _accentColor.withValues(alpha: 0.2),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(
            'AniMix User',
            style: TextStyle(
              color: _accentLight,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildLibraryProgressBar(dynamic user) {
    final int total = user.watched + user.planned + user.dropped;
    if (total == 0) return const SizedBox();

    return _PremiumGlass(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Медиатека',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 16),
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: SizedBox(
              height: 12,
              child: Row(
                children: [
                  Expanded(
                    flex: user.watched == 0 ? 0 : user.watched,
                    child: Container(color: _accentColor),
                  ),
                  Expanded(
                    flex: user.planned == 0 ? 0 : user.planned,
                    child: Container(color: CupertinoColors.systemBlue),
                  ),
                  Expanded(
                    flex: user.dropped == 0 ? 0 : user.dropped,
                    child: Container(color: CupertinoColors.systemRed),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 16,
            runSpacing: 10,
            children: [
              _buildLegendDot('Просмотрено', user.watched, _accentColor),
              _buildLegendDot(
                'В планах',
                user.planned,
                CupertinoColors.systemBlue,
              ),
              _buildLegendDot(
                'Брошено',
                user.dropped,
                CupertinoColors.systemRed,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildLegendDot(String title, int count, Color color) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          title,
          style: TextStyle(
            color: Colors.white.withValues(alpha: 0.6),
            fontSize: 12,
          ),
        ),
        const SizedBox(width: 4),
        Text(
          count.toString(),
          style: const TextStyle(
            color: Colors.white,
            fontSize: 13,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildStatsGrid(dynamic user) {
    return Row(
      children: [
        Expanded(
          child: _buildStatCard(
            'Завершено',
            user.watched.toString(),
            CupertinoIcons.check_mark_circled_solid,
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: _buildStatCard(
            'В процессе',
            user.watching.toString(),
            CupertinoIcons.time,
          ),
        ),
      ],
    );
  }

  Widget _buildStatCard(String title, String value, IconData icon) {
    return _PremiumGlass(
      padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: _accentLight, size: 26),
          const SizedBox(height: 16),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(
              value,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 32,
                fontWeight: FontWeight.bold,
                height: 1.1,
              ),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            title,
            style: TextStyle(
              color: Colors.white.withValues(alpha: 0.6),
              fontSize: 13,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

// =====================================================================
// ПРЕМИУМ ГРАФИК АКТИВНОСТИ
// =====================================================================
class _ActivityChartPremium extends StatelessWidget {
  final List<ShikimoriHistory> history;
  const _ActivityChartPremium({required this.history});

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: initializeDateFormatting('ru', null),
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const _PremiumGlass(
            padding: EdgeInsets.all(20),
            child: SizedBox(
              height: 204, // Сохраняем высоту, чтобы верстка не прыгала
              child: Center(child: CupertinoActivityIndicator()),
            ),
          );
        }

        final now = DateTime.now();
        final Map<String, int> monthlyActivity = {};
        for (int i = 5; i >= 0; i--) {
          final key = DateFormat(
            'MMM yy',
            'ru',
          ).format(DateTime(now.year, now.month - i));
          monthlyActivity[key] = 0;
        }
        for (var item in history) {
          if (item.createdAt.isEmpty) continue;
          try {
            final key = DateFormat(
              'MMM yy',
              'ru',
            ).format(DateTime.parse(item.createdAt));
            if (monthlyActivity.containsKey(key)) {
              monthlyActivity[key] = monthlyActivity[key]! + 1;
            }
          } catch (_) {}
        }
        final maxCount = monthlyActivity.values.isEmpty
            ? 1
            : monthlyActivity.values.reduce(math.max);
        final maxDivisor = maxCount == 0 ? 1 : maxCount;

        return _PremiumGlass(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Просмотры за полгода',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.7),
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Icon(
                    CupertinoIcons.graph_square_fill,
                    color: _accentLight,
                    size: 20,
                  ),
                ],
              ),
              const SizedBox(height: 24),
              SizedBox(
                height: 160,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: monthlyActivity.entries.map((entry) {
                    final heightRatio = entry.value / maxDivisor;
                    return Expanded(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          if (entry.value > 0)
                            Padding(
                              padding: const EdgeInsets.only(bottom: 6),
                              child: Text(
                                entry.value.toString(),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 12,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          AnimatedContainer(
                            duration: const Duration(milliseconds: 800),
                            curve: Curves.easeOutQuart,
                            width: 16,
                            height: math.max(10.0, 90.0 * heightRatio),
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.bottomCenter,
                                end: Alignment.topCenter,
                                colors: [
                                  _accentColor.withValues(
                                    alpha: 0.3 + (0.7 * heightRatio),
                                  ),
                                  _accentLight.withValues(
                                    alpha: 0.5 + (0.5 * heightRatio),
                                  ),
                                ],
                              ),
                              borderRadius: BorderRadius.circular(6),
                              boxShadow: [
                                if (entry.value > 0)
                                  BoxShadow(
                                    color: _accentColor.withValues(
                                      alpha: 0.3 * heightRatio,
                                    ),
                                    blurRadius: 8,
                                    offset: const Offset(0, 4),
                                  ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            entry.key.split(' ')[0],
                            style: TextStyle(
                              color: Colors.white.withValues(alpha: 0.5),
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    );
                  }).toList(),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

// =====================================================================
// КАРТОЧКА ИСТОРИИ (С добавленной физикой нажатия)
// =====================================================================
class _HistoryCard extends StatelessWidget {
  final ShikimoriHistory history;
  const _HistoryCard(this.history);

  @override
  Widget build(BuildContext context) {
    final anime = history.anime;
    final animeName = anime?.russian ?? anime?.name ?? '';
    final imageUrl = anime?.imageUrl ?? '';

    // 🔥 Обернули карточку в BouncingButton для приятного импакта
    return _BouncingButton(
      onTap: () {
        // Здесь можно будет добавить переход на экран аниме
        debugPrint('Нажали на: $animeName');
      },
      child: Container(
        width: 120,
        margin: const EdgeInsets.only(right: 14),
        child: AniMixGlass(
          blur: 15.0,
          borderRadius: 18.0,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Container(color: const Color(0x80121212)),

              if (anime != null)
                SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: imageUrl,
                  title: anime.name ?? '',
                  russianTitle: anime.russian,
                )
              else
                _buildFallback(),

              Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Colors.transparent,
                      Colors.black.withValues(alpha: 0.8),
                    ],
                    stops: const [0.3, 1.0],
                  ),
                ),
              ),

              Positioned(
                bottom: 12,
                left: 10,
                right: 10,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      history.description,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        height: 1.1,
                      ),
                    ),
                    if (animeName.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        animeName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.6),
                          fontSize: 10,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFallback() => Container(
    color: const Color(0xFF1C1C1E),
    child: const Center(
      child: Icon(CupertinoIcons.sparkles, color: Colors.grey, size: 24),
    ),
  );
}

// =====================================================================
// ХЕЛПЕР ДЛЯ ЭМУЛЯЦИИ "ТЕМНОГО СТЕКЛА" ИЗ БИБЛИОТЕКИ
// =====================================================================
class _PremiumGlass extends StatelessWidget {
  final Widget child;
  final double borderRadius;
  final EdgeInsetsGeometry padding;

  const _PremiumGlass({
    required this.child,
    this.borderRadius = 24.0,
    this.padding = EdgeInsets.zero,
  });

  @override
  Widget build(BuildContext context) {
    return AniMixGlass(
      blur: 25,
      borderRadius: borderRadius,
      child: Container(
        padding: padding,
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.4),
          borderRadius: BorderRadius.circular(borderRadius),
        ),
        child: child,
      ),
    );
  }
}

// =====================================================================
// 🔥 АНИМАЦИОННАЯ ОБЕРТКА ДЛЯ КНОПОК (ЭФФЕКТ УПРУГОСТИ / ИМПАКТ)
// =====================================================================
class _BouncingButton extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;

  const _BouncingButton({required this.child, this.onTap});

  @override
  State<_BouncingButton> createState() => _BouncingButtonState();
}

class _BouncingButtonState extends State<_BouncingButton> {
  bool _isPressed = false;

  void _handleTapDown(TapDownDetails details) =>
      setState(() => _isPressed = true);
  void _handleTapUp(TapUpDetails details) => setState(() => _isPressed = false);
  void _handleTapCancel() => setState(() => _isPressed = false);

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: _handleTapDown,
      onTapUp: _handleTapUp,
      onTapCancel: _handleTapCancel,
      onTap: widget.onTap,
      behavior: HitTestBehavior.opaque,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: _isPressed ? 0.7 : 1.0, // Легкое потускнение при клике
        child: AnimatedScale(
          scale: _isPressed ? 0.94 : 1.0, // Упругое масштабирование
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOutCubic,
          child: widget.child,
        ),
      ),
    );
  }
}
