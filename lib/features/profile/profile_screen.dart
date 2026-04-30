import 'dart:ui';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:intl/intl.dart';

import '../../providers/user_provider.dart';
import '../../models/shikimori_history.dart';
import 'settings_screen.dart';

// Цветовая палитра "Premium Violet"
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF09090B);

// Настройки для глубокого премиального стекла (кнопки, основные блоки)
const _premiumGlassSettings = LiquidGlassSettings(
  glassColor: Color(0x66000000), 
  thickness: 35.0,               
  chromaticAberration: 0.12,      
  refractiveIndex: 1.2,         
  lightIntensity: 0.7,           
  specularSharpness: GlassSpecularSharpness.sharp, 
);

// Эффект "Замороженного стекла" для карточек истории
const _frozenGlassSettings = LiquidGlassSettings(
  glassColor: Color(0x80121212), // Чуть плотнее для эффекта инея
  blur: 15.0,                    // Повышенное размытие
  lightIntensity: 0.3,           // Мягкое свечение граней
  thickness: 5.0,
);

// Провайдер истории
final userHistoryProvider = FutureProvider.autoDispose<List<ShikimoriHistory>>((ref) async {
  final user = await ref.watch(currentUserProvider.future);
  if (user == null) return [];
  final api = ref.read(apiClientProvider);
  return api.getUserHistory(user.id, limit: 100);
});

// =====================================================================
// ЭКРАН ПРОФИЛЯ
// =====================================================================
class ProfileScreen extends StatefulHookConsumerWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> with SingleTickerProviderStateMixin {
  late AnimationController _bgAnimController;

