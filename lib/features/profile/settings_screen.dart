import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';
import '../../core/secure_storage.dart';
import '../auth/login_screen.dart';
import '../watch/models/watch_mapping.dart';
import '../watch/repositories/watch_mapping_repository.dart';

// Цветовая палитра "Premium Violet"
const Color _accentColor = Color(0xFF8B5CF6);
const Color _accentLight = Color(0xFFA78BFA);
const Color _bgColor = Color(0xFF09090B);

// =====================================================================
// УМНАЯ ОБЕРТКА ДЛЯ ЛИКВИДНОГО СТЕКЛА
// =====================================================================
class _GlassUI extends StatelessWidget {
  final Widget child;
  final BorderRadius? borderRadius;
  final BoxBorder? border;
  final GlassQuality quality;
  final EdgeInsetsGeometry? padding;
  final Color? tintColor;

  const _GlassUI({
    required this.child,
    this.borderRadius,
    this.border,
    this.quality = GlassQuality.standard,
    this.padding,
    this.tintColor,
  });

  @override
  Widget build(BuildContext context) {
    Widget content = Container(
      padding: padding ?? EdgeInsets.zero,
      decoration: BoxDecoration(color: tintColor ?? Colors.transparent),
      child: child,
    );

    content = GlassContainer(quality: quality, child: content);

    if (borderRadius != null) content = ClipRRect(borderRadius: borderRadius!, child: content);
    if (border != null) {
      content = Container(
        foregroundDecoration: BoxDecoration(borderRadius: borderRadius, border: border),
        child: content,
      );
    }
    return content;
  }
}

// =====================================================================
// ЭКРАН НАСТРОЕК (БАЗА ПЛЕЕРОВ ВОССТАНОВЛЕНА)
// =====================================================================
class SettingsScreen extends StatefulHookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> with SingleTickerProviderStateMixin {
  late AnimationController _bgAnimController;
  
  // Фактические настройки: Репозиторий привязанных плееров
  final WatchMappingRepository _repo = WatchMappingRepository();
  List<WatchMapping> _mappings = [];
  bool _isLoadingMappings = true;

