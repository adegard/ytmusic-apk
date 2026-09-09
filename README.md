[![Buy me a coffee](https://cdn.buymeacoffee.com/buttons/v2/default-red.png)](https://www.buymeacoffee.com/adegard)

# YT Music Search

Search and stream songs from YouTube on your Android phone. Everything runs on-device — no server, no account.

## Features

- Search YouTube and play any song
- Search for channels and subscribe to them (stored locally on device)
- Subscriptions tab shows the latest video from each channel
- Browse a channel's recent videos (newest first) and play any of them
- Toggle between audio-only and video playback
- Background playback with a media notification
- Simple, no-login

## Download

Get the latest APK from the **[Releases](https://github.com/adegard/ytmusic-apk/releases)** page.

## Install

1. Download the APK from Releases.
2. Allow "install from unknown sources" when asked.
3. Search and play.

## Usage

- **Search**: use the *Songs* / *Channels* toggle above the search bar. Searching for channels shows a **Subscribe** button on each result.
- **Subscriptions**: the bottom *Subscriptions* tab lists all saved channels with their latest video. Tap one to browse its recent videos.
- **Video playback**: tap the video/audio icon above the player to switch playback mode.

![Screenshot](docs/screenshot.png)

## Build

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## iOS

An iOS companion app (same on-device, no-login approach) lives in the [`ios/`](ios/README.md) folder. eg. you can test it here (using proxy server with live page): https://adegard.github.io/ytmusic-apk/

## License

MIT

---

For an overview of all my other projects, see https://adegard.github.io/blog/
