'use strict';

/**
 * Single-file Musico extension (`*.musico.js`) exporting both `manifest` and hooks.
 * Install directly in Musico -> Extensions -> Import without zipping.
 */
var manifest = {
  apiVersion: 1,
  id: 'com.example.musico.singlefile',
  name: 'Single-File Starter',
  version: '1.0.0',
  author: 'Your Name',
  description: 'Single-file .musico.js template exporting manifest + CommonJS hooks.',
  badgeLabel: 'SingleEx',
  capabilities: ['playlist', 'stream'],
  urlPatterns: [
    '^https?://(www\\.)?example\\.org/mix/[a-zA-Z0-9_-]+'
  ],
  permissions: {
    hosts: ['example.org', '*.cdn.example.org']
  },
  settings: [
    {
      key: 'preferHighBitrate',
      label: 'Prefer highest bitrate',
      type: 'boolean',
      default: true
    }
  ]
};

function canHandle(input) {
  var raw = (input && (input.extractedUrl || input.rawText)) || '';
  var m = String(raw).match(/^https?:\/\/(?:www\.)?example\.org\/mix\/([a-zA-Z0-9_-]+)/i);
  if (!m) {
    return { matched: false };
  }
  return {
    matched: true,
    normalizedUrl: 'https://example.org/mix/' + m[1],
    remoteId: m[1],
    kind: 'playlist'
  };
}

async function fetchCollection(ctx, req) {
  var check = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!check.matched) {
    var err = new Error('Unsupported URL');
    err.code = 'INVALID_URL';
    throw err;
  }

  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://example.org/api/mix/' + encodeURIComponent(check.remoteId),
    followRedirects: true,
    timeoutMs: 15000
  });
  var data = JSON.parse(res.text || '{}');
  return {
    remoteId: check.remoteId,
    title: data.title || ('Mix ' + check.remoteId),
    tracks: Array.isArray(data.tracks) ? data.tracks : [],
    subfolders: [],
    nextCursor: data.nextCursor || null
  };
}

async function resolveStream(ctx, req) {
  var quality = ctx.settings.get('preferHighBitrate') ? 'high' : 'std';
  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://example.org/api/track/' + encodeURIComponent(req.remoteTrackId) + '?q=' + quality,
    followRedirects: true,
    timeoutMs: 12000
  });
  var payload = JSON.parse(res.text || '{}');
  return {
    url: payload.url,
    headers: payload.headers || {},
    fileExtension: payload.fileExtension || 'mp3',
    mimeType: payload.mimeType || 'audio/mpeg',
    expiresAtMs: payload.expiresAtMs || null
  };
}

module.exports = {
  manifest: manifest,
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  resolveStream: resolveStream
};
