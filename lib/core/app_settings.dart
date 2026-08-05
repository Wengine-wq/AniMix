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

class AppSettingsController extends ChangeNotifier {
  AppSettingsController._();

  static final AppSettingsController instance = AppSettingsController._();
  static const _accentKey = 'appearance_accent_v1';
  static const _layoutKey = 'appearance_layout_v1';

  AniMixAccent _accent = AniMixAccent.violet;
  AniMixContentLayout _contentLayout = AniMixContentLayout.automatic;
  bool _initialized = false;

  AniMixAccent get accent => _accent;
  Color get accentColor => _accent.color;
  AniMixContentLayout get contentLayout => _contentLayout;

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
    _initialized = true;
    notifyListeners();
  }

  Future<void> setAccent(AniMixAccent value) async {
    if (_accent == value) return;
    _accent = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_accentKey, value.name);
  }

  Future<void> setContentLayout(AniMixContentLayout value) async {
    if (_contentLayout == value) return;
    _contentLayout = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_layoutKey, value.name);
  }
}
