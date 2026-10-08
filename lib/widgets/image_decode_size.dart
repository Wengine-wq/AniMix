import 'dart:math' as math;

import 'package:flutter/widgets.dart';

/// Pixel width to decode a network image at, given the box it is drawn in.
///
/// Without a target size every poster is decoded at its full resolution (often
/// 4–8× more pixels than a grid tile shows), which costs decode time on the UI
/// isolate's raster thread and lots of image cache memory while scrolling.
///
/// `BoxFit.cover` can scale an image beyond the box width when the image is
/// relatively narrower than the box, so for covers the height is also taken
/// into account assuming a typical portrait poster ratio.
int? decodeWidthFor(
  BuildContext context,
  BoxConstraints constraints, {
  double? width,
  double? height,
  BoxFit? fit,
}) {
  final boxWidth = width ?? constraints.maxWidth;
  final boxHeight = height ?? constraints.maxHeight;
  if (!boxWidth.isFinite || boxWidth <= 0) return null;
  var logical = boxWidth;
  if ((fit ?? BoxFit.cover) == BoxFit.cover && boxHeight.isFinite) {
    logical = math.max(logical, boxHeight * 0.72);
  }
  final ratio = MediaQuery.maybeDevicePixelRatioOf(context) ?? 2;
  // Round up to a 64 px bucket so slightly different tiles share one decode.
  final pixels = (logical * ratio / 64).ceil() * 64;
  return pixels.clamp(64, 2048);
}
