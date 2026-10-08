import 'dart:async';

import 'package:animix/core/request_cache.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('shares concurrent reads and expires after TTL', () async {
    var now = DateTime(2026);
    final cache = RequestCache(now: () => now);
    final pending = Completer<int>();
    var calls = 0;
    Future<int> load() {
      calls++;
      return pending.future;
    }

    final first = cache.read('friends', load);
    final second = cache.read('friends', load);
    pending.complete(7);
    expect(await Future.wait([first, second]), [7, 7]);
    expect(await cache.read('friends', load), 7);
    expect(calls, 1);
    now = now.add(const Duration(seconds: 31));
    await cache.read('friends', load);
    expect(calls, 2);
  });

  test(
    'mutation prevents old in-flight reads from repopulating cache',
    () async {
      final cache = RequestCache();
      final pending = Completer<String>();
      final old = cache.read('friends', () => pending.future);
      cache.clear();
      expect(await cache.read('friends', () async => 'accepted'), 'accepted');
      pending.complete('incoming');
      await old;
      expect(await cache.read('friends', () async => 'wrong'), 'accepted');
    },
  );

  test('does not cache failures or null and bounds key count', () async {
    final cache = RequestCache();
    await expectLater(
      cache.read<int>('failed', () async => throw StateError('offline')),
      throwsStateError,
    );
    expect(await cache.read('failed', () async => 2), 2);
    expect(await cache.read<int?>('null', () async => null), isNull);
    expect(await cache.read('null', () async => 3), 3);
    for (var i = 0; i < 65; i++) {
      await cache.read('$i', () async => i);
    }
    expect(await cache.read('0', () async => 100), 100);
  });
}
