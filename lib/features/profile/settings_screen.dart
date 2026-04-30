import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../core/secure_storage.dart';
import '../auth/login_screen.dart';
import '../watch/models/watch_mapping.dart';
import '../watch/repositories/watch_mapping_repository.dart';

// =====================================================================
// ЦВЕТОВАЯ ПАЛИТРА И СТИЛИ
// =====================================================================
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF050507); // Максимально глубокий темный фон

// =====================================================================
// 1. ГЛАВНЫЙ ЭКРАН НАСТРОЕК (ХАБ)
// =====================================================================
class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  // 🔥 Универсальный чекер для обхода ошибки non_bool_condition
  bool _checkIsLoggedIn(dynamic val) {
    if (val is bool) return val;
    try {
      return val.value == true;
    } catch (_) {
      return false;
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bool isLoggedIn = _checkIsLoggedIn(ref.watch(isLoggedInProvider));

    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 25.0, thickness: 10.0),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            SliverAppBar(
              expandedHeight: 120,
              backgroundColor: _bgColor.withValues(alpha: 0.8),
              pinned: true,
              flexibleSpace: const FlexibleSpaceBar(
                titlePadding: EdgeInsets.only(left: 20, bottom: 16),
                title: Text(
                  'Настройки',
                  style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900, letterSpacing: -0.5),
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildSectionTitle('Основные'),
                    _buildSettingsGroup([
                      _SettingsTile(
                        icon: CupertinoIcons.play_rectangle,
                        title: 'Привязки плеера',
                        onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const _WatchMappingsSubScreen())),
                      ),
                    ]),
                    
                    const SizedBox(height: 32),
                    _buildSectionTitle('О приложении'),
                    _buildSettingsGroup([
                      _SettingsTile(
                        icon: CupertinoIcons.doc_text,
                        title: 'Список изменений',
                        onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const _ChangelogSubScreen())),
                      ),
                      _SettingsTile(
                        icon: CupertinoIcons.info_circle,
                        title: 'О приложении',
                        onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const _AboutSubScreen())),
                        isLast: true,
                      ),
                    ]),

                    const SizedBox(height: 32),
                    _buildSectionTitle('Аккаунт'),
                    _buildSettingsGroup([
                      isLoggedIn
                          ? _SettingsTile(
                              icon: CupertinoIcons.square_arrow_right,
                              title: 'Выйти из аккаунта',
                              iconColor: CupertinoColors.destructiveRed,
                              textColor: CupertinoColors.destructiveRed,
                              onTap: () => _handleLogout(context, ref),
                              isLast: true,
                            )
                          : _SettingsTile(
                              icon: CupertinoIcons.person_crop_circle_badge_plus,
                              title: 'Войти в Shikimori',
                              iconColor: _accentLight,
                              textColor: _accentLight,
                              onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const LoginScreen())),
                              isLast: true,
                            ),
                    ]),
                    
                    const SizedBox(height: 64),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 12, bottom: 8),
      child: Text(
        title.toUpperCase(),
        style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 12, fontWeight: FontWeight.bold, letterSpacing: 1.0),
      ),
    );
  }

  Widget _buildSettingsGroup(List<Widget> children) {
    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 20),
      settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 15),
      child: Column(
        children: children,
      ),
    );
  }

  Future<void> _handleLogout(BuildContext context, WidgetRef ref) async {
    showCupertinoDialog(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Выход из аккаунта'),
        content: const Text('Вы уверены, что хотите выйти?'),
        actions: [
          CupertinoDialogAction(
            child: const Text('Отмена'),
            onPressed: () => Navigator.pop(ctx),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () async {
              Navigator.pop(ctx);
              await SecureStorage.clear();
              ref.invalidate(isLoggedInProvider);
            },
            child: const Text('Выйти'),
          ),
        ],
      ),
    );
  }
}

