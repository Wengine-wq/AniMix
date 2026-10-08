import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/app_settings.dart';
import '../../downloads/hls_download_manager.dart';
import '../kodik_webview_screen.dart';

class EpisodeActionService {
  const EpisodeActionService._();

  static Future<Map<String, String>?> resolveKodik(
    BuildContext context, {
    required String embedUrl,
    required String episodeTitle,
    required String animeTitle,
    required int animeId,
    required String episodeNumber,
    String? posterUrl,
  }) {
    return Navigator.of(context).push<Map<String, String>>(
      MaterialPageRoute(
        builder: (_) => KodikWebViewScreen(
          kodikEmbedUrl: embedUrl,
          episodeTitle: episodeTitle,
          animeTitle: animeTitle,
          animeId: animeId,
          episodeNumber: episodeNumber,
          posterUrl: posterUrl,
        ),
      ),
    );
  }

  /// Starts a download using the app-wide quality from Settings → Data. Only
  /// when that setting is "ask" does a picker appear, and the picker can save
  /// the choice so the next episodes download without asking.
  static Future<void> chooseAndDownload(
    BuildContext context, {
    required Map<String, String> sources,
    required String episodeId,
    required String animeTitle,
    required String episodeName,
    int? animeId,
    String? posterUrl,
    String? suggestedQuality,
  }) async {
    final entries =
        sources.entries.where((entry) => entry.value.isNotEmpty).toList()
          ..sort((a, b) => _qualityRank(b.key).compareTo(_qualityRank(a.key)));
    if (entries.isEmpty || !context.mounted) return;

    final settings = AppSettingsController.instance;
    final preference = settings.downloadQuality;
    final preferred = preference.pick(entries.map((entry) => entry.key));
    MapEntry<String, String>? selected;
    if (preferred != null) {
      selected = entries.firstWhere((entry) => entry.key == preferred);
    } else if (entries.length == 1) {
      selected = entries.first;
    } else {
      selected = await _askQuality(context, entries, suggestedQuality);
    }
    if (selected == null || !context.mounted) return;

    final manager = HlsDownloadManager.instance;
    unawaited(
      manager.startDownload(
        url: selected.value,
        episodeId: episodeId,
        animeId: animeId ?? int.tryParse(episodeId.split('_').first) ?? 0,
        animeTitle: animeTitle,
        episodeName: episodeName,
        quality: selected.key,
        posterUrl: posterUrl,
      ),
    );
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$episodeName · ${selected.key}: загрузка началась'),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  static Future<MapEntry<String, String>?> _askQuality(
    BuildContext context,
    List<MapEntry<String, String>> entries,
    String? suggestedQuality,
  ) {
    var remember = false;
    return showModalBottomSheet<MapEntry<String, String>>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      constraints: const BoxConstraints(maxWidth: 560),
      builder: (sheetContext) => StatefulBuilder(
        builder: (sheetContext, setSheetState) {
          final scheme = Theme.of(sheetContext).colorScheme;
          return SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Качество загрузки',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Можно задать один раз в Настройки → Данные и кеш.',
                    style: TextStyle(color: scheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 10),
                  for (final entry in entries)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        entry.key,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: Text(_qualityDescription(entry.key)),
                      trailing: Icon(
                        entry.key == suggestedQuality
                            ? Icons.download_for_offline_rounded
                            : Icons.file_download_outlined,
                        color: scheme.onSurfaceVariant,
                      ),
                      onTap: () {
                        if (remember) {
                          unawaited(
                            AppSettingsController.instance.setDownloadQuality(
                              AniMixDownloadQuality.forHeight(
                                _qualityRank(entry.key),
                              ),
                            ),
                          );
                        }
                        Navigator.pop(sheetContext, entry);
                      },
                    ),
                  const Divider(height: 20),
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    value: remember,
                    onChanged: (value) => setSheetState(() => remember = value),
                    title: const Text('Запомнить и больше не спрашивать'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  static String downloadId({
    required int animeId,
    required String provider,
    required String episodeNumber,
    String? translation,
  }) {
    final scope = '$provider-${translation ?? 'default'}'
        .toLowerCase()
        .replaceAll(RegExp(r'[^a-zа-я0-9]+', caseSensitive: false), '-');
    return '${animeId}_${scope}_$episodeNumber';
  }

  static int _qualityRank(String quality) =>
      int.tryParse(RegExp(r'\d+').firstMatch(quality)?.group(0) ?? '') ??
      (quality.toLowerCase().contains('auto') || quality.contains('Авто')
          ? -1
          : 0);

  static String _qualityDescription(String quality) {
    final height = _qualityRank(quality);
    if (height >= 1080) return 'Лучшее качество, больший размер';
    if (height >= 720) return 'Оптимальный баланс качества и размера';
    if (height > 0) return 'Компактный размер';
    return 'Качество выберет источник';
  }
}
