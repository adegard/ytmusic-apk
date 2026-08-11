# YT Music Search

A lightweight Android app (Kotlin) to search and stream songs from YouTube — a mobile port of the
[`music_termux.py`](https://github.com/adegard/music_termux.py) idea (yt-dlp + mpv).

No server needed: the app does everything on-device.

## How it works

| Python script (`music_termux.py`) | Android app (this repo) |
| --- | --- |
| `yt-dlp` searches `ytsearch1:` | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) `SearchExtractor` |
| `yt-dlp` resolves bestaudio URL | NewPipeExtractor `StreamInfo.getInfo()` → best `AudioStream` |
| `mpv --no-video` plays the URL | [Media3 / ExoPlayer](https://developer.android.com/media/media3) plays the audio stream |

Flow (mirrors the script):

1. Type a song name → NewPipeExtractor searches YouTube on-device.
2. Tap a result → the app extracts the best audio stream URL.
3. ExoPlayer streams and plays it (DASH + progressive formats supported).

## Build

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

`.github/workflows/build-apk.yml` builds the debug APK automatically:

- on every push to `main` (uploaded as the `app-debug-apk` artifact),
- on `workflow_dispatch` (manual run),
- on tag pushes (`v*`) a GitHub Release is created with the APK attached.

## Install

1. Download `app-debug.apk` from the latest run's artifacts (or a Release).
2. Allow "Install from unknown sources" for the app on your phone.
3. Search and play.

> Note: streaming from YouTube relies on NewPipeExtractor, whose extraction endpoints can be
> patched by YouTube occasionally. Update the `NewPipeExtractor` version in
> `app/build.gradle.kts` and rebuild if search/playback stops working.

## License

MIT
