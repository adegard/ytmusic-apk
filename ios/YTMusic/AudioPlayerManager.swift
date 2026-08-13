import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// Owns the `AVPlayer`, keeps the audio session active for background
/// playback, and exposes Now Playing / lock-screen controls.
final class AudioPlayerManager: ObservableObject {

    @Published var isPlaying = false
    @Published var currentTitle = ""
    @Published var currentChannel = ""

    private let player = AVPlayer()
    private var statusCancellable: AnyCancellable?

    init() {
        statusCancellable = player.publisher(for: \.timeControlStatus)
            .map { $0 == .playing }
            .receive(on: DispatchQueue.main)
            .assign(to: &$isPlaying)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(itemDidEnd),
            name: .AVPlayerItemDidPlayToEndTime,
            object: nil
        )
        setupRemoteCommands()
    }

    func play(info: StreamInfo, result: SearchResult) {
        currentTitle = info.title.isEmpty ? result.title : info.title
        currentChannel = info.author.isEmpty ? result.channel : info.author

        player.replaceCurrentItem(with: AVPlayerItem(url: info.url))
        activateAudioSession()
        player.play()
        updateNowPlaying(artworkURL: result.thumbnailURL)
    }

    func togglePlayPause() {
        if player.timeControlStatus == .playing {
            player.pause()
        } else {
            activateAudioSession()
            player.play()
        }
    }

    // MARK: - Private

    private func activateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.activateAudioSession()
            self?.player.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.player.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
    }

    @objc private func itemDidEnd() {
        // Placeholder for future auto-advance.
    }

    private func updateNowPlaying(artworkURL: URL?) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: currentTitle,
            MPMediaItemPropertyArtist: currentChannel,
            MPNowPlayingInfoPropertyPlaybackRate: 1.0,
        ]

        if let artworkURL {
            Task {
                guard let (data, _) = try? await URLSession.shared.data(from: artworkURL),
                      let image = UIImage(data: data) else { return }
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
