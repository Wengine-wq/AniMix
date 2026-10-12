import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

import '../../core/local_profile.dart';
import '../../providers/auth_provider.dart';
import '../../providers/user_provider.dart';

/// The promise shown wherever a local profile is created or displayed.
const localProfileNotice =
    'Локальный профиль хранится полностью на этом устройстве и не переносится '
    'на сервер. Если позже войдёте в аккаунт AniMix, списки можно будет '
    'объединить.';

/// Bottom sheet that creates a device-only profile: just a name.
class LocalProfileSheet extends ConsumerStatefulWidget {
  const LocalProfileSheet({super.key});

  /// Returns true when a profile was created.
  static Future<bool> show(BuildContext context) async {
    final created = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => const LocalProfileSheet(),
    );
    return created ?? false;
  }

  @override
  ConsumerState<LocalProfileSheet> createState() => _LocalProfileSheetState();
}

class _LocalProfileSheetState extends ConsumerState<LocalProfileSheet> {
  final _name = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  String get _trimmed => _name.text.trim();
  bool get _valid => _trimmed.isNotEmpty && _trimmed.length <= 32;

  Future<void> _create() async {
    if (!_valid || _busy) return;
    setState(() => _busy = true);
    await LocalProfile.create(_trimmed);
    await ref.read(guestModeProvider.notifier).set(true);
    ref.read(userDataRevisionProvider.notifier).bump();
    ref.invalidate(currentUserProvider);
    if (mounted) Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        12,
        24,
        24 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: Theme.of(context).dividerColor,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 20),
          const Text(
            'Локальный профиль',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            'Списки, прогресс и достижения без аккаунта и без сервера.',
            style: TextStyle(color: scheme.onSurfaceVariant, height: 1.35),
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _name,
            autofocus: true,
            maxLength: 32,
            textInputAction: TextInputAction.done,
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) => _create(),
            decoration: InputDecoration(
              labelText: 'Как вас называть',
              filled: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(14),
                borderSide: BorderSide.none,
              ),
            ),
          ),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: scheme.primary.withValues(alpha: .10),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  CupertinoIcons.device_phone_portrait,
                  size: 18,
                  color: scheme.primary,
                ),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    localProfileNotice,
                    style: TextStyle(fontSize: 13, height: 1.35),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          FilledButton(
            onPressed: _valid && !_busy ? _create : null,
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
            ),
            child: const Text(
              'Создать профиль',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}
