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
- `data/cache` — memory + disk cache: fresh entries skip the network, identical requests share one call, and stale data (up to 7 days) is shown when offline.
- `ui/theme` — graphite + pastel pink palette, dark and light themes, Inter typeface.
- `ui/navigation` — per-tab back stacks and `animix://` links (`animix://anime/<id>`, `animix://catalog`, `animix://settings`).

## Build

Requires JDK 21 (a portable one lives in `D:\DevTools\jdk-21.0.12.1+1`).

```
set JAVA_HOME=D:\DevTools\jdk-21.0.12.1+1
gradlew :desktopApp:run                    # run on Windows
gradlew :desktopApp:createDistributable    # AniMix.exe in desktopApp/build/compose/binaries
gradlew :shared:desktopTest                # unit tests
```

The font is Inter (SIL Open Font License, see `licenses/`).
