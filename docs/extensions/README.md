# ♪ Musico JavaScript Extensions Developer Guide (API v1) ♪

**English** | [简体中文](README.zh-CN.md) | [← Back to Root README](../../README.md)

Write one JavaScript extension in CommonJS format and run it natively inside **Musico** across **iOS / iPadOS / macOS** (`JavaScriptCore`), **Android** (`QuickJS`), and **Windows** (`Jint`) with zero WebViews `(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧`! One extension model powers three workflows seamlessly:

1. **Custom Cloud Sources** (`"playlist"`, `"tree"`, `"stream"`): Shared `CloudSourceProvider` pipeline with YouTube, Bilibili, and Google Drive — automatically getting **Save to Local Folder**, **Update from Cloud**, **Playlists**, **Offline Cache**, and custom source badge pills.
2. **Extension-Only Cloud Search** (`"search"`): Opt-in search tab in **Songs** with conditional source switcher (stays hidden with zero `"search"` extensions enabled), ephemeral playback excluded from Library & Stats, single-track **Save to Local Folder…**, and playlist/album preview sheets with **Add as Cloud Folder**.
3. **Custom Cloud Lyrics Providers** (`"lyrics"`): Plugs directly into Musico's on-demand **Search Online…** lyrics picker alongside built-in **LRCLIB** and **NetEase**!

---

## 1. Quick Start — Minimal Cloud Source Example

The fastest way to ship an extension is a single **`.musico.js`** script exporting both `manifest` and your hooks. Save the example below as `minimal-source.musico.js`, test it locally with Node.js, or import it directly in **Musico → Extensions**.

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

## 3. Cloud Search Extension Example (`"search"` + `search`)

Want your extension to show up in Musico's opt-in **Search Source Switcher** inside **Songs**? Declare `"search"` in `capabilities`, export `search(ctx, req)`, and optionally narrow what users can filter with `searchKinds` (`["track", "playlist", "album"]`).

- If your extension **only searches and plays tracks** (`["search", "stream"]`), `urlPatterns`, `canHandle`, and `fetchCollection` can be omitted!
- If your search also returns playlists or albums and you want tapping a collection to open Musico's lightweight preview sheet + **Add as Cloud Folder**, also declare `"playlist"` (or `"tree"`), include `urlPatterns`, implement `canHandle` / `fetchCollection`, and return `sourceUrl` on collection items.

```js
'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.example.musico.cloudsearch',
  name: 'Example Search',
  version: '1.0.0',
  badgeLabel: 'ExSearch',
  capabilities: ['search', 'stream', 'cookie'],
  searchKinds: ['track', 'playlist', 'album'], // optional; defaults to all three
  urlPatterns: [], // optional/empty allowed when "playlist" and "tree" are omitted
  permissions: {
    hosts: ['api.music.example.com', '*.cdn.music.example.com']
  }
};

async function search(ctx, req) {
  // req = { query, kind: "all" | "track" | "playlist" | "album", limit, cursor?, cookie? }
  var headers = { Accept: 'application/json' };
  if (req.cookie) headers.Cookie = req.cookie; // from Extension Manager's masked Cookie field

  var apiUrl =
    'https://api.music.example.com/v1/search?q=' + encodeURIComponent(req.query) +
    '&kind=' + encodeURIComponent(req.kind || 'all') +
    '&limit=' + encodeURIComponent(String(req.limit || 20)) +
    (req.cursor ? '&cursor=' + encodeURIComponent(req.cursor) : '');

  var res = await ctx.http.request({ method: 'GET', url: apiUrl, headers: headers, timeoutMs: 12000 });
  if (res.status === 401 || res.status === 403) {
    var authErr = new Error('Please configure your cookie in Extension Manager.');
    authErr.code = 'AUTH_REQUIRED';
    throw authErr;
  }
  if (res.status >= 400) {
    var netErr = new Error('HTTP ' + String(res.status));
    netErr.code = 'NETWORK_ERROR';
    throw netErr;
  }

  var data = JSON.parse(res.text || '{}');
  var items = (data.items || []).map(function (it) {
    if (it.kind === 'playlist' || it.kind === 'album') {
      return {
        kind: it.kind,
        remoteId: String(it.id),
        title: String(it.title || 'Untitled'),
        artist: it.artist || null,
        artworkUrl: it.coverHttpsUrl || null, // https:// thumbnails are cached in memory only
        sourceUrl: it.url || null,            // passed to fetchCollection for preview & Add Folder
        trackCount: typeof it.trackCount === 'number' ? it.trackCount : null
      };
    }
    return {
      kind: 'track',
      remoteTrackId: String(it.id),
      title: String(it.title || 'Untitled'),
      artist: it.artist || 'Unknown Artist',
      album: it.album || null,
      durationSec: typeof it.durationSec === 'number' ? it.durationSec : 0,
      artworkUrl: it.coverHttpsUrl || null,
      relativePath: it.fileHint || null
    };
  });

  return {
    items: items,
    nextCursor: data.nextCursor ? String(data.nextCursor) : null
  };
}

async function resolveStream(ctx, req) {
  var headers = { Accept: 'application/json' };
  if (req.cookie) headers.Cookie = req.cookie;
  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://api.music.example.com/v1/tracks/' + encodeURIComponent(req.remoteTrackId) + '/stream',
    headers: headers
  });
  var stream = JSON.parse(res.text || '{}');
  return {
    url: String(stream.url),
    fileExtension: stream.ext || 'mp3',
    mimeType: stream.mime || 'audio/mpeg'
  };
}

module.exports = {
  manifest: manifest,
  search: search,
  resolveStream: resolveStream
};
```

