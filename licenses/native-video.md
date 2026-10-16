# Native video components

The Windows distribution includes the unmodified VLC 3.0.24 win64 runtime,
including its `COPYING.txt`, notices and bundled plugin files.

- Binary provenance: https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.zip
- SHA-256: `fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323`
- Corresponding upstream source: https://download.videolan.org/pub/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz
- VLC licensing and component details: https://www.videolan.org/legal.html
- vlcj 4.12.1 binding source and license: https://github.com/caprica/vlcj/tree/vlcj-4.12.1
- JNA source and license: https://github.com/java-native-access/jna

Android uses AndroidX Media3 (Apache License 2.0), retrieved from Google's Maven
repository. Source and notices: https://github.com/androidx/media

The generated native smoke-test video is synthetic (FFmpeg `testsrc2` plus a
sine tone); test media is not included in the application distribution.
