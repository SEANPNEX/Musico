#!/usr/bin/env node
/**
 * Local Node.js mock `ctx` runner for developing and conformance-testing Musico JS extensions.
 * Zero external dependencies (requires Node.js 18+).
 *
 * Usage:
 *   node mock-runner.mjs --self-test
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> can-handle <url>
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> fetch-collection <url> [--cookie "..."] [--cursor "..."] [--setting k=v]
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> fetch-folder <folderId> [--path a/b] [--depth 1] [--cursor "..."]
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> resolve-stream <remoteTrackId> [--cookie "..."] [--force]
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> search <query> [--kind all|track|playlist|album] [--limit 20] [--cursor "..."] [--cookie "..."] [--setting k=v]
 *   node mock-runner.mjs <path-to-bundle-or-.musico.js> search-lyrics <title> [--artist "..."] [--album "..."] [--duration 213] [--setting k=v]
 */

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FIXTURES_DIR = path.resolve(__dirname, '../fixtures');
const STARTER_DIR = path.resolve(__dirname, '../starter');
const STARTER_SINGLE = path.resolve(__dirname, '../starter-single-file.musico.js');

export const VALID_CAPABILITIES = new Set(['playlist', 'tree', 'stream', 'cookie', 'proxy', 'lyrics', 'search']);
export const VALID_SEARCH_KINDS = new Set(['track', 'playlist', 'album']);

export function validateManifest(manifest) {
  assert.ok(manifest && typeof manifest === 'object', 'manifest must be an object');
  assert.equal(manifest.apiVersion, 1, 'manifest.apiVersion must be 1');
  assert.match(String(manifest.id || ''), /^[a-z0-9._-]{3,64}$/, 'manifest.id invalid');
  assert.ok(typeof manifest.name === 'string' && manifest.name.trim().length > 0, 'manifest.name required');
  assert.match(String(manifest.version || ''), /^\d+\.\d+\.\d+/, 'manifest.version must be SemVer');
  assert.ok(Array.isArray(manifest.capabilities) && manifest.capabilities.length > 0, 'capabilities required');
  for (const cap of manifest.capabilities) {
    assert.ok(VALID_CAPABILITIES.has(String(cap)), `Unsupported capability: ${cap}`);
  }
  const requiresUrlPatterns = manifest.capabilities.includes('playlist') || manifest.capabilities.includes('tree');
  if (requiresUrlPatterns) {
    assert.ok(Array.isArray(manifest.urlPatterns) && manifest.urlPatterns.length > 0, 'urlPatterns required for playlist/tree extensions');
  } else if (manifest.urlPatterns !== undefined) {
    assert.ok(Array.isArray(manifest.urlPatterns), 'urlPatterns must be an array');
  }
  for (const pat of manifest.urlPatterns || []) {
    assert.ok(typeof pat === 'string' && pat.length > 0, 'urlPattern must be a non-empty string');
    new RegExp(pat);
  }
  if (manifest.searchKinds !== undefined) {
    assert.ok(Array.isArray(manifest.searchKinds), 'searchKinds must be an array');
    for (const kind of manifest.searchKinds) {
      assert.ok(VALID_SEARCH_KINDS.has(String(kind)), `Unsupported searchKind: ${kind}`);
    }
  }
  assert.ok(manifest.permissions && Array.isArray(manifest.permissions.hosts) && manifest.permissions.hosts.length > 0, 'permissions.hosts required');
  for (const hostPat of manifest.permissions.hosts) {
    validateHostPattern(hostPat);
  }
  return manifest;
}

export function validateHostPattern(pattern) {
  const trimmed = String(pattern || '').trim().toLowerCase().replace(/\.$/, '');
  if (!trimmed || trimmed === '*' || trimmed.startsWith('*.') && trimmed.slice(2).split('.').length < 2) {
    throw Object.assign(new Error(`Invalid host allowlist pattern: ${pattern}`), { code: 'PERMISSION_DENIED' });
  }
  return trimmed;
}

