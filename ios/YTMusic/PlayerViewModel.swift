import Foundation

@MainActor
final class PlayerViewModel: ObservableObject {

    @Published var query = ""
    @Published var results: [SearchResult] = []
    @Published var current: SearchResult?
    @Published var isSearching = false
    @Published var errorMessage: String?

    let client = YouTubeClient()
    let player = AudioPlayerManager()

    func search() async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        isSearching = true
        errorMessage = nil
        defer { isSearching = false }

        do {
            results = try await client.search(query: trimmed)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func play(_ result: SearchResult) {
        current = result
        errorMessage = nil
        Task {
            do {
                let info = try await client.resolveStream(videoId: result.videoId)
                player.play(info: info, result: result)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func togglePlayPause() {
        player.togglePlayPause()
    }
}