  @override
  void initState() {
    super.initState();
    _bgAnimController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 15),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _bgAnimController.dispose();
    super.dispose();
  }

  Future<void> _onRefresh() async {
    ref.invalidate(currentUserProvider);
    ref.invalidate(userHistoryProvider);
    await ref.read(currentUserProvider.future);
  }

  @override
  Widget build(BuildContext context) {
    final userAsync = ref.watch(currentUserProvider);
    final historyAsync = ref.watch(userHistoryProvider);

    return Scaffold(
      backgroundColor: _bgColor,
      body: Stack(
        children: [
          // 1. Анимированный Ambient-фон
          Positioned.fill(
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 90, sigmaY: 90),
              child: AnimatedBuilder(
                animation: _bgAnimController,
                builder: (context, child) {
                  final value = _bgAnimController.value;
                  return Stack(
                    children: [
                      Positioned(
                        top: -50 + (30 * value), right: -50 - (20 * value),
                        child: Container(width: 350, height: 350, decoration: BoxDecoration(color: _accentColor.withOpacity(0.15), shape: BoxShape.circle)),
                      ),
                      Positioned(
                        top: 400 - (40 * value), left: -100 + (30 * value),
                        child: Container(width: 400, height: 400, decoration: BoxDecoration(color: const Color(0xFF3B82F6).withOpacity(0.1), shape: BoxShape.circle)),
                      ),
                    ],
                  );
                }
              ),
            ),
          ),

          // 2. Основной контент
          userAsync.when(
            loading: () => const Center(child: CupertinoActivityIndicator(radius: 16)),
            error: (e, _) => Center(
              child: Text('Ошибка загрузки профиля\n$e', textAlign: TextAlign.center, style: const TextStyle(color: CupertinoColors.systemRed)),
            ),
            data: (user) {
              if (user == null) return const Center(child: Text('Нет данных', style: TextStyle(color: Colors.white)));

              return CustomScrollView(
                physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
                slivers: [
                  CupertinoSliverRefreshControl(onRefresh: _onRefresh),
                  
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top + 20),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                const Text('Профиль', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: -1.2)),
                                // Круглая кнопка настроек с физикой "желе"
                                GlassButton(
                                  onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const SettingsScreen())),
                                  quality: GlassQuality.premium,
                                  shape: const LiquidRoundedSuperellipse(borderRadius: 100.0), // Делает кнопку идеально круглой
                                  settings: _premiumGlassSettings,
                                  icon: const Padding(
                                    padding: EdgeInsets.all(12),
                                    child: Icon(CupertinoIcons.gear_alt_fill, color: Colors.white, size: 24),
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
                              padding: const EdgeInsets.symmetric(horizontal: 20),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text('Активность', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: -0.5)),
                                  const SizedBox(height: 16),
                                  _ActivityChartPremium(history: history),
                                  const SizedBox(height: 32),
                                  const Text('Недавняя история', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: -0.5)),
                                  const SizedBox(height: 16),
                                ],
                              ),
                            ),
                            SizedBox(
                              height: 180,
                              child: ListView.builder(
                                scrollDirection: Axis.horizontal,
                                padding: const EdgeInsets.symmetric(horizontal: 20),
                                physics: const BouncingScrollPhysics(),
                                itemCount: history.length > 15 ? 15 : history.length,
                                itemBuilder: (context, index) => _HistoryCard(history[index]),
                              ),
                            ),
                            const SizedBox(height: 120),
                          ],
                        );
                      },
                      loading: () => const Padding(padding: EdgeInsets.all(32), child: Center(child: CupertinoActivityIndicator())),
                      error: (_, __) => const SizedBox(),
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
          width: 120, height: 120,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            boxShadow: [BoxShadow(color: _accentColor.withOpacity(0.3), blurRadius: 30, spreadRadius: 5)],
            border: Border.all(color: Colors.white.withOpacity(0.1), width: 2),
          ),
          child: ClipOval(
            child: CachedNetworkImage(
              imageUrl: user.avatarUrl ?? '',
              fit: BoxFit.cover,
              errorWidget: (_, __, ___) => const Icon(CupertinoIcons.person_solid, size: 60, color: Colors.grey),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(user.nickname ?? 'Без имени', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: -0.5)),
        const SizedBox(height: 4),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(color: _accentColor.withOpacity(0.2), borderRadius: BorderRadius.circular(8)),
          child: const Text('AniMix User', style: TextStyle(color: _accentLight, fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }

  Widget _buildLibraryProgressBar(dynamic user) {
    final int total = user.watched + user.planned + user.dropped;
    if (total == 0) return const SizedBox();

    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 24.0),
      settings: _premiumGlassSettings,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Медиатека', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 16),
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: SizedBox(
                height: 12,
                child: Row(
                  children: [
                    Expanded(flex: user.watched == 0 ? 0 : user.watched, child: Container(color: _accentColor)),
                    Expanded(flex: user.planned == 0 ? 0 : user.planned, child: Container(color: CupertinoColors.systemBlue)),
                    Expanded(flex: user.dropped == 0 ? 0 : user.dropped, child: Container(color: CupertinoColors.systemRed)),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 16, runSpacing: 10,
              children: [
                _buildLegendDot('Просмотрено', user.watched, _accentColor),
                _buildLegendDot('В планах', user.planned, CupertinoColors.systemBlue),
                _buildLegendDot('Брошено', user.dropped, CupertinoColors.systemRed),
              ],
            )
          ],
        ),
      ),
    );
  }

  Widget _buildLegendDot(String title, int count, Color color) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(title, style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 12)),
        const SizedBox(width: 4),
        Text(count.toString(), style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold)),
      ],
    );
  }

  Widget _buildStatsGrid(dynamic user) {
    return Row(
      children: [
        Expanded(child: _buildStatCard('Завершено', user.watched.toString(), CupertinoIcons.check_mark_circled_solid)),
        const SizedBox(width: 16),
        Expanded(child: _buildStatCard('В процессе', user.watching.toString(), CupertinoIcons.time)),
      ],
    );
  }

  Widget _buildStatCard(String title, String value, IconData icon) {
    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 24.0),
      settings: _premiumGlassSettings,
      child: Padding(
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
              child: Text(value, style: const TextStyle(color: Colors.white, fontSize: 32, fontWeight: FontWeight.bold, height: 1.1)),
            ),
            const SizedBox(height: 4),
            Text(title, style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 13, fontWeight: FontWeight.w500)),
          ],
        ),
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
    final now = DateTime.now();
    final Map<String, int> monthlyActivity = {};
    for (int i = 5; i >= 0; i--) {
      final key = DateFormat('MMM yy', 'ru').format(DateTime(now.year, now.month - i));
      monthlyActivity[key] = 0;
    }
    for (var item in history) {
      if (item.createdAt.isEmpty) continue;
      try {
        final key = DateFormat('MMM yy', 'ru').format(DateTime.parse(item.createdAt));
        if (monthlyActivity.containsKey(key)) monthlyActivity[key] = monthlyActivity[key]! + 1;
      } catch (_) {}
    }
    final maxCount = monthlyActivity.values.isEmpty ? 1 : monthlyActivity.values.reduce(math.max);
    final maxDivisor = maxCount == 0 ? 1 : maxCount;

    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 24.0),
      settings: _premiumGlassSettings,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Просмотры за полгода', style: TextStyle(color: Colors.white.withOpacity(0.7), fontSize: 14, fontWeight: FontWeight.w600)),
                const Icon(CupertinoIcons.graph_square_fill, color: _accentLight, size: 20),
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
                            child: Text(entry.value.toString(), style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
                          ),
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 800),
                          curve: Curves.easeOutQuart,
                          width: 16, height: math.max(10.0, 90.0 * heightRatio),
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.bottomCenter, end: Alignment.topCenter,
                              colors: [_accentColor.withOpacity(0.3 + (0.7 * heightRatio)), _accentLight.withOpacity(0.5 + (0.5 * heightRatio))],
                            ),
                            borderRadius: BorderRadius.circular(6),
                            boxShadow: [if (entry.value > 0) BoxShadow(color: _accentColor.withOpacity(0.3 * heightRatio), blurRadius: 8, offset: const Offset(0, 4))],
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(entry.key.split(' ')[0], style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 11, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  );
                }).toList(),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// =====================================================================
