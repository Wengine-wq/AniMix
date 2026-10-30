# Обновления через GitHub Releases

- Источник — один параметр `UpdateSource.REPOSITORY` (сейчас `Wengine-wq/AniMix`). Приложение раз в шесть часов (не чаще) читает `https://api.github.com/repos/<REPOSITORY>/releases`; ошибки сети тихие, проверка повторится позже.
- Учитываются только опубликованные релизы этой линии (не черновики и не pre-release) с тегом `v<версия>-re`, например `v1.0.6-re`. Релизы 2.x в том же репозитории (`v2.5.33`) никогда не предлагаются. Версия сравнивается с `AppRelease.build` по числам.
- Файлы берутся только по ссылкам `https://github.com/…`:
  - Android — APK для ABI устройства (`…arm64-v8a.apk` и т. п.), иначе `…universal.apk`, иначе единственный APK. Файл скачивается в `cache/updates` и передаётся системному установщику через `FileProvider`. Если установка из AniMix не разрешена, открывается системная настройка, а скачанный файл сохраняется для повторного нажатия. APK должен быть подписан тем же ключом, что и установленная версия.
  - Windows — `.msi`, иначе `.exe`, иначе `.zip`. Установщик запускается сразу. Архив (`AniMix-<версия>-re-Windows-x64.zip`, внутри папка `AniMix` с `AniMix.exe`) распаковывается рядом с загрузкой, а скрипт PowerShell ждёт закрытия AniMix, копирует новые файлы в папку запущенного `AniMix.exe` (`jpackage.app-path`) и запускает его снова. Данные в `%LOCALAPPDATA%\AniMix3` не затрагиваются.
- На главной под заголовком — одна карточка «Доступна версия X re» с первой строкой описания релиза, кнопками «Обновить» и «Позже». «Позже» скрывает эту версию; более новая будет предложена снова.

## Как выпустить версию

1. Поднять `versionName`/`versionCode` (Android), `packageVersion` (Windows) и `AppRelease.build`.
2. `gradlew :androidApp:assembleRelease :desktopApp:createReleaseDistributable` — APK подписывается ключом из `%USERPROFILE%\.animix\release.properties` (`release.jks`). Без этого ключа обновления на Android невозможны: установленная копия примет только APK с той же подписью.
3. Создать релиз в `Wengine-wq/AniMix` с тегом `v<build>-re`: `AniMix-<build>-re-universal.apk`, `AniMix-<build>-re-Windows-x64.zip` (папка `AniMix` из `desktopApp/build/compose/binaries/main-release/app`) и `SHA256SUMS.txt`.

## Android: идентификатор и ключ

Релиз — `app.animix.newhorizons`, отдельно от AniMix 2.x (`com.example.animix`). 2.x подписывался отладочным ключом, которого больше нет, поэтому New Horizons не может обновить 2.x и ставится рядом. Перенос локального профиля 2.x на телефоне при этом недоступен: списки переносятся с ПК или через онлайн-профиль.