export function isHostAllowed(urlString, allowedPatterns) {
  let parsed;
  try {
    parsed = new URL(urlString);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return false;
  }
  const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
  if (!host) return false;
  for (const raw of allowedPatterns || []) {
    const pat = String(raw).trim().toLowerCase().replace(/\.$/, '');
    if (pat.startsWith('*.')) {
      const domain = pat.slice(2);
      if (domain.split('.').length < 2) continue;
      if (host.endsWith('.' + domain) && host.length > domain.length + 1) {
        return true;
      }
    } else if (host === pat) {
      return true;
    }
  }
  return false;
}

export function loadExtension(targetPath) {
  const abs = path.resolve(targetPath);
  const stat = fs.statSync(abs);
  let manifest;
  let source;

  if (stat.isDirectory()) {
    manifest = JSON.parse(fs.readFileSync(path.join(abs, 'manifest.json'), 'utf8'));
    const entry = manifest.entry || 'index.js';
    source = fs.readFileSync(path.join(abs, entry), 'utf8');
  } else if (abs.endsWith('.musicoext')) {
    const manifestJson = execFileSync('unzip', ['-p', abs, 'manifest.json'], { encoding: 'utf8' });
    manifest = JSON.parse(manifestJson);
    const entry = manifest.entry || 'index.js';
    source = execFileSync('unzip', ['-p', abs, entry], { encoding: 'utf8' });
  } else {
    source = fs.readFileSync(abs, 'utf8');
    const preloaded = evaluateCommonJS(source);
    manifest = preloaded.manifest;
  }

  validateManifest(manifest);
  const exportsObj = evaluateCommonJS(source);
  return { manifest, source, exportsObj };
}

export function evaluateCommonJS(source, timeoutMs = 15000) {
  const context = vm.createContext({
    console,
    URL,
    URLSearchParams,
    setTimeout,
    clearTimeout
  });
  const wrapped = `(function(exports, module) {\n${source}\n})`;
  const fn = vm.runInContext(wrapped, context, { timeout: timeoutMs });
  const mod = { exports: {} };
  fn(mod.exports, mod);
  return mod.exports;
}

