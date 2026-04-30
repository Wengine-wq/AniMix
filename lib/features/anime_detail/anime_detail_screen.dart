import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:dio/dio.dart';

import '../../models/shikimori_anime_detail.dart';
import '../../models/shikimori_anime.dart';
import '../../models/shikimori_user.dart';
import '../watch/watch_provider_selection_screen.dart';
import '../data/comments_screen.dart';
import '../../providers/user_provider.dart'; 
import '../../providers/auth_provider.dart'; 

// =====================================================================
// ЦВЕТОВАЯ ПАЛИТРА И СТИЛИ
// =====================================================================
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF050507); // Максимально глубокий темный фон

// Базовые настройки стекла для карточек (без перегруза)
const _cardGlassSettings = LiquidGlassSettings(
  glassColor: Color(0x33FFFFFF), // Легкий тинт для контраста
  blur: 15.0,
  chromaticAberration: 0.05,
  specularSharpness: GlassSpecularSharpness.sharp,
);

class AnimeDetailScreen extends StatefulHookConsumerWidget {
  final int animeId;
  const AnimeDetailScreen({required this.animeId, super.key});

  @override
  ConsumerState<AnimeDetailScreen> createState() => _AnimeDetailScreenState();
}

class _AnimeDetailScreenState extends ConsumerState<AnimeDetailScreen> {
  bool _isLoading = true;
  String? _errorMessage;
  bool _isDescriptionExpanded = false;

  ShikimoriAnimeDetail? anime;
  List<String> screenshots = [];
  List<Map<String, dynamic>> relatedList = [];
  List<ShikimoriAnime> similarList = [];
  
  // Сырые данные из API (чтобы обойти ограничения модели)
  int _statsWatching = 0;
  int _statsPlanned = 0;
  int _statsCompleted = 0;
  String? _rawDuration;
  String? _rawRating;
  
  // Данные пользователя
  ShikimoriUser? _currentUser;
  String? currentUserStatus;
  int _currentUserScore = 0; // Оценка пользователя (1-10)

  @override
  void initState() {
    super.initState();
    _fetchDataSafely();
  }

  // 🔥 ПОСЛЕДОВАТЕЛЬНАЯ ЗАГРУЗКА ДАННЫХ ДЛЯ ОБХОДА 429 TOO MANY REQUESTS
  Future<void> _fetchDataSafely() async {
    final api = ref.read(apiClientProvider);

    // 1. Грузим базу + сырой JSON для статистики (без заглушек!)
    try {
      anime = await api.getAnimeDetail(widget.animeId);
      await _fetchRawMissingData(); // Вытаскиваем то, чего нет в модели
      if (mounted) setState(() {});
    } catch (e) {
      _errorMessage = 'Не удалось загрузить данные: ${e.toString()}';
      if (mounted) setState(() => _isLoading = false);
      return; 
    }

    // 2. Скриншоты
    await Future.delayed(const Duration(milliseconds: 300));
    try {
      screenshots = await api.getAnimeScreenshots(widget.animeId);
      if (mounted) setState(() {});
    } catch (_) {}

    // 3. Франшизы
    await Future.delayed(const Duration(milliseconds: 300));
    try {
      relatedList = await api.getRelatedAnimes(widget.animeId);
      if (mounted) setState(() {});
    } catch (_) {}

    // 4. Похожие аниме
    await Future.delayed(const Duration(milliseconds: 300));
    try {
      similarList = await api.getAnimes(limit: 10, filters: {'order': 'popularity'});
      if (mounted) setState(() {});
    } catch (_) {}

    // 5. Статус и ОЦЕНКА юзера
    await Future.delayed(const Duration(milliseconds: 300));
    try {
      _currentUser = await api.getCurrentUser();
      if (_currentUser != null) {
        final rate = await api.getUserRate(widget.animeId, userId: _currentUser!.id);
        if (rate != null) {
          currentUserStatus = rate['status'] as String?;
          _currentUserScore = rate['score'] as int? ?? 0;
        }
      }
    } catch (_) {}

    // Все загружено
    if (mounted) setState(() => _isLoading = false);
  }