> **How Musico treats search results & playback ♪(´▽｀):**
> - Searches run **only on explicit submit** (`Enter` or Search button) and automatically cancel in-flight calls if the user edits and submits a new query or switches sources.
> - Musico deduplicates paginated results by `(kind, itemID)` and stops pagination automatically if `nextCursor === prevCursor`.
> - Played search tracks enter the ephemeral registry (`extsearch:<extensionId>:<remoteTrackId>`), stay **excluded from Library tabs & listening Stats**, and auto-prune once no queue references them — unless the user explicitly saves a song via **Save to Local Folder…**.

---

## 4. Cloud Lyrics Extension Example (`"lyrics"` + `searchLyrics`)

Want to plug a custom lyrics source straight into **Lyrics → Search Online…** alongside **LRCLIB** and **NetEase**? Declare `"lyrics"` in `capabilities` and export `searchLyrics(ctx, req)` — no `urlPatterns` or `canHandle` required!

```js
'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.example.musico.lyrics',
  name: 'Example Lyrics',
  version: '1.0.0',
  badgeLabel: 'ExLyrics',
  capabilities: ['lyrics'],
  permissions: {
    hosts: ['lyrics.example.com']
  }
};

async function searchLyrics(ctx, req) {
  // req = { title, artist, album?, durationSec? }
  var q = encodeURIComponent((req.title || '') + ' ' + (req.artist || ''));
  var res = await ctx.http.request({
    method: 'GET',
    url: 'https://lyrics.example.com/v1/search?q=' + q,
    headers: { Accept: 'application/json' },
    timeoutMs: 12000
  });
  if (res.status >= 400) {
    var err = new Error('Lyrics API HTTP ' + String(res.status));
    err.code = 'NETWORK_ERROR';
    throw err;
  }

  var rows = JSON.parse(res.text || '[]');
  return rows.map(function (row) {
    return {
      externalId: row.id ? String(row.id) : null,
      title: row.title || req.title,
      artist: row.artist || req.artist,
      album: row.album || null,
      durationSec: typeof row.durationSec === 'number' ? row.durationSec : null,
      syncedLyrics: row.lrc || null,
      plainLyrics: row.plain || null,
      translatedLyrics: row.translationLrc || null,
      instrumental: Boolean(row.instrumental)
    };
  });
}

module.exports = {
  manifest: manifest,
  searchLyrics: searchLyrics
};
```

> **Note:** Musico runs `searchLyrics` **only when the user opens Search Online…** (never automatically during playback) and waits for explicit user confirmation before writing lyrics locally. Both bare arrays (`[...]`) and object envelopes (`{ candidates: [...] }` / `{ results: [...] }`) are accepted across Apple, Android, and Windows.

---

## 5. Packaging Formats

