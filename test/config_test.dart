import 'package:flutter_test/flutter_test.dart';

import 'package:animix/core/config.dart';

void main() {
  test('production OAuth configuration works without a local env file', () {
    expect(Config.shikimoriClientId, isNotEmpty);

    final proxyUri = Uri.parse(Config.shikimoriOAuthProxyUrl);
    expect(proxyUri.scheme, 'https');
    expect(proxyUri.host, endsWith('.workers.dev'));

    final redirectUri = Uri.parse(Config.shikimoriRedirectUri);
    expect(redirectUri.scheme, 'https');

    final desktopRedirectUri = Uri.parse(Config.shikimoriDesktopRedirectUri);
    expect(desktopRedirectUri.host, 'localhost');
    expect(desktopRedirectUri.port, 33333);
    expect(desktopRedirectUri.path, '/callback');
    expect(Config.isInitialized, isTrue);
    expect(Config.providerEmbedHeaders['Referer'], contains('yani.tv'));
    expect(Config.providerMediaHeaders['Referer'], 'https://kodikapi.com/');
    expect(Config.providerMediaHeaders, isNot(contains('Origin')));
  });
}
