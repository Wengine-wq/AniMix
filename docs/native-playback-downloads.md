# Native playback and offline downloads

`WatchScreen` resolves the selected Kodik episode and mounts `AniMixPlayer`.
Android decodes with Media3 ExoPlayer; Windows uses the bundled libVLC runtime
through vlcj. On Windows libVLC renders into memory (`FrameSink`, RV32 frames)
and Compose draws each frame, so both OSes show the same controls over the
picture. Other embedded providers retain the existing browser fallback.

The overlay has play/pause, a timeline with the opening and ending marked,
±10 seconds (also a double tap on the left/right third), sound/volume, quality
selection (remembered for the next episode), fullscreen, next episode and
download. It hides after 3 seconds of playback. Windows keys: Space/K, ←/→ or
J/L, ↑/↓ volume, M mute, F fullscreen, Esc, N next, S skip.

Playback positions are stored per anime/episode in the library. From the
ending (or the last 8 % when the ending is unknown) an episode counts as
watched, its saved position is cleared, and opening it again starts from the
beginning. The watch screen offers the episode in progress, else the first
unwatched one. At the end, auto-next counts down 5 seconds and can be cancelled.

OP/ED times come from YummyAnime first and AniSkip (`data/skips`, by the
Shikimori/MAL id) second. AniSkip submissions are only used when their release
length is within 15 s of the playing one. Every answer is stored in
`skips.json` in the data folder, so downloaded episodes skip offline as well;
episodes queued for download are fetched ahead. No guessed fixed-duration
opening skip is applied.

## Queue and files

On the watch screen «Скачать» switches the episode grid into picking mode for the
selected dubbing: tap episodes or take the whole season. The download quality is
asked once (480p/720p/1080p), stored in settings and changeable there. On Android
the foreground service holds a partial wake lock and a Wi-Fi lock while the queue
runs and shows progress in its notification; network drops are retried for about
four minutes with the saved parts kept, so turning the screen off does not fail
the episode.

- Download the selected episode at the current quality, or enqueue every
  available episode of the selected dubbing at 720p/1080p. Missing renditions
  fall back to the closest available lower quality (or the smallest rendition
  when every one exceeds the request).
- One worker resolves each episode immediately before downloading it, retaining
  the original studio/dubbing. HTTP 401/403 refreshes the source up to twice.
  A failed episode does not block subsequent jobs.
- `Platform.dataDir/downloads/queue.json` stores jobs and statuses atomically.
  Signed stream URLs are kept in memory. Pause preserves downloaded resources
  and partial bytes, and resume uses HTTP Range/If-Range where supported. An
  ignored Range restarts the affected resource without concatenating duplicate
  bytes. Changed content/range mismatches fail without overwriting saved bytes.
- HLS master/media playlists, relative URIs, separate audio, initialization maps,
  byte-range resources and ordinary AES-128 HLS are handled. Local manifests
  reference app-generated filenames. Unsupported DRM/SAMPLE-AES and unfinished
  live streams return an error instead of a bogus downloaded file.
- Assembly uses Media3 Transformer on Android and native libVLC remux on Windows.
  The final `<job-id>.mp4` appears only after successful assembly and a nonempty
  file check; original MP4 sources are transferred directly. No FFmpeg install
  is required for the application. Intermediate HLS files are deleted on success.
- The Downloads screen has per-job pause/resume/retry/delete, queue-wide pause
  and resume, progress and offline playback. Offline auto-next selects the next
  already downloaded episode in the same dubbing and quality and never resolves
  a network source. Settings and per-episode OP/ED timestamps remain available.
- Restart restores interrupted jobs to the queue. Missing final files are marked
  failed; corrupt queue JSON is quarantined without deleting video files. A
  normal Windows exit pauses active jobs and flushes the library. Android uses a
  `dataSync` foreground service during downloads; platform timeouts pause the
  queue. OS process termination is recovered on the next app start.

## Runtime packaging

`prepareWindowsVlc` obtains a pinned official VLC 3.0.24 archive and verifies its
SHA-256 before extraction. Development runs and tests use the extracted bundle;
Compose native distributions put it in their application resources. Native
`java.net.http` is included for browser capture/CDP. Runtime notices and upstream
source links are recorded in [native video notices](../licenses/native-video.md).

## Verification

Fast parser, range-transfer, queue persistence, resolver and skip policy tests:

```powershell
.\gradlew.bat :shared:desktopTest
```

Real native media test (requires FFmpeg only to generate the synthetic fixture):

```powershell
powershell -File scripts/generate-media-fixture.ps1
$env:ANIMIX_MEDIA_TEST = '1'
$env:ANIMIX_MEDIA_FIXTURE = "$PWD/build/test-media"
.\gradlew.bat :shared:desktopTest --tests '*NativeMediaTest*'
.\gradlew.bat :androidApp:connectedDebugAndroidTest
```

Windows tests transfer HLS through a local server requiring the correct media
headers, assemble a real MP4, stop the server, then verify decoded audio/video,
frames handed to the Compose surface, pause, seek and end-of-media. Android tests
assemble the same HLS fixture, delete the source parts, render the resulting MP4
through the app's native surface, inspect decoded picture colors with PixelCopy,
and exercise pause, seek and fullscreen. A connected Android device/emulator is
required; these tests do not certify every device's codecs or a provider's uptime.
Both platforms also test a master playlist with a separate audio rendition.
Android additionally verifies the foreground service remains active when its
activity stops and is stopped when the queue is paused.

Browser/provider capture tests are documented in [resolver notes](kodik-resolver.md).
Generated test media lives under ignored `build/test-media` and ships only inside
the test APK, never the app APK.

Verified in this checkout: 45 desktop/common tests and 3 Android emulator tests
(API 36), with no skips or failures, including live Kodik capture through
YummyAnime. A separate packaged-launcher check also played the MP4 with the
distribution's own JVM and bundled libVLC, without development runtime paths.

## Commit/push protection

Run `powershell -File scripts/setup-git-guards.ps1` for a new clone. The local
`core.hooksPath=.githooks` setting enables guards for ordinary `git commit` and
`git push`. They reject excluded/untracked playback/download changes, missing
files from the required manifest, or disconnected player/queue entry points.
Pre-push checks the commit being sent, including the initially introduced module.
An unrelated change cannot silently leave these module edits outside the commit.

Local Git hooks can be explicitly disabled or bypassed by someone with control
of the checkout. Enforcing the same rule for every machine requires a remote CI
check and protected branch. This checkout currently has no remote configured.
