import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../providers/auth_provider.dart';
import '../../core/secure_storage.dart';
import '../../core/app_settings.dart';
import '../../core/animix_theme.dart';
import '../../core/poster_fallback_service.dart';
import '../auth/login_screen.dart';
import '../watch/models/watch_mapping.dart';
import '../watch/repositories/watch_mapping_repository.dart';
import '../downloads/downloads_screen.dart';
import '../watch/services/provider_response_cache.dart';
import '../watch/services/resolved_stream_cache.dart';

// =====================================================================
// ЦВЕТОВАЯ ПАЛИТРА И СТИЛИ
// =====================================================================
Color get _accentColor => AppSettingsController.instance.accentColor;
Color get _accentLight =>
    Color.lerp(_accentColor, Colors.white, 0.24) ?? _accentColor;
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
    final appearance = AppSettingsController.instance;

    return Scaffold(
      backgroundColor: _bgColor,
      body: CustomScrollView(
        physics: const BouncingScrollPhysics(
          parent: AlwaysScrollableScrollPhysics(),
        ),
        slivers: [
          SliverAppBar(
            expandedHeight: 120,
            backgroundColor: _bgColor.withValues(alpha: 0.8),
            pinned: true,
            flexibleSpace: const FlexibleSpaceBar(
              titlePadding: EdgeInsets.only(left: 20, bottom: 16),
              title: Text(
                'Настройки',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -0.5,
                ),
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildSectionTitle('Внешний вид'),
                  _buildSettingsGroup([
                    _SettingsTile(
                      icon: CupertinoIcons.paintbrush,
                      title: 'Акцентный цвет',
                      subtitle: appearance.accent.label,
                      iconColor: appearance.accentColor,
                      onTap: () => _showAccentPicker(context),
                    ),
                    _SettingsTile(
                      icon: CupertinoIcons.rectangle_grid_2x2,
                      title: 'Отображение контента',
                      subtitle: appearance.contentLayout.label,
                      onTap: () => _showLayoutPicker(context),
                      isLast: true,
                    ),
                  ]),

                  const SizedBox(height: 32),
                  _buildSectionTitle('Основные'),
                  _buildSettingsGroup([
                    _SettingsTile(
                      icon: CupertinoIcons.arrow_down_circle,
                      title: 'Загрузки',
                      onTap: () => Navigator.push(
                        context,
                        CupertinoPageRoute(
                          builder: (_) => const DownloadsScreen(),
                        ),
                      ),
                    ),
                    _SettingsTile(
                      icon: CupertinoIcons.delete_left,
                      title: 'Очистить временный кэш',
                      subtitle: 'API, восстановленные обложки и HLS-ссылки',
                      onTap: () => _clearTemporaryCache(context),
                    ),
                    _SettingsTile(
                      icon: CupertinoIcons.play_rectangle,
                      title: 'Привязки плеера',
                      onTap: () => Navigator.push(
                        context,
                        CupertinoPageRoute(
                          builder: (_) => const _WatchMappingsSubScreen(),
                        ),
                      ),
                      isLast: true,
                    ),
                  ]),

                  const SizedBox(height: 32),
                  _buildSectionTitle('О приложении'),
                  _buildSettingsGroup([
                    _SettingsTile(
                      icon: CupertinoIcons.doc_text,
                      title: 'Список изменений',
                      onTap: () => Navigator.push(
                        context,
                        CupertinoPageRoute(
                          builder: (_) => const _ChangelogSubScreen(),
                        ),
                      ),
                    ),
                    _SettingsTile(
                      icon: CupertinoIcons.info_circle,
                      title: 'О приложении',
                      onTap: () => Navigator.push(
                        context,
                        CupertinoPageRoute(
                          builder: (_) => const _AboutSubScreen(),
                        ),
                      ),
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
                            onTap: () => Navigator.push(
                              context,
                              CupertinoPageRoute(
                                builder: (_) => const LoginScreen(),
                              ),
                            ),
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
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 12, bottom: 8),
      child: Text(
        title.toUpperCase(),
        style: TextStyle(
          color: Colors.white.withValues(alpha: 0.5),
          fontSize: 12,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.0,
        ),
      ),
    );
  }

  Widget _buildSettingsGroup(List<Widget> children) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: AniMixTheme.surface.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AniMixTheme.divider),
      ),
      child: Column(children: children),
    );
  }

  Future<void> _showAccentPicker(BuildContext context) async {
    final settings = AppSettingsController.instance;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF111116),
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Акцентный цвет',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 18),
              Wrap(
                spacing: 14,
                runSpacing: 14,
                children: [
                  for (final accent in AniMixAccent.values)
                    Tooltip(
                      message: accent.label,
                      child: InkWell(
                        borderRadius: BorderRadius.circular(18),
                        onTap: () async {
                          await settings.setAccent(accent);
                          if (sheetContext.mounted) Navigator.pop(sheetContext);
                        },
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 180),
                          width: 54,
                          height: 54,
                          decoration: BoxDecoration(
                            color: accent.color,
                            borderRadius: BorderRadius.circular(18),
                            border: Border.all(
                              color: settings.accent == accent
                                  ? Colors.white
                                  : Colors.transparent,
                              width: 3,
                            ),
                          ),
                          child: settings.accent == accent
                              ? const Icon(
                                  CupertinoIcons.check_mark,
                                  color: Colors.white,
                                )
                              : null,
                        ),
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _showLayoutPicker(BuildContext context) async {
    final settings = AppSettingsController.instance;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF111116),
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const ListTile(
                title: Text(
                  'Отображение контента',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
                ),
                subtitle: Text(
                  'Автоматический режим использует список на телефоне и карточки на широком экране.',
                ),
              ),
              for (final layout in AniMixContentLayout.values)
                ListTile(
                  leading: Icon(
                    settings.contentLayout == layout
                        ? Icons.radio_button_checked_rounded
                        : Icons.radio_button_off_rounded,
                    color: settings.contentLayout == layout
                        ? settings.accentColor
                        : Colors.white38,
                  ),
                  title: Text(layout.label),
                  onTap: () async {
                    await settings.setContentLayout(layout);
                    if (sheetContext.mounted) Navigator.pop(sheetContext);
                  },
                ),
            ],
          ),
        ),
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

  Future<void> _clearTemporaryCache(BuildContext context) async {
    await Future.wait<void>([
      ProviderResponseCache.instance.clear(),
      PosterFallbackService.instance.clear(),
      ResolvedStreamCache.clear(),
    ]);
    if (!context.mounted) return;
    showCupertinoDialog<void>(
      context: context,
      builder: (dialogContext) => CupertinoAlertDialog(
        title: const Text('Кэш очищен'),
        content: const Text(
          'Загрузки и история просмотра сохранены. Данные провайдеров будут обновлены при следующем открытии.',
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Готово'),
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
  final String? subtitle;

  const _SettingsTile({
    required this.icon,
    required this.title,
    required this.onTap,
    this.isLast = false,
    this.iconColor,
    this.textColor,
    this.subtitle,
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
                Icon(
                  icon,
                  color: iconColor ?? Colors.white.withValues(alpha: 0.8),
                  size: 22,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: textColor ?? Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      if (subtitle != null) ...[
                        const SizedBox(height: 3),
                        Text(
                          subtitle!,
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.46),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                Icon(
                  CupertinoIcons.chevron_right,
                  color: Colors.white.withValues(alpha: 0.3),
                  size: 16,
                ),
              ],
            ),
          ),
        ),
        if (!isLast)
          Padding(
            padding: const EdgeInsets.only(left: 54),
            child: Divider(
              height: 1,
              color: Colors.white.withValues(alpha: 0.1),
            ),
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
  State<_WatchMappingsSubScreen> createState() =>
      _WatchMappingsSubScreenState();
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
          physics: const BouncingScrollPhysics(
            parent: AlwaysScrollableScrollPhysics(),
          ),
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
                title: Text(
                  'Привязки плеера',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                    fontSize: 20,
                  ),
                ),
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
                        Icon(
                          CupertinoIcons.exclamationmark_triangle,
                          size: 48,
                          color: CupertinoColors.systemRed.withValues(
                            alpha: 0.8,
                          ),
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'Не удалось загрузить',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          _errorMsg!,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.5),
                            fontSize: 14,
                          ),
                        ),
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
                      Icon(
                        CupertinoIcons.play_rectangle,
                        size: 64,
                        color: Colors.white.withValues(alpha: 0.2),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Нет сохраненных привязок',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.5),
                          fontSize: 16,
                        ),
                      ),
                    ],
                  ),
                ),
              )
            else
              SliverList(
                delegate: SliverChildBuilderDelegate((context, index) {
                  final mapping = _mappings[index];

                  return Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 8,
                    ),
                    child: GlassContainer(
                      quality: GlassQuality.standard,
                      shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                      settings: const LiquidGlassSettings(
                        glassColor: Color(0x1AFFFFFF),
                        blur: 15,
                      ),
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
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  const SizedBox(height: 6),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 8,
                                      vertical: 4,
                                    ),
                                    decoration: BoxDecoration(
                                      color: _accentColor.withValues(
                                        alpha: 0.2,
                                      ),
                                      borderRadius: BorderRadius.circular(8),
                                    ),
                                    child: Text(
                                      mapping.provider.toUpperCase(),
                                      style: TextStyle(
                                        color: _accentLight,
                                        fontSize: 10,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            CupertinoButton(
                              padding: const EdgeInsets.all(12),
                              onPressed: () => _deleteMapping(mapping.key),
                              child: const Icon(
                                CupertinoIcons.trash,
                                color: CupertinoColors.destructiveRed,
                                size: 22,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                }, childCount: _mappings.length),
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
          physics: const BouncingScrollPhysics(
            parent: AlwaysScrollableScrollPhysics(),
          ),
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
                title: Text(
                  'История версий',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                    fontSize: 20,
                  ),
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 16,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildVersionCard(
                      version: 'AniMix Reborn',
                      date: 'Текущая версия',
                      features: [
                        'Лёгкий адаптивный интерфейс: нижняя навигация на телефоне и боковая панель на широком экране.',
                        'Прямой нативный HLS-плеер без рекламного iframe с выбором доступного качества.',
                        'Загрузка отдельных серий, локальное HLS-воспроизведение и управление загрузками.',
                        'Восстановление сломанных обложек через YummyAnime и AniLiberty с кэшированием.',
                        'Акцентные цвета и режимы отображения карточками или списком.',
                        'Кэш ответов провайдеров, привязок релизов и перехваченных потоков.',
                      ],
                      fixes: [
                        'Исправлен запуск плеера после перехвата Kodik на Windows.',
                        'Убраны тяжёлые размытия из списков и полноэкранных скролл-слоёв.',
                        'Исправлены переполнения и сломанная пустая страница загрузок на широком экране.',
                        'Добавлена корректная iOS-интеграция WebKit и AVPlayer-плагинов.',
                        'Обновлена обработка ошибок API, HLS и повреждённых постеров.',
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
      settings: const LiquidGlassSettings(
        glassColor: Color(0x1AFFFFFF),
        blur: 20,
        specularSharpness: GlassSpecularSharpness.sharp,
      ),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  version,
                  style: TextStyle(
                    color: isLatest ? _accentLight : Colors.white,
                    fontSize: 22,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                if (isLatest)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: _accentColor.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      'NEW',
                      style: TextStyle(
                        color: _accentColor,
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              date,
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.5),
                fontSize: 13,
                fontWeight: FontWeight.w500,
              ),
            ),

            const SizedBox(height: 24),
            const Text(
              '🚀 Новшества:',
              style: TextStyle(
                color: Colors.white,
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            ...features.map(
              (f) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '• ',
                      style: TextStyle(
                        color: _accentColor,
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Expanded(
                      child: Text(
                        f,
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.8),
                          fontSize: 14,
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 24),
            const Text(
              '🔧 Исправления:',
              style: TextStyle(
                color: Colors.white,
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            ...fixes.map(
              (f) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '• ',
                      style: TextStyle(
                        color: CupertinoColors.activeGreen,
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Expanded(
                      child: Text(
                        f,
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.8),
                          fontSize: 14,
                          height: 1.4,
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
          physics: const BouncingScrollPhysics(
            parent: AlwaysScrollableScrollPhysics(),
          ),
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
                title: Text(
                  'О приложении',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                    fontSize: 20,
                  ),
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 32,
                ),
                child: Column(
                  children: [
                    // 🔥 ЛОГОТИП ПРИЛОЖЕНИЯ
                    GlassContainer(
                      shape: const LiquidRoundedSuperellipse(borderRadius: 36),
                      settings: const LiquidGlassSettings(
                        glassColor: Color(0x1AFFFFFF),
                        blur: 20,
                      ),
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
                              return const Icon(
                                CupertinoIcons.play_circle_fill,
                                size: 80,
                                color: Colors.white,
                              );
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    const Text(
                      'AniMix',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 32,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -1.0,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Версия 1.1.0 Reborn',
                      style: TextStyle(
                        color: _accentLight.withValues(alpha: 0.8),
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 32),
                    Text(
                      'Твой премиальный портал в мир аниме.\nСоздано с любовью к деталям, плавной анимации и безупречному дизайну.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.6),
                        fontSize: 15,
                        height: 1.5,
                      ),
                    ),
                    const SizedBox(height: 48),

                    // 🔥 ИСПРАВЛЕНА КНОПКА GITHUB: широкая, нормальный размер
                    SizedBox(
                      width: double.infinity,
                      child: GlassButton(
                        onTap: _openGitHub,
                        quality: GlassQuality.premium,
                        shape: const LiquidRoundedSuperellipse(
                          borderRadius: 20,
                        ),
                        settings: const LiquidGlassSettings(
                          glassColor: Color(0x1AFFFFFF),
                          blur: 15,
                        ),
                        icon: const Padding(
                          padding: EdgeInsets.symmetric(vertical: 18),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(
                                CupertinoIcons.link,
                                color: Colors.white,
                                size: 22,
                              ),
                              SizedBox(width: 12),
                              Text(
                                'Проект на GitHub',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 17,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
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
