# YT Music (iOS)

iOS companion to the [Android app](../README.md): search YouTube and stream songs
on-device — no server, no account.

## Test it!

 https://adegard.github.io/ytmusic-apk/

Everything runs on the device:

- **Search** — the results page HTML is parsed for `ytInitialData` (desktop
  User-Agent).
- **Streams** — the InnerTube `/youtubei/v1/player` API is called with the
  anonymous `ANDROID` client context, which returns already-signed googlevideo
  URLs. The best-quality AAC stream (`audio/mp4`, itag 139/140) is chosen,
  because `AVPlayer` cannot play WebM/Opus.
- **Playback** — `AVPlayer` + `AVAudioSession` background audio, Now Playing and
  lock-screen controls.

## Requirements

- macOS 14+ with Xcode 15+
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) — `brew install xcodegen`

## Build & run

```bash
cd ios
xcodegen generate
open YTMusic.xcodeproj
```

In Xcode: select your Team under *Signing & Capabilities* (a free Apple ID
"personal team" works), pick a device or simulator, and hit Run.

## Known limitations

- Only AAC (`audio/mp4`) streams are used; videos with no AAC audio track cannot
  be played.
- Formats that arrive only as `signatureCipher` (rare on the ANDROID client)
  are skipped — the current player deciphers signatures in WebAssembly, which
  this app does not implement.
- Anonymous streaming may be rate-limited or temporarily blocked by YouTube.