  // 🔥 МЕТОД ДЛЯ ВЫТАСКИВАНИЯ РЕАЛЬНОЙ СТАТИСТИКИ
  Future<void> _fetchRawMissingData() async {
    try {
      // Надежный User-Agent, чтобы 100% не получить 403 от Cloudflare
      final dio = Dio(BaseOptions(
        baseUrl: 'https://shikimori.io',
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 AniMix/1.0',
          'Accept': 'application/json',
        },
      ));
      final response = await dio.get('/api/animes/${widget.animeId}');
      final data = response.data;
      
      if (data != null && mounted) {
        setState(() {
          _rawDuration = data['duration']?.toString();
          _rawRating = data['rating']?.toString();

          final List<dynamic>? stats = data['rates_statuses_stats'];
          if (stats != null) {
            for (var stat in stats) {
              final String name = stat['name'] ?? '';
              final int value = int.tryParse(stat['value']?.toString() ?? '0') ?? 0;
              
              // 🔥 Шикимори отдает ключи НА РУССКОМ! Ищем именно их.
              if (name == 'watching' || name == 'Смотрю') _statsWatching = value;
              if (name == 'planned' || name == 'В планах' || name == 'Запланировано') _statsPlanned = value;
              if (name == 'completed' || name == 'Просмотрено') _statsCompleted = value;
            }
          }
        });
      }
    } catch (e) {
      debugPrint('Ошибка загрузки сырой статистики: $e');
    }
  }

  String _getValidImageUrl(String? rawPath) {
    if (rawPath == null || rawPath.isEmpty) return '';
    if (rawPath.startsWith('http')) return rawPath;
    return 'https://shikimori.io$rawPath';
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading && anime == null) {
      return const CupertinoPageScaffold(
        backgroundColor: _bgColor,
        child: Center(child: CupertinoActivityIndicator(radius: 16)),
      );
    }

    if (_errorMessage != null && anime == null) {
      return CupertinoPageScaffold(
        backgroundColor: _bgColor,
        navigationBar: const CupertinoNavigationBar(backgroundColor: Colors.transparent),
        child: Center(
          child: Text(_errorMessage!, style: TextStyle(color: Colors.white.withValues(alpha: 0.6))),
        ),
      );
    }

    final imageUrl = _getValidImageUrl(anime?.imageUrl);

    return Scaffold(
      backgroundColor: _bgColor,
      body: AdaptiveLiquidGlassLayer(
        settings: const LiquidGlassSettings(blur: 25.0, thickness: 12.0),
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
          slivers: [
            // 1. ИДЕАЛЬНАЯ ОБЛОЖКА HERO (Без обрезки и пикселизации)
            SliverAppBar(
              expandedHeight: 520, // Увеличили для полноразмерного постера
              pinned: true,
              stretch: true,
              backgroundColor: _bgColor.withValues(alpha: 0.8),
              leading: Padding(
                padding: const EdgeInsets.only(left: 12, top: 8, bottom: 8),
                child: GlassButton(
                  onTap: () => Navigator.pop(context),
                  shape: const LiquidRoundedSuperellipse(borderRadius: 50.0), 
                  settings: const LiquidGlassSettings(glassColor: Color(0x66000000), blur: 20),
                  icon: const Icon(CupertinoIcons.back, color: Colors.white),
                ),
              ),
              flexibleSpace: FlexibleSpaceBar(
                background: Stack(
                  fit: StackFit.expand,
                  children: [
                    // Задний фон: сильно размытая версия картинки (Ambient Blur)
                    if (imageUrl.isNotEmpty)
                      ImageFiltered(
                        imageFilter: ImageFilter.blur(sigmaX: 40, sigmaY: 40),
                        child: CachedNetworkImage(
                          imageUrl: imageUrl,
                          fit: BoxFit.cover,
                        ),
                      ),
                    // Градиентное затемнение для плавного перехода в контент
                    Container(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [
                            _bgColor.withValues(alpha: 0.3),
                            _bgColor.withValues(alpha: 0.5),
                            _bgColor,
                          ],
                          stops: const [0.0, 0.6, 1.0],
                        ),
                      ),
                    ),
                    // Передний план: Четкий, оригинальный постер без обрезки
                    if (imageUrl.isNotEmpty)
                      SafeArea(
                        child: Padding(
                          padding: const EdgeInsets.only(top: 40, bottom: 30),
                          child: Center(
                            child: GlassContainer(
                              quality: GlassQuality.premium,
                              shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                              settings: const LiquidGlassSettings(
                                glassColor: Colors.transparent, // Стекло только для блика и преломления
                                blur: 0,
                                specularSharpness: GlassSpecularSharpness.sharp,
                              ),
                              child: ClipPath(
                                clipper: ShapeBorderClipper(shape: const LiquidRoundedSuperellipse(borderRadius: 20)),
                                child: CachedNetworkImage(
                                  imageUrl: imageUrl,
                                  fit: BoxFit.contain, // 🔥 Магия здесь: постер больше не режется
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),

            // 2. ОСНОВНОЙ КОНТЕНТ (Заголовки, Кнопки, Статистика)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildHeaderInfo(),
                    const SizedBox(height: 28),
                    _buildActionButtons(),
                    const SizedBox(height: 32),
                    _buildMetadataGrid(), 
                    const SizedBox(height: 28),
                    _buildStatsRow(),
                    const SizedBox(height: 32),
                    _buildDescription(),
                    const SizedBox(height: 36),
                  ],
                ),
              ),
            ),

            // 3. СКРИНШОТЫ
            if (screenshots.isNotEmpty) ...[
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.only(left: 20, bottom: 16),
                  child: Text('Кадры из аниме', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                ),
              ),
              SliverToBoxAdapter(child: _buildScreenshots()),
              const SliverToBoxAdapter(child: SizedBox(height: 36)),
            ],

            // 4. СВЯЗАННЫЕ АНИМЕ (ФРАНШИЗЫ)
            if (relatedList.isNotEmpty) ...[
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.only(left: 20, bottom: 16),
                  child: Text('Хронология и франшиза', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                ),
              ),
              SliverToBoxAdapter(child: _buildRelatedAnimes()),
              const SliverToBoxAdapter(child: SizedBox(height: 36)),
            ],

            // 5. ПОХОЖИЕ АНИМЕ 
            if (similarList.isNotEmpty) ...[
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.only(left: 20, bottom: 16),
                  child: Text('Вам может понравиться', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                ),
              ),
              SliverToBoxAdapter(child: _buildSimilarAnimes()),
              const SliverToBoxAdapter(child: SizedBox(height: 64)),
            ],
            
            // Запасной отступ внизу
            if (similarList.isEmpty && relatedList.isEmpty)
              const SliverToBoxAdapter(child: SizedBox(height: 64)),
          ],
        ),
      ),
    );
  }

  // =====================================================================
  // UI БЛОКИ ДЕТАЛИЗАЦИИ
  // =====================================================================

  Widget _buildHeaderInfo() {
    final statusText = anime?.status == 'ongoing' ? 'Выходит' : (anime?.status == 'released' ? 'Вышло' : 'Анонс');
    final score = anime?.score ?? 0.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: _accentColor.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: _accentColor.withValues(alpha: 0.4)),
              ),
              child: Text(
                statusText.toUpperCase(),
                style: const TextStyle(color: _accentColor, fontSize: 11, fontWeight: FontWeight.w900, letterSpacing: 0.5),
              ),
            ),
            const SizedBox(width: 14),
            if (score > 0)
              Row(
                children: [
                  const Icon(CupertinoIcons.star_fill, color: Color(0xFFFBBF24), size: 18),
                  const SizedBox(width: 6),
                  Text(
                    score.toStringAsFixed(1),
                    style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                ],
              ),
            // Вывод оценки пользователя
            if (_currentUserScore > 0) ...[
              const SizedBox(width: 14),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(8)),
                child: Row(
                  children: [
                    Icon(CupertinoIcons.star_fill, color: _accentLight, size: 12),
                    const SizedBox(width: 4),
                    Text('Вы: $_currentUserScore', style: TextStyle(color: _accentLight, fontSize: 12, fontWeight: FontWeight.bold)),
                  ],
                ),
              ),
            ]
          ],
        ),
        const SizedBox(height: 16),
        Text(
          anime?.russian ?? anime?.name ?? 'Без названия',
          style: const TextStyle(color: Colors.white, fontSize: 32, fontWeight: FontWeight.w900, height: 1.1, letterSpacing: -1.0),
        ),
        if (anime?.name != null && anime!.russian != null) ...[
          const SizedBox(height: 6),
          Text(
            anime!.name!,
            style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 16, fontWeight: FontWeight.w500),
          ),
        ],
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            if (anime?.genres != null)
              ...anime!.genres.map((g) => _buildGenreTag(g)),
          ],
        ),
      ],
    );
  }

  Widget _buildGenreTag(String text) {
    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 12),
      settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 10),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Text(
          text,
          style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 13, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }

  Widget _buildActionButtons() {
    return Row(
      children: [
        Expanded(
          flex: 2,
          child: GlassButton(
            onTap: () {
              Navigator.push(context, CupertinoPageRoute(builder: (_) => WatchProviderSelectionScreen(
                animeId: anime!.id,
                animeNameRu: anime!.russian ?? '',
                animeNameEn: anime!.name ?? '',
              )));
            },
            quality: GlassQuality.premium,
            shape: const LiquidRoundedSuperellipse(borderRadius: 24),
            settings: const LiquidGlassSettings(
              glassColor: Color(0xCC8B5CF6), // Плотный фиолетовый для главной кнопки
              blur: 15,
            ),
            icon: Container(
              height: 56, 
              padding: const EdgeInsets.symmetric(horizontal: 8),
              alignment: Alignment.center,
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(CupertinoIcons.play_fill, color: Colors.white, size: 22),
                  SizedBox(width: 8),
                  Flexible(
                    child: Text('Смотреть', style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold), overflow: TextOverflow.ellipsis),
                  ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: GlassButton(
            onTap: _showStatusModal, 
            quality: GlassQuality.standard,
            shape: const LiquidRoundedSuperellipse(borderRadius: 24),
            settings: LiquidGlassSettings(
              glassColor: currentUserStatus != null ? _accentColor.withValues(alpha: 0.2) : const Color(0x26FFFFFF), 
              blur: 15
            ),
            icon: Container(
              height: 56,
              alignment: Alignment.center,
              child: Icon(
                currentUserStatus != null ? CupertinoIcons.bookmark_solid : CupertinoIcons.bookmark,
                color: currentUserStatus != null ? _accentLight : Colors.white,
                size: 22,
              ),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: GlassButton(
            onTap: () {
              if (anime?.topicId != null) {
                Navigator.push(context, CupertinoPageRoute(builder: (_) => CommentsScreen(topicId: anime!.topicId!)));
              }
            },
            quality: GlassQuality.standard,
            shape: const LiquidRoundedSuperellipse(borderRadius: 24),
            settings: const LiquidGlassSettings(glassColor: Color(0x26FFFFFF), blur: 15),
            icon: Container(
              height: 56,
              alignment: Alignment.center,
              child: const Icon(CupertinoIcons.chat_bubble_2, color: Colors.white, size: 22),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildMetadataGrid() {
    final episodes = anime?.episodes?.toString() ?? '?';
    final studio = anime?.studios.isNotEmpty == true ? anime!.studios.first : 'Неизвестно';

    return GlassContainer(
      quality: GlassQuality.standard,
      shape: const LiquidRoundedSuperellipse(borderRadius: 24),
      settings: const LiquidGlassSettings(glassColor: Color(0x0DFFFFFF), blur: 15),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            _buildMetaRow('Формат', anime?.kind?.toUpperCase() ?? 'TV'),
            const Divider(color: Colors.white10, height: 24),
            _buildMetaRow('Эпизоды', '${anime?.episodesAired ?? 0} / ${episodes == '0' ? '?' : episodes}'),
            const Divider(color: Colors.white10, height: 24),
            // 🔥 Используем реальные данные из сырого запроса
            _buildMetaRow('Длительность', _rawDuration != null ? '$_rawDuration мин. / эп.' : '? мин. / эп.'), 
            const Divider(color: Colors.white10, height: 24),
            _buildMetaRow('Рейтинг', _rawRating?.toUpperCase() ?? 'N/A'), 
            const Divider(color: Colors.white10, height: 24),
            _buildMetaRow('Студия', studio),
          ],
        ),
      ),
    );
  }

  Widget _buildMetaRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 15, fontWeight: FontWeight.w500)),
        Text(value, style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.bold)),
      ],
    );
  }

  Widget _buildStatsRow() {
    return Row(
      children: [
        _buildStatBox('Смотрят', _statsWatching.toString(), CupertinoIcons.eye),
        const SizedBox(width: 12),
        _buildStatBox('В планах', _statsPlanned.toString(), CupertinoIcons.calendar),
        const SizedBox(width: 12),
        _buildStatBox('Просмотрено', _statsCompleted.toString(), CupertinoIcons.check_mark_circled),
      ],
    );
  }

  Widget _buildStatBox(String title, String count, IconData icon) {
    return Expanded(
      child: GlassContainer(
        quality: GlassQuality.standard,
        shape: const LiquidRoundedSuperellipse(borderRadius: 20),
        settings: const LiquidGlassSettings(glassColor: Color(0x1AFFFFFF), blur: 10),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
          child: Column(
            children: [
              Icon(icon, color: _accentLight.withValues(alpha: 0.8), size: 22),
              const SizedBox(height: 12),
              Text(
                count,
                style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                title,
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 11, fontWeight: FontWeight.w600),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDescription() {
    final text = anime?.description ?? 'Описание отсутствует.';
    
    // Умная очистка от BB-кодов
    final cleanText = text.replaceAll(RegExp(r'\[.*?\]'), '');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Об аниме', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
        const SizedBox(height: 16),
        AnimatedCrossFade(
          duration: const Duration(milliseconds: 300),
          crossFadeState: _isDescriptionExpanded ? CrossFadeState.showSecond : CrossFadeState.showFirst,
          firstChild: Text(
            cleanText,
            maxLines: 4,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 15, height: 1.6, letterSpacing: 0.2),
          ),
          secondChild: Text(
            cleanText,
            style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 15, height: 1.6, letterSpacing: 0.2),
          ),
        ),
        if (cleanText.length > 150) ...[
          const SizedBox(height: 12),
          GestureDetector(
            onTap: () => setState(() => _isDescriptionExpanded = !_isDescriptionExpanded),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  _isDescriptionExpanded ? 'Свернуть' : 'Читать далее',
                  style: const TextStyle(color: _accentColor, fontSize: 15, fontWeight: FontWeight.w700),
                ),
                const SizedBox(width: 4),
                Icon(
                  _isDescriptionExpanded ? CupertinoIcons.chevron_up : CupertinoIcons.chevron_down,
                  color: _accentColor,
                  size: 14,
                ),
              ],
            ),
          ),
        ]
      ],
    );
  }

  Widget _buildScreenshots() {
    return SizedBox(
      height: 150,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: screenshots.length,
        itemBuilder: (context, index) {
          final url = screenshots[index];
          return GestureDetector(
            onTap: () {
              Navigator.push(context, CupertinoPageRoute(
                builder: (_) => _FullscreenGallery(screenshots: screenshots, initialIndex: index),
              ));
            },
            child: Container(
              width: 260,
              margin: const EdgeInsets.only(right: 16),
              child: GlassContainer(
                quality: GlassQuality.standard,
                shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                settings: const LiquidGlassSettings(glassColor: Color(0x33000000), blur: 10),
                child: ClipPath(
                  clipper: ShapeBorderClipper(shape: const LiquidRoundedSuperellipse(borderRadius: 20)),
                  child: CachedNetworkImage(
                    imageUrl: url,
                    fit: BoxFit.cover,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildRelatedAnimes() {
    final validRelations = relatedList.where((r) => r['anime'] != null).toList();

    return SizedBox(
      height: 220,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: validRelations.length,
        itemBuilder: (context, index) {
          final item = validRelations[index];
          final relationText = item['relation_russian'] ?? item['relation'] ?? 'Связанное';
          final relatedAnime = ShikimoriAnime.fromJson(item['anime']);
          final url = _getValidImageUrl(relatedAnime.imageUrl);

          return GestureDetector(
            onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: relatedAnime.id))),
            child: Container(
              width: 140,
              margin: const EdgeInsets.only(right: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: GlassContainer(
                      quality: GlassQuality.premium,
                      shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                      settings: _cardGlassSettings,
                      child: ClipPath(
                        clipper: ShapeBorderClipper(shape: const LiquidRoundedSuperellipse(borderRadius: 20)),
                        child: url.isNotEmpty
                            ? CachedNetworkImage(imageUrl: url, fit: BoxFit.cover, width: double.infinity, height: double.infinity)
                            : Container(color: const Color(0xFF1C1C1E)),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    relationText,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: _accentColor, fontSize: 11, fontWeight: FontWeight.w900, letterSpacing: 0.5),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    relatedAnime.russian ?? relatedAnime.name ?? '',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold, height: 1.2),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildSimilarAnimes() {
    return SizedBox(
      height: 220,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        itemCount: similarList.length,
        itemBuilder: (context, index) {
          final animeItem = similarList[index];
          final url = _getValidImageUrl(animeItem.imageUrl);
          final score = animeItem.score ?? 0.0;

          return GestureDetector(
            onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => AnimeDetailScreen(animeId: animeItem.id))),
            child: Container(
              width: 140,
              margin: const EdgeInsets.only(right: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: GlassContainer(
                      quality: GlassQuality.premium,
                      shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                      settings: _cardGlassSettings,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          ClipPath(
                            clipper: ShapeBorderClipper(shape: const LiquidRoundedSuperellipse(borderRadius: 20)),
                            child: url.isNotEmpty
                                ? CachedNetworkImage(imageUrl: url, fit: BoxFit.cover)
                                : Container(color: const Color(0xFF1C1C1E)),
                          ),
                          Container(
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [Colors.transparent, Colors.black.withValues(alpha: 0.8)],
                                stops: const [0.5, 1.0],
                              ),
                            ),
                          ),
                          if (score > 0)
                            Positioned(
                              bottom: 8, left: 8,
                              child: Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                                decoration: BoxDecoration(
                                  color: Colors.black.withValues(alpha: 0.6),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Row(
                                  children: [
                                    const Icon(CupertinoIcons.star_fill, color: Color(0xFFFBBF24), size: 10),
                                    const SizedBox(width: 4),
                                    Text(score.toStringAsFixed(1), style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
                                  ],
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    animeItem.russian ?? animeItem.name ?? '',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold, height: 1.2),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  // =====================================================================
  // 🔥 МОДАЛЬНОЕ ОКНО СО СТАТУСОМ И ОЦЕНКОЙ (1-10)
  // =====================================================================
  void _showStatusModal() {
    if (_currentUser == null) {
      showCupertinoDialog(
        context: context,
        builder: (ctx) => CupertinoAlertDialog(
          title: const Text('Требуется авторизация'),
          content: const Text('Для добавления аниме в список необходимо войти в аккаунт Shikimori.'),
          actions: [CupertinoDialogAction(child: const Text('OK'), onPressed: () => Navigator.pop(ctx))],
        ),
      );
      return;
    }

    int localScore = _currentUserScore;
    String? localStatus = currentUserStatus;

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder( 
        builder: (ctx, setModalState) {
          return Material(
            type: MaterialType.transparency,
            child: Container(
              padding: const EdgeInsets.only(top: 16),
              child: GlassContainer(
                shape: const LiquidRoundedSuperellipse(borderRadius: 36),
                settings: const LiquidGlassSettings(glassColor: Color(0xCC09090B), blur: 40),
                child: SafeArea(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(width: 48, height: 5, margin: const EdgeInsets.only(bottom: 24, top: 8), decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(10))),
                      const Text('Ваш список', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                      const SizedBox(height: 16),
                      
                      _buildStatusOptionModal('planned', 'В планах', CupertinoIcons.calendar, localStatus, (val) => setModalState(() => localStatus = val)),
                      _buildStatusOptionModal('watching', 'Смотрю', CupertinoIcons.eye, localStatus, (val) => setModalState(() => localStatus = val)),
                      _buildStatusOptionModal('completed', 'Просмотрено', CupertinoIcons.check_mark_circled, localStatus, (val) => setModalState(() => localStatus = val)),
                      _buildStatusOptionModal('on_hold', 'Отложено', CupertinoIcons.pause, localStatus, (val) => setModalState(() => localStatus = val)),
                      _buildStatusOptionModal('dropped', 'Брошено', CupertinoIcons.clear_circled, localStatus, (val) => setModalState(() => localStatus = val)),
                      
                      const Divider(color: Colors.white10, height: 32),
                      
                      // 🔥 ИНТЕРАКТИВНАЯ ПАНЕЛЬ ОЦЕНКИ (1-10)
                      Text('Оценка: ${localScore > 0 ? localScore : 'Нет'}', style: TextStyle(color: Colors.white.withValues(alpha: 0.7), fontSize: 15, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 12),
                      SizedBox(
                        height: 40,
                        child: ListView.builder(
                          scrollDirection: Axis.horizontal,
                          physics: const BouncingScrollPhysics(),
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                          itemCount: 10,
                          itemBuilder: (context, index) {
                            final starValue = index + 1;
                            final isSelected = starValue <= localScore;
                            return GestureDetector(
                              onTap: () => setModalState(() => localScore = starValue),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 4),
                                child: Icon(
                                  isSelected ? CupertinoIcons.star_fill : CupertinoIcons.star,
                                  color: isSelected ? const Color(0xFFFBBF24) : Colors.white.withValues(alpha: 0.2),
                                  size: 32,
                                ),
                              ),
                            );
                          },
                        ),
                      ),

                      const SizedBox(height: 24),
                      // 🔥 ИСПРАВЛЕНА КНОПКА "СОХРАНИТЬ" (Точно по центру, широкая, текст не режется)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 20),
                        child: SizedBox(
                          width: double.infinity,
                          child: GlassButton(
                            onTap: () => _updateUserRate(localStatus, newScore: localScore),
                            quality: GlassQuality.premium,
                            shape: const LiquidRoundedSuperellipse(borderRadius: 20),
                            settings: const LiquidGlassSettings(glassColor: Color(0xCC8B5CF6), blur: 15),
                            icon: Container(
                              alignment: Alignment.center,
                              padding: const EdgeInsets.symmetric(vertical: 18),
                              child: const Text('Сохранить', style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold)),
                            ),
                          ),
                        ),
                      ),
                      
                      if (localStatus != null) ...[
                        const SizedBox(height: 8),
                        CupertinoButton(
                          child: const Text('Удалить из списка', style: TextStyle(color: CupertinoColors.destructiveRed, fontWeight: FontWeight.bold)),
                          onPressed: () => _updateUserRate(null, newScore: 0),
                        ),
                      ],
                      const SizedBox(height: 24),
                    ],
                  ),
                ),
              ),
            ),
          );
        }
      ),
    );
  }

  Widget _buildStatusOptionModal(String statusValue, String title, IconData icon, String? currentLocalStatus, Function(String) onTap) {
    final isSelected = currentLocalStatus == statusValue;
    return ListTile(
      leading: Icon(icon, color: isSelected ? _accentColor : Colors.white.withValues(alpha: 0.5), size: 24),
      title: Text(title, style: TextStyle(color: isSelected ? _accentColor : Colors.white, fontSize: 17, fontWeight: isSelected ? FontWeight.bold : FontWeight.w500)),
      trailing: isSelected ? const Icon(CupertinoIcons.check_mark, color: _accentColor, size: 20) : null,
      onTap: () => onTap(statusValue),
    );
  }

  Future<void> _updateUserRate(String? newStatus, {required int newScore}) async {
    Navigator.pop(context);
    if (_currentUser == null) return;

    // Оптимистичное обновление UI
    setState(() {
      currentUserStatus = newStatus;
      _currentUserScore = newScore;
    });

    try {
      final api = ref.read(apiClientProvider);
      if (newStatus == null) {
        // Логика удаления (если поддерживается API)
      } else {
        // 🔥 Сохраняем и статус, и оценку
        await api.setUserRate(widget.animeId, newStatus, score: newScore, userId: _currentUser!.id);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ошибка сохранения: $e')));
    }
  }
}

// =====================================================================
// ГАЛЕРЕЯ СКРИНШОТОВ (Фуллскрин)
// =====================================================================
class _FullscreenGallery extends StatefulWidget {
  final List<String> screenshots;
  final int initialIndex;

  const _FullscreenGallery({required this.screenshots, required this.initialIndex});

  @override
  State<_FullscreenGallery> createState() => _FullscreenGalleryState();
}

class _FullscreenGalleryState extends State<_FullscreenGallery> {
  late PageController _pageController;
  late int _currentIndex;

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.initialIndex;
    _pageController = PageController(initialPage: widget.initialIndex);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: Colors.black,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Colors.black.withValues(alpha: 0.5),
        middle: Text('${_currentIndex + 1} / ${widget.screenshots.length}', style: const TextStyle(color: Colors.white)),
        leading: CupertinoButton(padding: EdgeInsets.zero, onPressed: () => Navigator.pop(context), child: const Icon(CupertinoIcons.xmark, color: Colors.white)),
      ),
      child: Stack(
        children: [
          PageView.builder(
            controller: _pageController,
            onPageChanged: (index) => setState(() => _currentIndex = index),
            itemCount: widget.screenshots.length,
            itemBuilder: (context, index) => Center(
              child: InteractiveViewer(
                minScale: 0.5,
                maxScale: 4.0,
                child: CachedNetworkImage(imageUrl: widget.screenshots[index], fit: BoxFit.contain),
              ),
            ),
          ),
        ],
      ),
    );
  }
}