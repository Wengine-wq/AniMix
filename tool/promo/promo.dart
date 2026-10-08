// AniMix promo video, drawn as a pure function of time so every frame can be
// ignore_for_file: unused_element_parameter
// rendered deterministically (see promo_render_test.dart).
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

const kPromoSize = Size(1920, 1080);
const kPromoDuration = 32.5;

const _bg = Color(0xFF08080B);
const _red = Color(0xFFFF3A47);
const _violet = Color(0xFF8B5CF6);
const _text = Color(0xFFF5F5F7);
const _muted = Color(0xFF9A9BA6);

double _seg(double t, double a, double b) => ((t - a) / (b - a)).clamp(0.0, 1.0);
double _out(double x) => Curves.easeOutCubic.transform(x);
double _back(double x) => Curves.easeOutBack.transform(x);
double _inOut(double x) => Curves.easeInOutCubic.transform(x);

/// Scene opacity with a short fade at both ends.
double _scene(double t, double start, double end, {double fade = .35}) {
  if (t < start || t > end) return 0;
  return math.min(_seg(t, start, start + fade), 1 - _seg(t, end - fade, end));
}

class PromoAssets {
  const PromoAssets({required this.logo, required this.atlas});
  final ui.Image logo;
  final ui.Image atlas;
}

class Promo extends StatelessWidget {
  const Promo({required this.t, required this.assets, super.key});

  final double t;
  final PromoAssets assets;