export function createHostBridge(manifest, { settingsOverrides = {}, kvStore = new Map(), httpResponder = null, platform = 'macos', appVersion = '1.2.4-dev' } = {}) {
  const logs = [];
  const allowedHosts = manifest.permissions?.hosts || [];
  const defaultSettings = {};
  for (const s of manifest.settings || []) {
    defaultSettings[s.key] = s.default;
  }

  function assertAllowedUrl(urlString) {
    if (!isHostAllowed(urlString, allowedHosts)) {
      throw Object.assign(new Error(`Host blocked by permissions.hosts: ${urlString}`), { code: 'PERMISSION_DENIED' });
    }
  }

  async function executeHttp(req) {
    const method = String((req && req.method) || 'GET').toUpperCase();
    let currentUrl = String(req && req.url ? req.url : '');
    let headers = { ...(req?.headers || {}) };
    const followRedirects = req?.followRedirects !== false;
    const timeoutMs = Number(req?.timeoutMs || 15000);
    const maxHops = 10;

    for (let hop = 0; hop <= maxHops; hop++) {
      assertAllowedUrl(currentUrl);
      let resp;
      if (httpResponder) {
        resp = await httpResponder({ method: hop === 0 ? method : 'GET', url: currentUrl, headers, body: hop === 0 ? req?.body : undefined });
      } else {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
          const nativeRes = await fetch(currentUrl, {
            method: hop === 0 ? method : 'GET',
            headers,
            body: hop === 0 ? req?.body : undefined,
            redirect: 'manual',
            signal: controller.signal
          });
          const buf = Buffer.from(await nativeRes.arrayBuffer());
          const hdrs = {};
          nativeRes.headers.forEach((v, k) => { hdrs[k.toLowerCase()] = v; });
          resp = { status: nativeRes.status, headers: hdrs, bodyBuffer: buf };
        } finally {
          clearTimeout(timer);
        }
      }

      const status = resp.status;
      const lowerHeaders = {};
      for (const [k, v] of Object.entries(resp.headers || {})) {
        lowerHeaders[k.toLowerCase()] = String(v);
      }

      if (followRedirects && [301, 302, 303, 307, 308].includes(status) && lowerHeaders.location) {
        const nextUrl = new URL(lowerHeaders.location, currentUrl).toString();
        const prevHost = new URL(currentUrl).hostname.toLowerCase();
        const nextHost = new URL(nextUrl).hostname.toLowerCase();
        assertAllowedUrl(nextUrl);
        if (prevHost !== nextHost) {
          for (const key of Object.keys(headers)) {
            if (key.toLowerCase() === 'cookie' || key.toLowerCase() === 'authorization') {
              delete headers[key];
            }
          }
        }
        currentUrl = nextUrl;
        continue;
      }

      const buf = resp.bodyBuffer ?? Buffer.from(resp.text || '', 'utf8');
      const capped = buf.subarray(0, 8 * 1024 * 1024);
      const out = {
        status,
        finalUrl: currentUrl,
        headers: lowerHeaders,
        text: capped.toString('utf8')
      };
      if (req?.returnBytes === true || capped.length <= 1024 * 1024) {
        out.bytesBase64 = capped.toString('base64');
      }
      return out;
    }

    throw Object.assign(new Error('Too many HTTP redirects'), { code: 'NETWORK_ERROR' });
  }

  const ctx = {
    http: { request: executeHttp },
    crypto: {
      md5: (s) => crypto.createHash('md5').update(String(s), 'utf8').digest('hex'),
      sha1: (s) => crypto.createHash('sha1').update(String(s), 'utf8').digest('hex'),
      sha256: (s) => crypto.createHash('sha256').update(String(s), 'utf8').digest('hex'),
      hmacSha256: (k, m) => crypto.createHmac('sha256', String(k)).update(String(m), 'utf8').digest('hex'),
      base64Encode: (s) => Buffer.from(String(s), 'utf8').toString('base64'),
      base64Decode: (s) => Buffer.from(String(s), 'base64').toString('utf8')
    },
    kv: {
      get: (key) => {
        const entry = kvStore.get(String(key));
        if (!entry) return null;
        if (entry.expiresAtMs !== null && Date.now() >= entry.expiresAtMs) {
          kvStore.delete(String(key));
          return null;
        }
        return entry.value;
      },
      set: (key, val, ttlSeconds) => {
        const expiresAtMs = typeof ttlSeconds === 'number' && ttlSeconds > 0
          ? Date.now() + ttlSeconds * 1000
          : null;
        kvStore.set(String(key), { value: String(val), expiresAtMs });
      },
      remove: (key) => {
        kvStore.delete(String(key));
      }
    },
    settings: {
      get: (key) => {
        if (Object.prototype.hasOwnProperty.call(settingsOverrides, key)) {
          return settingsOverrides[key];
        }
        if (Object.prototype.hasOwnProperty.call(defaultSettings, key)) {
          return defaultSettings[key];
        }
        return null;
      }
    },
    env: {
      platform,
      appVersion,
      apiVersion: 1
    },
    log: {
      info: (msg) => pushLog('info', msg),
      warn: (msg) => pushLog('warn', msg),
      error: (msg) => pushLog('error', msg)
    }
  };

  function pushLog(level, message) {
    logs.push({ timestampMs: Date.now(), level, message: String(message) });
    if (logs.length > 200) logs.shift();
  }

  return { ctx, logs, assertAllowedUrl };
}

