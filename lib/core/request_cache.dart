/// Bounded, session-local reads. Invalidated requests cannot repopulate the cache.
class RequestCache {
  RequestCache({DateTime Function()? now}) : _now = now ?? DateTime.now;

  final DateTime Function() _now;
  final _values = <String, ({Object value, DateTime expires})>{};
  final _pending = <String, Future<Object?>>{};
  int _generation = 0;

  void clear() {
    _generation++;
    _values.clear();
    _pending.clear();
  }

  Future<T> read<T>(
    String key,
    Future<T> Function() load, {
    Duration ttl = const Duration(seconds: 30),
  }) async {
    final cached = _values[key];
    if (cached != null && _now().isBefore(cached.expires)) {
      return cached.value as T;
    }
    final pending = _pending[key];
    if (pending != null) return (await pending) as T;
    final generation = _generation;
    final request = Future<T>.sync(load);
    _pending[key] = request;
    try {
      final value = await request;
      if (value != null && generation == _generation) {
        _values.remove(key);
        _values[key] = (value: value, expires: _now().add(ttl));
        while (_values.length > 64) {
          _values.remove(_values.keys.first);
        }
      }
      return value;
    } finally {
      if (identical(_pending[key], request)) _pending.remove(key);
    }
  }
}
