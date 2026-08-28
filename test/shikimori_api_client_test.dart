import 'dart:convert';
import 'dart:typed_data';

import 'package:animix/core/config.dart';
import 'package:animix/core/shikimori_api_client.dart';
import 'package:animix/models/shikimori_anime.dart';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  test(
    'repeated full Shikimori pages are deduplicated and stop pagination',
    () async {
      final adapter = _RepeatedRatesAdapter(itemCount: 100);
      final dio = Dio(BaseOptions(baseUrl: 'https://shikimori.io'))
        ..httpClientAdapter = adapter;
      final provider = Provider<ShikimoriApiClient>(
        (ref) => ShikimoriApiClient(ref, dio: dio),
      );
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final rates = await container.read(provider).getUserAnimeRates(42);

      expect(rates, hasLength(100));
      expect(adapter.requests, 2);
      expect(rates.map((rate) => rate['target_id']).toSet(), hasLength(100));
    },
  );

  test('Shikimori poster URLs are direct by default', () {
    final anime = ShikimoriAnime.fromJson({
      'id': 1,
      'name': 'Test',
      'image': {'original': '/system/animes/original/1.jpg?42'},
      'score': '8.0',
    });

    final uri = Uri.parse(anime.imageUrl!);
    expect('${uri.scheme}://${uri.host}', Config.shikimoriBaseUrl);
    expect(uri.path, '/system/animes/original/1.jpg');
    expect(uri.query, '42');
  });

  test('production Shikimori API is always direct', () {
    final uri = Uri.parse(Config.shikimoriApiBaseUrl);
    expect('${uri.scheme}://${uri.host}', Config.shikimoriBaseUrl);
    expect(uri.path, isEmpty);
  });
}

class _RepeatedRatesAdapter implements HttpClientAdapter {
  _RepeatedRatesAdapter({required this.itemCount});

  final int itemCount;
  int requests = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests++;
    final rates = List.generate(
      itemCount,
      (index) => {
        'id': index + 1,
        'target_id': 10_000 + index,
        'target_type': 'Anime',
        'status': 'planned',
        'score': 0,
        'episodes': 0,
      },
    );
    return ResponseBody.fromString(
      jsonEncode(rates),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
