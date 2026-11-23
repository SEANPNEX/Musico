'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.musico.fixture.security',
  name: 'Security Fixture Source',
  version: '1.2.0',
  author: 'Musico Tests',
  description: 'Cross-platform conformance fixture for host allowlist, redirects, error codes, and timeouts.',
  badgeLabel: 'Security',
  capabilities: ['playlist', 'stream'],
  urlPatterns: [
    '^https?://fixture\\.test/security/[a-zA-Z0-9_-]+$'
  ],
  permissions: {
    hosts: ['fixture.test', '*.cdn.fixture.test']
  }
};

function makeError(code, message) {
  var err = new Error(message);
  err.code = code;
  return err;
}

function canHandle(input) {
  var raw = (input && (input.extractedUrl || input.rawText)) || '';
  var m = String(raw).match(/^https?:\/\/fixture\.test\/security\/([a-zA-Z0-9_-]+)$/i);
  if (!m) {
    return { matched: false };
  }
  return {
    matched: true,
    normalizedUrl: 'https://fixture.test/security/' + m[1],
    remoteId: m[1],
    kind: 'playlist'
  };
}

async function fetchCollection(ctx, req) {
  var handled = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!handled.matched) {
    throw makeError('INVALID_URL', 'Unsupported security test URL.');
  }
  var mode = handled.remoteId;

  if (mode === 'disallowed-host') {
    await ctx.http.request({ method: 'GET', url: 'https://evil.example.com/secret' });
  } else if (mode === 'disallowed-scheme') {
    await ctx.http.request({ method: 'GET', url: 'file:///etc/passwd' });
  } else if (mode === 'redirect-escape') {
    await ctx.http.request({ method: 'GET', url: 'https://fixture.test/redirect-evil', followRedirects: true });
  } else if (mode === 'redirect-no-follow') {
    var r = await ctx.http.request({ method: 'GET', url: 'https://fixture.test/redirect-evil', followRedirects: false });
    return {
      remoteId: mode,
      title: 'status=' + String(r.status),
      tracks: [
        {
          remoteTrackId: 'redir-track',
          relativePath: 'redir.mp3',
          title: 'Redirect Header Status ' + String(r.status),
          artist: 'Fixture',
          album: (r.headers && r.headers.location) || '',
          trackNumber: 1,
          durationSec: 10
        }
      ]
    };
  } else if (mode === 'subdomain-exact-miss') {
    await ctx.http.request({ method: 'GET', url: 'https://cdn.fixture.test/ok' });
  } else if (mode === 'subdomain-hit') {
    var okRes = await ctx.http.request({ method: 'GET', url: 'https://edge1.cdn.fixture.test/ok' });
    return {
      remoteId: mode,
      title: 'Subdomain Hit ' + String(okRes.status),
      tracks: [
        {
          remoteTrackId: 'sub-track',
          relativePath: 'sub.mp3',
          title: 'Subdomain OK',
          artist: 'Fixture',
          album: okRes.text || '',
          trackNumber: 1,
          durationSec: 12
        }
      ]
    };
  } else if (mode === 'sync-loop') {
    while (true) {}
  } else if (mode === 'async-hang') {
    return await new Promise(function() {});
  } else if (mode === 'throw-invalid-url') {
    throw makeError('INVALID_URL', 'Simulated invalid URL');
  } else if (mode === 'throw-auth-required') {
    throw makeError('AUTH_REQUIRED', 'Simulated auth required');
  } else if (mode === 'throw-empty-collection') {
    throw makeError('EMPTY_COLLECTION', 'Simulated empty collection');
  } else if (mode === 'throw-no-stream') {
    throw makeError('NO_PLAYABLE_STREAM', 'Simulated no stream');
  } else if (mode === 'throw-rate-limited') {
    throw makeError('RATE_LIMITED', 'Simulated rate limited');
  } else if (mode === 'throw-network-error') {
    throw makeError('NETWORK_ERROR', 'Simulated network error');
  }

  return {
    remoteId: mode,
    title: 'Security ' + mode,
    tracks: []
  };
}

async function resolveStream(ctx, req) {
  if (req.remoteTrackId === 'bad-stream-host') {
    return {
      url: 'https://evil-cdn.example.com/audio.mp3',
      fileExtension: 'mp3'
    };
  }
  if (req.remoteTrackId === 'bad-fallback-host') {
    return {
      url: 'https://media1.cdn.fixture.test/audio.mp3',
      fallbackUrls: ['https://evil.example.com/backup.mp3'],
      fileExtension: 'mp3'
    };
  }
  return {
    url: 'https://media1.cdn.fixture.test/audio.mp3',
    fallbackUrls: ['https://media2.cdn.fixture.test/audio.mp3'],
    fileExtension: 'mp3',
    mimeType: 'audio/mpeg'
  };
}

module.exports = {
  manifest: manifest,
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  resolveStream: resolveStream
};
