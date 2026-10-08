import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum AniMixAccent {
  violet('Фиолетовый', Color(0xFF8B5CF6)),
  blue('Синий', Color(0xFF3B82F6)),
  cyan('Бирюзовый', Color(0xFF06B6D4)),
  rose('Розовый', Color(0xFFF43F5E)),
  orange('Оранжевый', Color(0xFFF97316));

  const AniMixAccent(this.label, this.color);
  final String label;
  final Color color;
}

enum AniMixContentLayout {
  automatic('Автоматически'),
  cards('Карточки'),
  list('Список');

  const AniMixContentLayout(this.label);
  final String label;
}

enum AniMixThemeMode {
  system('Как в системе', 'Автоматически следует за iOS или Windows'),
  dark('Тёмная', 'Глубокий фон и светлый текст'),
  light('Светлая', 'Светлые поверхности и мягкие тени');

  const AniMixThemeMode(this.label, this.description);
  final String label;
  final String description;
}

enum AniMixThemeStyle {
  graphite('Графит', 'Нейтральный тёмный интерфейс'),
  midnight('Полночь', 'Холодный сине-чёрный фон'),
  translucent('Воздух', 'Полупрозрачные поверхности и мягкий свет'),
  oled('OLED', 'Чистый чёрный фон без подсветки');

  const AniMixThemeStyle(this.label, this.description);
  final String label;
  final String description;
}

class AppSettingsController extends ChangeNotifier {
  AppSettingsController._();

  static final AppSettingsController instance = AppSettingsController._();
  static const _accentKey = 'appearance_accent_v1';
  static const _layoutKey = 'appearance_layout_v1';
  static const _customAccentKey = 'appearance_custom_accent_v1';
  static const _themeStyleKey = 'appearance_theme_style_v1';
  static const _themeModeKey = 'appearance_theme_mode_v1';
  static const _smartConnectionKey = 'watch_smart_connection_v1';
  static const _autoSkipOpeningsKey = 'watch_auto_skip_openings_v1';
  static const _autoPlayNextEpisodeKey = 'watch_auto_play_next_episode_v1';
  static const _preferredQualityKey = 'watch_preferred_quality_v1';

  AniMixAccent _accent = AniMixAccent.violet;
  AniMixContentLayout _contentLayout = AniMixContentLayout.automatic;
  Color? _customAccent;
  AniMixThemeStyle _themeStyle = AniMixThemeStyle.graphite;
  AniMixThemeMode _themeMode = AniMixThemeMode.system;
  bool _smartConnectionEnabled = true;
  bool _autoSkipOpenings = true;
  bool _autoPlayNextEpisode = true;
  String? _preferredQuality;
  bool _initialized = false;

  AniMixAccent get accent => _accent;
  Color get accentColor => _customAccent ?? _accent.color;
  Color? get customAccent => _customAccent;
  bool get hasCustomAccent => _customAccent != null;
  AniMixContentLayout get contentLayout => _contentLayout;
  AniMixThemeStyle get themeStyle => _themeStyle;
  AniMixThemeMode get themeMode => _themeMode;
  bool get smartConnectionEnabled => _smartConnectionEnabled;
  bool get autoSkipOpenings => _autoSkipOpenings;
  bool get autoPlayNextEpisode => _autoPlayNextEpisode;

  /// Quality label the viewer last picked in the player (for example `720p`).
  /// Starting the next episode at that quality avoids a second, slow
  /// re-initialisation of the native player right after it opened.
  String? get preferredQuality => _preferredQuality;

  Future<void> initialize() async {
    if (_initialized) return;
    final prefs = await SharedPreferences.getInstance();
    _accent = AniMixAccent.values.firstWhere(
      (value) => value.name == prefs.getString(_accentKey),
      orElse: () => AniMixAccent.violet,
    );
    _contentLayout = AniMixContentLayout.values.firstWhere(
      (value) => value.name == prefs.getString(_layoutKey),
      orElse: () => AniMixContentLayout.automatic,
    );
    final customAccentValue = prefs.getInt(_customAccentKey);
    _customAccent = customAccentValue == null ? null : Color(customAccentValue);
    _themeStyle = AniMixThemeStyle.values.firstWhere(
      (value) => value.name == prefs.getString(_themeStyleKey),
      orElse: () => AniMixThemeStyle.graphite,
    );
    _themeMode = AniMixThemeMode.values.firstWhere(
      (value) => value.name == prefs.getString(_themeModeKey),
      orElse: () => AniMixThemeMode.system,
    );
    _smartConnectionEnabled = prefs.getBool(_smartConnectionKey) ?? true;
    _autoSkipOpenings = prefs.getBool(_autoSkipOpeningsKey) ?? true;
    _autoPlayNextEpisode = prefs.getBool(_autoPlayNextEpisodeKey) ?? true;
    _preferredQuality = prefs.getString(_preferredQualityKey);
    _initialized = true;
    notifyListeners();
  }

  Future<void> setAccent(AniMixAccent value) async {
    if (_accent == value && _customAccent == null) return;
    _accent = value;
    _customAccent = null;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_accentKey, value.name);
    await prefs.remove(_customAccentKey);
  }

  Future<void> setCustomAccent(Color value) async {
    _customAccent = Color(value.toARGB32());
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_customAccentKey, _customAccent!.toARGB32());
  }

  Future<void> setContentLayout(AniMixContentLayout value) async {
    if (_contentLayout == value) return;
    _contentLayout = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_layoutKey, value.name);
  }

  Future<void> setThemeStyle(AniMixThemeStyle value) async {
    if (_themeStyle == value) return;
    _themeStyle = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_themeStyleKey, value.name);
  }

  Future<void> setThemeMode(AniMixThemeMode value) async {
    if (_themeMode == value) return;
    _themeMode = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_themeModeKey, value.name);
  }

  Future<void> setSmartConnectionEnabled(bool value) async {
    if (_smartConnectionEnabled == value) return;
    _smartConnectionEnabled = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_smartConnectionKey, value);
  }

  Future<void> setAutoSkipOpenings(bool value) async {
    if (_autoSkipOpenings == value) return;
    _autoSkipOpenings = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoSkipOpeningsKey, value);
  }

  Future<void> setAutoPlayNextEpisode(bool value) async {
    if (_autoPlayNextEpisode == value) return;
    _autoPlayNextEpisode = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoPlayNextEpisodeKey, value);
  }

  Future<void> setPreferredQuality(String? value) async {
    if (_preferredQuality == value) return;
    _preferredQuality = value;
    // Not a visual setting, so listeners (the whole MaterialApp) stay quiet.
    final prefs = await SharedPreferences.getInstance();
    if (value == null) {
      await prefs.remove(_preferredQualityKey);
    } else {
      await prefs.setString(_preferredQualityKey, value);
    }
  }
}
