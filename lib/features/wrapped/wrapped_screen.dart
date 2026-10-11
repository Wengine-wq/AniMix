import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:video_player/video_player.dart';

import '../../core/app_settings.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../widgets/smart_anime_poster.dart';
import '../profile/achievements_screen.dart';
import 'wrapped_data.dart';
import 'wrapped_stats.dart';

/// «Итоги года»: a story of the viewer's year in AniMix, slide by slide,
/// with a free Creative Commons soundtrack.
class WrappedScreen extends ConsumerStatefulWidget {
  const WrappedScreen({
    super.key,
    required this.year,
    this.previewData,
    this.previewSlide,
  });

  final int year;

  /// Screenshot harness only: render these numbers on this slide, no network.
  @visibleForTesting
  final WrappedData? previewData;
  @visibleForTesting
  final int? previewSlide;

  static Future<void> open(BuildContext context, int year) {
    return Navigator.of(context, rootNavigator: true).push(
      PageRouteBuilder<void>(
        opaque: true,
        transitionDuration: const Duration(milliseconds: 420),
        pageBuilder: (_, _, _) => WrappedScreen(year: year),
        transitionsBuilder: (_, animation, _, child) => FadeTransition(
          opacity: CurvedAnimation(parent: animation, curve: Curves.easeOut),
          child: ScaleTransition(
            scale: Tween(begin: 1.04, end: 1.0).animate(
              CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
            ),
            child: child,
          ),
        ),
      ),
    );
  }

  @override
  ConsumerState<WrappedScreen> createState() => _WrappedScreenState();
}

enum _Slide {
  intro,
  hours,
  episodes,
  genres,
  topAnime,
  marathon,
  month,
  awards,
  outro,
}