// =====================================================================
// ГЕНЕРИРУЕМЫЙ ЭЛЕМЕНТ СПИСКА
// =====================================================================
class _SettingsTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  final bool isLast;
  final Color? iconColor;
  final Color? textColor;

  const _SettingsTile({
    required this.icon,
    required this.title,
    required this.onTap,
    this.isLast = false,
    this.iconColor,
    this.textColor,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            child: Row(
              children: [
                Icon(icon, color: iconColor ?? Colors.white.withValues(alpha: 0.8), size: 22),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(color: textColor ?? Colors.white, fontSize: 16, fontWeight: FontWeight.w500),
                  ),
                ),
                Icon(CupertinoIcons.chevron_right, color: Colors.white.withValues(alpha: 0.3), size: 16),
              ],
            ),
          ),
        ),
        if (!isLast)
          Padding(
            padding: const EdgeInsets.only(left: 54),
            child: Divider(height: 1, color: Colors.white.withValues(alpha: 0.1)),
          ),
      ],
    );
  }
}

// =====================================================================
// 2. ПОДЭКРАН: ПРИВЯЗКИ ПЛЕЕРА
// =====================================================================
class _WatchMappingsSubScreen extends StatefulWidget {
  const _WatchMappingsSubScreen();

  @override
  State<_WatchMappingsSubScreen> createState() => _WatchMappingsSubScreenState();
}

class _WatchMappingsSubScreenState extends State<_WatchMappingsSubScreen> {
  final WatchMappingRepository _repository = WatchMappingRepository();
  List<WatchMapping> _mappings = []; 
  String? _errorMsg;

  @override
  void initState() {
    super.initState();
    _loadMappings();
  }

  Future<void> _loadMappings() async {
    try {
      // Строго используем метод getAll(), который был в предоставленном репозитории
      final list = await _repository.getAll();
      if (mounted) {
        setState(() {
          _mappings = list;
          _errorMsg = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _errorMsg = "Ошибка загрузки: $e");
    }
  }

  Future<void> _deleteMapping(String key) async {
    try {
      // Строго используем метод delete(), который был в предоставленном репозитории
      await _repository.delete(key);
      _loadMappings();
    } catch (e) {
      debugPrint('Ошибка удаления привязки плеера: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 25.0, thickness: 10.0),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            SliverAppBar(
              expandedHeight: 100,
              backgroundColor: _bgColor.withValues(alpha: 0.8),
              pinned: true,
              leading: CupertinoButton(
                padding: EdgeInsets.zero,
                child: const Icon(CupertinoIcons.back, color: Colors.white),
                onPressed: () => Navigator.pop(context),
              ),
              flexibleSpace: const FlexibleSpaceBar(
                titlePadding: EdgeInsets.only(left: 48, bottom: 16),
                title: Text('Привязки плеера', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 20)),
              ),
            ),
            if (_errorMsg != null && _mappings.isEmpty)
              SliverFillRemaining(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24.0),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(CupertinoIcons.exclamationmark_triangle, size: 48, color: CupertinoColors.systemRed.withValues(alpha: 0.8)),
                        const SizedBox(height: 16),
                        const Text('Не удалось загрузить', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 8),
                        Text(_errorMsg!, textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 14)),
                      ],
                    ),
                  ),
                ),
              )
            else if (_mappings.isEmpty)
              SliverFillRemaining(
                child: Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(CupertinoIcons.play_rectangle, size: 64, color: Colors.white.withValues(alpha: 0.2)),
                      const SizedBox(height: 16),
                      Text('Нет сохраненных привязок', style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 16)),
                    ],
                  ),
                ),
              )
            else
              SliverList(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final mapping = _mappings[index];
                    
                    return Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                      child: GlassContainer(
                        quality: GlassQuality.standard,
                        shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                        settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 15),
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Row(
                            children: [
                              _buildFallbackPoster(),
                              const SizedBox(width: 16),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      mapping.releaseTitle,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600),
                                    ),
                                    const SizedBox(height: 6),
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                      decoration: BoxDecoration(
                                        color: _accentColor.withValues(alpha: 0.2),
                                        borderRadius: BorderRadius.circular(8),
                                      ),
                                      child: Text(
                                        mapping.provider.toUpperCase(),
                                        style: const TextStyle(color: _accentLight, fontSize: 10, fontWeight: FontWeight.bold),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              CupertinoButton(
                                padding: const EdgeInsets.all(12),
                                onPressed: () => _deleteMapping(mapping.key),
                                child: const Icon(CupertinoIcons.trash, color: CupertinoColors.destructiveRed, size: 22),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                  childCount: _mappings.length,
                ),
              ),
            
            // 🔥 ЗАЩИТА ОТ ПЕРЕКРЫТИЯ НИЖНИМ НАВБАРОМ
            const SliverToBoxAdapter(child: SizedBox(height: 120)),
          ],
        ),
      ),
    );
  }

  Widget _buildFallbackPoster() {
    return Container(
      width: 50,
      height: 70,
      decoration: BoxDecoration(
        color: const Color(0xFF1C1C1E),
        borderRadius: BorderRadius.circular(8),
      ),
      child: const Icon(CupertinoIcons.play_rectangle, color: Colors.grey),
    );
  }
}

