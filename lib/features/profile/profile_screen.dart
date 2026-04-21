import 'dart:ui';
import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'; // Для теней, градиентов и продвинутого UI
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';


import '../../providers/user_provider.dart';

import '../../models/shikimori_history.dart';
import 'settings_screen.dart';

// Провайдер истории с увеличенным лимитом для точного графика
final userHistoryProvider = FutureProvider.autoDispose<List<ShikimoriHistory>>((ref) async {
  final user = await ref.watch(currentUserProvider.future);
  if (user == null) return [];
  final api = ref.read(apiClientProvider);
  return api.getUserHistory(user.id, limit: 100);
});

class ProfileScreen extends HookConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final userAsync = ref.watch(currentUserProvider);
    final historyAsync = ref.watch(userHistoryProvider);

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      child: userAsync.when(
        data: (user) {
          if (user == null) {
            return const Center(child: Text('Ошибка загрузки профиля', style: TextStyle(color: Colors.white)));
          }

          final totalAnime = user.watched + user.watching + user.planned + user.dropped + user.rewatched;

          return CustomScrollView(
            physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
            slivers: [
              // ==================== 1. ИММЕРСИВНЫЙ ХЕДЕР ====================
              SliverToBoxAdapter(
                child: SizedBox(
                  height: 440,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      // Размытый фон из аватара
                      if (user.avatarUrl != null)
                        CachedNetworkImage(
                          imageUrl: user.avatarUrl!,
                          fit: BoxFit.cover,
                          errorWidget: (_, __, ___) => const SizedBox(),
                        ),
                      // 🔥 ФИКС БЛЮРА: Обернули в ClipRect, чтобы размытие не ломало весь экран
                      Positioned.fill(
                        child: ClipRect(
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 35, sigmaY: 35),
                            child: Container(color: Colors.black.withValues(alpha: 0.4)),
                          ),
                        ),
                      ),
                      // Градиентный переход в цвет фона приложения
                      Container(
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Colors.transparent, Color(0xAA0F0F0F), Color(0xFF0F0F0F)],
                            stops: [0.3, 0.75, 1.0],
                          ),
                        ),
                      ),
                      
                      // Кнопка настроек (Шестеренка) в SafeArea
                      Positioned(
                        top: MediaQuery.of(context).padding.top + 10,
                        right: 16,
                        child: GestureDetector(
                          onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const SettingsScreen())),
                          child: Container(
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              color: Colors.black.withValues(alpha: 0.3),
                              shape: BoxShape.circle,
                              border: Border.all(color: Colors.white.withValues(alpha: 0.1)),
                            ),
                            child: const Icon(CupertinoIcons.gear_alt_fill, color: Colors.white, size: 24),
                          ),
                        ),
                      ),

                      // Контент профиля (Светящийся Аватар + Имя + Бейдж)
                      Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Container(
                            width: 150,
                            height: 150,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(color: const Color(0xFFFF5722), width: 4),
                              boxShadow: [
                                BoxShadow(
                                  color: const Color(0xFFFF5722).withValues(alpha: 0.5),
                                  blurRadius: 40,
                                  spreadRadius: 5,
                                ),
                                BoxShadow(
                                  color: Colors.black.withValues(alpha: 0.5),
                                  blurRadius: 20,
                                  offset: const Offset(0, 10),
                                ),
                              ],
                            ),
                            child: ClipOval(
                              child: CachedNetworkImage(
                                imageUrl: user.avatarUrl ?? '',
                                fit: BoxFit.cover,
                                placeholder: (_, __) => Container(color: const Color(0xFF1E1E1E)),
                                errorWidget: (_, __, ___) => const Icon(CupertinoIcons.person_fill, size: 60, color: Colors.grey),
                              ),
                            ),
                          ),
                          const SizedBox(height: 20),
                          
                          Text(
                            user.nickname,
                            style: const TextStyle(
                              fontSize: 34,
                              fontWeight: FontWeight.w900,
                              color: Colors.white,
                              letterSpacing: -0.5,
                            ),
                          ),
                          const SizedBox(height: 10),
                          
                          if (user.totalHours != null && user.totalHours! > 0)
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              decoration: BoxDecoration(
                                color: const Color(0xFFFF5722).withValues(alpha: 0.15),
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(color: const Color(0xFFFF5722).withValues(alpha: 0.3)),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  const Icon(CupertinoIcons.time, color: Color(0xFFFF5722), size: 16),
                                  const SizedBox(width: 8),
                                  Text(
                                    '${user.totalHours} часов за аниме',
                                    style: const TextStyle(color: Color(0xFFFF5722), fontWeight: FontWeight.w600, fontSize: 14),
                                  ),
                                ],
                              ),
                            ),
                          const SizedBox(height: 30),
                        ],
                      ),
                    ],
                  ),
                ),
              ),

              // ==================== 2. КАРТОЧКА СТАТИСТИКИ (GLASSMORPHISM) ====================
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1E1E1E),
                      borderRadius: BorderRadius.circular(28),
                      border: Border.all(color: Colors.white.withValues(alpha: 0.05), width: 1.5),
                      boxShadow: [
                        BoxShadow(color: Colors.black.withValues(alpha: 0.4), blurRadius: 30, offset: const Offset(0, 15)),
                      ],
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Библиотека', style: TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 24),
                        
                        if (totalAnime > 0) ...[
                          ClipRRect(
                            borderRadius: BorderRadius.circular(12),
                            child: SizedBox(
                              height: 12,
                              child: Row(
                                children: [
                                  if (user.watched > 0) Expanded(flex: user.watched, child: Container(color: const Color(0xFF4CAF50))),
                                  if (user.watching > 0) Expanded(flex: user.watching, child: Container(color: const Color(0xFF2196F3))),
                                  if (user.planned > 0) Expanded(flex: user.planned, child: Container(color: const Color(0xFFFF9800))),
                                  if (user.dropped > 0) Expanded(flex: user.dropped, child: Container(color: const Color(0xFFF44336))),
                                ],
                              ),
                            ),
                          ),
                          const SizedBox(height: 24),
                        ] else
                          const Text('В твоем списке пока нет аниме.', style: TextStyle(color: CupertinoColors.systemGrey)),

                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            _buildStatItem('Просмотрено', user.watched, const Color(0xFF4CAF50)),
                            _buildStatItem('Смотрю', user.watching, const Color(0xFF2196F3)),
                            _buildStatItem('В планах', user.planned, const Color(0xFFFF9800)),
                            _buildStatItem('Брошено', user.dropped, const Color(0xFFF44336)),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),

              // ==================== 3. ВОССТАНОВЛЕННЫЙ И РЕАЛЬНЫЙ ГРАФИК АКТИВНОСТИ ====================
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(20, 40, 20, 20),
                  child: Text('Активность', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.white)),
                ),
              ),
              SliverToBoxAdapter(
                child: historyAsync.when(
                  data: (history) => Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: _ActivityChartPremium(history: history),
                  ),
                  loading: () => const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20),
                    child: SizedBox(height: 225, child: Center(child: CupertinoActivityIndicator())),
                  ),
                  error: (_, __) => const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20),
                    child: SizedBox(height: 225, child: Center(child: Text('Ошибка загрузки графика', style: TextStyle(color: CupertinoColors.systemGrey)))),
                  ),
                ),
              ),

              // ==================== 4. ИСТОРИЯ АКТИВНОСТИ ====================
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(20, 40, 20, 20),
                  child: Text('Недавние действия', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.white)),
                ),
              ),
              
              SliverToBoxAdapter(
                child: historyAsync.when(
                  data: (history) {
                    if (history.isEmpty) {
                      return const Padding(
                        padding: EdgeInsets.symmetric(horizontal: 20),
                        child: Text('История пуста', style: TextStyle(color: CupertinoColors.systemGrey)),
                      );
                    }
                    return SizedBox(
                      height: 230,
                      child: ListView.builder(
                        physics: const BouncingScrollPhysics(),
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.symmetric(horizontal: 20),
                        itemCount: history.length,
                        itemBuilder: (context, i) => _HistoryCard(history: history[i]),
                      ),
                    );
                  },
                  loading: () => const SizedBox(height: 230, child: Center(child: CupertinoActivityIndicator())),
                  error: (_, __) => const SizedBox(height: 230, child: Center(child: Text('Не удалось загрузить историю', style: TextStyle(color: CupertinoColors.systemGrey)))),
                ),
              ),
              
              const SliverToBoxAdapter(child: SizedBox(height: 120)), // Отступ для нижнего меню
            ],
          );
        },
        loading: () => const Center(child: CupertinoActivityIndicator(radius: 20)),
        error: (_, __) => const Center(child: Text('Ошибка сети', style: TextStyle(color: Colors.white))),
      ),
    );
  }

  Widget _buildStatItem(String label, int count, Color color) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
            const SizedBox(width: 6),
            Text(label, style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 13, fontWeight: FontWeight.w500)),
          ],
        ),
        const SizedBox(height: 6),
        Text(count.toString(), style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold)),
      ],
    );
  }
}

