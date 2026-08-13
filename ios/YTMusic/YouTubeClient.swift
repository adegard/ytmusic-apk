import Foundation

enum YouTubeError: LocalizedError {
    case noInitialData
    case playability(String)
    case noAudioStreams
    case noUsableURL

    var errorDescription: String? {
        switch self {
        case .noInitialData:
            return "YouTube returned no search data. Try again in a moment."
        case .playability(let reason):
            return "Video unavailable: \(reason)"
        case .noAudioStreams:
            return "No playable audio stream found for this video."
        case .noUsableURL:
            return "This stream needs a signature that this app cannot compute. Try another video."
        }
    }
}

/// Anonymous YouTube client.
///
/// - Search: parses `ytInitialData` out of the results page HTML (desktop User-Agent).
/// - Streams: POSTs to the InnerTube `/player` API using the ANDROID client context,
///   which returns pre-signed googlevideo URLs (the modern player deciphers
///   signatures in WebAssembly, so `signatureCipher`-only formats are unsupported).
final class YouTubeClient {

    static let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.httpAdditionalHeaders = [
            "User-Agent": Self.userAgent,
            "Accept-Language": "en",
            "Accept": "*/*",
        ]
        session = URLSession(configuration: config)
    }

    // MARK: - Search

    func search(query: String) async throws -> [SearchResult] {
        var components = URLComponents(string: "https://www.youtube.com/results")!
        components.queryItems = [
            URLQueryItem(name: "search_query", value: query),
            URLQueryItem(name: "hl", value: "en"),
        ]
        guard let url = components.url else { throw YouTubeError.noInitialData }

        let (data, _) = try await session.data(from: url)
        guard let html = String(data: data, encoding: .utf8),
              let root = Self.extractJSON(from: html, variable: "ytInitialData") else {
            throw YouTubeError.noInitialData
        }
        return Self.parseSearchResults(root)
    }

    // MARK: - Stream resolution

    func resolveStream(videoId: String) async throws -> StreamInfo {
        let endpoint = URL(string: "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")!

        let body: [String: Any] = [
            "context": [
                "client": [
                    "clientName": "ANDROID",
                    "clientVersion": "20.20.35",
                    "androidSdkVersion": 34,
                ]
            ],
            "videoId": videoId,
        ]

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, _) = try await session.data(for: request)
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw YouTubeError.noAudioStreams
        }

        if let status = (root["playabilityStatus"] as? [String: Any])?["status"] as? String,
           status != "OK" {
            let reason = (root["playabilityStatus"] as? [String: Any])?["reason"] as? String ?? status
            throw YouTubeError.playability(reason)
        }

        guard let streamingData = root["streamingData"] as? [String: Any],
              let formats = streamingData["adaptiveFormats"] as? [[String: Any]] else {
            throw YouTubeError.noAudioStreams
        }

        // AVPlayer cannot play WebM/Opus, so prefer audio/mp4 (AAC), highest bitrate.
        var best: [String: Any]?
        for format in formats {
            guard let mimeType = format["mimeType"] as? String,
                  mimeType.hasPrefix("audio/"),
                  mimeType.contains("mp4") else { continue }
            let bitrate = format["bitrate"] as? Int ?? 0
            let bestBitrate = best?["bitrate"] as? Int ?? 0
            if bitrate > bestBitrate {
                best = format
            }
        }
        guard let chosen = best, let url = Self.playableURL(from: chosen) else {
            throw YouTubeError.noAudioStreams
        }

        let videoDetails = root["videoDetails"] as? [String: Any]
        return StreamInfo(
            url: url,
            mimeType: (chosen["mimeType"] as? String)?.components(separatedBy: ";").first ?? "audio/mp4",
            bitrate: chosen["bitrate"] as? Int ?? 0,
            title: videoDetails?["title"] as? String ?? "",
            author: videoDetails?["author"] as? String ?? "",
            lengthSeconds: Int((videoDetails?["lengthSeconds"] as? String) ?? "")
        )
    }

    // MARK: - JSON helpers

    /// Extracts `var <variable> = {...}` from HTML with brace counting that
    /// skips quoted strings (and escape sequences).
    static func extractJSON(from html: String, variable: String) -> Any? {
        guard let marker = html.range(of: "var \(variable) = ") else { return nil }
        var index = marker.upperBound
        var depth = 0
        var inString = false
        var escaped = false
        var end: String.Index?

        while index < html.endIndex {
            let c = html[index]
            if inString {
                if escaped {
                    escaped = false
                } else if c == "\\" {
                    escaped = true
                } else if c == "\"" {
                    inString = false
                }
            } else {
                switch c {
                case "{":
                    depth += 1
                case "}":
                    depth -= 1
                    if depth == 0 {
                        end = index
                    }
                case "\"":
                    inString = true
                default:
                    break
                }
            }
            if end != nil { break }
            index = html.index(after: index)
        }

        guard let end else { return nil }
        let json = html[marker.upperBound...end]
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    static func parseSearchResults(_ root: Any) -> [SearchResult] {
        guard let contents = root as? [String: Any],
              let results = contents["contents"] as? [String: Any],
              let twoColumn = results["twoColumnSearchResultsRenderer"] as? [String: Any],
              let primary = twoColumn["primaryContents"] as? [String: Any],
              let sectionList = primary["sectionListRenderer"] as? [String: Any],
              let sections = sectionList["contents"] as? [[String: Any]] else {
            return []
        }

        var out: [SearchResult] = []
        for section in sections {
            guard let itemSection = section["itemSectionRenderer"] as? [String: Any],
                  let items = itemSection["contents"] as? [[String: Any]] else { continue }
            for item in items {
                guard let video = item["videoRenderer"] as? [String: Any],
                      let videoId = video["videoId"] as? String else { continue }

                let title = runs(in: video["title"])
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")
                let channel = runs(in: video["ownerText"])
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")
                let duration = Self.text(in: video["lengthText"])

                if !title.isEmpty || !channel.isEmpty {
                    out.append(SearchResult(videoId: videoId, title: title, channel: channel, duration: duration))
                }
                if out.count >= 20 {
                    return out
                }
            }
        }
        return out
    }

    /// Builds a playable URL from an adaptive format entry. Handles the
    /// pre-signed `url` field, and a best-effort decode of `signatureCipher`
    /// when it already contains the resolved `sig` value.
    static func playableURL(from format: [String: Any]) -> URL? {
        if let url = format["url"] as? String {
            return URL(string: url)
        }
        guard let cipher = format["signatureCipher"] as? String else { return nil }

        var query: [String: String] = [:]
        for pair in cipher.components(separatedBy: "&") {
            let parts = pair.components(separatedBy: "=")
            guard parts.count == 2 else { continue }
            let key = parts[0].removingPercentEncoding ?? parts[0]
            let value = parts[1].removingPercentEncoding ?? parts[1]
            query[key] = value
        }
        guard let urlString = query["url"],
              var components = URLComponents(string: urlString) else { return nil }

        var items = components.queryItems ?? []
        if let sig = query["sig"] {
            items.append(URLQueryItem(name: "sig", value: sig))
        } else if query["s"] != nil {
            // Requires the JS/WASM signature decipher, which we do not implement.
            return nil
        }
        components.queryItems = items
        return components.url
    }

    private static func runs(in value: Any?) -> [String] {
        guard let dict = value as? [String: Any],
              let runs = dict["runs"] as? [[String: Any]] else { return [] }
        return runs.compactMap { $0["text"] as? String }
    }

    private static func text(in value: Any?) -> String {
        if let dict = value as? [String: Any], let simple = dict["simpleText"] as? String {
            return simple
        }
        return runs(in: value).joined(separator: " ")
    }
}
