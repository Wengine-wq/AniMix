import 'package:flutter/cupertino.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/watch_mapping.dart';

Future<void> showReleasePicker({
  required BuildContext context,
  required List<Map<String, dynamic>> candidates,
  required int shikimoriId,
  required String provider,
  required Function(WatchMapping) onSelected,
}) async {
  await showCupertinoModalPopup(
    context: context,
    builder: (ctx) => CupertinoActionSheet(
      title: const Text('Выбери правильный релиз'),
      message: const Text('Найдено несколько похожих тайтлов'),
      actions: candidates.map((c) {
        final score = c['matchScore'] as int;
        return CupertinoActionSheetAction(
          onPressed: () {
            Navigator.pop(ctx);
            final mapping = WatchMapping(
              shikimoriId: shikimoriId,
              provider: provider,
              releaseId: c['id'].toString(),
              releaseTitle: c['title'],
              posterUrl: c['poster'],
              savedAt: DateTime.now(),
            );
            onSelected(mapping);
          },
          child: Row(
            children: [
              if (c['poster'] != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: CachedNetworkImage(
                    imageUrl: c['poster'],
                    width: 48,
                    height: 68,
                    fit: BoxFit.cover,
                  ),
                ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(c['title'], style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                    Text('${c['year']} • ${c['episodes']} эп.', style: const TextStyle(fontSize: 13, color: CupertinoColors.systemGrey)),
                  ],
                ),
              ),
              Text('$score%', style: TextStyle(color: score >= 90 ? const Color(0xFF4CAF50) : const Color(0xFFFF9800), fontWeight: FontWeight.bold)),
            ],
          ),
        );
      }).toList(),
      cancelButton: CupertinoActionSheetAction(
        onPressed: () => Navigator.pop(ctx),
        child: const Text('Отмена'),
      ),
    ),
  );
}