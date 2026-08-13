import Foundation

struct SearchResult: Identifiable, Hashable {
    let videoId: String
    let title: String
    let channel: String
    let duration: String

    var id: String { videoId }

    var thumbnailURL: URL {
        URL(string: "https://i.ytimg.com/vi/\(videoId)/hqdefault.jpg")!
    }
}

struct StreamInfo {
    let url: URL
    let mimeType: String
    let bitrate: Int
    let title: String
    let author: String
    let lengthSeconds: Int?
}
