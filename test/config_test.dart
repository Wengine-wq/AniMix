import 'package:flutter_test/flutter_test.dart';

import 'package:animix/core/config.dart';

void main() {
  test('production OAuth configuration works without a local env file', () {
    expect(Config.shikimoriClientId, isNotEmpty);

    final proxyUri = Uri.parse(Config.shikimoriOAuthProxyUrl);
    expect(proxyUri.scheme, 'https');
    expect(proxyUri.host, endsWith('.apigw.yandexcloud.net'));

    final apiUri = Uri.parse(Config.animixApiBaseUrl);
    expect(apiUri.scheme, 'https');
    expect(apiUri.host, proxyUri.host);

    final shikimoriApiUri = Uri.parse(Config.shikimoriApiBaseUrl);
    expect(shikimoriApiUri.host, 'shikimori.io');
    expect(shikimoriApiUri.path, isEmpty);

    final redirectUri = Uri.parse(Config.shikimoriRedirectUri);
    expect(redirectUri.scheme, 'https');

    expect(redirectUri.host, isNot('localhost'));
    expect(redirectUri.path, '/callback');
    expect(Config.isInitialized, isTrue);
    expect(Config.providerEmbedHeaders['Referer'], contains('yani.tv'));
    expect(Config.providerMediaHeaders['Referer'], 'https://kodikapi.com/');
    expect(Config.providerMediaHeaders, isNot(contains('Origin')));
    expect(
      Uri.parse(Config.fallbackImageUrl('/system/animes/1.jpg')).host,
      'shikimori.one',
    );
    expect(
      Config.fallbackImageUrl('https://images.example.com/poster.jpg'),
      'https://images.example.com/poster.jpg',
    );
  });
}