// КАРТОЧКА ИСТОРИИ (Эффект замороженного стекла)
// =====================================================================
class _HistoryCard extends StatelessWidget {
  final ShikimoriHistory history;
  const _HistoryCard(this.history);

  @override
  Widget build(BuildContext context) {
    final anime = history.anime;
    final animeName = anime?.russian ?? anime?.name ?? '';
    final shape = const LiquidRoundedSuperellipse(borderRadius: 18.0);
    
    return Container(
      width: 120,
      margin: const EdgeInsets.only(right: 14),
      // GlassContainer с качеством Minimal создает эффект замороженного стекла
      // без лишних анимаций и клипперов, которые вызывают "обрезание" при нажатии.
      child: GlassContainer(
        quality: GlassQuality.minimal, 
        shape: shape,
        settings: _frozenGlassSettings,
        child: Stack(
          fit: StackFit.expand,
          children: [
            // Внутренний контент с аккуратным скруглением
            if (anime?.imageUrl != null)
              ClipPath(
                clipper: ShapeBorderClipper(shape: shape),
                child: CachedNetworkImage(
                  imageUrl: anime!.imageUrl!, 
                  fit: BoxFit.cover, 
                  errorWidget: (_, __, ___) => _buildFallback(),
                ),
              )
            else
              _buildFallback(),

            // Затемнение снизу вверх
            Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter, 
                  end: Alignment.bottomCenter, 
                  colors: [Colors.transparent, Colors.black.withOpacity(0.8)], 
                  stops: const [0.3, 1.0],
                ),
              ),
            ),

            Positioned(
              bottom: 12, left: 10, right: 10,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    history.description, 
                    maxLines: 2, 
                    overflow: TextOverflow.ellipsis, 
                    style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700, height: 1.1),
                  ),
                  if (animeName.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      animeName, 
                      maxLines: 1, 
                      overflow: TextOverflow.ellipsis, 
                      style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 10, fontWeight: FontWeight.w500),
                    ),
                  ]
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFallback() => Container(
    color: const Color(0xFF1C1C1E), 
    child: const Center(child: Icon(CupertinoIcons.sparkles, color: Colors.grey, size: 24)),
  );
}