  @override
  Widget build(BuildContext context) {
    final fadeOut = 1 - _seg(t, kPromoDuration - .5, kPromoDuration);
    return DefaultTextStyle(
      style: const TextStyle(
        fontFamily: 'Roboto',
        color: _text,
        decoration: TextDecoration.none,
      ),
      child: SizedBox.fromSize(
        size: kPromoSize,
        child: ColoredBox(
          color: Colors.black,
          child: Opacity(
            opacity: fadeOut,
            child: Stack(
              fit: StackFit.expand,
              children: [
                _Background(t: t),
                _layer(0, 3.5, (lt) => _Intro(lt: lt, logo: assets.logo)),
                _layer(3.4, 7.5, (lt) => _Free(lt: lt)),
                _layer(7.4, 12.7, (lt) => _Player(lt: lt)),
                _layer(12.6, 16.9, (lt) => _Dubs(lt: lt)),
                _layer(16.8, 21.3, (lt) => _Offline(lt: lt)),
                _layer(
                  21.2,
                  25.7,
                  (lt) => _Achievements(lt: lt, atlas: assets.atlas),
                ),
                _layer(25.6, 28.7, (lt) => _Montage(lt: lt)),
                _layer(
                  28.6,
                  kPromoDuration,
                  (lt) => _Finale(lt: lt, logo: assets.logo),
                  fadeOutEnd: false,
                ),
                PromoWipes(t: t),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _layer(
    double start,
    double end,
    Widget Function(double lt) builder, {
    bool fadeOutEnd = true,
  }) {
    final opacity = fadeOutEnd
        ? _scene(t, start, end)
        : (t < start ? 0.0 : _seg(t, start, start + .35));
    if (opacity <= 0) return const SizedBox.shrink();
    return Opacity(opacity: opacity, child: builder(t - start));
  }
}

// ---------------------------------------------------------------- background

class _Background extends StatelessWidget {
  const _Background({required this.t});
  final double t;

  @override
  Widget build(BuildContext context) {
    final a = t * .35;
    return Stack(
      fit: StackFit.expand,
      children: [
        const ColoredBox(color: _bg),
        _Glow(
          center: Offset(420 + math.sin(a) * 160, 260 + math.cos(a * .8) * 90),
          radius: 760,
          color: _red.withValues(alpha: .20),
        ),
        _Glow(
          center: Offset(
            1500 + math.cos(a * .9) * 180,
            820 + math.sin(a * 1.1) * 80,
          ),
          radius: 820,
          color: _violet.withValues(alpha: .18),
        ),
        // Fine diagonal lines add depth without noise.
        CustomPaint(painter: _LinesPainter(t)),
      ],
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({required this.center, required this.radius, required this.color});
  final Offset center;
  final double radius;
  final Color color;

  @override
  Widget build(BuildContext context) => Positioned(
    left: center.dx - radius,
    top: center.dy - radius,
    width: radius * 2,
    height: radius * 2,
    child: DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(colors: [color, color.withValues(alpha: 0)]),
      ),
    ),
  );
}

class _LinesPainter extends CustomPainter {
  const _LinesPainter(this.t);
  final double t;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: .025)
      ..strokeWidth = 1;
    final shift = (t * 30) % 80;
    for (var x = -size.height; x < size.width; x += 80) {
      canvas.drawLine(
        Offset(x + shift, size.height),
        Offset(x + shift + size.height, 0),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_LinesPainter oldDelegate) => oldDelegate.t != t;
}

// ---------------------------------------------------------------- shared

class _Kicker extends StatelessWidget {
  const _Kicker(this.text, {this.color = _red});
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Text(
    text.toUpperCase(),
    style: TextStyle(
      color: color,
      fontSize: 22,
      letterSpacing: 6,
      fontWeight: FontWeight.w700,
    ),
  );
}

/// Text that rises and fades in, starting at [delay] seconds of [lt].
class _Rise extends StatelessWidget {
  const _Rise({
    required this.lt,
    required this.delay,
    required this.child,
    this.distance = 40,
    this.duration = .6,
  });

  final double lt;
  final double delay;
  final double distance;
  final double duration;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final p = _out(_seg(lt, delay, delay + duration));
    return Opacity(
      opacity: p,
      child: Transform.translate(
        offset: Offset(0, (1 - p) * distance),
        child: child,
      ),
    );
  }
}

class _Phone extends StatelessWidget {
  const _Phone({
    required this.child,
    this.width = 400,
    this.height = 820,
  });

  final Widget child;
  final double width;
  final double height;

  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: height,
    padding: const EdgeInsets.all(11),
    decoration: BoxDecoration(
      color: const Color(0xFF17171C),
      borderRadius: BorderRadius.circular(58),
      boxShadow: [
        BoxShadow(
          color: Colors.black.withValues(alpha: .6),
          blurRadius: 80,
          offset: const Offset(0, 40),
        ),
        BoxShadow(color: _red.withValues(alpha: .12), blurRadius: 120),
      ],
    ),
    child: ClipRRect(
      borderRadius: BorderRadius.circular(48),
      child: ColoredBox(
        color: const Color(0xFF0C0C0F),
        child: Stack(
          children: [
            Positioned.fill(child: child),
            Positioned(
              top: 12,
              left: 0,
              right: 0,
              child: Center(
                child: Container(
                  width: 110,
                  height: 30,
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(20),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

const _palettes = [
  [Color(0xFF3B1E7A), Color(0xFFE0457B), Color(0xFFFFB86B)],
  [Color(0xFF0E3B5C), Color(0xFF1FB5C9), Color(0xFFB8F2E6)],
  [Color(0xFF2B0F1E), Color(0xFFC81E3A), Color(0xFFFF8A5B)],
  [Color(0xFF1B2A1E), Color(0xFF2E9E6B), Color(0xFFD8F28A)],
  [Color(0xFF1A1440), Color(0xFF6C4BFF), Color(0xFFFF7AD9)],
  [Color(0xFF3A2410), Color(0xFFE08A2E), Color(0xFFFFE08A)],
  [Color(0xFF0D1B2A), Color(0xFF415A77), Color(0xFFE0E1DD)],
  [Color(0xFF301934), Color(0xFFB5179E), Color(0xFF7209B7)],
];

const _titles = [
  'Магическая битва',
  'Фрирен',
  'Клинок, рассекающий демонов',
  'Ванпанчмен',
  'Атака титанов',
  'Ходячий замок',
  'Монолог фармацевта',
  'Поднятие уровня',
];

/// Stylised stand-in for poster art: layered gradients and shapes.
class _Art extends StatelessWidget {
  const _Art(this.index, {this.t = 0});
  final int index;
  final double t;

  @override
  Widget build(BuildContext context) {
    final p = _palettes[index % _palettes.length];
    return CustomPaint(painter: _ArtPainter(p, index, t));
  }
}

class _ArtPainter extends CustomPainter {
  const _ArtPainter(this.palette, this.seed, this.t);
  final List<Color> palette;
  final int seed;
  final double t;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [palette[0], palette[1]],
        ).createShader(rect),
    );
    final random = math.Random(seed * 31 + 7);
    for (var i = 0; i < 5; i++) {
      final r = size.shortestSide * (.18 + random.nextDouble() * .35);
      final c = Offset(
        size.width * random.nextDouble() + math.sin(t + i) * 6,
        size.height * random.nextDouble(),
      );
      canvas.drawCircle(
        c,
        r,
        Paint()
          ..shader = RadialGradient(
            colors: [
              palette[2].withValues(alpha: .55),
              palette[2].withValues(alpha: 0),
            ],
          ).createShader(Rect.fromCircle(center: c, radius: r)),
      );
    }
    // Silhouette-like horizon for a "scene" feel.
    final path = Path()..moveTo(0, size.height * .78);
    for (var x = 0.0; x <= size.width; x += size.width / 6) {
      path.lineTo(x, size.height * (.68 + random.nextDouble() * .14));
    }
    path
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(path, Paint()..color = Colors.black.withValues(alpha: .35));
  }

  @override
  bool shouldRepaint(_ArtPainter oldDelegate) => oldDelegate.t != t;
}

class _Poster extends StatelessWidget {
  const _Poster({required this.index, this.width = 220, this.t = 0});
  final int index;
  final double width;
  final double t;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(width * .09),
    child: SizedBox(
      width: width,
      height: width * 1.45,
      child: Stack(
        fit: StackFit.expand,
        children: [
          _Art(index, t: t),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0x00000000), Color(0xCC000000)],
                stops: [.5, 1],
              ),
            ),
          ),
          Positioned(
            left: width * .08,
            right: width * .08,
            bottom: width * .08,
            child: Text(
              _titles[index % _titles.length],
              maxLines: 2,
              style: TextStyle(
                fontSize: width * .085,
                height: 1.1,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

// ---------------------------------------------------------------- 1. intro

class _Intro extends StatelessWidget {
  const _Intro({required this.lt, required this.logo});
  final double lt;
  final ui.Image logo;

  @override
  Widget build(BuildContext context) {
    final logoIn = _back(_seg(lt, .15, 1.1));
    final exit = _inOut(_seg(lt, 2.9, 3.5));
    const word = 'AniMix';
    return Transform.scale(
      scale: 1 + exit * .25,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Opacity(
              opacity: _seg(lt, .15, .6),
              child: Transform.scale(
                scale: .55 + .45 * logoIn,
                child: Container(
                  width: 260,
                  height: 260,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(64),
                    boxShadow: [
                      BoxShadow(
                        color: _red.withValues(alpha: .35 * logoIn),
                        blurRadius: 120,
                      ),
                    ],
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: RawImage(image: logo, fit: BoxFit.cover),
                ),
              ),
            ),
            const SizedBox(height: 46),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (var i = 0; i < word.length; i++)
                  _Rise(
                    lt: lt,
                    delay: .8 + i * .07,
                    distance: 60,
                    child: Text(
                      word[i],
                      style: const TextStyle(
                        fontSize: 132,
                        height: 1,
                        letterSpacing: -4,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 20),
            _Rise(
              lt: lt,
              delay: 1.6,
              child: const Text(
                'Аниме — без лишнего',
                style: TextStyle(
                  color: _muted,
                  fontSize: 40,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 2. free

class _Free extends StatelessWidget {
  const _Free({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) {
    final slam = _out(_seg(lt, .25, .7));
    final bar = _inOut(_seg(lt, .7, 1.2));
    final exit = _inOut(_seg(lt, 3.6, 4.1));
    const chips = ['Без подписок', 'Без рекламы в плеере', 'Без скрытых платежей'];
    return Transform.translate(
      offset: Offset(-exit * 300, 0),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _Rise(lt: lt, delay: 0, child: const _Kicker('Всё в AniMix')),
            const SizedBox(height: 24),
            Opacity(
              opacity: slam,
              child: Transform.scale(
                scale: 1.6 - .6 * slam,
                child: const Text(
                  'БЕСПЛАТНО',
                  style: TextStyle(
                    fontSize: 230,
                    height: .95,
                    letterSpacing: -8,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Container(
              width: 1180 * bar,
              height: 10,
              decoration: BoxDecoration(
                color: _red,
                borderRadius: BorderRadius.circular(10),
                boxShadow: [
                  BoxShadow(color: _red.withValues(alpha: .6), blurRadius: 30),
                ],
              ),
            ),
            const SizedBox(height: 56),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (var i = 0; i < chips.length; i++) ...[
                  if (i > 0) const SizedBox(width: 22),
                  Transform.scale(
                    scale: .8 + .2 * _back(_seg(lt, 1.3 + i * .22, 1.8 + i * .22)),
                    child: Opacity(
                      opacity: _seg(lt, 1.3 + i * .22, 1.6 + i * .22),
                      child: _Chip(chips[i]),
                    ),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.label, {this.icon = Icons.check_rounded});
  final String label;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(26, 18, 32, 18),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .07),
      borderRadius: BorderRadius.circular(999),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: _red, size: 34),
        const SizedBox(width: 14),
        Text(
          label,
          style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w600),
        ),
      ],
    ),
  );
}

// ---------------------------------------------------------------- 3. player

class _Player extends StatelessWidget {
  const _Player({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) {
    const bullets = [
      'Тап — отклик без задержки',
      'Тапы перемотки складываются',
      'Качество меняется без чёрного экрана',
      'Опенинги пропускаются сами',
    ];
    final enter = _out(_seg(lt, .1, .9));
    return Stack(
      children: [
        Positioned(
          left: 130,
          top: 230,
          width: 640,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Rise(lt: lt, delay: 0, child: const _Kicker('Свой плеер')),
              const SizedBox(height: 22),
              _Rise(
                lt: lt,
                delay: .15,
                child: const Text(
                  'Без задержек.\nБез рекламы.',
                  style: TextStyle(
                    fontSize: 88,
                    height: 1.02,
                    letterSpacing: -3,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(height: 44),
              for (var i = 0; i < bullets.length; i++)
                _Rise(
                  lt: lt,
                  delay: .9 + i * .45,
                  distance: 20,
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 20),
                    child: Row(
                      children: [
                        Container(
                          width: 12,
                          height: 12,
                          decoration: const BoxDecoration(
                            color: _red,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 20),
                        Text(
                          bullets[i],
                          style: const TextStyle(
                            fontSize: 32,
                            color: Color(0xFFD9D9DE),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
        Positioned(
          right: 110,
          top: 250,
          child: Opacity(
            opacity: enter,
            child: Transform(
              alignment: Alignment.centerLeft,
              transform: Matrix4.identity()
                ..setEntry(3, 2, .0009)
                ..rotateY(-.28 + .2 * enter)
                ..translateByDouble((1 - enter) * 300, 0, 0, 1),
              child: _PlayerMock(lt: lt),
            ),
          ),
        ),
      ],
    );
  }
}

class _PlayerMock extends StatelessWidget {
  const _PlayerMock({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) {
    final progress = .18 + lt * .045;
    // Three quick taps at 1.6 s: the seek amount accumulates 10 → 30.
    final taps = lt < 1.6
        ? 0
        : lt < 1.85
        ? 1
        : lt < 2.1
        ? 2
        : 3;
    final seekVisible = lt > 1.6 && lt < 2.9;
    final quality = lt < 3.1 ? '720p' : '1080p';
    final switchPulse = _seg(lt, 3.1, 3.5);
    final skipIn = _back(_seg(lt, 3.7, 4.1));
    return Container(
      width: 900,
      height: 506,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF17171C),
        borderRadius: BorderRadius.circular(40),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: .6),
            blurRadius: 50,
            offset: const Offset(0, 16),
          ),
          BoxShadow(color: _violet.withValues(alpha: .16), blurRadius: 120),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(30),
        child: Stack(
          fit: StackFit.expand,
          children: [
            Transform.scale(scale: 1.05 + lt * .01, child: _Art(4, t: lt)),
            const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0x80000000), Color(0x00000000), Color(0xB3000000)],
                  stops: [0, .45, 1],
                ),
              ),
            ),
            const Positioned(
              left: 28,
              top: 22,
              child: Text(
                'Серия 7 · Фрирен',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
              ),
            ),
            const Center(
              child: Icon(Icons.pause_rounded, size: 96, color: Colors.white),
            ),
            if (seekVisible)
              Positioned(
                right: 120,
                top: 0,
                bottom: 0,
                child: Center(
                  child: Transform.scale(
                    scale: .9 + .1 * math.sin(lt * 18).abs(),
                    child: Container(
                      width: 150,
                      height: 150,
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: .5),
                        shape: BoxShape.circle,
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(
                            Icons.fast_forward_rounded,
                            size: 50,
                            color: Colors.white,
                          ),
                          Text(
                            '+${taps * 10} c',
                            style: const TextStyle(
                              fontSize: 26,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            Positioned(
              left: 28,
              right: 28,
              bottom: 26,
              child: Row(
                children: [
                  const Text('12:41', style: TextStyle(fontSize: 20)),
                  const SizedBox(width: 18),
                  Expanded(
                    child: Stack(
                      alignment: Alignment.centerLeft,
                      children: [
                        Container(height: 6, color: Colors.white24),
                        FractionallySizedBox(
                          widthFactor: progress.clamp(0.0, 1.0),
                          child: Container(height: 6, color: _red),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 18),
                  Transform.scale(
                    scale: 1 + .18 * math.sin(switchPulse * math.pi),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 7,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: .16),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        quality,
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Positioned(
              right: 28,
              bottom: 82,
              child: Opacity(
                opacity: skipIn.clamp(0.0, 1.0),
                child: Transform.scale(
                  scale: skipIn,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 26,
                      vertical: 16,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.skip_next_rounded, color: Colors.black),
                        SizedBox(width: 8),
                        Text(
                          'Пропустить опенинг',
                          style: TextStyle(
                            color: Colors.black,
                            fontSize: 22,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 4. dubs

class _Dubs extends StatelessWidget {
  const _Dubs({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) {
    const dubs = [
      ('AniLiberty', '28 эп. · прямой поток'),
      ('AniDUB', '28 эп. · прямой поток'),
      ('Studio Band', '24 эп. · прямой поток'),
      ('Dream Cast', '28 эп. · прямой поток'),
      ('SHIZA Project', '20 эп. · прямой поток'),
      ('JAM CLUB', '28 эп. · прямой поток'),
      ('Субтитры', '28 эп. · прямой поток'),
    ];
    final enter = _out(_seg(lt, 0, .8));
    return Stack(
      children: [
        Positioned(
          left: 220,
          top: 130 + (1 - enter) * 200,
          child: Opacity(
            opacity: enter,
            child: _Phone(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(22, 70, 22, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Озвучки',
                      style: TextStyle(fontSize: 34, fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      '42 озвучки',
                      style: TextStyle(color: _muted, fontSize: 18),
                    ),
                    const SizedBox(height: 18),
                    for (var i = 0; i < dubs.length; i++)
                      Opacity(
                        opacity: _seg(lt, .5 + i * .14, .8 + i * .14),
                        child: Transform.translate(
                          offset: Offset(
                            60 * (1 - _out(_seg(lt, .5 + i * .14, .9 + i * .14))),
                            0,
                          ),
                          child: Container(
                            margin: const EdgeInsets.only(bottom: 10),
                            padding: const EdgeInsets.fromLTRB(20, 14, 16, 14),
                            decoration: BoxDecoration(
                              color: i == 1 && lt > 2.6
                                  ? _red.withValues(alpha: .22)
                                  : const Color(0xFF16161A),
                              borderRadius: BorderRadius.circular(18),
                            ),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        dubs[i].$1,
                                        style: const TextStyle(
                                          fontSize: 21,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                      const SizedBox(height: 3),
                                      Text(
                                        dubs[i].$2,
                                        style: const TextStyle(
                                          color: _muted,
                                          fontSize: 15,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                const Icon(
                                  Icons.chevron_right_rounded,
                                  color: _muted,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
        Positioned(
          left: 840,
          top: 320,
          width: 900,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Rise(lt: lt, delay: .2, child: const _Kicker('Два источника')),
              const SizedBox(height: 22),
              _Rise(
                lt: lt,
                delay: .35,
                child: const Text(
                  'Десятки озвучек\nв одном месте',
                  style: TextStyle(
                    fontSize: 92,
                    height: 1.02,
                    letterSpacing: -3,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(height: 30),
              _Rise(
                lt: lt,
                delay: .7,
                child: const Text(
                  'YummyAnime и AniLiberty — выбирай любимую студию,\nAniMix запомнит выбор',
                  style: TextStyle(color: _muted, fontSize: 32, height: 1.35),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------- 5. offline

class _Offline extends StatelessWidget {
  const _Offline({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) => Stack(
    children: [
      Positioned(
        left: 0,
        right: 0,
        top: 120,
        child: Column(
          children: [
            _Rise(lt: lt, delay: 0, child: const _Kicker('Загрузки')),
            const SizedBox(height: 20),
            _Rise(
              lt: lt,
              delay: .15,
              child: const Text(
                'Смотри без интернета',
                style: TextStyle(
                  fontSize: 96,
                  letterSpacing: -3,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            const SizedBox(height: 14),
            _Rise(
              lt: lt,
              delay: .4,
              child: const Text(
                'Одно качество для всех серий · удаление сезоном · обложки офлайн',
                style: TextStyle(color: _muted, fontSize: 30),
              ),
            ),
          ],
        ),
      ),
      Positioned(
        left: 0,
        right: 0,
        top: 470,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < 6; i++) ...[
              if (i > 0) const SizedBox(width: 30),
              _DownloadCard(index: i, lt: lt),
            ],
          ],
        ),
      ),
    ],
  );
}

class _DownloadCard extends StatelessWidget {
  const _DownloadCard({required this.index, required this.lt});
  final int index;
  final double lt;

  @override
  Widget build(BuildContext context) {
    final rise = _out(_seg(lt, .4 + index * .1, 1.1 + index * .1));
    final start = 1.1 + index * .18;
    final progress = _inOut(_seg(lt, start, start + 1.4 + (index % 3) * .3));
    final done = progress >= 1;
    final check = _back(_seg(lt, start + 1.4 + (index % 3) * .3, start + 1.9 + (index % 3) * .3));
    return Opacity(
      opacity: rise,
      child: Transform.translate(
        offset: Offset(0, (1 - rise) * 160),
        child: SizedBox(
          width: 230,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Stack(
                children: [
                  _Poster(index: index + 1, width: 230, t: lt),
                  Positioned(
                    top: 14,
                    right: 14,
                    child: Transform.scale(
                      scale: check,
                      child: Container(
                        width: 46,
                        height: 46,
                        decoration: const BoxDecoration(
                          color: _red,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.download_done_rounded,
                          color: Colors.white,
                          size: 28,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: Stack(
                  children: [
                    Container(height: 6, color: Colors.white12),
                    FractionallySizedBox(
                      widthFactor: progress,
                      child: Container(height: 6, color: _red),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              Text(
                done ? '${8 + index * 4} серий · готово' : 'Загрузка ${(progress * 100).round()}%',
                style: const TextStyle(color: _muted, fontSize: 20),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 6. achievements

class _Achievements extends StatelessWidget {
  const _Achievements({required this.lt, required this.atlas});
  final double lt;
  final ui.Image atlas;

  @override
  Widget build(BuildContext context) {
    final count = (_seg(lt, .8, 3.0) * 12).round();
    final toast = _out(_seg(lt, 2.4, 2.9)) - _out(_seg(lt, 3.9, 4.3));
    return Stack(
      children: [
        Positioned(
          left: 130,
          top: 290,
          width: 700,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Rise(lt: lt, delay: 0, child: const _Kicker('Прогресс')),
              const SizedBox(height: 22),
              _Rise(
                lt: lt,
                delay: .15,
                child: const Text(
                  'Достижения\nи друзья',
                  style: TextStyle(
                    fontSize: 96,
                    height: 1.02,
                    letterSpacing: -3,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(height: 30),
              _Rise(
                lt: lt,
                delay: .45,
                child: const Text(
                  'Награды синхронизируются с аккаунтом —\nсравнивай прогресс с друзьями',
                  style: TextStyle(color: _muted, fontSize: 32, height: 1.35),
                ),
              ),
              const SizedBox(height: 40),
              _Rise(
                lt: lt,
                delay: .7,
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: '$count',
                        style: const TextStyle(color: _red),
                      ),
                      const TextSpan(text: ' из 20 открыто'),
                    ],
                  ),
                  style: const TextStyle(
                    fontSize: 44,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
        ),
        Positioned(
          right: 120,
          top: 230,
          width: 900,
          child: Wrap(
            spacing: 26,
            runSpacing: 26,
            children: [
              for (var i = 0; i < 10; i++)
                Transform.translate(
                  offset: Offset(0, math.sin(lt * 2 + i) * 6),
                  child: Transform.scale(
                    scale: _back(_seg(lt, .5 + i * .14, 1.0 + i * .14)),
                    child: SizedBox.square(
                      dimension: 154,
                      child: CustomPaint(painter: _AtlasCell(atlas, i)),
                    ),
                  ),
                ),
            ],
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          top: 60,
          child: Center(
            child: Opacity(
              opacity: toast.clamp(0.0, 1.0),
              child: Transform.translate(
                offset: Offset(0, -40 * (1 - toast.clamp(0.0, 1.0))),
                child: Container(
                  padding: const EdgeInsets.fromLTRB(14, 12, 30, 12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF1C1C22),
                    borderRadius: BorderRadius.circular(26),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: .5),
                        blurRadius: 40,
                      ),
                    ],
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox.square(
                        dimension: 64,
                        child: CustomPaint(painter: _AtlasCell(atlas, 2)),
                      ),
                      const SizedBox(width: 16),
                      const Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            'Достижение открыто',
                            style: TextStyle(color: _muted, fontSize: 18),
                          ),
                          Text(
                            'Сон для слабых',
                            style: TextStyle(
                              fontSize: 26,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _AtlasCell extends CustomPainter {
  const _AtlasCell(this.atlas, this.index);
  final ui.Image atlas;
  final int index;

  @override
  void paint(Canvas canvas, Size size) {
    final w = atlas.width / 5;
    final h = atlas.height / 2;
    canvas.drawImageRect(
      atlas,
      Rect.fromLTWH((index % 5) * w, (index ~/ 5) * h, w, h),
      Offset.zero & size,
      Paint()..filterQuality = FilterQuality.high,
    );
  }

  @override
  bool shouldRepaint(_AtlasCell oldDelegate) => oldDelegate.index != index;
}

// ---------------------------------------------------------------- 7. montage

class _Montage extends StatelessWidget {
  const _Montage({required this.lt});
  final double lt;

  @override
  Widget build(BuildContext context) {
    const words = [
      ('Онгоинги каждый день', Icons.live_tv_rounded),
      ('Умные рекомендации', Icons.auto_awesome_rounded),
      ('Комментарии Shikimori', Icons.forum_rounded),
      ('Тёмная и светлая тема', Icons.contrast_rounded),
      ('Windows · Android · iOS', Icons.devices_rounded),
    ];
    const step = .6;
    final index = (lt / step).floor().clamp(0, words.length - 1);
    final local = lt - index * step;
    final p = _out(_seg(local, 0, .25));
    final out = _seg(local, step - .12, step);
    return Stack(
      fit: StackFit.expand,
      children: [
        Opacity(
          opacity: .35 * (1 - out),
          child: Transform.scale(
            scale: 1.1 - .1 * p,
            child: _Art(index + 2, t: lt),
          ),
        ),
        const ColoredBox(color: Color(0x99000000)),
        Center(
          child: Opacity(
            opacity: p * (1 - out),
            child: Transform.translate(
              offset: Offset(0, 50 * (1 - p)),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(words[index].$2, size: 110, color: _red),
                  const SizedBox(height: 30),
                  Text(
                    words[index].$1,
                    style: const TextStyle(
                      fontSize: 110,
                      letterSpacing: -3,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: 90,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              for (var i = 0; i < words.length; i++)
                Container(
                  width: i == index ? 46 : 12,
                  height: 12,
                  margin: const EdgeInsets.symmetric(horizontal: 6),
                  decoration: BoxDecoration(
                    color: i == index ? _red : Colors.white24,
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------- 8. finale

class _Finale extends StatelessWidget {
  const _Finale({required this.lt, required this.logo});
  final double lt;
  final ui.Image logo;

  @override
  Widget build(BuildContext context) {
    final logoIn = _back(_seg(lt, .1, .9));
    final sweep = _inOut(_seg(lt, 1.1, 1.9));
    return Stack(
      fit: StackFit.expand,
      children: [
        _Glow(
          center: const Offset(960, 470),
          radius: 600 + 120 * logoIn,
          color: _red.withValues(alpha: .22 * logoIn),
        ),
        Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Transform.scale(
                    scale: logoIn,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(42),
                      child: SizedBox.square(
                        dimension: 170,
                        child: RawImage(image: logo, fit: BoxFit.cover),
                      ),
                    ),
                  ),
                  const SizedBox(width: 40),
                  _Rise(
                    lt: lt,
                    delay: .35,
                    child: const Text(
                      'AniMix',
                      style: TextStyle(
                        fontSize: 150,
                        letterSpacing: -5,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 50),
              ShaderMask(
                shaderCallback: (rect) => LinearGradient(
                  colors: const [_red, _red, Colors.transparent],
                  stops: [0, sweep, (sweep + .05).clamp(0.0, 1.0)],
                ).createShader(rect),
                blendMode: BlendMode.dstIn,
                child: const Text(
                  'Бесплатно. Навсегда.',
                  style: TextStyle(
                    color: _red,
                    fontSize: 84,
                    letterSpacing: -2,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(height: 24),
              _Rise(
                lt: lt,
                delay: 1.9,
                child: const Text(
                  'Без подписок и рекламы в плеере · Windows · Android · iOS',
                  style: TextStyle(color: _muted, fontSize: 32),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------- transitions

const _cuts = [3.45, 7.45, 12.65, 16.85, 21.25, 25.65, 28.65];

/// A fast diagonal brand-red slab sweeping across at each scene change.
class PromoWipes extends StatelessWidget {
  const PromoWipes({required this.t, super.key});
  final double t;

  @override
  Widget build(BuildContext context) {
    for (final cut in _cuts) {
      final p = _seg(t, cut - .22, cut + .22);
      if (p <= 0 || p >= 1) continue;
      final x = -900 + _inOut(p) * (kPromoSize.width + 1800);
      return Stack(
        children: [
          Positioned(
            left: x - 420,
            top: -200,
            child: Transform.rotate(
              angle: -.32,
              child: Container(
                width: 220,
                height: kPromoSize.height + 400,
                color: _red,
              ),
            ),
          ),
          Positioned(
            left: x - 140,
            top: -200,
            child: Transform.rotate(
              angle: -.32,
              child: Container(
                width: 90,
                height: kPromoSize.height + 400,
                color: _violet,
              ),
            ),
          ),
        ],
      );
    }
    return const SizedBox.shrink();
  }
}
