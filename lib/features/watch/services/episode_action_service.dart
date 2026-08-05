import 'dart:async';

import 'package:flutter/material.dart';

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

  static Future<void> chooseAndDownload(
    BuildContext context, {
    required Map<String, String> sources,
    required String episodeId,
    required String animeTitle,
    required String episodeName,
    String? posterUrl,
  }) async {
    final entries =
        sources.entries.where((entry) => entry.value.isNotEmpty).toList()
          ..sort((a, b) => _qualityRank(b.key).compareTo(_qualityRank(a.key)));
    if (entries.isEmpty || !context.mounted) return;

    final selected = await showModalBottomSheet<MapEntry<String, String>>(
      context: context,
      backgroundColor: const Color(0xFF141419),
      showDragHandle: true,
      constraints: const BoxConstraints(maxWidth: 560),
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Качество загрузки',
                style: TextStyle(fontSize: 21, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 5),
              const Text(
                'Будет сохранён прямой поток без рекламного плеера.',
                style: TextStyle(color: Colors.white60),
              ),
              const SizedBox(height: 16),
              for (final entry in entries)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: CircleAvatar(
                    backgroundColor: Theme.of(
                      sheetContext,
                    ).colorScheme.primary.withValues(alpha: 0.16),
                    child: Icon(
                      Icons.high_quality_rounded,
                      color: Theme.of(sheetContext).colorScheme.primary,
                    ),
                  ),
                  title: Text(
                    entry.key,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: Text(_qualityDescription(entry.key)),
                  trailing: const Icon(Icons.download_rounded),
                  onTap: () => Navigator.pop(sheetContext, entry),
                ),
            ],
          ),
        ),
      ),
    );
    if (selected == null || !context.mounted) return;

    final manager = HlsDownloadManager.instance;
    unawaited(
      manager.startDownload(
        url: selected.value,
        episodeId: episodeId,
        animeTitle: animeTitle,
        episodeName: episodeName,
        quality: selected.key,
        posterUrl: posterUrl,
      ),
    );
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$episodeName • ${selected.key}: загрузка началась'),
        behavior: SnackBarBehavior.floating,
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
