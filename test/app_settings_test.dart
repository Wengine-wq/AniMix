import 'package:animix/core/app_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('custom accent, theme and smart connection are persisted', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final settings = AppSettingsController.instance;
    await settings.initialize();

    const custom = Color(0xFF2BD9A8);
    await settings.setCustomAccent(custom);
    await settings.setThemeStyle(AniMixThemeStyle.midnight);
    await settings.setSmartConnectionEnabled(false);
    var notifications = 0;
    void listener() => notifications++;
    settings.addListener(listener);
    await settings.setContentLayout(AniMixContentLayout.cards);
    await settings.setContentLayout(AniMixContentLayout.list);
    settings.removeListener(listener);

    final prefs = await SharedPreferences.getInstance();
    expect(settings.accentColor.toARGB32(), custom.toARGB32());
    expect(settings.hasCustomAccent, isTrue);
    expect(settings.themeStyle, AniMixThemeStyle.midnight);
    expect(settings.smartConnectionEnabled, isFalse);
    expect(settings.contentLayout, AniMixContentLayout.list);
    expect(notifications, 2);
    expect(prefs.getInt('appearance_custom_accent_v1'), custom.toARGB32());
    expect(prefs.getString('appearance_theme_style_v1'), 'midnight');
    expect(prefs.getBool('watch_smart_connection_v1'), isFalse);
  });
}