// =====================================================================
// 3. ПОДЭКРАН: СПИСОК ИЗМЕНЕНИЙ (CHANGELOG)
// =====================================================================
class _ChangelogSubScreen extends StatelessWidget {
  const _ChangelogSubScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 25.0, thickness: 10.0),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            SliverAppBar(
              expandedHeight: 100,
              backgroundColor: _bgColor.withValues(alpha: 0.8),
              pinned: true,
              leading: CupertinoButton(
                padding: EdgeInsets.zero,
                child: const Icon(CupertinoIcons.back, color: Colors.white),
                onPressed: () => Navigator.pop(context),
              ),
              flexibleSpace: const FlexibleSpaceBar(
                titlePadding: EdgeInsets.only(left: 48, bottom: 16),
                title: Text('История версий', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 20)),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildVersionCard(
                      version: 'AniMix Reborn (Текущая)',
                      date: 'В рамках масштабного рефакторинга',
                      features: [
                        '✨ Полный редизайн интерфейса по стандартам Apple Liquid Glass (iOS 26).',
                        '🚀 Внедрение AdaptiveLiquidGlassLayer для достижения стабильных 60 FPS при отрисовке размытия.',
                        '🛡 Создание монолитного ShikimoriApiClient с умным перехватом истекших сессий (ошибка 401).',
                        '🚦 Умная система последовательных очередей запросов для обхода лимитов и защиты от бана (429 Cloudflare).',
                        '🌟 Интерактивная модальная панель со звездами для оценки аниме (от 1 до 10) и добавления тайтлов в списки.',
                        '🔍 Интеграция полнофункционального поиска и фильтрации прямо на главном экране.',
                        '📝 Разработка умного парсера BB-кодов для чистого и читаемого описания аниме.',
                        '🔥 Разделение настроек на удобные подэкраны с нативной навигацией.',
                      ],
                      fixes: [
                        '🐛 Полностью устранен баг с белой пустой плашкой фильтров на iOS при отсутствии VPN.',
                        '🐛 Исправлен краш приложения (NoSuchMethodError) при несоответствии моделей и ответов API.',
                        '🐛 Починена статистика "В планах" (добавлено распознавание русскоязычных ключей "Запланировано").',
                        '🐛 Исправлено сжатие и обрезание текста на стеклянных кнопках (теперь "Сохранить" отображается корректно).',
                        '🐛 Восстановлено отображение постеров: они больше не обрезаются и выводятся в оригинальном качестве.',
                      ],
                      isLatest: true,
                    ),
                    const SizedBox(height: 64),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildVersionCard({
    required String version,
    required String date,
    required List<String> features,
    required List<String> fixes,
    bool isLatest = false,
  }) {
    return GlassContainer(
      quality: GlassQuality.premium,
      shape: const LiquidRoundedSuperellipse(borderRadius: 24),
      settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 20, specularSharpness: GlassSpecularSharpness.sharp),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(version, style: TextStyle(color: isLatest ? _accentLight : Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
                if (isLatest)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(color: _accentColor.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(8)),
                    child: const Text('NEW', style: TextStyle(color: _accentColor, fontSize: 10, fontWeight: FontWeight.bold)),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Text(date, style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 13, fontWeight: FontWeight.w500)),
            
            const SizedBox(height: 24),
            const Text('🚀 Новшества:', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            ...features.map((f) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('• ', style: TextStyle(color: _accentColor, fontSize: 16, fontWeight: FontWeight.bold)),
                Expanded(child: Text(f, style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 14, height: 1.4))),
              ]),
            )),

            const SizedBox(height: 24),
            const Text('🔧 Исправления:', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            ...fixes.map((f) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('• ', style: TextStyle(color: CupertinoColors.activeGreen, fontSize: 16, fontWeight: FontWeight.bold)),
                Expanded(child: Text(f, style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 14, height: 1.4))),
              ]),
            )),
          ],
        ),
      ),
    );
  }
}

