param([string]$Output = "$PSScriptRoot/../build/test-media")
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
# Synthetic picture and tone; no downloaded or licensed video is required for native smoke tests.
& ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 12 -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 72 -c:a aac -b:a 64k -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_filename "$Output/part-%02d.ts" "$Output/index.m3u8"
if ($LASTEXITCODE -ne 0) { throw 'Unable to generate HLS fixture; install FFmpeg and check its output.' }
& ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 12 -map 0:v:0 -map 1:a:0 -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 72 -c:a aac -b:a 64k -f hls -hls_time 3 -hls_playlist_type vod -var_stream_map 'a:0,agroup:dub,default:yes,name:audio v:0,agroup:dub,name:video' -master_pl_name index.m3u8 -hls_segment_filename "$Output/separate/%v/part-%02d.ts" "$Output/separate/%v/index.m3u8"
if ($LASTEXITCODE -ne 0) { throw 'Unable to generate HLS with separate audio.' }
