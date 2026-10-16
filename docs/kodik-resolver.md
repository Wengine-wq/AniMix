# Kodik direct-stream resolver

Ported from AniMix 2.5.34's `kodik_webview_screen.dart` and
`services/hls_playlist_service.dart`. The old Flutter project is read-only input;
3.0 keeps the browser adapter, playlist parsing and cache separate.

## Use

`AppGraph.kodik.resolve(episode.iframeUrl)` returns a `ResolvedStream` containing
quality labels/URLs and required media headers. It runs when a Kodik episode is
selected on `WatchScreen`. Changing episode/dubbing or leaving the screen cancels
the request. Retry forces a new capture. The result feeds the native player with
AniMix controls and the persistent download manager; see [native playback](native-playback-downloads.md).

Pass `ResolvedStream.headers` to the native player for BOTH HLS manifests
and media segments. The embed uses `https://yani.tv/` as its referrer; media uses
`https://kodikapi.com/` and the capturing browser's User-Agent. A copied bare URL
does not carry these headers and is not guaranteed to work in another player.

## Platforms

- Android: system WebView, created on the main thread. Captures network requests
  and PlayerJS/media-element sources through the shared script. No file/content
  access, no external popups, no SSL-error overrides. Disposed after resolution,
  failure, timeout or cancellation. Browser/provider capture and native media
  playback have separate integration tests; the latter uses synthetic HLS.
- Windows: installed Microsoft Edge, with Chrome as a fallback. Runs headless
  with a fresh profile below AniMix's own cache and a loopback-only random CDP
  port. It does not attach to the user's browser or use its profile. Commands,
  fragmented WebSocket messages, subprocesses and owned profile cleanup have
  lifecycle handling. No bundled Chromium download or extra runtime dependency.
  Desktop detection also knows common Linux/macOS Chrome paths, but those OSes
  have not been tested.

The shared script observes normal page/network activity; the resolver does not
decode DRM, fetch content keys, or recover credentials. It does not log signed
stream URLs. Rejected HTTP responses remain failures, with a retry in the UI.

## Selection, validation and cache

- Collect candidates briefly (150–500 ms); prefer a PlayerJS quality list, then
  master HLS, media HLS and MP4. Retain lower candidates if the preferred one
  fails verification. Ranking state has one coroutine owner.
- Validate HLS content, not just a `200` status; HTML fallbacks are rejected.
  Resolve variants against the final URL after redirects using URI semantics,
  including relative paths and independent signed queries.
- Probe known sibling renditions only for `/720.mp4` or
  `/720.mp4:hls:...`-style pathnames. Never replace resolution-like digits in a
  signed query. Expose only reachable sibling candidates.
- MP4 validation uses HEAD, not a full episode download. HLS reads are bounded
  to 1 MiB plus an overflow byte; the streaming response closes after probing.
- Resolution has a 28-second budget, probes have a 5-second budget. Parent
  cancellation propagates rather than becoming an error message.
- In-memory cache: at most 80 embeds, 20 minutes from capture. Verify the cached
  stream before reuse; expired/rejected sources reopen the browser. A cache hit
  does not extend the original TTL. App cache clearing also clears this cache.
  Signed URLs are not persisted into the catalog's seven-day offline cache.

## Verification

```powershell
$env:JAVA_HOME = 'D:\DevTools\jdk-21.0.12.1+1'
.\gradlew.bat :shared:desktopTest :shared:compileAndroidMain :desktopApp:compileKotlin
```

Browser and public-provider integration tests are opt-in:

```powershell
$env:ANIMIX_BROWSER_TEST = '1'
$env:ANIMIX_KODIK_LIVE_TEST = '1'
.\gradlew.bat :shared:desktopTest --tests '*ChromiumStreamCaptureTest'
```

They check PlayerJS capture and network capture through a real Edge/Chrome,
cleanup during cancellation, and an actual Kodik episode obtained through
YummyAnime. The live check validates playlist/URL resolution, not decoded video
playback. It reports only the number of resolved sources. Browser/provider tests
depend on installed software/network access and are skipped without opt-in flags.

## API references

- [CDP Page](https://chromedevtools.github.io/devtools-protocol/tot/Page/)
- [CDP Runtime bindings](https://chromedevtools.github.io/devtools-protocol/tot/Runtime/)
- [Android WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient)
