# AniMix promo video

The video is a Flutter widget (`promo.dart`) drawn as a pure function of time,
rendered frame by frame through `flutter test` and assembled with ffmpeg.
1920×1080, 30 fps, ~32 s.

```powershell
# Review stills at chosen seconds
$env:PROMO_STILLS='1.6,5.2,10.8'; flutter test tool/promo --update-goldens

# All frames (~5 min) → tool/promo/frames/
Remove-Item Env:PROMO_STILLS; flutter test tool/promo --update-goldens

# Placeholder beat (120 BPM) and final mp4
ffmpeg -framerate 30 -i tool/promo/frames/f%04d.png -i tool/promo/out/beat.wav `
  -c:v libx264 -preset slow -crf 17 -pix_fmt yuv420p -c:a aac -b:a 192k `
  -shortest -movflags +faststart tool/promo/out/animix_promo.mp4
```

Scenes: logo → «Бесплатно» → own player → dubs → offline downloads →
achievements & friends → feature montage → finale. Timings live in `Promo`.
The soundtrack is a synthesized placeholder; replace `beat.wav` with a
licensed track before publishing.
