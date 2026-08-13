import SwiftUI

@main
struct YTMusicApp: App {
    @StateObject private var viewModel = PlayerViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(viewModel)
        }
    }
}