export async function invokeWithTimeout(fn, timeoutMs) {
  let timer;
  try {
    return await Promise.race([
      Promise.resolve().then(fn),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(Object.assign(new Error(`Execution timed out after ${timeoutMs}ms`), { code: 'EXTENSION_RUNTIME_ERROR' })), timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function runResolveStream(ext, bridge, req, timeoutMs = 15000) {
  const result = await invokeWithTimeout(() => ext.exportsObj.resolveStream(bridge.ctx, req), timeoutMs);
  bridge.assertAllowedUrl(result.url);
  for (const fb of result.fallbackUrls || []) {
    bridge.assertAllowedUrl(fb);
  }
  return result;
}

export async function runSearch(ext, bridge, req, timeoutMs = 15000) {
  assert.equal(typeof ext.exportsObj.search, 'function', 'Extension must export search(ctx, req)');
  const payload = {
    query: String(req?.query || ''),
    kind: String(req?.kind || 'all'),
    limit: Math.max(1, Number(req?.limit || 20)),
    ...(req?.cursor ? { cursor: String(req.cursor) } : {}),
    ...(req?.cookie ? { cookie: String(req.cookie) } : {})
  };
  const result = await invokeWithTimeout(() => ext.exportsObj.search(bridge.ctx, payload), timeoutMs);
  if (result == null) {
    return { items: [], nextCursor: null };
  }
  assert.ok(typeof result === 'object' && !Array.isArray(result), 'search() must return an object { items, nextCursor? }');
  assert.ok(Array.isArray(result.items), 'search() result.items must be an array');
  for (const item of result.items) {
    assert.ok(item && typeof item === 'object', 'Each search item must be an object');
    assert.ok(VALID_SEARCH_KINDS.has(String(item.kind)), `Invalid search item kind: ${item.kind}`);
    assert.ok(typeof item.title === 'string' && item.title.trim().length > 0, 'Each search item requires a non-empty title');
    if (item.kind === 'track') {
      assert.ok(item.remoteTrackId != null && String(item.remoteTrackId).trim().length > 0, 'Track search item requires remoteTrackId');
    } else {
      assert.ok(item.remoteId != null && String(item.remoteId).trim().length > 0, `${item.kind} search item requires remoteId`);
    }
  }
  return {
    items: result.items,
    nextCursor: result.nextCursor == null ? null : String(result.nextCursor)
  };
}

export async function runSearchLyrics(ext, bridge, req, timeoutMs = 15000) {
  assert.equal(typeof ext.exportsObj.searchLyrics, 'function', 'Extension must export searchLyrics(ctx, req)');
  const payload = {
    title: String(req?.title || ''),
    artist: String(req?.artist || ''),
    ...(req?.album ? { album: String(req.album) } : {}),
    ...(typeof req?.durationSec === 'number' && Number.isFinite(req.durationSec) && req.durationSec > 0
      ? { durationSec: req.durationSec }
      : {})
  };
  const result = await invokeWithTimeout(() => ext.exportsObj.searchLyrics(bridge.ctx, payload), timeoutMs);
  if (result == null) {
    return [];
  }
  if (Array.isArray(result)) {
    return result;
  }
  if (typeof result === 'object') {
    if (Array.isArray(result.candidates)) return result.candidates;
    if (Array.isArray(result.results)) return result.results;
  }
  throw Object.assign(new Error('searchLyrics() must return an array or { candidates | results } object'), {
    code: 'EXTENSION_RUNTIME_ERROR'
  });
}

async function runSelfTest() {
  const kvStore = new Map();
  const capturedRequests = [];

  const responder = async ({ method, url, headers, body }) => {
    capturedRequests.push({ method, url, headers, body });
    const parsed = new URL(url);

    if (parsed.hostname === 'api.fixture.test' && parsed.pathname.startsWith('/playlists/')) {
      const cursor = parsed.searchParams.get('cursor');
      if (!cursor) {
        return {
          status: 200,
          headers: { 'content-type': 'application/json' },
          text: JSON.stringify({
            remoteId: 'conformance',
            title: 'Conformance Playlist',
            injectDiagnostics: true,
            tracks: [
              { remoteTrackId: 't1', relativePath: '01.mp3', title: 'Track One', artist: 'Tester', album: '', trackNumber: 1, durationSec: 120, artworkUrl: 'https://img.cdn.fixture.test/1.jpg' }
            ],
            nextCursor: 'page-2'
          })
        };
      }
      return {
        status: 200,
        headers: { 'content-type': 'application/json' },
        text: JSON.stringify({
          remoteId: 'conformance',
          title: 'Conformance Playlist',
          tracks: [
            { remoteTrackId: 't2', relativePath: '02.flac', title: 'Track Two', artist: 'Tester', album: 'Page 2', trackNumber: 2, durationSec: 200 }
          ],
          nextCursor: null
        })
      };
    }

    if (parsed.hostname === 'api.fixture.test' && parsed.pathname === '/stream') {
      return {
        status: 200,
        headers: { 'content-type': 'application/json' },
        text: JSON.stringify({
          url: 'https://edge1.cdn.fixture.test/audio/t1.mp3',
          headers: { Referer: 'https://fixture.test/' },
          fileExtension: 'mp3',
          mimeType: 'audio/mpeg',
          expiresAtMs: 1800000000000,
          fallbackUrls: ['https://edge2.cdn.fixture.test/audio/t1.mp3'],
          contentLength: 1024000
        })
      };
    }

    if (parsed.hostname === 'tree.fixture.test' && parsed.pathname === '/api/root/root-1') {
      return {
        status: 200,
        headers: { 'content-type': 'application/json' },
        text: JSON.stringify({
          remoteId: 'root-1',
          title: 'Tree Root',
          tracks: [{ remoteTrackId: 'rt-1', relativePath: 'intro.mp3', title: 'Intro', artist: 'Root', album: 'Tree', trackNumber: 1, durationSec: 30 }],
          subfolders: [{ id: 'sub-a', name: 'Disc 1', pathSegments: ['Disc 1'], extra: { pageSize: 1 } }],
          nextCursor: null
        })
      };
    }

    if (parsed.hostname === 'tree.fixture.test' && parsed.pathname === '/api/folder/sub-a') {
      const cursor = parsed.searchParams.get('cursor');
      return {
        status: 200,
        headers: { 'content-type': 'application/json' },
        text: JSON.stringify(
          !cursor
            ? {
                folderId: 'sub-a',
                tracks: [{ remoteTrackId: 'sa-1', relativePath: 'Disc 1/01.mp3', title: 'Sub 1', artist: 'Tree', album: 'Disc 1', trackNumber: 1, durationSec: 90 }],
                subfolders: [],
                nextCursor: 'sub-p2'
              }
            : {
                folderId: 'sub-a',
                tracks: [{ remoteTrackId: 'sa-2', relativePath: 'Disc 1/02.mp3', title: 'Sub 2', artist: 'Tree', album: 'Disc 1', trackNumber: 2, durationSec: 95 }],
                subfolders: [],
                nextCursor: null
              }
        )
      };
    }

    if (parsed.hostname === 'fixture.test' && parsed.pathname === '/redirect-evil') {
      return { status: 302, headers: { location: 'https://evil.example.com/steal' }, text: '' };
    }

    if (parsed.hostname === 'edge1.cdn.fixture.test' && parsed.pathname === '/ok') {
      return { status: 200, headers: { 'content-type': 'text/plain' }, text: 'edge-ok' };
    }

    return { status: 404, headers: {}, text: 'not found' };
  };

  // 1. Test basic-playlist.musico.js
  const basic = loadExtension(path.join(FIXTURES_DIR, 'basic-playlist.musico.js'));
  const handled = basic.exportsObj.canHandle({ extractedUrl: 'https://www.fixture.test/playlist/conformance?foo=bar' });
  assert.equal(handled.matched, true);
  assert.equal(handled.remoteId, 'conformance');
  assert.equal(handled.normalizedUrl, 'https://fixture.test/playlist/conformance');

  const bridge1 = createHostBridge(basic.manifest, {
    settingsOverrides: { preferHighBitrate: false, apiTag: 'custom-tag', region: 'jp' },
    kvStore,
    httpResponder: responder
  });

  const p1 = await basic.exportsObj.fetchCollection(bridge1.ctx, { url: handled.normalizedUrl, cookie: 'sid=abc' });
  assert.equal(p1.nextCursor, 'page-2');
  assert.equal(p1.tracks.length, 1);
  assert.ok(p1.tracks[0].album.includes('md5=860a75194508c8447e93dbcba86b5ffe'));
  assert.ok(p1.tracks[0].album.includes('sha1=e16682e68bc1074d3b8f9e4c9869ebcdb3b20090'));
  assert.ok(p1.tracks[0].album.includes('sha256=cbcd5268d62f393b8ce6cc33ed8f5309be03b110012a0bc48d302855d63edd11'));
  assert.ok(p1.tracks[0].album.includes('hmac=9edcd2ade9a09eac5f91629abeb0fb0ff5fe6a4cad391511b4dbeb4a333a1ba8'));
  assert.ok(p1.tracks[0].album.includes('b64=musico-hello'));
  assert.ok(p1.tracks[0].album.includes('kv=1/temp/null'));
  assert.ok(p1.tracks[0].album.includes('settings=false|custom-tag|jp'));
  assert.equal(bridge1.logs.length, 3);

  const p2 = await basic.exportsObj.fetchCollection(bridge1.ctx, { url: handled.normalizedUrl, cookie: 'sid=abc', cursor: p1.nextCursor });
  assert.equal(p2.tracks[0].title, 'Track Two');
  assert.equal(p2.nextCursor, null);

  await assert.rejects(
    () => basic.exportsObj.fetchCollection(bridge1.ctx, { url: 'https://fixture.test/playlist/auth-required' }),
    (err) => err.code === 'AUTH_REQUIRED'
  );

  const stream = await runResolveStream(basic, bridge1, { remoteTrackId: 't1', cookie: 'sid=abc', forceRefresh: true });
  assert.equal(stream.url, 'https://edge1.cdn.fixture.test/audio/t1.mp3');
  assert.equal(stream.fallbackUrls[0], 'https://edge2.cdn.fixture.test/audio/t1.mp3');

  // 2. Test tree-source bundle
  const tree = loadExtension(path.join(FIXTURES_DIR, 'tree-source'));
  const treeBridge = createHostBridge(tree.manifest, { httpResponder: responder });
  const rootCol = await tree.exportsObj.fetchCollection(treeBridge.ctx, { url: 'https://tree.fixture.test/root/root-1' });
  assert.equal(rootCol.subfolders.length, 1);
  const f1 = await tree.exportsObj.fetchFolder(treeBridge.ctx, {
    folderId: rootCol.subfolders[0].id,
    pathSegments: rootCol.subfolders[0].pathSegments,
    depth: 1
  });
  assert.equal(f1.nextCursor, 'sub-p2');
  const f2 = await tree.exportsObj.fetchFolder(treeBridge.ctx, {
    folderId: 'sub-a',
    pathSegments: ['Disc 1'],
    depth: 1,
    cursor: f1.nextCursor
  });
  assert.equal(f2.tracks[0].title, 'Sub 2');

  // 3. Test security-and-errors.musico.js
  const sec = loadExtension(path.join(FIXTURES_DIR, 'security-and-errors.musico.js'));
  const secBridge = createHostBridge(sec.manifest, { httpResponder: responder });
  for (const mode of ['disallowed-host', 'disallowed-scheme', 'redirect-escape', 'subdomain-exact-miss']) {
    await assert.rejects(
      () => sec.exportsObj.fetchCollection(secBridge.ctx, { url: `https://fixture.test/security/${mode}` }),
      (err) => err.code === 'PERMISSION_DENIED',
      `Expected PERMISSION_DENIED for ${mode}`
    );
  }
  const noFollow = await sec.exportsObj.fetchCollection(secBridge.ctx, { url: 'https://fixture.test/security/redirect-no-follow' });
  assert.equal(noFollow.title, 'status=302');
  assert.equal(noFollow.tracks[0].album, 'https://evil.example.com/steal');

  const subHit = await sec.exportsObj.fetchCollection(secBridge.ctx, { url: 'https://fixture.test/security/subdomain-hit' });
  assert.equal(subHit.tracks[0].album, 'edge-ok');

  for (const [mode, code] of [
    ['throw-invalid-url', 'INVALID_URL'],
    ['throw-auth-required', 'AUTH_REQUIRED'],
    ['throw-empty-collection', 'EMPTY_COLLECTION'],
    ['throw-no-stream', 'NO_PLAYABLE_STREAM'],
    ['throw-rate-limited', 'RATE_LIMITED'],
    ['throw-network-error', 'NETWORK_ERROR']
  ]) {
    await assert.rejects(
      () => sec.exportsObj.fetchCollection(secBridge.ctx, { url: `https://fixture.test/security/${mode}` }),
      (err) => err.code === code,
      `Expected ${code} for ${mode}`
    );
  }

  await assert.rejects(
    () => runResolveStream(sec, secBridge, { remoteTrackId: 'bad-stream-host', forceRefresh: false }),
    (err) => err.code === 'PERMISSION_DENIED'
  );
  await assert.rejects(
    () => runResolveStream(sec, secBridge, { remoteTrackId: 'bad-fallback-host', forceRefresh: false }),
    (err) => err.code === 'PERMISSION_DENIED'
  );
  await assert.rejects(
    () => invokeWithTimeout(() => sec.exportsObj.fetchCollection(secBridge.ctx, { url: 'https://fixture.test/security/async-hang' }), 50),
    (err) => err.code === 'EXTENSION_RUNTIME_ERROR'
  );

  // 4. Test starter templates validate cleanly
  const starterBundle = loadExtension(STARTER_DIR);
  assert.equal(starterBundle.exportsObj.canHandle({ rawText: 'https://example.com/playlist/abc_1' }).matched, true);
  const starterSingle = loadExtension(STARTER_SINGLE);
  assert.equal(starterSingle.exportsObj.canHandle({ rawText: 'https://example.org/mix/demo99' }).matched, true);

  // 5. Test v1.2.3/v1.2.4 manifest rules plus in-memory lyrics & cloud-search hooks
  const lyricsSource = `
    var manifest = {
      apiVersion: 1,
      id: 'com.example.test.lyrics',
      name: 'Test Lyrics',
      version: '1.0.0',
      capabilities: ['lyrics'],
      permissions: { hosts: ['lyrics.fixture.test'] }
    };
    async function searchLyrics(ctx, req) {
      if (req.title === 'envelope') {
        return { candidates: [{ externalId: 'c1', title: req.title, artist: req.artist, plainLyrics: 'hello' }] };
      }
      return [{ externalId: 'c2', title: req.title, artist: req.artist, syncedLyrics: '[00:01.00]la', instrumental: false }];
    }
    module.exports = { manifest: manifest, searchLyrics: searchLyrics };
  `;
  const lyricsExports = evaluateCommonJS(lyricsSource);
  validateManifest(lyricsExports.manifest);
  const lyricsBridge = createHostBridge(lyricsExports.manifest);
  const lyrArr = await runSearchLyrics({ manifest: lyricsExports.manifest, source: lyricsSource, exportsObj: lyricsExports }, lyricsBridge, {
    title: 'Idol',
    artist: 'YOASOBI',
    durationSec: 213
  });
  assert.equal(lyrArr.length, 1);
  assert.equal(lyrArr[0].externalId, 'c2');
  const lyrEnv = await runSearchLyrics({ manifest: lyricsExports.manifest, source: lyricsSource, exportsObj: lyricsExports }, lyricsBridge, {
    title: 'envelope',
    artist: 'YOASOBI'
  });
  assert.equal(lyrEnv.length, 1);
  assert.equal(lyrEnv[0].externalId, 'c1');

  const searchSource = `
    var manifest = {
      apiVersion: 1,
      id: 'com.example.test.search',
      name: 'Test Search',
      version: '1.0.0',
      capabilities: ['search', 'stream', 'cookie'],
      searchKinds: ['track', 'album'],
      urlPatterns: [],
      permissions: { hosts: ['search.fixture.test'] }
    };
    async function search(ctx, req) {
      return {
        items: [
          { kind: 'track', remoteTrackId: 'tr-1', title: req.query + ' Song', artist: 'Singer', durationSec: 185 },
          { kind: 'album', remoteId: 'al-1', title: req.query + ' Album', trackCount: 10, sourceUrl: 'https://search.fixture.test/album/al-1' }
        ],
        nextCursor: req.cookie ? 'cur-auth' : 'cur-anon'
      };
    }
    module.exports = { manifest: manifest, search: search };
  `;
  const searchExports = evaluateCommonJS(searchSource);
  validateManifest(searchExports.manifest);
  const searchBridge = createHostBridge(searchExports.manifest);
  const searchPage = await runSearch({ manifest: searchExports.manifest, source: searchSource, exportsObj: searchExports }, searchBridge, {
    query: 'Star',
    kind: 'all',
    limit: 20,
    cookie: 'sid=ok'
  });
  assert.equal(searchPage.items.length, 2);
  assert.equal(searchPage.items[0].remoteTrackId, 'tr-1');
  assert.equal(searchPage.items[1].remoteId, 'al-1');
  assert.equal(searchPage.nextCursor, 'cur-auth');

  assert.throws(() => validateManifest({
    apiVersion: 1,
    id: 'com.example.invalid.playlist',
    name: 'Invalid Playlist',
    version: '1.0.0',
    capabilities: ['playlist', 'stream'],
    urlPatterns: [],
    permissions: { hosts: ['example.com'] }
  }), /urlPatterns required/);

  assert.throws(() => validateManifest({
    apiVersion: 1,
    id: 'com.example.invalid.cap',
    name: 'Invalid Cap',
    version: '1.0.0',
    capabilities: ['unknown-cap'],
    permissions: { hosts: ['example.com'] }
  }), /Unsupported capability/);

  assert.throws(() => validateManifest({
    apiVersion: 1,
    id: 'com.example.invalid.kind',
    name: 'Invalid Kind',
    version: '1.0.0',
    capabilities: ['search'],
    searchKinds: ['podcast'],
    permissions: { hosts: ['example.com'] }
  }), /Unsupported searchKind/);

  console.log('ALL_SELF_TESTS_PASSED', {
    testedFixtures: ['basic-playlist.musico.js', 'tree-source', 'security-and-errors.musico.js', 'starter', 'starter-single-file.musico.js', 'in-memory-lyrics', 'in-memory-search'],
    httpRequestsExecuted: capturedRequests.length
  });
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--self-test')) {
    await runSelfTest();
    return;
  }
  if (args.length < 3) {
    console.error('Usage: node mock-runner.mjs [--self-test | <ext-path> <can-handle|fetch-collection|fetch-folder|resolve-stream|search|search-lyrics> <target> [options]]');
    process.exit(2);
  }

  const [extPath, command, target, ...rest] = args;
  let cookie = null;
  let cursor = null;
  let depth = 1;
  let pathSegments = [];
  let forceRefresh = false;
  let kind = 'all';
  let limit = 20;
  let artist = '';
  let album = null;
  let durationSec = null;
  const settingsOverrides = {};

  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    if (flag === '--cookie' && rest[i + 1]) cookie = rest[++i];
    else if (flag === '--cursor' && rest[i + 1]) cursor = rest[++i];
    else if (flag === '--depth' && rest[i + 1]) depth = Number(rest[++i]);
    else if (flag === '--path' && rest[i + 1]) pathSegments = rest[++i].split('/').filter(Boolean);
    else if (flag === '--force') forceRefresh = true;
    else if (flag === '--kind' && rest[i + 1]) kind = rest[++i];
    else if (flag === '--limit' && rest[i + 1]) limit = Number(rest[++i]);
    else if (flag === '--artist' && rest[i + 1]) artist = rest[++i];
    else if (flag === '--album' && rest[i + 1]) album = rest[++i];
    else if (flag === '--duration' && rest[i + 1]) durationSec = Number(rest[++i]);
    else if (flag === '--setting' && rest[i + 1]) {
      const [k, v] = rest[++i].split('=');
      settingsOverrides[k] = v === 'true' ? true : v === 'false' ? false : v;
    }
  }

  const ext = loadExtension(extPath);
  const bridge = createHostBridge(ext.manifest, { settingsOverrides });
  let output;
  if (command === 'can-handle') {
    output = ext.exportsObj.canHandle({ rawText: target, extractedUrl: target });
  } else if (command === 'fetch-collection') {
    output = await invokeWithTimeout(() => ext.exportsObj.fetchCollection(bridge.ctx, { url: target, cookie, cursor }), 60000);
  } else if (command === 'fetch-folder') {
    output = await invokeWithTimeout(() => ext.exportsObj.fetchFolder(bridge.ctx, { folderId: target, pathSegments, depth, cookie, cursor }), 60000);
  } else if (command === 'resolve-stream') {
    output = await runResolveStream(ext, bridge, { remoteTrackId: target, cookie, forceRefresh }, 15000);
  } else if (command === 'search') {
    output = await runSearch(ext, bridge, { query: target, kind, limit, cursor, cookie }, 15000);
  } else if (command === 'search-lyrics') {
    output = await runSearchLyrics(ext, bridge, { title: target, artist, album, durationSec }, 15000);
  } else {
    throw new Error(`Unknown command: ${command}`);
  }
  console.log(JSON.stringify({ result: output, logs: bridge.logs }, null, 2));
}

if (path.resolve(process.argv[1] || '') === __filename) {
  main().catch((err) => {
    console.error('RUNNER_ERROR', err?.code || 'ERROR', err?.message || err);
    process.exit(1);
  });
}
