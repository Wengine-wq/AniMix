import 'dart:convert';
import 'dart:typed_data';

import 'package:animix/core/app_settings.dart';
import 'package:animix/features/watch/services/watch_resolver_service.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('AniLiberty current API shape produces usable candidates', () async {
    SharedPreferences.setMockInitialValues({});
    await AppSettingsController.instance.setSmartConnectionEnabled(false);
    final dio = Dio()..httpClientAdapter = _AniLibertyAdapter();
    final resolver = WatchResolverService(dio: dio);

    final candidates = await resolver.searchManual('anilibria', 'Наруто');

    expect(candidates, hasLength(1));
    expect(candidates.single['id'], 413);
    expect(candidates.single['episodes'], 450);
    expect(
      candidates.single['poster'],
      'https://anilibria.top/storage/releases/posters/413/poster.jpg',
    );
  });
}

class _AniLibertyAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    jsonEncode([
      {
        'id': 413,
        'year': 2007,
        'name': {
          'main': 'Наруто Ураганные хроники',
          'english': 'Naruto: Shippuuden',
          'alternative': null,
        },
        'episodes_total': 450,
        'poster': {'preview': '/storage/releases/posters/413/poster.jpg'},
      },
    ]),
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );

  @override
  void close({bool force = false}) {}
}
