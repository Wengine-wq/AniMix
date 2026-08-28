import 'package:animix/core/config.dart';
import 'package:animix/core/shikimori_oauth_flow.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('authorization and callback use one canonical HTTPS redirect', () {
    const state = 'secure-state';
    final authorization = ShikimoriOAuthFlow.authorizationUri(
      clientId: 'public-client',
      state: state,
    );

    expect(
      authorization.queryParameters['redirect_uri'],
      Config.shikimoriRedirectUri,
    );
    expect(authorization.queryParameters['state'], state);

    final callback = Uri.parse(
      Config.shikimoriRedirectUri,
    ).replace(queryParameters: {'code': 'auth-code', 'state': state});
    final result = ShikimoriOAuthFlow.parseCallback(
      callback.toString(),
      expectedState: state,
    );

    expect(result?.code, 'auth-code');
    expect(result?.isSuccess, isTrue);
  });

  test('rejects callback state mismatch and ignores unrelated navigation', () {
    final callback = Uri.parse(
      Config.shikimoriRedirectUri,
    ).replace(queryParameters: {'code': 'auth-code', 'state': 'wrong'});

    final mismatch = ShikimoriOAuthFlow.parseCallback(
      callback.toString(),
      expectedState: 'expected',
    );
    final unrelated = ShikimoriOAuthFlow.parseCallback(
      'https://shikimori.io/animes',
      expectedState: 'expected',
    );

    expect(mismatch?.isSuccess, isFalse);
    expect(mismatch?.error, contains('state'));
    expect(unrelated, isNull);
  });

  test('creates non-repeating URL-safe state values', () {
    final first = ShikimoriOAuthFlow.createState();
    final second = ShikimoriOAuthFlow.createState();

    expect(first, isNot(second));
    expect(first, matches(RegExp(r'^[A-Za-z0-9_-]{40,}$')));
  });
}