// ==================== ПРЕМИУМ ГРАФИК АКТИВНОСТИ (С РЕАЛЬНЫМИ ДАННЫМИ) ====================
class _ActivityChartPremium extends StatefulWidget {
  final List<ShikimoriHistory> history;
  const _ActivityChartPremium({required this.history});

  @override
  State<_ActivityChartPremium> createState() => _ActivityChartPremiumState();
}

class _ActivityChartPremiumState extends State<_ActivityChartPremium> with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  late Animation<double> _heightAnim;

  late List<String> months;
  late List<double> activityData;
  late List<int> rawCounts;

  @override
  void initState() {
    super.initState();
    _calculateRealData();
    _animController = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
    _heightAnim = CurvedAnimation(parent: _animController, curve: Curves.easeOutQuart);
    _animController.forward();
  }

  void _calculateRealData() {
    final now = DateTime.now();
    months = [];
    rawCounts = List.filled(6, 0);
    
    // Русские названия месяцев
    final monthNames = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'];

    // Заполняем подписи для последних 6 месяцев (включая текущий)
    for (int i = 5; i >= 0; i--) {
      int m = now.month - i;
      if (m <= 0) m += 12; // корректировка для перехода через год
      months.add(monthNames[m - 1]);
    }

    // Анализируем историю
    for (var item in widget.history) {
      if (item.createdAt.isEmpty) continue;
      try {
        final date = DateTime.parse(item.createdAt).toLocal();
        // Считаем разницу в месяцах между сегодня и датой события
        int monthDiff = (now.year - date.year) * 12 + now.month - date.month;
        
        // Если событие было в последние 6 месяцев (0 - текущий, 5 - пять месяцев назад)
        if (monthDiff >= 0 && monthDiff < 6) {
          rawCounts[5 - monthDiff]++; // 5 - это текущий месяц (справа), 0 - самый старый
        }
      } catch (_) {}
    }

    // Вычисляем проценты для высоты столбиков
    int maxCount = rawCounts.reduce(math.max);
    if (maxCount == 0) {
      activityData = List.filled(6, 0.0);
    } else {
      activityData = rawCounts.map((c) => c / maxCount).toList();
    }
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 225,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E1E),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: Colors.white.withValues(alpha: 0.05), width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('Просмотры по месяцам', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
              Icon(CupertinoIcons.graph_square_fill, color: const Color(0xFFFF5722).withValues(alpha: 0.8), size: 20),
            ],
          ),
          const Spacer(),
          // Сам график с анимацией
          AnimatedBuilder(
            animation: _heightAnim,
            builder: (context, child) {
              return Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: List.generate(months.length, (i) {
                  final heightPercent = activityData[i] * _heightAnim.value;
                  final hasActivity = rawCounts[i] > 0;

                  return Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        // Если есть активность - рисуем градиентный столбик, если нет - серую "точку" 
                        Container(
                          height: hasActivity ? (115 * heightPercent).clamp(4.0, 115.0) : 4.0,
                          width: 28,
                          decoration: BoxDecoration(
                            gradient: hasActivity 
                                ? const LinearGradient(
                                    begin: Alignment.bottomCenter,
                                    end: Alignment.topCenter,
                                    colors: [Color(0xFFFF5722), Color(0xFFFF8A65)],
                                  )
                                : null,
                            color: hasActivity ? null : Colors.white.withValues(alpha: 0.05),
                            borderRadius: BorderRadius.circular(8),
                            boxShadow: hasActivity ? [
                              BoxShadow(
                                color: const Color(0xFFFF5722).withValues(alpha: 0.3 * heightPercent),
                                blurRadius: 10,
                                offset: const Offset(0, 5),
                              ),
                            ] : null,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          months[i], 
                          style: TextStyle(
                            fontSize: 12, 
                            color: hasActivity ? Colors.white : CupertinoColors.systemGrey, 
                            fontWeight: FontWeight.w600
                          ),
                        ),
                      ],
                    ),
                  );
                }),
              );
            },
          ),
        ],
      ),
    );
  }
}

