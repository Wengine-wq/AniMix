# AniMix 3.0 — New Horizons

A from-scratch rewrite of AniMix in Kotlin and Compose Multiplatform.
Targets: Android and Windows desktop, from one shared codebase.

## Layout

| Module       | What it holds |
|--------------|---------------|
| `shared`     | All UI (Compose), theme, navigation, data layer. |
| `desktopApp` | Windows entry point, window chrome, installer config. |
| `androidApp` | Android entry point (included once an Android SDK is installed). |

Inside `shared/src/commonMain/kotlin/app/animix`:

- `data/shikimori` — catalog via Shikimori's public GraphQL API.
- `data/yummy` — finds the YummyAnime release for a Shikimori id and groups its videos into dubbings (studio + player) with opening/ending skip times.
- `data/playback` — Kodik browser capture, verified HLS/MP4 qualities and native Media3/libVLC playback with shared AniMix controls; see [resolver notes](docs/kodik-resolver.md).
- `data/downloads` — persistent season/episode queue, resumable HLS resources, single-file MP4 assembly and offline playback; see [player and download notes](docs/native-playback-downloads.md).
- `data/cache` — memory + disk cache: fresh entries skip the network, identical requests share one call, and stale data (up to 7 days) is shown when offline.
- `ui/theme` — graphite + pastel pink palette, dark and light themes, Inter typeface.
- `ui/navigation` — per-tab back stacks and `animix://` links (`animix://anime/<id>`, `animix://catalog`, `animix://settings`).

## Build

UI changes follow the [AniMix interface guidelines](docs/interface-guidelines.md): shared shapes, spacing, components, motion and navigation behavior.

Requires JDK 21 (a portable one lives in `D:\DevTools\jdk-21.0.12.1+1`).

```
set JAVA_HOME=D:\DevTools\jdk-21.0.12.1+1
gradlew :desktopApp:run                    # run on Windows
gradlew :desktopApp:createDistributable    # AniMix.exe in desktopApp/build/compose/binaries
gradlew :shared:desktopTest                # unit tests
```

The font is Inter (SIL Open Font License, see `licenses/`).

Windows builds bundle a pinned, checksum-verified VLC runtime (first build downloads about 82 MB); no VLC installation is required. See [native video notices](licenses/native-video.md).

Enable the complete-module commit/push checks after cloning:
`powershell -File scripts/setup-git-guards.ps1`. They are already enabled in this checkout.
