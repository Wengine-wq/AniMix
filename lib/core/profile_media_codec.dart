import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

/// Produces small, consistent JPEG profile media that safely fits in AniMix D1.
/// The original never leaves the device; only the normalized rendition is sent.
class ProfileMediaCodec {
  ProfileMediaCodec._();

  static const maxBytes = 1500 * 1024;

  static Future<Uint8List> encodeForUpload(
    Uint8List source, {
    required bool isBanner,
  }) => compute(
    _encode,
    _ProfileMediaRequest(source: source, maxDimension: isBanner ? 1600 : 640),
  );
}

class _ProfileMediaRequest {
  const _ProfileMediaRequest({
    required this.source,
    required this.maxDimension,
  });

  final Uint8List source;
  final int maxDimension;
}

Uint8List _encode(_ProfileMediaRequest request) {
  final decoded = img.decodeImage(request.source);
  if (decoded == null) {
    throw const FormatException('Unsupported profile image format');
  }

  var longestSide = request.maxDimension;
  var quality = 88;
  for (var attempt = 0; attempt < 5; attempt++) {
    final normalized = img.copyResize(
      decoded,
      width: decoded.width >= decoded.height ? longestSide : null,
      height: decoded.height > decoded.width ? longestSide : null,
      interpolation: img.Interpolation.average,
    );
    final encoded = Uint8List.fromList(
      img.encodeJpg(normalized, quality: quality),
    );
    if (encoded.lengthInBytes <= ProfileMediaCodec.maxBytes) return encoded;
    longestSide = (longestSide * .76).round();
    quality = quality - 10 < 48 ? 48 : quality - 10;
  }
  throw const FormatException(
    'Profile image remains too large after compression',
  );
}
