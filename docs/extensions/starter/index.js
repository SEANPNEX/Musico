'use strict';

function extensionError(code, message) {
  var err = new Error(message);
  err.code = code;
  return err;
}

/**
 * Synchronous URL recognizer called after native `urlPatterns` regex pre-filtering.
 * Keep this lightweight and do NOT perform network requests here.
 */
function canHandle(input) {
  var raw = (input && (input.extractedUrl || input.rawText)) || '';
  var match = String(raw).match(
    /^https?:\/\/(?:www\.)?example\.com\/(playlist|album)\/([a-zA-Z0-9_-]+)/i
  );
  if (!match) {
    return { matched: false };
  }
  return {
    matched: true,
    normalizedUrl: 'https://example.com/' + match[1].toLowerCase() + '/' + match[2],
    remoteId: match[2],
    kind: 'playlist'
  };
}

/**
 * Fetches track metadata for a playlist/album/collection.
 * Supports optional cursor pagination via `req.cursor` -> `nextCursor`.
 */
async function fetchCollection(ctx, req) {
  var check = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!check.matched) {
    throw extensionError('INVALID_URL', 'Please provide a valid Example Cloud playlist URL.');
  }

  ctx.log.info('Fetching collection ' + check.remoteId + (req.cursor ? ' cursor=' + req.cursor : ''));

  var headers = {
    Accept: 'application/json',
    'User-Agent': 'MusicoExtension/' + String(ctx.env.platform)
  };
  if (req.cookie) {
    headers.Cookie = req.cookie;
  }

  var apiUrl =
    'https://api.example.com/v1/collections/' +
    encodeURIComponent(check.remoteId) +
    '?region=' +
    encodeURIComponent(String(ctx.settings.get('region') || 'auto')) +
    (req.cursor ? '&cursor=' + encodeURIComponent(req.cursor) : '');

  var res = await ctx.http.request({
    method: 'GET',
    url: apiUrl,
    headers: headers,
    followRedirects: true,
    timeoutMs: 15000
  });

  if (res.status === 401 || res.status === 403) {
    throw extensionError('AUTH_REQUIRED', 'Authentication cookie required or expired.');
  }
  if (res.status === 429) {
    throw extensionError('RATE_LIMITED', 'Upstream API rate limited.');
  }
  if (res.status >= 400) {
    throw extensionError('NETWORK_ERROR', 'HTTP ' + String(res.status));
  }

  var data = JSON.parse(res.text || '{}');
  var tracks = Array.isArray(data.items)
    ? data.items.map(function (item, idx) {
        return {
          remoteTrackId: String(item.id),
          relativePath: item.fileName || (String(idx + 1).padStart(2, '0') + ' - ' + String(item.title) + '.mp3'),
          title: String(item.title || 'Untitled'),
          artist: item.artist ? String(item.artist) : 'Unknown Artist',
          album: data.title ? String(data.title) : null,
          trackNumber: typeof item.trackNumber === 'number' ? item.trackNumber : idx + 1,
          durationSec: typeof item.durationSec === 'number' ? item.durationSec : 0,
          artworkUrl: item.artworkUrl ? String(item.artworkUrl) : null
        };
      })
    : [];

  if (tracks.length === 0 && !req.cursor) {
    throw extensionError('EMPTY_COLLECTION', 'No playable tracks found in this collection.');
  }

  return {
    remoteId: check.remoteId,
    title: data.title || ('Example ' + check.remoteId),
    tracks: tracks,
    subfolders: [],
    nextCursor: data.nextCursor || null
  };
}

/**
 * Resolves a playable audio URL and HTTP headers right before playback or caching.
 */
async function resolveStream(ctx, req) {
  var preferHiFi = Boolean(ctx.settings.get('preferHighBitrate'));
  var headers = { Accept: 'application/json' };
  if (req.cookie) {
    headers.Cookie = req.cookie;
  }

  var res = await ctx.http.request({
    method: 'GET',
    url:
      'https://api.example.com/v1/tracks/' +
      encodeURIComponent(req.remoteTrackId) +
      '/stream?quality=' +
      (preferHiFi ? 'high' : 'standard'),
    headers: headers,
    followRedirects: true,
    timeoutMs: 12000
  });

  if (res.status === 401 || res.status === 403) {
    throw extensionError('AUTH_REQUIRED', 'Track requires valid cookie.');
  }
  if (res.status >= 400) {
    throw extensionError('NO_PLAYABLE_STREAM', 'Upstream returned HTTP ' + String(res.status));
  }

  var stream = JSON.parse(res.text || '{}');
  if (!stream.url) {
    throw extensionError('NO_PLAYABLE_STREAM', 'No audio URL available.');
  }

  return {
    url: String(stream.url),
    headers: stream.headers || { Referer: 'https://example.com/' },
    fileExtension: stream.fileExtension || 'mp3',
    mimeType: stream.mimeType || 'audio/mpeg',
    expiresAtMs: typeof stream.expiresAtMs === 'number' ? stream.expiresAtMs : Date.now() + 1800 * 1000,
    fallbackUrls: Array.isArray(stream.fallbackUrls) ? stream.fallbackUrls : null,
    contentLength: typeof stream.contentLength === 'number' ? stream.contentLength : null
  };
}

module.exports = {
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  resolveStream: resolveStream
};