class _WrappedScreenState extends ConsumerState<WrappedScreen>
    with SingleTickerProviderStateMixin {
  static const _slideDuration = Duration(milliseconds: 6500);

  late final AnimationController _progress = AnimationController(
    vsync: this,
    duration: _slideDuration,
  )..addStatusListener(_onProgressStatus);

  WrappedData? _data;
  Object? _error;
  List<_Slide> _slides = const [_Slide.intro];
  int _index = 0;
  bool _held = false;

  VideoPlayerController? _music;
  WrappedTrack? _track;
  bool _muted = false;

  @override
  void initState() {
    super.initState();
    final preview = widget.previewData;
    if (preview != null) {
      _apply(preview);
      _index = (widget.previewSlide ?? 0).clamp(0, _slides.length - 1);
      return;
    }
    _load();
    _loadMusic();
  }

  Future<void> _load() async {
    try {
      final data = await WrappedData.load(
        year: widget.year,
        auth: ref.read(animixAuthServiceProvider),
        api: ref.read(apiClientProvider),
      );
      if (!mounted) return;
      setState(() => _apply(data));
      _progress.forward(from: 0);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  void _apply(WrappedData data) {
    final stats = data.stats;
    _data = data;
    _slides = stats.isEmpty
        ? const [_Slide.intro, _Slide.outro]
        : [
            _Slide.intro,
            if (stats.watchSeconds > 0) _Slide.hours,
            if (stats.episodes > 0) _Slide.episodes,
            if (data.genres.isNotEmpty) _Slide.genres,
            if (data.topAnime != null) _Slide.topAnime,
            if (stats.marathonEpisodes >= 2) _Slide.marathon,
            if (stats.busiestMonth > 0) _Slide.month,
            if (data.achievementIndices.isNotEmpty) _Slide.awards,
            _Slide.outro,
          ];
  }

  Future<void> _loadMusic() async {
    final track = await WrappedTrack.fetch();
    if (track == null || !mounted) return;
    final controller = VideoPlayerController.file(track.file);
    try {
      await controller.initialize();
      await controller.setLooping(true);
      await controller.setVolume(_muted ? 0 : 0.6);
      if (!mounted) {
        await controller.dispose();
        return;
      }
      await controller.play();
      setState(() {
        _music = controller;
        _track = track;
      });
    } catch (_) {
      await controller.dispose();
    }
  }

  void _onProgressStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed && _index < _slides.length - 1) {
      _go(_index + 1);
    }
  }

  void _go(int index) {
    if (_data == null) return;
    final next = index.clamp(0, _slides.length - 1);
    if (next == _index && index >= 0 && index < _slides.length) {
      _progress.forward(from: 0);
      return;
    }
    HapticFeedback.selectionClick();
    setState(() => _index = next);
    _progress.forward(from: 0);
  }

  void _toggleMute() {
    setState(() => _muted = !_muted);
    _music?.setVolume(_muted ? 0 : 0.6);
  }

  void _hold(bool held) {
    if (_held == held || _data == null) return;
    _held = held;
    if (held) {
      _progress.stop();
    } else if (_index < _slides.length - 1 || !_progress.isCompleted) {
      _progress.forward();
    }
  }

  @override
  void dispose() {
    _progress.dispose();
    _music?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final accent = AppSettingsController.instance.accentColor;
    final slide = _slides[_index];
    final palette = _palette(slide, accent);
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: AnimatedContainer(
          duration: const Duration(milliseconds: 700),
          curve: Curves.easeOut,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: palette,
            ),
          ),
          child: SafeArea(
            child: Stack(
              children: [
                Positioned.fill(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTapUp: (details) {
                      final width = context.size?.width ?? 1;
                      _go(
                        details.localPosition.dx < width / 3
                            ? _index - 1
                            : _index + 1,
                      );
                    },
                    onLongPressStart: (_) => _hold(true),
                    onLongPressEnd: (_) => _hold(false),
                    child: AnimatedSwitcher(
                      duration: const Duration(milliseconds: 450),
                      switchInCurve: Curves.easeOutCubic,
                      transitionBuilder: (child, animation) => FadeTransition(
                        opacity: animation,
                        child: SlideTransition(
                          position: Tween(
                            begin: const Offset(0, 0.04),
                            end: Offset.zero,
                          ).animate(animation),
                          child: child,
                        ),
                      ),
                      child: KeyedSubtree(
                        key: ValueKey(slide),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(28, 72, 28, 40),
                          child: _buildSlide(slide, accent),
                        ),
                      ),
                    ),
                  ),
                ),
                Positioned(left: 12, right: 12, top: 10, child: _buildTopBar()),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Column(
      children: [
        AnimatedBuilder(
          animation: _progress,
          builder: (context, _) => Row(
            children: [
              for (var i = 0; i < _slides.length; i++) ...[
                if (i > 0) const SizedBox(width: 4),
                Expanded(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      minHeight: 3,
                      value: i < _index
                          ? 1
                          : i == _index
                          ? (_data == null ? 0 : _progress.value)
                          : 0,
                      backgroundColor: Colors.white.withValues(alpha: 0.22),
                      valueColor: const AlwaysStoppedAnimation(Colors.white),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            const SizedBox(width: 4),
            Text(
              'AniMix · Итоги ${widget.year}',
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.85),
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
            const Spacer(),
            if (_music != null)
              _RoundButton(
                icon: _muted
                    ? CupertinoIcons.speaker_slash_fill
                    : CupertinoIcons.speaker_2_fill,
                tooltip: _muted ? 'Включить музыку' : 'Выключить музыку',
                onPressed: _toggleMute,
              ),
            const SizedBox(width: 6),
            _RoundButton(
              icon: CupertinoIcons.xmark,
              tooltip: 'Закрыть',
              onPressed: () => Navigator.of(context).maybePop(),
            ),
          ],
        ),
      ],
    );
  }

  // One mood per slide; the intro follows the viewer's accent colour.
  static const _moods = <_Slide, (Color, Color)>{
    _Slide.hours: (Color(0xFFDB2777), Color(0xFF4C0519)),
    _Slide.episodes: (Color(0xFF2563EB), Color(0xFF0B1E4F)),
    _Slide.genres: (Color(0xFF0D9488), Color(0xFF042F2E)),
    _Slide.topAnime: (Color(0xFF7C3AED), Color(0xFF2E1065)),
    _Slide.marathon: (Color(0xFFEA580C), Color(0xFF431407)),
    _Slide.month: (Color(0xFF0891B2), Color(0xFF083344)),
    _Slide.awards: (Color(0xFFCA8A04), Color(0xFF3B2604)),
    _Slide.outro: (Color(0xFF9333EA), Color(0xFF111827)),
  };

  List<Color> _palette(_Slide slide, Color accent) {
    final hsl = HSLColor.fromColor(accent);
    final mood =
        _moods[slide] ??
        (
          hsl.withLightness(0.42).toColor(),
          hsl.withHue((hsl.hue + 30) % 360).withLightness(0.14).toColor(),
        );
    return [mood.$1, mood.$2, const Color(0xFF07070B)];
  }

  Widget _buildSlide(_Slide slide, Color accent) {
    final data = _data;
    if (data == null) {
      return _Centered(
        children: [
          if (_error == null) ...[
            const CupertinoActivityIndicator(color: Colors.white, radius: 14),
            const SizedBox(height: 18),
            const _Caption('Собираем твой год…'),
          ] else ...[
            const _Headline('Не получилось собрать итоги'),
            const SizedBox(height: 12),
            const _Caption('Проверь подключение и попробуй ещё раз.'),
          ],
        ],
      );
    }
    final stats = data.stats;
    switch (slide) {
      case _Slide.intro:
        return _Centered(
          children: [
            const _Kicker('ТВОЙ ГОД В ANIMIX'),
            const SizedBox(height: 14),
            _Headline(
              stats.isEmpty
                  ? 'Год только начинается'
                  : '${widget.year}.\nВот как это было.',
              size: 44,
            ),
            const SizedBox(height: 18),
            _Caption(
              stats.isEmpty
                  ? 'Посмотри пару серий, и здесь появится твоя история.'
                  : 'Нажимай справа, чтобы листать. Удерживай, чтобы задержаться.',
            ),
          ],
        );
      case _Slide.hours:
        final hours = stats.hours;
        return _Centered(
          children: [
            const _Kicker('ВРЕМЯ В ANIMIX'),
            const SizedBox(height: 10),
            _CountUp(
              value: hours >= 10 ? hours.round() : (hours * 60).round(),
              suffix: hours >= 10 ? ' ч' : ' мин',
            ),
            const SizedBox(height: 12),
            _Caption(
              hours >= 24
                  ? 'Это ${ruPlural((hours / 24).floor(), 'полные сутки', 'полных суток', 'полных суток')} аниме без перерыва.'
                  : hours >= 1
                  ? 'Целый вечер, а то и несколько, наедине с хорошими историями.'
                  : 'Отличное начало. Дальше будет больше.',
            ),
          ],
        );
      case _Slide.episodes:
        return _Centered(
          children: [
            const _Kicker('ПОСМОТРЕНО'),
            const SizedBox(height: 10),
            _CountUp(value: stats.episodes, suffix: ''),
            _Headline(
              ruPlural(
                stats.episodes,
                'серия',
                'серии',
                'серий',
              ).split(' ').last,
              size: 30,
            ),
            const SizedBox(height: 16),
            _Caption(
              '${ruPlural(stats.titles, 'тайтл', 'тайтла', 'тайтлов')} '
              'и ${ruPlural(stats.activeDays, 'день', 'дня', 'дней')} с аниме.',
            ),
          ],
        );
      case _Slide.genres:
        return _Centered(
          children: [
            const _Kicker('ЛЮБИМЫЕ ЖАНРЫ'),
            const SizedBox(height: 22),
            for (var i = 0; i < data.genres.length; i++)
              _GenreBar(
                place: i + 1,
                label: data.genres[i].key,
                share: data.genres[i].value,
                delay: Duration(milliseconds: 200 + i * 220),
              ),
          ],
        );
      case _Slide.topAnime:
        final anime = data.topAnime!;
        final episodes = stats.episodesByAnime[anime.id] ?? 0;
        final title = (anime.russian?.isNotEmpty ?? false)
            ? anime.russian!
            : anime.name ?? '';
        return _Centered(
          children: [
            const _Kicker('ТАЙТЛ ГОДА'),
            const SizedBox(height: 18),
            TweenAnimationBuilder<double>(
              tween: Tween(begin: 0.85, end: 1),
              duration: const Duration(milliseconds: 900),
              curve: Curves.easeOutBack,
              builder: (_, scale, child) =>
                  Transform.scale(scale: scale, child: child),
              child: Container(
                width: 168,
                height: 240,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(18),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.45),
                      blurRadius: 40,
                      offset: const Offset(0, 18),
                    ),
                  ],
                ),
                clipBehavior: Clip.antiAlias,
                child: SmartAnimePoster(
                  animeId: anime.id,
                  imageUrl: anime.imageUrl,
                  title: anime.name ?? title,
                  russianTitle: anime.russian,
                ),
              ),
            ),
            const SizedBox(height: 22),
            _Headline(title, size: 26),
            const SizedBox(height: 8),
            _Caption(
              '${ruPlural(episodes, 'серия', 'серии', 'серий')} за год. '
              'Похоже, это любовь.',
            ),
          ],
        );
      case _Slide.marathon:
        final day = stats.marathonDay!;
        return _Centered(
          children: [
            const _Kicker('САМЫЙ ДЛИННЫЙ МАРАФОН'),
            const SizedBox(height: 10),
            _CountUp(value: stats.marathonEpisodes, suffix: ''),
            _Headline(
              '${ruPlural(stats.marathonEpisodes, 'серия', 'серии', 'серий').split(' ').last} за день',
              size: 30,
            ),
            const SizedBox(height: 16),
            _Caption(
              '${day.day} ${_genitiveMonths[day.month - 1]}. '
              '«Ещё одну и спать» — мы помним.',
            ),
          ],
        );
      case _Slide.month:
        return _Centered(
          children: [
            const _Kicker('САМЫЙ АНИМЕ-МЕСЯЦ'),
            const SizedBox(height: 14),
            _Headline(
              WrappedStats.monthNames[stats.busiestMonth - 1].toUpperCase(),
              size: 48,
            ),
            const SizedBox(height: 14),
            _Caption(
              '${_hoursLabel(stats.busiestMonthSeconds)} просмотра за месяц.',
            ),
          ],
        );
      case _Slide.awards:
        final icons = data.achievementIndices.take(9).toList();
        return _Centered(
          children: [
            const _Kicker('ДОСТИЖЕНИЯ ГОДА'),
            const SizedBox(height: 10),
            _Headline(
              ruPlural(
                data.achievementIndices.length,
                'награда',
                'награды',
                'наград',
              ),
              size: 38,
            ),
            const SizedBox(height: 24),
            Wrap(
              alignment: WrapAlignment.center,
              spacing: 12,
              runSpacing: 12,
              children: [
                for (var i = 0; i < icons.length; i++)
                  TweenAnimationBuilder<double>(
                    tween: Tween(begin: 0, end: 1),
                    duration: Duration(milliseconds: 500 + i * 120),
                    curve: Curves.easeOutBack,
                    builder: (_, t, child) => Opacity(
                      opacity: t.clamp(0, 1),
                      child: Transform.scale(scale: t, child: child),
                    ),
                    child: AchievementIcon(index: icons[i], size: 72),
                  ),
              ],
            ),
          ],
        );
      case _Slide.outro:
        return _Centered(
          children: [
            const Icon(CupertinoIcons.sparkles, color: Colors.white, size: 40),
            const SizedBox(height: 18),
            _Headline(
              stats.isEmpty
                  ? 'Итоги ждут тебя'
                  : 'Спасибо, что смотрел\nвместе с AniMix',
              size: 32,
            ),
            const SizedBox(height: 12),
            _Caption('Увидимся в ${widget.year + 1}.'),
            const SizedBox(height: 28),
            if (!stats.isEmpty)
              CupertinoButton(
                color: Colors.white,
                borderRadius: BorderRadius.circular(22),
                padding: const EdgeInsets.symmetric(
                  horizontal: 22,
                  vertical: 12,
                ),
                onPressed: () => _go(0),
                child: const Text(
                  'Посмотреть ещё раз',
                  style: TextStyle(
                    color: Colors.black,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            if (_track != null) ...[
              const SizedBox(height: 26),
              Text(
                'Музыка: ${_track!.credit}',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.5),
                  fontSize: 11,
                ),
              ),
            ],
          ],
        );
    }
  }

  static String _hoursLabel(int seconds) {
    final hours = seconds / 3600;
    if (hours < 1) {
      return ruPlural((seconds / 60).round(), 'минута', 'минуты', 'минут');
    }
    return ruPlural(hours.round(), 'час', 'часа', 'часов');
  }

  static const _genitiveMonths = [
    'января',
    'февраля',
    'марта',
    'апреля',
    'мая',
    'июня',
    'июля',
    'августа',
    'сентября',
    'октября',
    'ноября',
    'декабря',
  ];
}