// ==================== ПРЕМИУМ КАРТОЧКА ИСТОРИИ ====================
class _HistoryCard extends StatefulWidget {
  final ShikimoriHistory history;
  const _HistoryCard({required this.history});

  @override
  State<_HistoryCard> createState() => _HistoryCardState();
}

class _HistoryCardState extends State<_HistoryCard> with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _scaleAnim;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(duration: const Duration(milliseconds: 150), vsync: this);
    _scaleAnim = Tween<double>(begin: 1.0, end: 0.96).animate(CurvedAnimation(parent: _controller, curve: Curves.easeInOut));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final h = widget.history;
    final imageUrl = h.anime?.imageUrl ?? '';

    return GestureDetector(
      onTapDown: (_) => _controller.forward(),
      onTapUp: (_) => _controller.reverse(),
      onTapCancel: () => _controller.reverse(),
      child: AnimatedBuilder(
        animation: _scaleAnim,
        builder: (context, child) => Transform.scale(scale: _scaleAnim.value, child: child),
        child: Container(
          width: 150,
          margin: const EdgeInsets.only(right: 16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            color: const Color(0xFF1E1E1E),
            boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.3), blurRadius: 10, offset: const Offset(0, 5))],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (imageUrl.isNotEmpty)
                  CachedNetworkImage(
                    imageUrl: imageUrl,
                    fit: BoxFit.cover,
                    memCacheWidth: 300,
                    memCacheHeight: 450,
                    placeholder: (_, __) => Container(color: const Color(0xFF2A2A2A)),
                    errorWidget: (_, __, ___) => const Icon(CupertinoIcons.photo, color: CupertinoColors.systemGrey),
                  ),
                // Градиентное затемнение снизу для читаемости текста
                Container(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Colors.transparent, Colors.black87],
                      stops: [0.4, 1.0],
                    ),
                  ),
                ),
                // Текст активности
                Positioned(
                  bottom: 12,
                  left: 12,
                  right: 12,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        h.description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600, height: 1.2),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        h.anime?.russian ?? h.anime?.name ?? '',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 11),
                      ),
                    ],
                  ),
                )
              ],
            ),
          ),
        ),
      ),
    );
  }
}