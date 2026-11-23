# Musico JavaScript Extensions Developer Guide (API v1)

**English** | [简体中文](README.zh-CN.md)

Write one JavaScript extension in CommonJS format and run it natively inside **Musico** across **iOS / iPadOS / macOS** (`JavaScriptCore`), **Android** (`QuickJS`), and **Windows** (`Jint`) with zero WebViews. Extensions share the exact same `CloudSourceProvider` pipeline as YouTube, Bilibili, and Google Drive, so they automatically support **Save to Local Folder**, **Update from Cloud**, **Playlists**, **Offline Cache**, and source badge pills.

---

## 1. Quick Start — Minimal Practical Example

The fastest way to ship an extension is a single **`.musico.js`** script that exports both `manifest` and your provider hooks. Save the example below as `minimal-source.musico.js`, test it locally with Node.js, or import it directly in **Musico → Extensions**.

```js
'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.example.musico.minimal',
  name: 'Minimal Cloud Source',
  version: '1.0.0',
  author: 'Your Name',
  description: 'Minimal practical playlist and stream extension.',
  badgeLabel: 'Minimal',
  capabilities: ['playlist', 'stream', 'cookie'],
  urlPatterns: [
    '^https?://(www\\.)?music\\.example\\.com/playlist/[a-zA-Z0-9_-]+'
  ],
  permissions: {
    hosts: ['music.example.com', 'api.music.example.com', '*.cdn.music.example.com']
  },
  settings: [
    {
      key: 'highQuality',
      label: 'Prefer lossless / high bitrate',
      type: 'boolean',
      default: true
    }
  ]
};

function extError(code, message) {
  var err = new Error(message);
  err.code = code;
  return err;
}

// 1. Synchronous URL recognizer (runs after native urlPatterns regex check)
function canHandle(input) {
  var raw = String((input && (input.extractedUrl || input.rawText)) || '');
  var m = raw.match(/^https?:\/\/(?:www\.)?music\.example\.com\/playlist\/([a-zA-Z0-9_-]+)/i);
  if (!m) return { matched: false };
  return {
    matched: true,
    normalizedUrl: 'https://music.example.com/playlist/' + m[1],
    remoteId: m[1],
    kind: 'playlist'
  };
}

// 2. Async collection loader (with cursor pagination & cookie isolation)
async function fetchCollection(ctx, req) {
  var match = canHandle({ rawText: req.url, extractedUrl: req.url });
  if (!match.matched) throw extError('INVALID_URL', 'Invalid playlist URL.');

  ctx.log.info('Fetching playlist ' + match.remoteId);

  var headers = { Accept: 'application/json' };
  if (req.cookie) headers.Cookie = req.cookie;

  var apiUrl = 'https://api.music.example.com/v1/playlists/' + encodeURIComponent(match.remoteId) +
    (req.cursor ? '?cursor=' + encodeURIComponent(req.cursor) : '');

  var res = await ctx.http.request({ method: 'GET', url: apiUrl, headers: headers, timeoutMs: 15000 });
  if (res.status === 401 || res.status === 403) throw extError('AUTH_REQUIRED', 'Cookie required or expired.');
  if (res.status === 429) throw extError('RATE_LIMITED', 'Rate limited by upstream API.');
  if (res.status >= 400) throw extError('NETWORK_ERROR', 'HTTP ' + String(res.status));

  var data = JSON.parse(res.text || '{}');
  var tracks = (data.tracks || []).map(function (item, idx) {
    return {
      remoteTrackId: String(item.id),
      relativePath: String(idx + 1).padStart(2, '0') + ' - ' + String(item.title || 'Untitled') + '.mp3',
      title: String(item.title || 'Untitled'),
      artist: String(item.artist || 'Unknown Artist'),
      album: String(data.title || 'Minimal Playlist'),
      trackNumber: typeof item.trackNumber === 'number' ? item.trackNumber : idx + 1,
      durationSec: typeof item.durationSec === 'number' ? item.durationSec : 0,
      artworkUrl: item.coverUrl ? String(item.coverUrl) : null
    };
  });

  if (tracks.length === 0 && !req.cursor) {
    throw extError('EMPTY_COLLECTION', 'Playlist contains no playable tracks.');
  }

  return {
    remoteId: match.remoteId,
    title: String(data.title || match.remoteId),
    tracks: tracks,
    nextCursor: data.nextCursor || null
  };
}

// 3. Async audio URL resolver (called just before playback, caching, or download)
async function resolveStream(ctx, req) {
  var hq = Boolean(ctx.settings.get('highQuality'));
  var headers = { Accept: 'application/json' };
  if (req.cookie) headers.Cookie = req.cookie;

  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://api.music.example.com/v1/tracks/' + encodeURIComponent(req.remoteTrackId) + '?hq=' + String(hq),
    headers: headers,
    timeoutMs: 12000
  });
  if (res.status === 401 || res.status === 403) throw extError('AUTH_REQUIRED', 'Track requires login.');
  if (res.status >= 400) throw extError('NO_PLAYABLE_STREAM', 'Upstream returned HTTP ' + String(res.status));

  var stream = JSON.parse(res.text || '{}');
  if (!stream.url) throw extError('NO_PLAYABLE_STREAM', 'No audio URL returned.');

  return {
    url: String(stream.url),
    headers: { Referer: 'https://music.example.com/' },
    fileExtension: stream.ext || 'mp3',
    mimeType: stream.mime || 'audio/mpeg',
    expiresAtMs: Date.now() + 1800 * 1000
  };
}

module.exports = {
  manifest: manifest,
  canHandle: canHandle,
  fetchCollection: fetchCollection,
  resolveStream: resolveStream
};
```