class _Centered extends StatelessWidget {
  const _Centered({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        physics: const NeverScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: children,
          ),
        ),
      ),
    );
  }
}

class _Kicker extends StatelessWidget {
  const _Kicker(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: TextStyle(
      color: Colors.white.withValues(alpha: 0.7),
      fontSize: 12,
      fontWeight: FontWeight.w700,
      letterSpacing: 1.6,
    ),
  );
}

class _Headline extends StatelessWidget {
  const _Headline(this.text, {this.size = 34});

  final String text;
  final double size;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: TextStyle(
      color: Colors.white,
      fontSize: size,
      height: 1.08,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.6,
    ),
  );
}

class _Caption extends StatelessWidget {
  const _Caption(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: TextStyle(
      color: Colors.white.withValues(alpha: 0.78),
      fontSize: 16,
      height: 1.35,
    ),
  );
}

class _CountUp extends StatelessWidget {
  const _CountUp({required this.value, required this.suffix});

  final int value;
  final String suffix;

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: value.toDouble()),
      duration: const Duration(milliseconds: 1600),
      curve: Curves.easeOutCubic,
      builder: (_, current, _) => FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.centerLeft,
        child: Text(
          '${current.round()}$suffix',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 96,
            height: 1,
            fontWeight: FontWeight.w900,
            letterSpacing: -3,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
      ),
    );
  }
}

class _GenreBar extends StatelessWidget {
  const _GenreBar({
    required this.place,
    required this.label,
    required this.share,
    required this.delay,
  });

  final int place;
  final String label;
  final double share;
  final Duration delay;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 22),
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: 1),
        duration: delay + const Duration(milliseconds: 900),
        curve: Interval(
          delay.inMilliseconds / (delay.inMilliseconds + 900),
          1,
          curve: Curves.easeOutCubic,
        ),
        builder: (context, t, _) => Opacity(
          opacity: t,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(
                    '$place',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.5),
                      fontSize: 28,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: place == 1 ? 30 : 24,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  Text(
                    '${(share * 100).round()}%',
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.7),
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: LinearProgressIndicator(
                  minHeight: 5,
                  value: (share * 2).clamp(0.05, 1) * t,
                  backgroundColor: Colors.white.withValues(alpha: 0.12),
                  valueColor: const AlwaysStoppedAnimation(Colors.white),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: Colors.white.withValues(alpha: 0.14),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onPressed,
          child: SizedBox(
            width: 36,
            height: 36,
            child: Icon(icon, color: Colors.white, size: 17),
          ),
        ),
      ),
    );
  }
}
