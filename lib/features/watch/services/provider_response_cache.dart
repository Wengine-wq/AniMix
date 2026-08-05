import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

/// Small persistent cache for provider JSON responses.
///
/// It mirrors the short-lived API cache from the Swift app, but survives an
/// app restart and can return stale data when a provider is temporarily down.
class ProviderResponseCache {
  ProviderResponseCache._();

  static final ProviderResponseCache instance = ProviderResponseCache._();

  static const _maxFiles = 120;
  final Map<String, _CacheEntry> _memory = <String, _CacheEntry>{};

  Future<dynamic> get(String key, {required Duration maxAge}) async {
    final memory = _memory[key];
    if (memory != null && DateTime.now().difference(memory.savedAt) <= maxAge) {
      return memory.data;
    }

    final file = await _fileFor(key);
    if (!await file.exists()) return null;
    try {
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map) return null;
      final savedAt = DateTime.tryParse(decoded['savedAt']?.toString() ?? '');
      if (savedAt == null || DateTime.now().difference(savedAt) > maxAge) {
        return null;
      }
      final entry = _CacheEntry(savedAt: savedAt, data: decoded['data']);
      _memory[key] = entry;
      return entry.data;
    } catch (_) {
      return null;
    }
  }

  Future<void> put(String key, dynamic data) async {
    final now = DateTime.now();
    _memory[key] = _CacheEntry(savedAt: now, data: data);
    final file = await _fileFor(key);
    await file.parent.create(recursive: true);
    await file.writeAsString(
      jsonEncode(<String, dynamic>{
        'savedAt': now.toIso8601String(),
        'data': data,
      }),
      flush: true,
    );
    await _trim(file.parent);
  }

  Future<void> clear() async {
    _memory.clear();
    final root = await _root();
    if (await root.exists()) await root.delete(recursive: true);
  }

  Future<File> _fileFor(String key) async {
    final root = await _root();
    return File(
      '${root.path}${Platform.pathSeparator}${_stableHash(key)}.json',
    );
  }

  Future<Directory> _root() async {
    final cache = await getApplicationCacheDirectory();
    return Directory(
      '${cache.path}${Platform.pathSeparator}animix_provider_cache_v1',
    );
  }

  Future<void> _trim(Directory root) async {
    final files = await root
        .list()
        .where((entity) => entity is File)
        .cast<File>()
        .toList();
    if (files.length <= _maxFiles) return;
    files.sort((a, b) => a.lastModifiedSync().compareTo(b.lastModifiedSync()));
    for (final file in files.take(files.length - _maxFiles)) {
      await file.delete();
    }
  }

  static String _stableHash(String value) {
    var hash = 0x811c9dc5;
    for (final byte in utf8.encode(value)) {
      hash ^= byte;
      hash = (hash * 0x01000193) & 0xffffffff;
    }
    return hash.toRadixString(16).padLeft(8, '0');
  }
}

class _CacheEntry {
  const _CacheEntry({required this.savedAt, required this.data});

  final DateTime savedAt;
  final dynamic data;
}