| Format | Structure | Best For |
|---|---|---|
| **Single-File (`.musico.js`)** | One JS file exporting `{ manifest, ...hooks }` on `module.exports`. | Simple scripts, quick sharing, single-file starters (e.g. [`starter-single-file.musico.js`](starter-single-file.musico.js)). |
| **Bundle Archive (`.musicoext`)** | Standard `.zip` containing `manifest.json`, `index.js` (or `manifest.entry`), and optional `icon.png`. | Extensions with separate assets or multi-file packaging (e.g. [`starter/`](starter/manifest.json)). |

Package the included starter directory into a `.musicoext` bundle:

```bash
(cd docs/extensions/starter && zip -r ../../starter-example.musicoext manifest.json index.js)
```

> **Install locations inside Musico:** `<AppData>/Extensions/<id>/` (`.\Data\Extensions\<id>\` in Windows Portable mode). ZIP bundles are validated for path traversal (`../`, absolute paths), max 256 entries, and max 16 MB uncompressed size.

---

## 6. `manifest.json` Reference

| Field | Type | Required | Constraints & Notes |
|---|---|---|---|
| `apiVersion` | `integer` | Yes | Must be `1`. |
| `id` | `string` | Yes | Reverse-DNS ID matching `^[a-z0-9._-]{3,64}$`. Stored on roots as `providerID = "ext.<id>"`. |
| `name` | `string` | Yes | Display name in **Extensions**, **Add Cloud Folder**, and **Search Source Switcher** / **Search Online…** (fallback). |
| `version` | `string` | Yes | SemVer (`MAJOR.MINOR.PATCH`). |
| `author` | `string` | No | Optional author name shown in Extension Manager. |
| `description` | `string` | No | Short description shown in Extension Manager. |
| `badgeLabel` | `string` | No | Short pill badge in **Folders**, **Songs** search chips, and lyrics badges (defaults to `name`, max 16 chars). |
| `entry` | `string` | No | Relative script path inside `.musicoext` (defaults to `"index.js"`). |
| `updateUrl` | `string` | No | Optional HTTPS URL to a `.musicoext` package used for automatic and manual extension updates (falls back to the link import URL if omitted). |
| `capabilities` | `string[]` | Yes | Non-empty subset of `"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`, `"lyrics"`, `"search"`, `"qr_login"`. |
| `urlPatterns` | `string[]` | Conditional | Native regex strings tested before starting a JS VM. **Required** when `"playlist"` or `"tree"` is declared; optional/empty allowed otherwise. |
| `searchKinds` | `string[]` | No | Subset of `"track"`, `"playlist"`, `"album"`. Controls available filter pills when `"search"` is declared (defaults to all three when omitted/empty). |
| `permissions.hosts` | `string[]` | Conditional | Strict HTTP/HTTPS allowlist: exact domain (`example.com`) or `*.sub.domain` (≥ 2 domain labels after `*.`; bare `*` and `*.tld` are rejected). May be empty when `settings` includes a `"server_url"` entry. |
| `qualities` | `array` | No | Optional list (max 16) of selectable audio qualities `{ id, label, badge?, lossless? }`. When omitted/empty, Musico hides quality pickers. |
| `defaultStreamQuality` | `string` | No | Optional quality `id` from `qualities` used as the default for live playback (`"standard"` when omitted). |
| `defaultDownloadQuality` | `string` | No | Optional quality `id` from `qualities` used as the default for Save to Local / Export (`"highest"` when omitted). |
| `settings` | `array` | No | List of `{ key, label, type, default, options? }` where `type` is `"boolean"`, `"string"`, `"select"`, or `"server_url"`. Select `options` may be strings (`["us", "jp"]`) or `{ value, label }` objects. Setting `type: "server_url"` allows the exact `http://` or `https://` hostname configured by the user at runtime. |

---

## 7. Hook Contracts & Schemas (`module.exports`)

- **`canHandle(input)`** *(sync — required when `"playlist"` or `"tree"` is declared)*
  - **Input:** `{ rawText, extractedUrl }`
  - **Returns:** `{ matched, normalizedUrl?, remoteId?, kind?: "playlist" | "folder" | "track" }`
- **`fetchCollection(ctx, req)`** *(async — required for `"playlist"` or `"tree"`; also called by ephemeral search previews for `"playlist"`/`"album"` items with `sourceUrl`)*
  - **Input:** `{ url, cookie?, cursor? }`
  - **Returns:** `{ remoteId, title, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`fetchFolder(ctx, req)`** *(async — required when `"tree"` capability is declared)*
  - **Input:** `{ folderId, pathSegments, depth, cookie?, cursor?, extra? }`
  - **Returns:** `{ folderId, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`resolveStream(ctx, req)`** *(async — required when `"stream"` capability is declared; receives extension cookie on ephemeral tracks or root cookie on mounted Cloud Folders)*
  - **Input:** `{ remoteTrackId, relativePath?, cookie?, forceRefresh, quality?, purpose?: "stream" | "download" }`
  - **Returns:** `{ url, headers?, fileExtension, mimeType?, expiresAtMs?, fallbackUrls?, contentLength?, actualQuality?, qualityLabel?, bitrateKbps? }`
- **`search(ctx, req)`** *(async — required when `"search"` capability is declared)*
  - **Input:** `{ query, kind: "all" | "track" | "playlist" | "album", limit, cursor?, cookie? }`
  - **Returns:** `{ items: ExtSearchResult[], nextCursor?: string | null }` (or `null` when empty)
- **`searchLyrics(ctx, req)`** *(async — required when `"lyrics"` capability is declared)*
  - **Input:** `{ title, artist, album?, durationSec? }`
  - **Returns:** `ExtLyricsCandidate[]` | `{ candidates: ExtLyricsCandidate[] }` | `{ results: ExtLyricsCandidate[] }` | `null`
- **`startQrLogin(ctx, req)`** *(async — required when `"qr_login"` capability is declared)*
  - **Input:** `{}`
  - **Returns:** `{ sessionId, qrContent, expiresAtMs?, pollIntervalMs?, message? }` (`qrContent` ≤ 512 UTF-8 bytes; `pollIntervalMs` clamped to `1000`–`10000` ms, default `2000`)
- **`pollQrLogin(ctx, req)`** *(async — required when `"qr_login"` capability is declared)*
  - **Input:** `{ sessionId }`
  - **Returns:** `{ status: "pending" | "scanned" | "confirmed" | "expired" | "error", cookie?, accountName?, message? }` — when `status === "confirmed"`, `cookie` must be non-empty and is persisted directly into the extension's cookie store without logging

### `ExtSearchResult` Schema
- **`kind`** *(required)*: `"track"` | `"playlist"` | `"album"`
- **`remoteTrackId`**: Required string when `kind === "track"` *(note: Apple requires `remoteTrackId` specifically, while Android/Windows also accept numeric IDs or fallback `id` — always emit string `remoteTrackId` for cross-platform reliability!)*
- **`remoteId`**: Required string when `kind === "playlist"` or `"album"`
- **`title`** *(required)*: Non-empty string
- **Optional fields:** `artist?`, `album?`, `durationSec?` (seconds), `artworkUrl?` (`https://` cached in-memory only), `sourceUrl?` (passed to `fetchCollection` when previewing/mounting a playlist or album), `relativePath?` (forwarded to `resolveStream`), `trackCount?` (displayed on playlist/album rows)

### `ExtLyricsCandidate` Schema
- `externalId?` (`string`)
- `title?` (`string`), `artist?` (`string`), `album?` (`string`), `durationSec?` (`number`)
- `syncedLyrics?` (`string`, LRC format), `plainLyrics?` (`string`), `translatedLyrics?` (`string`, LRC or plain translation), `instrumental?` (`boolean`)

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

## 8. Host Bridge API (`ctx`), Cookies & Security Guardrails

- **`await ctx.http.request({ method?, url, headers?, body?, followRedirects?, timeoutMs?, returnBytes? })`**
  - Routes through native HTTP clients (preserving platform timeouts and Windows proxy settings when `"proxy"` capability is declared).
  - Returns `{ status, finalUrl, headers, setCookies?, text, bytesBase64? }` with lowercase header keys (`setCookies` preserves individual `Set-Cookie` entries cleanly without HTTP comma-joining; response body capped at 8 MB; `bytesBase64` included when `returnBytes === true` or body ≤ 1 MB).
  - Enforces `permissions.hosts` on the initial URL **and every redirect hop** (up to 10 hops; strips `Cookie` & `Authorization` headers across host changes; non-`http`/`https` schemes reject with `PERMISSION_DENIED`).
  - Also validates `resolveStream`'s returned `url` and `fallbackUrls` against `permissions.hosts`.
- **`ctx.crypto`**: `md5(str)`, `sha1(str)`, `sha256(str)`, `hmacSha256(key, msg)` *(all lowercase hex)*, `base64Encode(str)`, `base64Decode(b64)`.
- **`ctx.kv`**: Per-extension persistent key-value store: `get(key) -> string | null`, `set(key, val, ttlSeconds?)`, `remove(key)`.
- **`ctx.settings.get(key)`**: Returns the user's configured value (or manifest default, or `null`).
- **`ctx.env`**: `{ platform: "ios" | "macos" | "android" | "windows", appVersion, apiVersion: 1 }`.
- **`ctx.log`**: `info(msg)`, `warn(msg)`, `error(msg)` stored in a 200-entry ring buffer visible in Extension Manager.

### Extension-Level Cookies & Privacy
- Extensions declaring `"cookie"` or `"qr_login"` get a masked **Extension Cookie** input in **Extensions** (Extension Manager), alongside existing per-folder cookies on mounted Cloud Folders; `"qr_login"` extensions also show an in-app **Sign In with QR Code** action that runs `startQrLogin` / `pollQrLogin` and saves the returned cookie automatically.
- Musico passes this extension-level cookie via `req.cookie` to `search(ctx, req)`, ephemeral preview `fetchCollection(ctx, req)`, and root-less `resolveStream(ctx, req)` for ephemeral tracks.
- For mounted Cloud Folders, each `LibraryRoot` keeps its own strictly isolated cookie that takes precedence over the extension-level cookie.
- **Never log `req.cookie` via `ctx.log`!** *(As defense-in-depth, Android and Windows also automatically redact raw and URL-encoded cookie strings from `ctx.log` entries.)*

### Guardrails & Compatibility Tips
- **Fresh Isolated VM Per Call:** Global JS variables do not persist across calls. Use `ctx.kv` for intentional short-lived token caching.
- **Resource & Network Limits:** `15s` cap on `canHandle`, `resolveStream`, `search`, and `searchLyrics` (with automatic cancellation when a new query is submitted or search source switches), `60s` cap on `fetchCollection` / `fetchFolder`, `32 MB` heap limit on QuickJS (Android) & Jint (Windows), and `https://`-only in-memory LRU caching for search/preview thumbnails (`artworkUrl`).
- **Portable JS Subset:** Stick to **ES2020 + CommonJS `module.exports` + `ctx.*`**. Avoid Node-only modules (`require`, `fs`, `Buffer`) and browser DOM globals (`window`, `document`).

---

## 9. Standard Error Codes

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

## 10. Local Testing & Included Templates

Test extensions without rebuilding native apps using the zero-dependency Node.js 18+ runner in [`tools/mock-runner.mjs`](tools/mock-runner.mjs):

```bash
# Run built-in conformance suite across all fixtures & starter templates
node docs/extensions/tools/mock-runner.mjs --self-test

# Inspect cloud source, search & lyrics hooks on your extension
node docs/extensions/tools/mock-runner.mjs docs/extensions/starter can-handle "https://example.com/playlist/demo_1"
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-collection "https://example.com/playlist/demo_1" --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-folder "folder_1" --path "Album/Disc 1" --depth 1
node docs/extensions/tools/mock-runner.mjs path/to/ext resolve-stream "track_1" --setting preferHighBitrate=true
node docs/extensions/tools/mock-runner.mjs path/to/ext search "Yoasobi" --kind track --limit 20 --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext search-lyrics "Idol" --artist "YOASOBI" --duration 213
node docs/extensions/tools/mock-runner.mjs path/to/ext start-qr-login
node docs/extensions/tools/mock-runner.mjs path/to/ext poll-qr-login "<sessionId>"
```

More starter and test examples in this folder:
- Bundle starter: [`starter/manifest.json`](starter/manifest.json) + [`starter/index.js`](starter/index.js)
- Single-file starter: [`starter-single-file.musico.js`](starter-single-file.musico.js)
- Conformance fixtures: [`fixtures/basic-playlist.musico.js`](fixtures/basic-playlist.musico.js), [`fixtures/tree-source/`](fixtures/tree-source/manifest.json), [`fixtures/security-and-errors.musico.js`](fixtures/security-and-errors.musico.js)
