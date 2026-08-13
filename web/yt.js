"use strict";

// Pure parsing/extraction logic for the YT Music PWA.
// No DOM access so it can run in Node for tests.
// In the browser, functions are exposed via `window.YT`.

(function (root, factory) {
  if (typeof module !== "undefined" && module.exports) {
    module.exports = factory();
  } else {
    root.YT = factory();
  }
})(typeof self !== "undefined" ? self : this, function () {
  function extractVar(html, variable) {
    var marker = "var " + variable + " = ";
    var start = html.indexOf(marker);
    if (start < 0) return null;
    start += marker.length;

    var depth = 0;
    var inString = false;
    var escaped = false;
    for (var i = start; i < html.length; i++) {
      var c = html[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (c === "\\") escaped = true;
        else if (c === '"') inString = false;
      } else {
        if (c === "{") depth++;
        else if (c === "}") {
          depth--;
          if (depth === 0) {
            try {
              return JSON.parse(html.slice(start, i + 1));
            } catch (e) {
              return null;
            }
          }
        } else if (c === '"') inString = true;
      }
    }
    return null;
  }

  function runs(obj) {
    if (!obj || typeof obj !== "object" || !Array.isArray(obj.runs)) return [];
    return obj.runs
      .map(function (r) { return (r && r.text) ? String(r.text).trim() : ""; })
      .filter(Boolean);
  }

  function textOf(obj) {
    if (!obj || typeof obj !== "object") return "";
    if (typeof obj.simpleText === "string") return obj.simpleText;
    return runs(obj).join(" ");
  }

  // html: full results-page HTML. Returns array of result objects.
  function parseSearch(html) {
    var data = extractVar(html, "ytInitialData");
    if (!data) return [];
    var out = [];
    try {
      var contents = data.contents;
      var two = contents && contents.twoColumnSearchResultsRenderer;
      var primary = two && two.primaryContents;
      var sectionList = primary && primary.sectionListRenderer;
      if (!sectionList || !Array.isArray(sectionList.contents)) return [];

      for (var s = 0; s < sectionList.contents.length; s++) {
        var itemSection = sectionList.contents[s].itemSectionRenderer;
        if (!itemSection || !Array.isArray(itemSection.contents)) continue;
        for (var k = 0; k < itemSection.contents.length; k++) {
          var video = itemSection.contents[k].videoRenderer;
          if (!video || typeof video.videoId !== "string") continue;
          var title = textOf(video.title);
          var channel = runs(video.ownerText).join(" ");
          if (!title && !channel) continue;
          out.push({
            id: video.videoId,
            title: title,
            channel: channel,
            duration: textOf(video.lengthText)
          });
          if (out.length >= 20) return out;
        }
      }
    } catch (e) {
      return [];
    }
    return out;
  }

  // player: parsed JSON from the InnerTube /player endpoint.
  // Returns { url, title, author, duration, mimeType } or null.
  function pickStream(player) {
    if (!player || typeof player !== "object") return null;
    var status = player.playabilityStatus || {};
    if (status.status && status.status !== "OK") return null;
    if (!player.streamingData || !Array.isArray(player.streamingData.adaptiveFormats)) return null;

    var best = null;
    var bestBitrate = -1;
    var formats = player.streamingData.adaptiveFormats;
    for (var i = 0; i < formats.length; i++) {
      var f = formats[i];
      var mime = f.mimeType || "";
      // AVPlayer/<audio> in browsers cannot play WebM/Opus on iOS reliably; prefer AAC.
      if (mime.indexOf("audio/") !== 0 || mime.indexOf("mp4") < 0) continue;
      var bitrate = f.bitrate || 0;
      if (bitrate > bestBitrate) {
        best = f;
        bestBitrate = bitrate;
      }
    }
    if (!best || typeof best.url !== "string") return null;

    var vd = player.videoDetails || {};
    return {
      url: best.url,
      mimeType: (best.mimeType || "audio/mp4").split(";")[0],
      title: vd.title || "",
      author: vd.author || "",
      duration: parseInt(vd.lengthSeconds, 10) || 0
    };
  }

  return {
    extractVar: extractVar,
    parseSearch: parseSearch,
    pickStream: pickStream
  };
});
