import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';


import '../../providers/auth_provider.dart';
import '../auth/login_screen.dart';
import '../watch/models/watch_mapping.dart';
import '../watch/repositories/watch_mapping_repository.dart';

class SettingsScreen extends HookConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authService = ref.watch(authServiceProvider);

    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: CupertinoNavigationBar(
        middle: const Text('Настройки'),
        backgroundColor: const Color(0xFF1E1E1E).withValues(alpha: 0.95),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => Navigator.pop(context),
          child: const Icon(CupertinoIcons.back, color: CupertinoColors.white),
        ),
      ),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _buildSectionTitle('Плеер и Данные'),
            _buildTile(
              icon: CupertinoIcons.link,
              title: 'База сопоставлений',
              subtitle: 'Управление изученными аниме',
              onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const WatchMappingsScreen())),
            ),
            
            const SizedBox(height: 32),
            _buildSectionTitle('Внешний вид'),
            _buildTile(
              icon: CupertinoIcons.moon_fill,
              title: 'Тема приложения',
              subtitle: 'Темная (стандартная)',
              onTap: () {
                showCupertinoDialog(
                  context: context,
                  builder: (ctx) => CupertinoAlertDialog(
                    title: const Text('Смена темы'),
                    content: const Text('Светлая тема пока в разработке. Сейчас приложение оптимизировано под глубокий темный режим.'),
                    actions: [CupertinoDialogAction(child: const Text('ОК'), onPressed: () => Navigator.pop(ctx))],
                  ),
                );
              },
            ),

            const SizedBox(height: 32),
            _buildSectionTitle('О приложении'),
            _buildTile(
              icon: CupertinoIcons.doc_text_fill,
              title: 'Список изменений',
              onTap: () => Navigator.push(context, CupertinoPageRoute(builder: (_) => const ChangelogScreen())),
            ),
            _buildTile(
              icon: CupertinoIcons.info,
              title: 'Версия AniMix',
              trailing: const Text('1.0.0 (Build 26)', style: TextStyle(color: CupertinoColors.systemGrey)),
              onTap: () {},
            ),

            const SizedBox(height: 48),
            CupertinoButton(
              color: CupertinoColors.systemRed.withValues(alpha: 0.15),
              onPressed: () async {
                final confirmed = await showCupertinoDialog<bool>(
                  context: context,
                  builder: (ctx) => CupertinoAlertDialog(
                    title: const Text('Выйти из аккаунта?'),
                    content: const Text('Вы будете перенаправлены на экран входа.'),
                    actions: [
                      CupertinoDialogAction(child: const Text('Отмена'), onPressed: () => Navigator.pop(ctx, false)),
                      CupertinoDialogAction(isDestructiveAction: true, child: const Text('Выйти'), onPressed: () => Navigator.pop(ctx, true)),
                    ],
                  ),
                );
                if (confirmed == true && context.mounted) {
                  await authService.logout();
                  if (context.mounted) {
                    Navigator.of(context, rootNavigator: true).pushAndRemoveUntil(
                      CupertinoPageRoute(builder: (_) => const LoginScreen()),
                      (route) => false,
                    );
                  }
                }
              },
              child: const Text('Выйти из Shikimori', style: TextStyle(color: CupertinoColors.systemRed, fontWeight: FontWeight.bold)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) => Padding(
    padding: const EdgeInsets.only(left: 12, bottom: 8, top: 12),
    child: Text(title.toUpperCase(), style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 12, fontWeight: FontWeight.bold)),
  );

  Widget _buildTile({required IconData icon, required String title, String? subtitle, Widget? trailing, VoidCallback? onTap}) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        margin: const EdgeInsets.only(bottom: 2),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: const BoxDecoration(color: Color(0xFF1E1E1E)),
        child: Row(
          children: [
            Icon(icon, color: const Color(0xFFFF5722), size: 22),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(color: Colors.white, fontSize: 17)),
                  if (subtitle != null) Text(subtitle, style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 13)),
                ],
              ),
            ),
            if (trailing != null) trailing else const Icon(CupertinoIcons.chevron_right, color: CupertinoColors.systemGrey, size: 16),
          ],
        ),
      ),
    );
  }
}