// =====================================================================
// 4. ПОДЭКРАН: О ПРИЛОЖЕНИИ
// =====================================================================
class _AboutSubScreen extends StatelessWidget {
  const _AboutSubScreen();

  Future<void> _openGitHub() async {
    final url = Uri.parse('https://github.com/Wengine-wq/AniMix');
    if (await canLaunchUrl(url)) {
      await launchUrl(url, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 25.0, thickness: 10.0),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            SliverAppBar(
              expandedHeight: 100,
              backgroundColor: _bgColor.withValues(alpha: 0.8),
              pinned: true,
              leading: CupertinoButton(
                padding: EdgeInsets.zero,
                child: const Icon(CupertinoIcons.back, color: Colors.white),
                onPressed: () => Navigator.pop(context),
              ),
              flexibleSpace: const FlexibleSpaceBar(
                titlePadding: EdgeInsets.only(left: 48, bottom: 16),
                title: Text('О приложении', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 20)),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 32),
                child: Column(
                  children: [
                    // 🔥 ЛОГОТИП ПРИЛОЖЕНИЯ
                    GlassContainer(
                      shape: const LiquidRoundedSuperellipse(borderRadius: 36),
                      settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 20),
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(20),
                          child: Image.asset(
                            'assets/icon/app_icon.png',
                            width: 80, 
                            height: 80, 
                            fit: BoxFit.cover,
                            errorBuilder: (context, error, stackTrace) {
                              return const Icon(CupertinoIcons.play_circle_fill, size: 80, color: Colors.white);
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    const Text(
                      'AniMix',
                      style: TextStyle(color: Colors.white, fontSize: 32, fontWeight: FontWeight.w900, letterSpacing: -1.0),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Версия 1.1.0 Reborn',
                      style: TextStyle(color: _accentLight.withValues(alpha: 0.8), fontSize: 16, fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 32),
                    Text(
                      'Твой премиальный портал в мир аниме.\nСоздано с любовью к деталям, плавной анимации и безупречному дизайну.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white.withValues(alpha: 0.6), fontSize: 15, height: 1.5),
                    ),
                    const SizedBox(height: 48),
                    
                    // 🔥 ИСПРАВЛЕНА КНОПКА GITHUB: широкая, нормальный размер
                    SizedBox(
                      width: double.infinity,
                      child: GlassButton(
                        onTap: _openGitHub,
                        quality: GlassQuality.premium,
                        shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                        settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 15),
                        icon: const Padding(
                          padding: EdgeInsets.symmetric(vertical: 18),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(CupertinoIcons.link, color: Colors.white, size: 22),
                              SizedBox(width: 12),
                              Text('Проект на GitHub', style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold)),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}