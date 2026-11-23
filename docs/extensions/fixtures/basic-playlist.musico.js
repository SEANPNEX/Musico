'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.musico.fixture.basic',
  name: 'Basic Fixture Source',
  version: '1.2.0',
  author: 'Musico Tests',
  description: 'Cross-platform conformance fixture for playlist, crypto, kv, settings, env, log, and stream resolution.',
  badgeLabel: 'Fixture',
  capabilities: ['playlist', 'stream', 'cookie'],
  urlPatterns: [
    '^https?://(www\\.)?fixture\\.test/playlist/[a-zA-Z0-9_-]+(\\?.*)?$'
  ],
  permissions: {
    hosts: ['fixture.test', 'api.fixture.test', '*.cdn.fixture.test']
  },
  settings: [
    {
      key: 'preferHighBitrate',
      label: 'Prefer High Bitrate',
      type: 'boolean',
      default: true
    },
    {
      key: 'apiTag',
      label: 'API Tag',
      type: 'string',
      default: 'default-tag'
    },
    {
      key: 'region',
      label: 'Region',
      type: 'select',
      default: 'us',
      options: [
        { value: 'us', label: 'United States' },
        { value: 'jp', label: 'Japan' },
        { value: 'eu', label: 'Europe' }
      ]
    }
  ]
};

function makeError(code, message) {
  var err = new Error(message);
  err.code = code;
  return err;
}

function canHandle(input) {
  var raw = (input && (input.extractedUrl || input.rawText)) || '';
  var m = String(raw).match(/^https?:\/\/(?:www\.)?fixture\.test\/playlist\/([a-zA-Z0-9_-]+)/i);
  if (!m) {
    return { matched: false };
  }
  var remoteId = m[1];
  return {
    matched: true,
    normalizedUrl: 'https://fixture.test/playlist/' + remoteId,
    remoteId: remoteId,
    kind: 'playlist'
  };
}

async function fetchCollection(ctx, req) {
  var handled = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!handled.matched) {
    throw makeError('INVALID_URL', 'Unsupported fixture playlist URL.');
  }

  var remoteId = handled.remoteId;
  if (remoteId === 'auth-required' && !req.cookie) {
    throw makeError('AUTH_REQUIRED', 'Cookie is required for private fixture playlist.');
  }

  ctx.log.info('fetchCollection start: ' + remoteId);
  ctx.log.warn('diagnostic warning sample');
  ctx.log.error('diagnostic error sample');

  var prevCount = ctx.kv.get('visit_count');
  var nextCount = String((parseInt(prevCount || '0', 10) || 0) + 1);
  ctx.kv.set('visit_count', nextCount, 300);
  ctx.kv.set('ephemeral', 'temp', 60);
  var beforeRemove = ctx.kv.get('ephemeral');
  ctx.kv.remove('ephemeral');
  var afterRemove = ctx.kv.get('ephemeral');

  var md5Hex = ctx.crypto.md5('musico');
  var sha1Hex = ctx.crypto.sha1('musico');
  var sha256Hex = ctx.crypto.sha256('musico');
  var hmacHex = ctx.crypto.hmacSha256('secret', 'musico');
  var b64 = ctx.crypto.base64Encode('musico-hello');
  var b64RoundTrip = ctx.crypto.base64Decode(b64);

  var preferHighBitrate = ctx.settings.get('preferHighBitrate');
  var apiTag = ctx.settings.get('apiTag');
  var region = ctx.settings.get('region');

  var apiUrl = 'https://api.fixture.test/playlists/' + remoteId +
    (req.cursor ? ('?cursor=' + encodeURIComponent(req.cursor)) : '');

  var headers = {
    'X-Api-Tag': String(apiTag || ''),
    'X-Region': String(region || ''),
    'X-High-Bitrate': String(Boolean(preferHighBitrate)),
    'X-Platform': String(ctx.env && ctx.env.platform ? ctx.env.platform : '')
  };
  if (req.cookie) {
    headers['Cookie'] = req.cookie;
  }

  var res = await ctx.http.request({
    method: 'GET',
    url: apiUrl,
    headers: headers,
    followRedirects: true,
    timeoutMs: 5000
  });

  var payload = JSON.parse(res.text || '{}');
  var tracks = payload.tracks || [];
  if (payload.injectDiagnostics && tracks.length > 0) {
    tracks[0].album = [
      'md5=' + md5Hex,
      'sha1=' + sha1Hex,
      'sha256=' + sha256Hex,
      'hmac=' + hmacHex,
      'b64=' + b64RoundTrip,
      'kv=' + nextCount + '/' + String(beforeRemove) + '/' + String(afterRemove === null ? 'null' : afterRemove),
      'settings=' + String(preferHighBitrate) + '|' + String(apiTag) + '|' + String(region),
      'env=' + String(ctx.env.platform) + '|v' + String(ctx.env.apiVersion)
    ].join(';');
  }

  return {
    remoteId: payload.remoteId || remoteId,
    title: payload.title || ('Fixture ' + remoteId),
    tracks: tracks,
    subfolders: payload.subfolders || [],
    nextCursor: payload.nextCursor || null
  };
}

async function resolveStream(ctx, req) {
  ctx.log.info('resolveStream start: ' + req.remoteTrackId);
  var preferHighBitrate = Boolean(ctx.settings.get('preferHighBitrate'));
  var region = String(ctx.settings.get('region') || 'us');
  var headers = {
    'Content-Type': 'application/json'
  };
  if (req.cookie) {
    headers['Cookie'] = req.cookie;
  }

  var res = await ctx.http.request({
    method: 'POST',
    url: 'https://api.fixture.test/stream',
    headers: headers,
    body: JSON.stringify({
      remoteTrackId: req.remoteTrackId,
      relativePath: req.relativePath || null,
      forceRefresh: Boolean(req.forceRefresh),
      preferHighBitrate: preferHighBitrate,
      region: region
    }),
    followRedirects: true,
    timeoutMs: 5000
  });

  var payload = JSON.parse(res.text || '{}');
  if (!payload.url) {
    throw makeError('NO_PLAYABLE_STREAM', 'No stream URL returned.');
  }
  return {
    url: payload.url,
    headers: payload.headers || {},
    fileExtension: payload.fileExtension || 'mp3',
    mimeType: payload.mimeType || null,
    expiresAtMs: typeof payload.expiresAtMs === 'number' ? payload.expiresAtMs : null,
    fallbackUrls: Array.isArray(payload.fallbackUrls) ? payload.fallbackUrls : null,
    contentLength: typeof payload.contentLength === 'number' ? payload.contentLength : null
  };
}

module.exports = {
  manifest: manifest,
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  resolveStream: resolveStream
};