Test it locally against Musico's CLI runner right away:

```bash
node docs/extensions/tools/mock-runner.mjs path/to/minimal-source.musico.js can-handle "https://music.example.com/playlist/demo_1"
```

---

## 2. Minimal Tree / Lazy Subfolder Example

If your source has nested folders (like a cloud drive), add `"tree"` to `capabilities`, return `subfolders` from `fetchCollection`, and implement `fetchFolder(ctx, req)`:

```js
// Add "tree" to manifest.capabilities: ["tree", "stream"]
async function fetchFolder(ctx, req) {
  // req = { folderId, pathSegments, depth, cookie?, cursor?, extra? }
  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://api.music.example.com/v1/folders/' + encodeURIComponent(req.folderId) +
      (req.cursor ? '?cursor=' + encodeURIComponent(req.cursor) : '')
  });
  var data = JSON.parse(res.text || '{}');
  var prefix = (req.pathSegments || []).join('/');

  return {
    folderId: req.folderId,
    tracks: (data.files || []).map(function (f, i) {
      return {
        remoteTrackId: String(f.id),
        relativePath: (prefix ? prefix + '/' : '') + String(f.name),
        title: String(f.title || f.name),
        artist: f.artist || null,
        album: req.pathSegments && req.pathSegments.length ? req.pathSegments[req.pathSegments.length - 1] : null,
        trackNumber: i + 1,
        durationSec: f.durationSec || 0
      };
    }),
    subfolders: (data.folders || []).map(function (dir) {
      return {
        id: String(dir.id),
        name: String(dir.name),
        pathSegments: (req.pathSegments || []).concat([String(dir.name)]),
        extra: dir.meta || null
      };
    }),
    nextCursor: data.nextCursor || null
  };
}
```

Musico's progressive crawler automatically populates subfolders live as they finish and prioritizes whichever subfolder the user opens in **Folders**.

---

## 3. Packaging Formats

| Format | Structure | Best For |
|---|---|---|
| **Single-File (`.musico.js`)** | One JS file exporting `{ manifest, canHandle, ... }` on `module.exports`. | Simple scripts, quick sharing, single-file starters (e.g. [`starter-single-file.musico.js`](starter-single-file.musico.js)). |
| **Bundle Archive (`.musicoext`)** | Standard `.zip` containing `manifest.json`, `index.js` (or `manifest.entry`), and optional `icon.png`. | Extensions with separate assets or multi-file packaging (e.g. [`starter/`](starter/manifest.json)). |

Package the included starter directory into a `.musicoext` bundle:

```bash
(cd docs/extensions/starter && zip -r ../../starter-example.musicoext manifest.json index.js)
```

> **Install locations inside Musico:** `<AppData>/Extensions/<id>/` (`.\Data\Extensions\<id>\` in Windows Portable mode). ZIP bundles are validated for path traversal (`../`, absolute paths), max 256 entries, and max 16 MB uncompressed size.

---

## 4. `manifest.json` Reference

| Field | Type | Required | Constraints & Notes |
|---|---|---|---|
| `apiVersion` | `integer` | Yes | Must be `1`. |
| `id` | `string` | Yes | Reverse-DNS ID matching `^[a-z0-9._-]{3,64}$`. Stored on roots as `providerID = "ext.<id>"`. |
| `name` | `string` | Yes | Display name in **Extensions** and **Add Cloud Folder**. |
| `version` | `string` | Yes | SemVer (`MAJOR.MINOR.PATCH`). |
| `author` | `string` | No | Optional author name shown in Extension Manager. |
| `description` | `string` | No | Short description shown in Extension Manager. |
| `badgeLabel` | `string` | No | Short pill badge in **Folders** (defaults to `name`, max 16 chars). |
| `entry` | `string` | No | Relative script path inside `.musicoext` (defaults to `"index.js"`). |
| `capabilities` | `string[]` | Yes | Subset of `"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`. |
| `urlPatterns` | `string[]` | Yes | Native regex strings tested before starting a JS VM. |
| `permissions.hosts` | `string[]` | Yes | Strict HTTP/HTTPS allowlist: exact domain (`example.com`) or `*.sub.domain` (≥ 2 domain labels after `*.`; bare `*` and `*.tld` are rejected). |
| `settings` | `array` | No | List of `{ key, label, type, default, options? }` where `type` is `"boolean"`, `"string"`, or `"select"`. Select `options` may be strings (`["us", "jp"]`) or `{ value, label }` objects. |

---

## 5. Hook Contracts & Track Schema (`module.exports`)

- **`canHandle(input)`** *(sync — required)*
  - **Input:** `{ rawText, extractedUrl }`
  - **Returns:** `{ matched, normalizedUrl?, remoteId?, kind?: "playlist" | "folder" | "track" }`
- **`fetchCollection(ctx, req)`** *(async — required for `"playlist"` or `"tree"`)*
  - **Input:** `{ url, cookie?, cursor? }`
  - **Returns:** `{ remoteId, title, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`fetchFolder(ctx, req)`** *(async — required when `"tree"` capability is declared)*
  - **Input:** `{ folderId, pathSegments, depth, cookie?, cursor?, extra? }`
  - **Returns:** `{ folderId, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`resolveStream(ctx, req)`** *(async — required when `"stream"` capability is declared)*
  - **Input:** `{ remoteTrackId, relativePath?, cookie?, forceRefresh }`
  - **Returns:** `{ url, headers?, fileExtension, mimeType?, expiresAtMs?, fallbackUrls?, contentLength? }`

### `ExtTrack` Mapping Across Platforms

| Extension Field | Apple (`CloudTrackInfo`) | Android (`CloudTrackMetadata`) | Windows (`CloudPlaylistItem`) |
|---|---|---|---|
| `remoteTrackId` | `remoteTrackID` | `remoteTrackId` | `RemoteTrackId` |
| `relativePath` | `relativePath` | `relativePath` | `RelativePath` |
| `title` | `title` | `title` | `Title` |
| `artist` | `artist` | `artist` | `Artist` |
| `album` | `album` | `album` | `Album` |
| `trackNumber` | `trackNumber` | `trackNumber` | `TrackNumber` |
| `durationSec` | `duration` | `durationSec` | `DurationSeconds` |
| `artworkUrl` | `artworkURL` | `artworkUrl` | `ArtworkUrl` |

---

## 6. Host Bridge API (`ctx`) & Security Guardrails

- **`await ctx.http.request({ method?, url, headers?, body?, followRedirects?, timeoutMs?, returnBytes? })`**
  - Routes through native HTTP clients (preserving platform timeouts and Windows proxy settings when `"proxy"` capability is declared).
  - Returns `{ status, finalUrl, headers, text, bytesBase64? }` with lowercase header keys (response body capped at 8 MB; `bytesBase64` included when `returnBytes === true` or body ≤ 1 MB).
  - Enforces `permissions.hosts` on the initial URL **and every redirect hop** (up to 10 hops; strips `Cookie` & `Authorization` headers across host changes; non-`http`/`https` schemes reject with `PERMISSION_DENIED`).
  - Also validates `resolveStream`'s returned `url` and `fallbackUrls` against `permissions.hosts`.
- **`ctx.crypto`**: `md5(str)`, `sha1(str)`, `sha256(str)`, `hmacSha256(key, msg)` *(all lowercase hex)*, `base64Encode(str)`, `base64Decode(b64)`.
- **`ctx.kv`**: Per-extension persistent key-value store: `get(key) -> string | null`, `set(key, val, ttlSeconds?)`, `remove(key)`.
- **`ctx.settings.get(key)`**: Returns the user's configured value (or manifest default, or `null`).
- **`ctx.env`**: `{ platform: "ios" | "macos" | "android" | "windows", appVersion, apiVersion: 1 }`.
- **`ctx.log`**: `info(msg)`, `warn(msg)`, `error(msg)` stored in a 200-entry ring buffer visible in Extension Manager.

### Guardrails & Compatibility Tips
- **Fresh Isolated VM Per Call:** Global JS variables do not persist across calls (and cookies stay isolated per `LibraryRoot`). Use `ctx.kv` for intentional token caching.
- **Resource Limits:** `15s` cap on `canHandle` / `resolveStream`, `60s` cap on `fetchCollection` / `fetchFolder`, and a `32 MB` heap limit on QuickJS (Android) and Jint (Windows).
- **Portable JS Subset:** Stick to **ES2020 + CommonJS `module.exports` + `ctx.*`**. Avoid Node-only modules (`require`, `fs`, `Buffer`) and browser DOM globals (`window`, `document`).

---

## 7. Standard Error Codes

Set `err.code` on thrown errors so Musico shows localized messages and handles auth/retries cleanly:

| Code | When to Throw |
|---|---|
| `INVALID_URL` | Input URL does not match a valid resource ID. |
| `AUTH_REQUIRED` | Missing, private, or expired session `req.cookie`. |
| `EMPTY_COLLECTION` | Collection loaded cleanly but contains zero tracks/subfolders. |
| `NO_PLAYABLE_STREAM` | Track has no playable stream or returned upstream error. |
| `RATE_LIMITED` | Upstream returned HTTP `429` or rate-limit payload. |
| `NETWORK_ERROR` | Upstream HTTP `4xx`/`5xx`, invalid JSON, or connectivity failure. |
| `PERMISSION_DENIED` | Host/scheme blocked by `permissions.hosts` (usually raised by host). |

---

## 8. Local Testing & Included Templates

Test extensions without rebuilding native apps using the zero-dependency Node.js 18+ runner in [`tools/mock-runner.mjs`](tools/mock-runner.mjs):

```bash
# Run built-in conformance suite across all fixtures & starter templates
node docs/extensions/tools/mock-runner.mjs --self-test

# Inspect canHandle & fetchCollection on your extension
node docs/extensions/tools/mock-runner.mjs docs/extensions/starter can-handle "https://example.com/playlist/demo_1"
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-collection "https://example.com/playlist/demo_1" --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-folder "folder_1" --path "Album/Disc 1" --depth 1
node docs/extensions/tools/mock-runner.mjs path/to/ext resolve-stream "track_1" --setting preferHighBitrate=true
```

More starter and test examples in this folder:
- Bundle starter: [`starter/manifest.json`](starter/manifest.json) + [`starter/index.js`](starter/index.js)
- Single-file starter: [`starter-single-file.musico.js`](starter-single-file.musico.js)
- Conformance fixtures: [`fixtures/basic-playlist.musico.js`](fixtures/basic-playlist.musico.js), [`fixtures/tree-source/`](fixtures/tree-source/manifest.json), [`fixtures/security-and-errors.musico.js`](fixtures/security-and-errors.musico.js)
