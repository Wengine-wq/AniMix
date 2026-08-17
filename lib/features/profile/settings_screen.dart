import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/animix_theme.dart';
import '../../core/app_logging.dart';
import '../../core/app_settings.dart';
import '../../core/poster_fallback_service.dart';
import '../../core/secure_storage.dart';
import '../../providers/auth_provider.dart';
import '../../widgets/animix_surface.dart';
import '../auth/login_screen.dart';
import '../downloads/downloads_screen.dart';
import '../downloads/hls_download_manager.dart';
import '../watch/models/watch_mapping.dart';
import '../watch/repositories/watch_mapping_repository.dart';
import '../watch/services/provider_response_cache.dart';
import '../watch/services/resolved_stream_cache.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authenticated = ref
        .watch(isLoggedInProvider)
        .maybeWhen(data: (value) => value, orElse: () => false);
    return AnimatedBuilder(
      animation: AppSettingsController.instance,
      builder: (context, _) {
        final settings = AppSettingsController.instance;
        return AniMixPage(
          title: 'Настройки',
          child: ListView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
            children: [
              _SettingsHero(accent: settings.accentColor),
              const SizedBox(height: 30),
              const _SettingsSectionLabel('AniMix'),
              _SettingsGroup(
                children: [
                  _SettingsRow(
                    icon: CupertinoIcons.person_crop_circle_fill,
                    color: const Color(0xFFFF5E8A),
                    title: 'Мой профиль',
                    subtitle: authenticated
                        ? 'Shikimori подключён, списки синхронизируются'
                        : 'Войдите, чтобы синхронизировать списки',
                    onTap: authenticated
                        ? () => Navigator.maybePop(context)
                        : () => _push(context, const LoginScreen()),
                  ),
                  _SettingsRow(
                    icon: CupertinoIcons.paintbrush_fill,
                    color: settings.accentColor,
                    title: 'Оформление',
                    subtitle:
                        '${settings.themeMode.label} · ${settings.hasCustomAccent ? 'Свой цвет' : settings.accent.label} · ${settings.themeStyle.label}',
                    onTap: () => _push(context, const _AppearanceScreen()),
                  ),
                  _SettingsRow(
                    icon: CupertinoIcons.link_circle_fill,
                    color: const Color(0xFF43C6FF),
                    title: 'Умное соединение',
                    subtitle: settings.smartConnectionEnabled
                        ? 'Автовыбор включён'
                        : 'Ручной выбор включён',
                    onTap: () => _push(context, const _ConnectionScreen()),
                  ),
                  _SettingsRow(
                    icon: CupertinoIcons.archivebox_fill,
                    color: const Color(0xFF35D07F),
                    title: 'Данные и кеш',
                    subtitle: 'Загрузки, привязки и временные файлы',
                    onTap: () => _push(context, const _DataScreen()),
                  ),
                ],
              ),
              const SizedBox(height: 26),
              const _SettingsSectionLabel('Приложение'),
              _SettingsGroup(
                children: [
                  _SettingsRow(
                    icon: CupertinoIcons.doc_text_search,
                    color: const Color(0xFFFFA34D),
                    title: 'Диагностика',
                    subtitle: 'Ошибки приложения, которые можно скопировать',
                    onTap: () => _push(context, const _DiagnosticsScreen()),
                  ),
                  _SettingsRow(
                    icon: CupertinoIcons.info_circle_fill,
                    color: const Color(0xFF8D8D98),
                    title: 'О приложении',
                    subtitle: 'Возможности, изменения и разработчик',
                    onTap: () => _push(context, const _AboutScreen()),
                  ),
                ],
              ),
              const SizedBox(height: 26),
              const _SettingsSectionLabel('Аккаунт'),
              _SettingsGroup(
                children: [
                  _SettingsRow(
                    icon: authenticated
                        ? CupertinoIcons.square_arrow_right_fill
                        : CupertinoIcons.person_badge_plus,
                    color: authenticated
                        ? const Color(0xFFFF4E58)
                        : settings.accentColor,
                    title: authenticated
                        ? 'Выйти из аккаунта'
                        : 'Войти через Shikimori',
                    subtitle: authenticated
                        ? 'Загрузки останутся на устройстве'
                        : 'Синхронизация закладок и прогресса',
                    destructive: authenticated,
                    onTap: authenticated
                        ? () => _logout(context, ref)
                        : () => _push(context, const LoginScreen()),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  static void _push(BuildContext context, Widget page) =>
      Navigator.push(context, CupertinoPageRoute<void>(builder: (_) => page));

  static Future<void> _logout(BuildContext context, WidgetRef ref) async {
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('Выйти из аккаунта?'),
        content: const Text(
          'История и списки перестанут синхронизироваться. Локальные загрузки сохранятся.',
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
    ref.read(sessionNoticeProvider.notifier).clear();
    ref.invalidate(isLoggedInProvider);
  }
}

class _SettingsHero extends StatelessWidget {
  const _SettingsHero({required this.accent});
  final Color accent;

  @override
  Widget build(BuildContext context) => AniMixSurface(
    elevated: true,
    padding: const EdgeInsets.all(22),
    child: Row(
      children: [
        Container(
          width: 62,
          height: 62,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [accent, Color.lerp(accent, Colors.black, .32)!],
            ),
            borderRadius: BorderRadius.circular(19),
          ),
          alignment: Alignment.center,
          child: const Text(
            'A',
            style: TextStyle(fontSize: 31, fontWeight: FontWeight.w900),
          ),
        ),
        const SizedBox(width: 17),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'AniMix',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900),
              ),
              SizedBox(height: 4),
              Text(
                'Воспроизведение, данные и внешний вид',
                style: TextStyle(color: AniMixTheme.subtleText, fontSize: 14),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _AppearanceScreen extends StatelessWidget {
  const _AppearanceScreen();

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: AppSettingsController.instance,
    builder: (context, _) {
      final settings = AppSettingsController.instance;
      return AniMixPage(
        title: 'Оформление',
        child: ListView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
          children: [
            AniMixSurface(
              elevated: true,
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const AniMixSectionHeader(
                    title: 'Предпросмотр',
                    subtitle: 'Изменения применяются ко всему приложению',
                  ),
                  const SizedBox(height: 18),
                  Container(
                    height: 145,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Theme.of(context).scaffoldBackgroundColor,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 76,
                          decoration: BoxDecoration(
                            color: settings.accentColor.withValues(alpha: .22),
                            borderRadius: BorderRadius.circular(15),
                          ),
                          child: Icon(
                            CupertinoIcons.play_fill,
                            color: settings.accentColor,
                          ),
                        ),
                        const SizedBox(width: 15),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Container(
                                height: 13,
                                width: 150,
                                decoration: BoxDecoration(
                                  color: Theme.of(
                                    context,
                                  ).colorScheme.onSurface,
                                  borderRadius: BorderRadius.circular(8),
                                ),
                              ),
                              const SizedBox(height: 12),
                              Container(
                                height: 9,
                                width: 105,
                                decoration: BoxDecoration(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .onSurfaceVariant
                                      .withValues(alpha: .38),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                              ),
                              const SizedBox(height: 20),
                              Container(
                                height: 32,
                                width: 108,
                                decoration: BoxDecoration(
                                  color: settings.accentColor,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Акцентный цвет'),
            AniMixSurface(
              padding: const EdgeInsets.all(18),
              child: Wrap(
                spacing: 14,
                runSpacing: 14,
                children: [
                  for (final accent in AniMixAccent.values)
                    Builder(
                      builder: (context) {
                        final selected =
                            !settings.hasCustomAccent &&
                            settings.accent == accent;
                        return Semantics(
                          button: true,
                          selected: selected,
                          label: accent.label,
                          child: InkWell(
                            borderRadius: BorderRadius.circular(18),
                            onTap: () => settings.setAccent(accent),
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 180),
                              width: 58,
                              height: 58,
                              decoration: BoxDecoration(
                                color: accent.color,
                                borderRadius: BorderRadius.circular(18),
                                border: Border.all(
                                  width: 3,
                                  color: selected
                                      ? Colors.white
                                      : Colors.transparent,
                                ),
                              ),
                              child: selected
                                  ? const Icon(
                                      CupertinoIcons.check_mark,
                                      size: 22,
                                    )
                                  : null,
                            ),
                          ),
                        );
                      },
                    ),
                  _CustomColorButton(
                    color: settings.accentColor,
                    selected: settings.hasCustomAccent,
                    onTap: () => _pickCustomAccent(context, settings),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Яркость интерфейса'),
            _SettingsGroup(
              children: AniMixThemeMode.values
                  .map(
                    (mode) => _ChoiceRow(
                      icon: switch (mode) {
                        AniMixThemeMode.system =>
                          CupertinoIcons.circle_lefthalf_fill,
                        AniMixThemeMode.dark => CupertinoIcons.moon_fill,
                        AniMixThemeMode.light => CupertinoIcons.sun_max_fill,
                      },
                      title: mode.label,
                      subtitle: mode.description,
                      selected: settings.themeMode == mode,
                      onTap: () => settings.setThemeMode(mode),
                    ),
                  )
                  .toList(),
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Тема интерфейса'),
            _SettingsGroup(
              children: AniMixThemeStyle.values
                  .map(
                    (style) => _ChoiceRow(
                      icon: switch (style) {
                        AniMixThemeStyle.graphite =>
                          CupertinoIcons.circle_grid_hex_fill,
                        AniMixThemeStyle.midnight =>
                          CupertinoIcons.moon_stars_fill,
                        AniMixThemeStyle.translucent =>
                          CupertinoIcons.circle_grid_3x3_fill,
                        AniMixThemeStyle.oled =>
                          CupertinoIcons.circle_lefthalf_fill,
                      },
                      title: style.label,
                      subtitle: style.description,
                      selected: settings.themeStyle == style,
                      onTap: () => settings.setThemeStyle(style),
                    ),
                  )
                  .toList(),
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Вид коллекций'),
            _SettingsGroup(
              children: AniMixContentLayout.values
                  .map(
                    (layout) => _ChoiceRow(
                      icon: switch (layout) {
                        AniMixContentLayout.automatic =>
                          CupertinoIcons.wand_stars,
                        AniMixContentLayout.cards =>
                          CupertinoIcons.rectangle_grid_2x2,
                        AniMixContentLayout.list => CupertinoIcons.list_bullet,
                      },
                      title: layout.label,
                      subtitle: switch (layout) {
                        AniMixContentLayout.automatic =>
                          'Список на телефоне, карточки на широком экране',
                        AniMixContentLayout.cards =>
                          'Постеры и визуальная сетка',
                        AniMixContentLayout.list =>
                          'Компактно, больше информации',
                      },
                      selected: settings.contentLayout == layout,
                      onTap: () => settings.setContentLayout(layout),
                    ),
                  )
                  .toList(),
            ),
          ],
        ),
      );
    },
  );

  Future<void> _pickCustomAccent(
    BuildContext context,
    AppSettingsController settings,
  ) async {
    final color = await showModalBottomSheet<Color>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (_) => _CustomAccentSheet(initial: settings.accentColor),
    );
    if (color != null) await settings.setCustomAccent(color);
  }
}

class _CustomColorButton extends StatelessWidget {
  const _CustomColorButton({
    required this.color,
    required this.selected,
    required this.onTap,
  });
  final Color color;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: 'Свой цвет',
    child: InkWell(
      key: const ValueKey('custom_accent_button'),
      borderRadius: BorderRadius.circular(18),
      onTap: onTap,
      child: Container(
        width: 58,
        height: 58,
        decoration: BoxDecoration(
          gradient: const SweepGradient(
            colors: [
              Colors.red,
              Colors.yellow,
              Colors.green,
              Colors.cyan,
              Colors.blue,
              Colors.purple,
              Colors.red,
            ],
          ),
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
            width: 3,
            color: selected ? Colors.white : Colors.transparent,
          ),
        ),
        child: Container(
          margin: const EdgeInsets.all(13),
          decoration: BoxDecoration(
            color: selected ? color : Colors.black54,
            shape: BoxShape.circle,
            border: Border.all(color: Colors.white70),
          ),
          child: selected
              ? const Icon(CupertinoIcons.check_mark, size: 15)
              : const Icon(CupertinoIcons.plus, size: 15),
        ),
      ),
    ),
  );
}

class _CustomAccentSheet extends StatefulWidget {
  const _CustomAccentSheet({required this.initial});
  final Color initial;

  @override
  State<_CustomAccentSheet> createState() => _CustomAccentSheetState();
}

class _CustomAccentSheetState extends State<_CustomAccentSheet> {
  late HSVColor _hsv;

  @override
  void initState() {
    super.initState();
    _hsv = HSVColor.fromColor(widget.initial);
  }

  Color get _color => _hsv.toColor();

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.fromLTRB(
      22,
      12,
      22,
      22 + MediaQuery.viewInsetsOf(context).bottom,
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: Container(
            width: 38,
            height: 5,
            decoration: BoxDecoration(
              color: Colors.white24,
              borderRadius: BorderRadius.circular(99),
            ),
          ),
        ),
        const SizedBox(height: 20),
        const Text(
          'Свой акцентный цвет',
          style: TextStyle(fontSize: 23, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 6),
        const Text(
          'Цвет кнопок, выделений, навигации и индикаторов.',
          style: TextStyle(color: AniMixTheme.subtleText),
        ),
        const SizedBox(height: 22),
        Container(
          height: 72,
          decoration: BoxDecoration(
            color: _color,
            borderRadius: BorderRadius.circular(20),
          ),
          alignment: Alignment.center,
          child: Text(
            '#${_color.toARGB32().toRadixString(16).substring(2).toUpperCase()}',
            style: const TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w900,
              fontSize: 18,
              shadows: [Shadow(color: Colors.black54, blurRadius: 6)],
            ),
          ),
        ),
        const SizedBox(height: 18),
        _ColorSlider(
          label: 'Оттенок',
          value: _hsv.hue,
          max: 360,
          gradient: const LinearGradient(
            colors: [
              Colors.red,
              Colors.yellow,
              Colors.green,
              Colors.cyan,
              Colors.blue,
              Colors.purple,
              Colors.red,
            ],
          ),
          onChanged: (value) => setState(() => _hsv = _hsv.withHue(value)),
        ),
        _ColorSlider(
          label: 'Насыщенность',
          value: _hsv.saturation,
          max: 1,
          gradient: LinearGradient(
            colors: [
              HSVColor.fromAHSV(1, _hsv.hue, 0, _hsv.value).toColor(),
              HSVColor.fromAHSV(1, _hsv.hue, 1, _hsv.value).toColor(),
            ],
          ),
          onChanged: (value) =>
              setState(() => _hsv = _hsv.withSaturation(value)),
        ),
        _ColorSlider(
          label: 'Яркость',
          value: _hsv.value,
          max: 1,
          gradient: LinearGradient(
            colors: [Colors.black, _hsv.withValue(1).toColor()],
          ),
          onChanged: (value) => setState(() => _hsv = _hsv.withValue(value)),
        ),
        const SizedBox(height: 14),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: () => Navigator.pop(context, _color),
            child: const Text('Применить'),
          ),
        ),
      ],
    ),
  );
}

class _ColorSlider extends StatelessWidget {
  const _ColorSlider({
    required this.label,
    required this.value,
    required this.max,
    required this.gradient,
    required this.onChanged,
  });
  final String label;
  final double value;
  final double max;
  final Gradient gradient;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 7),
        Stack(
          alignment: Alignment.center,
          children: [
            Container(
              height: 12,
              margin: const EdgeInsets.symmetric(horizontal: 10),
              decoration: BoxDecoration(
                gradient: gradient,
                borderRadius: BorderRadius.circular(99),
              ),
            ),
            Slider(value: value, max: max, onChanged: onChanged),
          ],
        ),
      ],
    ),
  );
}

class _ConnectionScreen extends StatefulWidget {
  const _ConnectionScreen();

  @override
  State<_ConnectionScreen> createState() => _ConnectionScreenState();
}

class _ConnectionScreenState extends State<_ConnectionScreen> {
  var _mappingCount = 0;
  Object? _mappingError;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    try {
      final mappings = await WatchMappingRepository().getAll();
      if (mounted) {
        setState(() {
          _mappingCount = mappings.length;
          _mappingError = null;
        });
      }
    } catch (error) {
      if (mounted) setState(() => _mappingError = error);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: AppSettingsController.instance,
    builder: (context, _) {
      final settings = AppSettingsController.instance;
      final enabled = settings.smartConnectionEnabled;
      return AniMixPage(
        title: 'Умное соединение',
        child: ListView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
          children: [
            AniMixSurface(
              selected: enabled,
              padding: const EdgeInsets.fromLTRB(18, 16, 12, 16),
              child: Row(
                children: [
                  _SettingsIcon(
                    icon: enabled
                        ? CupertinoIcons.bolt_horizontal_circle_fill
                        : CupertinoIcons.hand_raised_fill,
                    color: enabled
                        ? Theme.of(context).colorScheme.primary
                        : AniMixTheme.subtleText,
                  ),
                  const SizedBox(width: 13),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          enabled
                              ? 'Автовыбор включён'
                              : 'Ручной выбор включён',
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          enabled
                              ? 'AniMix повторно использует проверенные релизы и потоки.'
                              : 'Приложение каждый раз спросит, какой релиз открыть.',
                          style: const TextStyle(
                            color: AniMixTheme.subtleText,
                            fontSize: 12,
                            height: 1.35,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Switch.adaptive(
                    value: enabled,
                    onChanged: settings.setSmartConnectionEnabled,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (_mappingError != null) ...[
              AniMixSurface(
                padding: const EdgeInsets.all(15),
                child: Row(
                  children: [
                    const Expanded(
                      child: Text('Не удалось прочитать сохранённые привязки'),
                    ),
                    TextButton(
                      onPressed: _reload,
                      child: const Text('Повторить'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],
            AniMixSurface(
              elevated: true,
              padding: const EdgeInsets.all(22),
              child: Column(
                children: [
                  Container(
                    width: 70,
                    height: 70,
                    decoration: BoxDecoration(
                      color: Theme.of(
                        context,
                      ).colorScheme.primary.withValues(alpha: .14),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      CupertinoIcons.link,
                      size: 30,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                  const SizedBox(height: 17),
                  Text(
                    enabled
                        ? 'Меньше повторных действий'
                        : 'Полный контроль перед запуском',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 21,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    enabled
                        ? 'Подходит, если вы обычно смотрите одну озвучку: сохраняются привязка релиза, ответы провайдеров и найденный HLS.'
                        : 'Подходит, если релизы часто определяются неверно или вы хотите вручную выбирать источник. Кеш и автосопоставление не используются.',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: AniMixTheme.subtleText,
                      height: 1.45,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Что делает автовыбор'),
            const _SettingsGroup(
              children: [
                _ConnectionStep(
                  index: '1',
                  title: 'YummyAnime',
                  subtitle: 'Находит релиз по данным Shikimori',
                ),
                _ConnectionStep(
                  index: '2',
                  title: 'Kodik HLS',
                  subtitle: 'Получает прямую HLS-ссылку вместо iframe',
                ),
                _ConnectionStep(
                  index: '3',
                  title: 'AniLiberty',
                  subtitle: 'Подставляет резервный прямой поток',
                ),
              ],
            ),
            const SizedBox(height: 28),
            const _SettingsSectionLabel('Сохранённые решения'),
            _SettingsGroup(
              children: [
                _SettingsRow(
                  icon: CupertinoIcons.rectangle_stack_fill,
                  color: const Color(0xFF43C6FF),
                  title: 'Управление привязками',
                  subtitle: '$_mappingCount сопоставлений аниме и релизов',
                  onTap: () async {
                    await Navigator.push(
                      context,
                      CupertinoPageRoute<void>(
                        builder: (_) => const _BindingsScreen(),
                      ),
                    );
                    await _reload();
                  },
                ),
              ],
            ),
          ],
        ),
      );
    },
  );
}

class _DiagnosticsScreen extends StatelessWidget {
  const _DiagnosticsScreen();

  @override
  Widget build(BuildContext context) {
    final logs = AppLogBuffer.instance;
    return AnimatedBuilder(
      animation: logs,
      builder: (context, _) => AniMixPage(
        title: 'Диагностика',
        child: ListView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
          children: [
            AniMixSurface(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const AniMixSectionHeader(
                    title: 'Журнал ошибок',
                    subtitle:
                        'Токены и коды авторизации скрываются автоматически',
                    icon: CupertinoIcons.exclamationmark_bubble_fill,
                  ),
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: () async {
                            await Clipboard.setData(
                              ClipboardData(text: logs.exportText()),
                            );
                            if (!context.mounted) return;
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Диагностика скопирована'),
                              ),
                            );
                          },
                          icon: const Icon(CupertinoIcons.doc_on_clipboard),
                          label: const Text('Скопировать'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      IconButton.filledTonal(
                        tooltip: 'Очистить журнал',
                        onPressed: logs.isEmpty ? null : logs.clear,
                        icon: const Icon(CupertinoIcons.trash),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            if (logs.isEmpty)
              const AniMixEmptyState(
                icon: CupertinoIcons.check_mark_circled,
                title: 'Ошибок нет',
                message: 'Если что-то сломается, подробности появятся здесь.',
              )
            else
              ...logs.entries.reversed.map(
                (entry) => Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: AniMixSurface(
                    radius: 16,
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              switch (entry.level) {
                                AppLogLevel.info => CupertinoIcons.info_circle,
                                AppLogLevel.warning =>
                                  CupertinoIcons.exclamationmark_triangle,
                                AppLogLevel.error =>
                                  CupertinoIcons.xmark_octagon_fill,
                              },
                              size: 17,
                              color: switch (entry.level) {
                                AppLogLevel.info => CupertinoColors.systemBlue,
                                AppLogLevel.warning =>
                                  CupertinoColors.systemOrange,
                                AppLogLevel.error => CupertinoColors.systemRed,
                              },
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                entry.source ?? entry.level.name.toUpperCase(),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            ),
                            Text(
                              _diagnosticTime(entry.timestamp),
                              style: const TextStyle(
                                color: AniMixTheme.subtleText,
                                fontSize: 10,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 9),
                        SelectableText(
                          entry.message,
                          style: const TextStyle(fontSize: 12, height: 1.4),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  static String _diagnosticTime(DateTime value) =>
      '${value.hour.toString().padLeft(2, '0')}:'
      '${value.minute.toString().padLeft(2, '0')}:'
      '${value.second.toString().padLeft(2, '0')}';
}

class _DataScreen extends StatefulWidget {
  const _DataScreen();

  @override
  State<_DataScreen> createState() => _DataScreenState();
}

class _DataScreenState extends State<_DataScreen> {
  var _downloads = 0;
  var _downloadBytes = 0;
  var _mappings = 0;
  var _busy = false;
  Object? _dataError;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    if (mounted) setState(() => _dataError = null);
    try {
      await HlsDownloadManager.instance.initialize();
      final items = HlsDownloadManager.instance.downloads;
      final mappings = await WatchMappingRepository().getAll();
      if (!mounted) return;
      setState(() {
        _downloads = items.length;
        _downloadBytes = items.fold<int>(
          0,
          (sum, item) => sum + (item.fileSizeBytes ?? 0),
        );
        _mappings = mappings.length;
      });
    } catch (error) {
      if (mounted) setState(() => _dataError = error);
    }
  }

  Future<void> _clearCache() async {
    if (_busy) return;
    setState(() => _busy = true);
    await Future.wait<void>([
      ProviderResponseCache.instance.clear(),
      PosterFallbackService.instance.clear(),
      ResolvedStreamCache.clear(),
    ]);
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Временный кеш очищен')));
  }

  @override
  Widget build(BuildContext context) => AniMixPage(
    title: 'Данные и кеш',
    child: ListView(
      physics: const BouncingScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
      children: [
        if (_dataError != null) ...[
          AniMixSurface(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                const Expanded(
                  child: Text('Не удалось прочитать локальные данные'),
                ),
                TextButton.icon(
                  onPressed: _reload,
                  icon: const Icon(CupertinoIcons.refresh),
                  label: const Text('Повторить'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
        ],
        Row(
          children: [
            Expanded(
              child: _DataStat(
                value: '$_downloads',
                label: 'загрузок',
                icon: CupertinoIcons.arrow_down_circle_fill,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _DataStat(
                value: _formatBytes(_downloadBytes),
                label: 'на устройстве',
                icon: CupertinoIcons.folder_fill,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _DataStat(
                value: '$_mappings',
                label: 'привязок',
                icon: CupertinoIcons.link_circle_fill,
              ),
            ),
          ],
        ),
        const SizedBox(height: 28),
        const _SettingsSectionLabel('Медиатека'),
        _SettingsGroup(
          children: [
            _SettingsRow(
              icon: CupertinoIcons.arrow_down_circle_fill,
              color: const Color(0xFF35D07F),
              title: 'Загрузки',
              subtitle: 'Скачанные серии и активные задачи',
              onTap: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(
                  builder: (_) => const DownloadsScreen(),
                ),
              ).then((_) => _reload()),
            ),
            _SettingsRow(
              icon: CupertinoIcons.link_circle_fill,
              color: const Color(0xFF43C6FF),
              title: 'Привязки плеера',
              subtitle: 'Изменить сохранённый выбор релиза',
              onTap: () => Navigator.push(
                context,
                CupertinoPageRoute<void>(
                  builder: (_) => const _BindingsScreen(),
                ),
              ).then((_) => _reload()),
            ),
          ],
        ),
        const SizedBox(height: 28),
        const _SettingsSectionLabel('Временные данные'),
        _SettingsGroup(
          children: [
            _SettingsRow(
              icon: CupertinoIcons.sparkles,
              color: const Color(0xFFFFB23E),
              title: _busy ? 'Очищаем…' : 'Очистить кеш',
              subtitle: 'Ответы API, обложки и перехваченные HLS-ссылки',
              onTap: _busy ? null : _clearCache,
            ),
          ],
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(4, 14, 4, 0),
          child: Text(
            'Скачанные серии и вход в аккаунт при очистке кеша не удаляются.',
            style: TextStyle(color: AniMixTheme.subtleText, fontSize: 12),
          ),
        ),
      ],
    ),
  );

  static String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 МБ';
    final mb = bytes / (1024 * 1024);
    if (mb < 1024) return '${mb.toStringAsFixed(mb < 10 ? 1 : 0)} МБ';
    return '${(mb / 1024).toStringAsFixed(1)} ГБ';
  }
}

class _BindingsScreen extends StatefulWidget {
  const _BindingsScreen();

  @override
  State<_BindingsScreen> createState() => _BindingsScreenState();
}

class _BindingsScreenState extends State<_BindingsScreen> {
  final _repository = WatchMappingRepository();
  late Future<List<WatchMapping>> _future;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _future = _repository.getAll();

  Future<void> _delete(WatchMapping mapping) async {
    await _repository.delete(mapping.key);
    if (mounted) setState(_reload);
  }

  Future<void> _clearAll() async {
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: const Text('Удалить все привязки?'),
        content: const Text(
          'При следующем запуске серии AniMix снова найдёт релизы.',
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Отмена'),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (accepted != true) return;
    await _repository.clearAll();
    if (mounted) setState(_reload);
  }

  @override
  Widget build(BuildContext context) => AniMixPage(
    title: 'Управление привязками',
    actions: [
      IconButton(
        tooltip: 'Удалить все',
        onPressed: _clearAll,
        icon: const Icon(CupertinoIcons.trash),
      ),
    ],
    child: FutureBuilder<List<WatchMapping>>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CupertinoActivityIndicator(radius: 14));
        }
        if (snapshot.hasError) {
          return AniMixEmptyState(
            icon: CupertinoIcons.exclamationmark_triangle,
            title: 'Не удалось загрузить привязки',
            message: 'Локальное хранилище временно недоступно.',
            actionLabel: 'Повторить',
            onAction: () => setState(_reload),
          );
        }
        final mappings = snapshot.data ?? const <WatchMapping>[];
        if (mappings.isEmpty) {
          return const AniMixEmptyState(
            icon: CupertinoIcons.link,
            title: 'Привязок пока нет',
            message: 'Они появятся после первого выбора релиза в плеере.',
          );
        }
        return ListView.separated(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
          itemCount: mappings.length,
          separatorBuilder: (_, _) => const SizedBox(height: 10),
          itemBuilder: (context, index) {
            final mapping = mappings[index];
            return Dismissible(
              key: ValueKey(mapping.key),
              direction: DismissDirection.endToStart,
              background: Container(
                alignment: Alignment.centerRight,
                padding: const EdgeInsets.only(right: 24),
                decoration: BoxDecoration(
                  color: const Color(0xFFBB2935),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Icon(CupertinoIcons.trash_fill),
              ),
              onDismissed: (_) => _delete(mapping),
              child: AniMixSurface(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    _SettingsIcon(
                      icon: mapping.provider.contains('yummy')
                          ? CupertinoIcons.play_rectangle_fill
                          : CupertinoIcons.tv_fill,
                      color: mapping.provider.contains('yummy')
                          ? const Color(0xFFFF8B43)
                          : const Color(0xFF43C6FF),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            mapping.releaseTitle,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '${_providerName(mapping.provider)} · Shikimori #${mapping.shikimoriId}',
                            style: const TextStyle(
                              color: AniMixTheme.subtleText,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      tooltip: 'Удалить',
                      onPressed: () => _delete(mapping),
                      icon: const Icon(CupertinoIcons.xmark_circle_fill),
                      color: AniMixTheme.subtleText,
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    ),
  );

  static String _providerName(String provider) => provider.contains('yummy')
      ? 'YummyAnime / Kodik'
      : provider.contains('anilibr')
      ? 'AniLiberty'
      : provider;
}

class _AboutScreen extends StatelessWidget {
  const _AboutScreen();

  @override
  Widget build(BuildContext context) => AniMixPage(
    title: 'О приложении',
    child: ListView(
      physics: const BouncingScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 72),
      children: [
        Column(
          children: [
            Container(
              width: 86,
              height: 86,
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Theme.of(context).colorScheme.primary,
                    Color.lerp(
                      Theme.of(context).colorScheme.primary,
                      Colors.black,
                      .38,
                    )!,
                  ],
                ),
                borderRadius: BorderRadius.circular(26),
              ),
              alignment: Alignment.center,
              child: const Text(
                'A',
                style: TextStyle(fontSize: 43, fontWeight: FontWeight.w900),
              ),
            ),
            const SizedBox(height: 14),
            const Text(
              'AniMix',
              style: TextStyle(fontSize: 30, fontWeight: FontWeight.w900),
            ),
            const SizedBox(height: 5),
            const Text(
              'Каталог, списки и просмотр в одном приложении',
              textAlign: TextAlign.center,
              style: TextStyle(color: AniMixTheme.subtleText),
            ),
          ],
        ),
        const SizedBox(height: 30),
        const _SettingsSectionLabel('Возможности'),
        const _FeatureGrid(),
        const SizedBox(height: 28),
        const _SettingsSectionLabel('Что нового'),
        const AniMixSurface(
          padding: EdgeInsets.symmetric(horizontal: 18, vertical: 4),
          child: Column(
            children: [
              _ChangeRow(
                icon: CupertinoIcons.rectangle_stack_fill,
                title: 'Обновлённый интерфейс',
                subtitle: 'Новая структура экранов и адаптивная навигация',
              ),
              _ChangeRow(
                icon: CupertinoIcons.play_rectangle_fill,
                title: 'Прямой HLS-плеер',
                subtitle: 'Перехват Kodik, качества и резервные источники',
              ),
              _ChangeRow(
                icon: CupertinoIcons.arrow_down_circle_fill,
                title: 'Умные загрузки',
                subtitle: 'Выбор серии и качества, офлайн-воспроизведение',
                divider: false,
              ),
            ],
          ),
        ),
        const SizedBox(height: 28),
        const _SettingsSectionLabel('Разработчик'),
        _SettingsGroup(
          children: [
            _SettingsRow(
              icon: CupertinoIcons.chevron_left_slash_chevron_right,
              color: Theme.of(context).colorScheme.primary,
              title: 'Wengine-wq',
              subtitle: 'Исходный код AniMix на GitHub',
              onTap: () => launchUrl(
                Uri.parse('https://github.com/Wengine-wq/AniMix'),
                mode: LaunchMode.externalApplication,
              ),
            ),
          ],
        ),
        const SizedBox(height: 22),
        const Text(
          'AniMix — независимый клиент для просмотра и ведения списков.',
          textAlign: TextAlign.center,
          style: TextStyle(color: AniMixTheme.subtleText, fontSize: 12),
        ),
      ],
    ),
  );
}

class _FeatureGrid extends StatelessWidget {
  const _FeatureGrid();

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final columns = constraints.maxWidth >= 720 ? 4 : 2;
      const items = [
        (CupertinoIcons.person_crop_circle_fill, 'Профиль', 'Shikimori sync'),
        (CupertinoIcons.play_rectangle_fill, 'Плеер', 'Прямой HLS'),
        (CupertinoIcons.arrow_down_circle_fill, 'Офлайн', 'Серии с собой'),
        (CupertinoIcons.sparkles, 'Для вас', 'Личная лента'),
      ];
      return GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: columns,
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 1.45,
        ),
        itemCount: items.length,
        itemBuilder: (context, index) {
          final item = items[index];
          return AniMixSurface(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  item.$1,
                  color: Theme.of(context).colorScheme.primary,
                  size: 24,
                ),
                const SizedBox(height: 11),
                Text(
                  item.$2,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 3),
                Text(
                  item.$3,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AniMixTheme.subtleText,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          );
        },
      );
    },
  );
}

class _ConnectionStep extends StatelessWidget {
  const _ConnectionStep({
    required this.index,
    required this.title,
    required this.subtitle,
  });
  final String index;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 17, vertical: 14),
    child: Row(
      children: [
        Container(
          width: 32,
          height: 32,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primary.withValues(alpha: .16),
            shape: BoxShape.circle,
          ),
          alignment: Alignment.center,
          child: Text(
            index,
            style: TextStyle(
              color: Theme.of(context).colorScheme.primary,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        const SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 3),
              Text(
                subtitle,
                style: const TextStyle(
                  color: AniMixTheme.subtleText,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
        const Icon(
          CupertinoIcons.check_mark_circled_solid,
          color: Color(0xFF35D07F),
        ),
      ],
    ),
  );
}

class _DataStat extends StatelessWidget {
  const _DataStat({
    required this.value,
    required this.label,
    required this.icon,
  });
  final String value;
  final String label;
  final IconData icon;

  @override
  Widget build(BuildContext context) => AniMixSurface(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 16),
    child: Column(
      children: [
        Icon(icon, color: Theme.of(context).colorScheme.primary, size: 21),
        const SizedBox(height: 9),
        FittedBox(
          child: Text(
            value,
            style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
          ),
        ),
        const SizedBox(height: 3),
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: AniMixTheme.subtleText, fontSize: 10),
        ),
      ],
    ),
  );
}

class _ChangeRow extends StatelessWidget {
  const _ChangeRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.divider = true,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final bool divider;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: 15),
    decoration: BoxDecoration(
      border: divider
          ? const Border(bottom: BorderSide(color: AniMixTheme.divider))
          : null,
    ),
    child: Row(
      children: [
        Icon(icon, color: Theme.of(context).colorScheme.primary, size: 21),
        const SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              Text(
                subtitle,
                style: const TextStyle(
                  color: AniMixTheme.subtleText,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _ChoiceRow extends StatelessWidget {
  const _ChoiceRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => _SettingsRow(
    icon: icon,
    color: selected
        ? Theme.of(context).colorScheme.primary
        : AniMixTheme.subtleText,
    title: title,
    subtitle: subtitle,
    onTap: onTap,
    trailing: Icon(
      selected
          ? CupertinoIcons.check_mark_circled_solid
          : CupertinoIcons.circle,
      color: selected ? Theme.of(context).colorScheme.primary : Colors.white24,
    ),
  );
}

class _SettingsSectionLabel extends StatelessWidget {
  const _SettingsSectionLabel(this.label);
  final String label;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(5, 0, 5, 9),
    child: Text(
      label.toUpperCase(),
      style: const TextStyle(
        color: AniMixTheme.subtleText,
        fontSize: 11,
        fontWeight: FontWeight.w800,
        letterSpacing: .8,
      ),
    ),
  );
}

class _SettingsGroup extends StatelessWidget {
  const _SettingsGroup({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => AniMixSurface(
    child: Column(
      children: [
        for (var index = 0; index < children.length; index++) ...[
          children[index],
          if (index != children.length - 1)
            const Padding(
              padding: EdgeInsets.only(left: 66),
              child: Divider(height: 1, color: AniMixTheme.divider),
            ),
        ],
      ],
    ),
  );
}

class _SettingsRow extends StatelessWidget {
  const _SettingsRow({
    required this.icon,
    required this.color,
    required this.title,
    this.subtitle,
    this.onTap,
    this.trailing,
    this.destructive = false,
  });
  final IconData icon;
  final Color color;
  final String title;
  final String? subtitle;
  final VoidCallback? onTap;
  final Widget? trailing;
  final bool destructive;

  @override
  Widget build(BuildContext context) => Material(
    color: Colors.transparent,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        child: Row(
          children: [
            _SettingsIcon(icon: icon, color: color),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      color: destructive
                          ? const Color(0xFFFF606A)
                          : Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (subtitle?.isNotEmpty == true) ...[
                    const SizedBox(height: 3),
                    Text(
                      subtitle!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AniMixTheme.subtleText,
                        fontSize: 12,
                        height: 1.3,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 10),
            trailing ??
                const Icon(
                  CupertinoIcons.chevron_forward,
                  size: 16,
                  color: Colors.white30,
                ),
          ],
        ),
      ),
    ),
  );
}

class _SettingsIcon extends StatelessWidget {
  const _SettingsIcon({required this.icon, required this.color});
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    width: 37,
    height: 37,
    decoration: BoxDecoration(
      color: color.withValues(alpha: .16),
      borderRadius: BorderRadius.circular(11),
    ),
    alignment: Alignment.center,
    child: Icon(icon, color: color, size: 20),
  );
}
