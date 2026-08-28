import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Owns the custom profile backdrop.
///
/// The selected file is copied into AniMix storage so the profile does not
/// break when the original image is moved, renamed or removed by the picker.
class ProfileCoverStorage {
  ProfileCoverStorage._();

  static const _preferenceKey = 'profile_custom_cover_v1';
  static const _directoryName = 'profile';
  static const _fileStem = 'custom_cover';
  static const _animixAvatarKey = 'animix_profile_avatar_v1';
  static const _animixBannerKey = 'animix_profile_banner_v1';

  static Future<String?> currentPath() async {
    final preferences = await SharedPreferences.getInstance();
    final value = preferences.getString(_preferenceKey)?.trim();
    if (value == null || value.isEmpty) return null;
    if (await File(value).exists()) return value;
    await preferences.remove(_preferenceKey);
    return null;
  }

  static Future<String?> chooseAndSave() async {
    final selected = await pickImage();
    if (selected == null) return null;

    final source = File(selected.path);
    if (!await source.exists()) return null;
    final support = await getApplicationSupportDirectory();
    final directory = Directory(
      '${support.path}${Platform.pathSeparator}$_directoryName',
    );
    await directory.create(recursive: true);
    final extension = _safeExtension(selected.name);
    final destination = File(
      '${directory.path}${Platform.pathSeparator}$_fileStem$extension',
    );

    await _removeOwnedCovers(directory, exceptPath: destination.path);
    await source.copy(destination.path);
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_preferenceKey, destination.path);
    return destination.path;
  }

  static Future<XFile?> pickImage() => openFile(
    acceptedTypeGroups: const [
      XTypeGroup(
        label: 'Изображения',
        extensions: ['jpg', 'jpeg', 'png', 'webp'],
        uniformTypeIdentifiers: ['public.image'],
      ),
    ],
  );

  static Future<void> clearAniMixMedia({required bool isBanner}) async {
    final preferences = await SharedPreferences.getInstance();
    final key = isBanner ? _animixBannerKey : _animixAvatarKey;
    final path = preferences.getString(key);
    await preferences.remove(key);
    if (path == null) return;
    final file = File(path);
    if (await file.exists()) await file.delete();
  }

  static Future<void> clear() async {
    final preferences = await SharedPreferences.getInstance();
    final stored = preferences.getString(_preferenceKey);
    await preferences.remove(_preferenceKey);
    if (stored == null) return;
    final file = File(stored);
    if (await file.exists()) await file.delete();
  }

  static String _safeExtension(String name) {
    final match = RegExp(
      r'\.(jpe?g|png|webp)$',
      caseSensitive: false,
    ).firstMatch(name.trim());
    return match == null ? '.jpg' : match.group(0)!.toLowerCase();
  }

  static Future<void> _removeOwnedCovers(
    Directory directory, {
    required String exceptPath,
  }) async {
    await for (final entity in directory.list()) {
      if (entity is! File || entity.path == exceptPath) continue;
      final name = entity.uri.pathSegments.last;
      if (name.startsWith(_fileStem)) await entity.delete();
    }
  }
}
