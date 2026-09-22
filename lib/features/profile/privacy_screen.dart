import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../providers/auth_provider.dart';

class PrivacyScreen extends ConsumerStatefulWidget {
  const PrivacyScreen({super.key});

  @override
  ConsumerState<PrivacyScreen> createState() => _PrivacyScreenState();
}

class _PrivacyScreenState extends ConsumerState<PrivacyScreen> {
  bool? _visible;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final value = await ref
          .read(animixAuthServiceProvider)
          .getLibraryVisible();
      if (mounted) {
        setState(() {
          _visible = value;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Не удалось загрузить настройку приватности.');
      }
    }
  }

  Future<void> _save(bool value) async {
    setState(() => _saving = true);
    try {
      final saved = await ref
          .read(animixAuthServiceProvider)
          .setLibraryVisible(value);
      if (mounted) {
        setState(() {
          _visible = saved;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = 'Не удалось сохранить настройку. Попробуйте снова.',
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Приватность')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        if (_visible == null && _error == null)
          const Center(child: CircularProgressIndicator.adaptive()),
        if (_error != null) ...[
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
          TextButton(onPressed: _load, child: const Text('Повторить')),
        ],
        if (_visible != null)
          Card(
            child: SwitchListTile.adaptive(
              title: const Text('Показывать мою библиотеку'),
              subtitle: const Text(
                'Другие пользователи AniMix смогут видеть ваши аниме, статусы и прогресс.',
              ),
              value: _visible!,
              onChanged: _saving ? null : _save,
            ),
          ),
        const SizedBox(height: 14),
        const ListTile(
          leading: Icon(CupertinoIcons.info_circle),
          title: Text('Обмен работает в обе стороны'),
          subtitle: Text(
            'Если закрыть свою библиотеку, вы тоже не сможете смотреть библиотеки других пользователей. '
            'В будущем вам также будут недоступны общая рулетка и общая библиотека аниме.',
          ),
        ),
      ],
    ),
  );
}