// Экран сопоставлений
class WatchMappingsScreen extends StatefulWidget {
  const WatchMappingsScreen({super.key});
  @override
  State<WatchMappingsScreen> createState() => _WatchMappingsScreenState();
}

class _WatchMappingsScreenState extends State<WatchMappingsScreen> {
  final _repo = WatchMappingRepository();
  List<WatchMapping>? _list;

  @override
  void initState() {
    super.initState();
    // 🔥 ФИКС МИКРОЛАГОВ ПРИ АНИМАЦИИ ПЕРЕХОДА
    // Ждем 350мс (пока экран плавно выедет), и только потом грузим данные
    Future.delayed(const Duration(milliseconds: 350), () {
      if (mounted) _load();
    });
  }

  Future<void> _load() async {
    final data = await _repo.getAll();
    if (mounted) {
      setState(() => _list = data);
    }
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(middle: Text('Изученные аниме')),
      child: SafeArea(
        child: _list == null 
          ? const Center(child: CupertinoActivityIndicator())
          : _list!.isEmpty 
            ? const Center(child: Text('База пуста', style: TextStyle(color: Colors.white)))
            : ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: _list!.length,
                itemBuilder: (context, i) {
                  final m = _list![i];
                  
                  // 🔥 ЖЕСТКИЙ ФИКС КРАША ДЛЯ WINDOWS
                  // Защищаем плеер от старых кривых ссылок, закэшированных в телефоне/пк
                  final String rawPoster = m.posterUrl ?? '';
                  final String? validPoster = rawPoster.isNotEmpty 
                      ? (rawPoster.startsWith('http') ? rawPoster : 'https://anilibria.top$rawPoster')
                      : null;

                  return Dismissible(
                    key: Key(m.key),
                    direction: DismissDirection.endToStart,
                    background: Container(alignment: Alignment.centerRight, padding: const EdgeInsets.only(right: 20), color: Colors.red, child: const Icon(CupertinoIcons.trash, color: Colors.white)),
                    onDismissed: (_) => _repo.delete(m.key),
                    child: Container(
                      margin: const EdgeInsets.only(bottom: 10),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(color: const Color(0xFF1E1E1E), borderRadius: BorderRadius.circular(12)),
                      child: Row(
                        children: [
                          if (validPoster != null) 
                            ClipRRect(
                              borderRadius: BorderRadius.circular(6), 
                              child: CachedNetworkImage(
                                imageUrl: validPoster, 
                                width: 40, 
                                height: 56, 
                                fit: BoxFit.cover,
                                memCacheWidth: 120,
                                memCacheHeight: 168,
                                // 🔥 Если картинка битая — показываем серый квадрат, а не вешаем приложение
                                errorWidget: (context, url, error) => Container(
                                  width: 40, height: 56,
                                  color: const Color(0xFF2A2A2A),
                                  child: const Icon(CupertinoIcons.photo, size: 20, color: CupertinoColors.systemGrey),
                                ),
                              )
                            )
                          else
                            Container(
                              width: 40, height: 56,
                              decoration: BoxDecoration(color: const Color(0xFF2A2A2A), borderRadius: BorderRadius.circular(6)),
                              child: const Icon(CupertinoIcons.photo, size: 20, color: CupertinoColors.systemGrey),
                            ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(m.releaseTitle, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
                                Text(m.provider == 'anilibria' ? 'Anilibria' : m.provider, style: const TextStyle(color: CupertinoColors.systemGrey, fontSize: 12)),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
      ),
    );
  }
}

// Экран лога изменений
class ChangelogScreen extends StatelessWidget {
  const ChangelogScreen({super.key});
  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: const Color(0xFF0F0F0F),
      navigationBar: const CupertinoNavigationBar(middle: Text('Что нового')),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: const [
            Text('Версия 1.0.0', style: TextStyle(color: Color(0xFFFF5722), fontSize: 20, fontWeight: FontWeight.bold)),
            SizedBox(height: 16),
            Text('• Внедрена система самообучения маппинга\n• Полная поддержка Windows и iOS\n• Плеер с выбором качества 1080p/720p/480p\n• Умный поиск с учетом сезонов и цифр\n• Обработка ошибок 401 и автоматический релогин', style: TextStyle(color: Colors.white, fontSize: 16, height: 1.5)),
          ],
        ),
      ),
    );
  }
}