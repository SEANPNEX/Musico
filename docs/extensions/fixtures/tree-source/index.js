'use strict';

function makeError(code, message) {
  var err = new Error(message);
  err.code = code;
  return err;
}

function canHandle(input) {
  var raw = (input && (input.extractedUrl || input.rawText)) || '';
  var m = String(raw).match(/^https?:\/\/tree\.fixture\.test\/root\/([a-zA-Z0-9_-]+)/i);
  if (!m) {
    return { matched: false };
  }
  var remoteId = m[1];
  return {
    matched: true,
    normalizedUrl: 'https://tree.fixture.test/root/' + remoteId,
    remoteId: remoteId,
    kind: 'folder'
  };
}

async function fetchCollection(ctx, req) {
  var handled = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!handled.matched) {
    throw makeError('INVALID_URL', 'Unsupported tree fixture URL.');
  }
  ctx.log.info('tree fetchCollection: ' + handled.remoteId);
  var url = 'https://tree.fixture.test/api/root/' + handled.remoteId +
    (req.cursor ? ('?cursor=' + encodeURIComponent(req.cursor)) : '');
  var res = await ctx.http.request({
    method: 'GET',
    url: url,
    headers: req.cookie ? { Cookie: req.cookie } : {},
    followRedirects: true,
    timeoutMs: 5000
  });
  var data = JSON.parse(res.text || '{}');
  return {
    remoteId: data.remoteId || handled.remoteId,
    title: data.title || ('Tree ' + handled.remoteId),
    tracks: data.tracks || [],
    subfolders: data.subfolders || [],
    nextCursor: data.nextCursor || null
  };
}

async function fetchFolder(ctx, req) {
  ctx.log.info('tree fetchFolder: ' + req.folderId + ' depth=' + String(req.depth));
  var pathJoined = Array.isArray(req.pathSegments) ? req.pathSegments.join('/') : '';
  var url = 'https://tree.fixture.test/api/folder/' + encodeURIComponent(req.folderId) +
    '?depth=' + encodeURIComponent(String(req.depth || 0)) +
    '&path=' + encodeURIComponent(pathJoined) +
    (req.cursor ? ('&cursor=' + encodeURIComponent(req.cursor)) : '');
  var res = await ctx.http.request({
    method: 'GET',
    url: url,
    headers: req.cookie ? { Cookie: req.cookie } : {},
    followRedirects: true,
    timeoutMs: 5000
  });
  var data = JSON.parse(res.text || '{}');
  return {
    folderId: data.folderId || req.folderId,
    tracks: data.tracks || [],
    subfolders: data.subfolders || [],
    nextCursor: data.nextCursor || null
  };
}

async function resolveStream(ctx, req) {
  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://tree.fixture.test/api/stream/' + encodeURIComponent(req.remoteTrackId),
    followRedirects: true,
    timeoutMs: 5000
  });
  var data = JSON.parse(res.text || '{}');
  if (!data.url) {
    throw makeError('NO_PLAYABLE_STREAM', 'Missing stream URL for tree track.');
  }
  return {
    url: data.url,
    headers: data.headers || {},
    fileExtension: data.fileExtension || 'mp3',
    mimeType: data.mimeType || 'audio/mpeg',
    expiresAtMs: typeof data.expiresAtMs === 'number' ? data.expiresAtMs : null,
    fallbackUrls: Array.isArray(data.fallbackUrls) ? data.fallbackUrls : null,
    contentLength: typeof data.contentLength === 'number' ? data.contentLength : null
  };
}

module.exports = {
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  fetchFolder: fetchFolder,
  resolveStream: resolveStream
};