  @override
  void initState() {
    super.initState();
    _bgAnimController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 15),
    )..repeat(reverse: true);
    
    _loadMappings();
  }

  @override
  void dispose() {
    _bgAnimController.dispose();
    super.dispose();
  }

  Future<void> _loadMappings() async {
    final data = await _repo.getAll();
    if (mounted) {
      setState(() {
        _mappings = data;
        _isLoadingMappings = false;
      });
    }
  }

  Future<void> _deleteMapping(String key) async {
    await _repo.delete(key);
    _loadMappings(); // Перезагружаем список после удаления
  }

  Future<void> _openGitHub() async {
    final Uri url = Uri.parse('https://github.com/Wengine-wq/AniMix');
    if (!await launchUrl(url, mode: LaunchMode.externalApplication)) {
      debugPrint('Не удалось открыть $url');
    }
  }

  void _logout() async {
    showCupertinoDialog(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Выход из аккаунта'),
        content: const Text('Вы уверены, что хотите выйти? Данные сессии будут удалены.'),
        actions: [
          CupertinoDialogAction(
            child: const Text('Отмена'),
            onPressed: () => Navigator.pop(ctx),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () async {
              Navigator.pop(ctx);
              await SecureStorage.clear();
              ref.invalidate(isLoggedInProvider);
              ref.invalidate(currentUserProvider);
              Navigator.of(context, rootNavigator: true).pushAndRemoveUntil(
                CupertinoPageRoute(builder: (_) => const LoginScreen()),
                (route) => false,
              );
            },
            child: const Text('Выйти'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bgColor,
      body: Stack(
        children: [
          Positioned.fill(
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 90, sigmaY: 90),
              child: AnimatedBuilder(
                animation: _bgAnimController,
                builder: (context, child) {
                  final value = _bgAnimController.value;
                  return Stack(
                    children: [
                      Positioned(
                        top: -50 + (30 * value), right: -50 - (20 * value),
                        child: Container(width: 350, height: 350, decoration: BoxDecoration(color: _accentColor.withOpacity(0.15), shape: BoxShape.circle)),
                      ),
                      Positioned(
                        bottom: 0 - (40 * value), left: -100 + (30 * value),
                        child: Container(width: 400, height: 400, decoration: BoxDecoration(color: const Color(0xFF3B82F6).withOpacity(0.1), shape: BoxShape.circle)),
                      ),
                    ],
                  );
                }
              ),
            ),
          ),

          CustomScrollView(
            physics: const BouncingScrollPhysics(),
            slivers: [
              SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(20, MediaQuery.of(context).padding.top + 20, 20, 32),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Header
                      Row(
                        children: [
                          GestureDetector(
                            onTap: () => Navigator.pop(context),
                            child: _GlassUI(
                              quality: GlassQuality.standard,
                              tintColor: Colors.black.withOpacity(0.3),
                              borderRadius: BorderRadius.circular(20),
                              border: Border.all(color: Colors.white.withOpacity(0.1)),
                              padding: const EdgeInsets.all(12),
                              child: const Icon(CupertinoIcons.back, color: Colors.white, size: 24),
                            ),
                          ),
                          const SizedBox(width: 16),
                          const Text('Настройки', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: -1.2)),
                        ],
                      ),
                      const SizedBox(height: 32),

                      // Блок информации о приложении
                      _GlassUI(
                        quality: GlassQuality.standard,
                        tintColor: Colors.black.withOpacity(0.4),
                        borderRadius: BorderRadius.circular(24),
                        border: Border.all(color: Colors.white.withOpacity(0.08)),
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Container(
                                  width: 64, height: 64,
                                  decoration: BoxDecoration(
                                    gradient: const LinearGradient(colors: [_accentColor, _accentLight], begin: Alignment.topLeft, end: Alignment.bottomRight),
                                    borderRadius: BorderRadius.circular(16),
                                  ),
                                  child: const Icon(CupertinoIcons.play_circle_fill, color: Colors.white, size: 36),
                                ),
                                const SizedBox(width: 16),
                                Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    const Text('AniMix', style: TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                                    const SizedBox(height: 2),
                                    Text('Версия 1.1.0', style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 14)),
                                  ],
                                ),
                              ],
                            ),
                            const SizedBox(height: 24),
                            const Text(
                              'Лучший клиент для просмотра аниме. Создан с любовью к деталям и технологиям.',
                              style: TextStyle(color: Colors.white, fontSize: 15, height: 1.4),
                            ),
                            const SizedBox(height: 24),
                            GestureDetector(
                              onTap: _openGitHub,
                              child: Container(
                                width: double.infinity,
                                padding: const EdgeInsets.symmetric(vertical: 16),
                                decoration: BoxDecoration(
                                  color: Colors.white.withOpacity(0.05),
                                  borderRadius: BorderRadius.circular(16),
                                  border: Border.all(color: Colors.white.withOpacity(0.1)),
                                ),
                                child: Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: const [
                                    Icon(CupertinoIcons.link, color: _accentLight, size: 20),
                                    SizedBox(width: 8),
                                    Text('GitHub Репозиторий', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
                                  ],
                                ),
                              ),
                            )
                          ],
                        ),
                      ),
                      const SizedBox(height: 32),

                      // 🔥 БАЗА ПРИВЯЗАННЫХ ПЛЕЕРОВ (ВОССТАНОВЛЕНО)
                      const Text('Привязанные плееры', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: -0.5)),
                      const SizedBox(height: 16),
                      if (_isLoadingMappings)
                        const Center(child: CupertinoActivityIndicator())
                      else if (_mappings.isEmpty)
                        _GlassUI(
                          quality: GlassQuality.minimal,
                          tintColor: Colors.black.withOpacity(0.3),
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(color: Colors.white.withOpacity(0.05)),
                          padding: const EdgeInsets.all(24),
                          child: Center(
                            child: Text('Нет сохраненных тайтлов.\nОни появятся здесь после выбора плеера.', 
                                textAlign: TextAlign.center, 
                                style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 14, height: 1.4)),
                          ),
                        )
                      else
                        ListView.builder(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          padding: EdgeInsets.zero,
                          itemCount: _mappings.length,
                          itemBuilder: (context, index) {
                            return _buildMappingCard(_mappings[index]);
                          },
                        ),
                        
                      const SizedBox(height: 32),

                      // Кнопка выхода
                      GestureDetector(
                        onTap: _logout,
                        child: _GlassUI(
                          quality: GlassQuality.standard,
                          tintColor: CupertinoColors.systemRed.withOpacity(0.15),
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(color: CupertinoColors.systemRed.withOpacity(0.3)),
                          padding: const EdgeInsets.symmetric(vertical: 18),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: const [
                              Icon(CupertinoIcons.square_arrow_right, color: CupertinoColors.systemRed, size: 22),
                              SizedBox(width: 8),
                              Text('Выйти из аккаунта', style: TextStyle(color: CupertinoColors.systemRed, fontSize: 17, fontWeight: FontWeight.bold)),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 80),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // Дизайн карточки привязанного плеера
  Widget _buildMappingCard(WatchMapping mapping) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: _GlassUI(
        quality: GlassQuality.minimal,
        tintColor: Colors.black.withOpacity(0.3),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withOpacity(0.05)),
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: mapping.posterUrl != null 
                  ? CachedNetworkImage(
                      imageUrl: mapping.posterUrl!,
                      width: 50,
                      height: 70,
                      fit: BoxFit.cover,
                      errorWidget: (_, __, ___) => _buildFallbackPoster(),
                    )
                  : _buildFallbackPoster(),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    mapping.releaseTitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: _accentColor.withOpacity(0.2),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      mapping.provider.toUpperCase(),
                      style: const TextStyle(color: _accentLight, fontSize: 10, fontWeight: FontWeight.bold),
                    ),
                  ),
                ],
              ),
            ),
            CupertinoButton(
              padding: const EdgeInsets.all(12),
              onPressed: () => _deleteMapping(mapping.key),
              child: const Icon(CupertinoIcons.trash, color: CupertinoColors.systemRed, size: 22),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFallbackPoster() {
    return Container(
      width: 50,
      height: 70,
      color: const Color(0xFF1C1C1E),
      child: const Icon(CupertinoIcons.play_rectangle, color: Colors.grey),
    );
  }
}