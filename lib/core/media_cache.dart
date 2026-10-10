import 'package:flutter_cache_manager/flutter_cache_manager.dart';

import 'app_settings.dart';

/// Dedicated image caches with lifetimes matched to the type of media.
///
/// Keeping posters and comment attachments separate prevents a large forum
/// image from evicting the artwork used across the whole application.
abstract final class AniMixMediaCache {
  static const _posterLifetime = Duration(days: 21);
  static const _commentLifetime = Duration(days: 7);
  static const _posterCapacity = 520;
  static const _commentCapacity = 240;

  static final CacheManager posters = CacheManager(
    Config(
      'animix_posters_v1',
      stalePeriod: _posterLifetime,
      maxNrOfCacheObjects: _posterCapacity,
    ),
  );

  /// Posters kept on the device for good: shown instantly and offline.
  static final CacheManager devicePosters = CacheManager(
    Config(
      'animix_posters_device_v1',
      stalePeriod: const Duration(days: 3650),
      maxNrOfCacheObjects: 20000,
    ),
  );

  static CacheManager postersFor(AniMixPosterStorage mode) =>
      mode == AniMixPosterStorage.device ? devicePosters : posters;

  static final CacheManager commentMedia = CacheManager(
    Config(
      'animix_comment_media_v1',
      stalePeriod: _commentLifetime,
      maxNrOfCacheObjects: _commentCapacity,
    ),
  );
}
