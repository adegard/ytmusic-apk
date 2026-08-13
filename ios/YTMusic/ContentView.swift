import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var viewModel: PlayerViewModel

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                searchBar
                if let error = viewModel.errorMessage {
                    Text(error)
                        .font(.footnote)
                        .foregroundColor(.red)
                        .padding(.horizontal)
                        .padding(.top, 4)
                }
                if viewModel.isSearching {
                    Spacer()
                    ProgressView("Searching...")
                    Spacer()
                } else {
                    resultList
                }
                if viewModel.current != nil {
                    playerBar
                }
            }
            .navigationTitle("YT Music")
        }
    }

    private var searchBar: some View {
        HStack {
            TextField("Search songs", text: $viewModel.query)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.search)
                .onSubmit {
                    Task { await viewModel.search() }
                }
            Button("Search") {
                Task { await viewModel.search() }
            }
            .disabled(viewModel.query.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal)
        .padding(.top)
    }

    private var resultList: some View {
        List {
            ForEach(viewModel.results) { result in
                Button {
                    viewModel.play(result)
                } label: {
                    HStack(spacing: 12) {
                        AsyncImage(url: result.thumbnailURL) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Color.gray.opacity(0.2)
                        }
                        .frame(width: 80, height: 45)
                        .clipShape(RoundedRectangle(cornerRadius: 6))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(result.title)
                                .font(.headline)
                                .lineLimit(2)
                            Text(result.channel)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        if !result.duration.isEmpty {
                            Text(result.duration)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .listStyle(.plain)
        .overlay {
            if viewModel.results.isEmpty && !viewModel.isSearching {
                Text("Type a query and hit Search.")
                    .foregroundColor(.secondary)
            }
        }
    }

    private var playerBar: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(viewModel.current?.title ?? "")
                    .font(.headline)
                    .lineLimit(1)
                Text(viewModel.player.isPlaying ? "Playing" : "Paused")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            Button {
                viewModel.togglePlayPause()
            } label: {
                Image(systemName: viewModel.player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 40))
            }
            .buttonStyle(.plain)
        }
        .padding()
        .background(.bar)
    }
}
