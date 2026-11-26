# ♪ Musico JavaScript 扩展开发指南（API v1）♪

[English](README.md) | **简体中文** | [← 返回主说明](../../README.zh-CN.md)

只需编写一份 CommonJS JavaScript 扩展，即可在 **iOS / iPadOS / macOS**（`JavaScriptCore`）、**Android**（`QuickJS`）和 **Windows**（`Jint`）三大平台上零 WebView 原生运行 `(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧`！同一套扩展接口同时驱动三种能力：

1. **自定义云端音源**（`"playlist"`、`"tree"`、`"stream"`）：与内置 YouTube、Bilibili、Google Drive 共用一套 `CloudSourceProvider` 管线，自动获得 **存到本地（Save to Local Folder）**、**从云端更新（Update from Cloud）**、播放列表、离线音频缓存和自定义来源标签。
2. **扩展专属云端搜索**（`"search"`）：在 **歌曲（Songs）** 页面提供按需开启的 **搜索源切换器**（未启用任何 `"search"` 扩展时完全隐藏），支持临时队列试听（不混入曲库与听歌统计）、单曲 **存到本地**，以及歌单/专辑轻量预览 + 一键 **添加为云端文件夹**。
3. **自定义云端歌词源**（`"lyrics"`）：直接接入 **在线搜索歌词（Search Online…）** 选择器，与内置的 **LRCLIB**、**网易云音乐** 并列展示！

---

## 1. 快速上手 —— 最小云端音源示例

最轻量的扩展形式是一个 **`.musico.js`** 单文件脚本，在 `module.exports` 中同时导出 `manifest` 与钩子函数。将下面的代码保存为 `minimal-source.musico.js`，即可直接在 **Musico → 扩展（Extensions）** 中导入，或使用本地 Node 工具调试。

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

// 1. 同步链接识别（仅在原生 urlPatterns 正则预匹配命中后才会被调用）
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

// 2. 异步拉取歌单 / 收藏列表（支持 cursor 游标分页与 Cookie 隔离）
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

// 3. 异步解析真实音频流（播放、缓存或导出到本地时调用）
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

用内置 Node.js 工具立即验证你的脚本：

```bash
node docs/extensions/tools/mock-runner.mjs path/to/minimal-source.musico.js can-handle "https://music.example.com/playlist/demo_1"
```

---

## 2. 最小树形目录 / 懒加载示例（`"tree"` + `fetchFolder`）

若你的音源像网盘一样包含多层子文件夹，只需在 `capabilities` 中声明 `"tree"`，让 `fetchCollection` 返回 `subfolders`，并实现 `fetchFolder(ctx, req)`：

```js
// 在 manifest.capabilities 中声明 ["tree", "stream"]
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

Musico 会在后台按优先级逐层抓取目录树并在界面实时刷新；当用户点开某个子文件夹时，会自动优先加载该分支。

---

## 3. 云端搜索扩展示例（`"search"` + `search`）

想让你的扩展出现在 **歌曲（Songs）** 页面的按需搜索源切换器里吗？在 `capabilities` 中声明 `"search"`、导出 `search(ctx, req)` 即可，还可以用可选的 `searchKinds`（`["track", "playlist", "album"]`）定制支持的结果分类：

- 若你的扩展 **只做搜索与单曲试听**（`["search", "stream"]`），可以完全省略 `urlPatterns`、`canHandle` 与 `fetchCollection`。
- 若搜索结果还包含歌单或专辑，且希望用户点开时能弹出轻量预览页并一键 **添加为云端文件夹（Add as Cloud Folder）**，请同时声明 `"playlist"`（或 `"tree"`）、填写 `urlPatterns`、导出 `canHandle` / `fetchCollection`，并在集合条目中返回 `sourceUrl`。

```js
'use strict';

var manifest = {
  apiVersion: 1,
  id: 'com.example.musico.cloudsearch',
  name: 'Example Search',
  version: '1.0.0',
  badgeLabel: 'ExSearch',
  capabilities: ['search', 'stream', 'cookie'],
  searchKinds: ['track', 'playlist', 'album'], // 可选；缺省时默认支持全部三种
  urlPatterns: [], // 未声明 "playlist" / "tree" 时可留空或省略
  permissions: {
    hosts: ['api.music.example.com', '*.cdn.music.example.com']
  }
};

async function search(ctx, req) {
  // req = { query, kind: "all" | "track" | "playlist" | "album", limit, cursor?, cookie? }
  var headers = { Accept: 'application/json' };
  if (req.cookie) headers.Cookie = req.cookie; // 来自扩展管理页的遮罩 Cookie 输入框

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
        artworkUrl: it.coverHttpsUrl || null, // https:// 缩略图仅走内存缓存，不写入磁盘曲库封面
        sourceUrl: it.url || null,            // 用于预览弹窗及“添加为云端文件夹”的 fetchCollection
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

> **Musico 对搜索结果与临时试听的贴心处理 ♪(´▽｀)：**
> - 搜索仅在用户显式提交（回车或点击搜索）时执行，若用户输入新关键词或切换搜索源会自动取消上一条未完成请求。
> - 宿主会自动按 `(kind, itemID)` 去重，并在 `nextCursor === prevCursor` 时安全终止翻页。
> - 播放搜索单曲会进入临时曲目表（`extsearch:<extensionId>:<remoteTrackId>`），**不会混入本地曲库标签页或听歌统计**，队列不再引用后自动清理；只有当用户点 **Save to Local Folder…** 时才正式下载到本地曲库。

---

## 4. 云端歌词扩展示例（`"lyrics"` + `searchLyrics`）

想把自己的歌词接口接到 **歌词 → 在线搜索歌词…** 里，和内置的 **LRCLIB**、**网易云音乐** 并肩工作吗？只需声明 `capabilities: ['lyrics']` 并导出 `searchLyrics(ctx, req)`，同样无需填写 `urlPatterns` 或 `canHandle`：

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

> **提示：** `searchLyrics` **仅在用户主动打开在线搜索歌词时调用**（切歌播放时绝不在后台偷偷联网），并且只有用户点击确认使用后才会写入本地。三大平台均同时兼容直接返回候选数组 `[...]` 或 `{ candidates: [...] }` / `{ results: [...] }` 包装对象。

---

## 5. 打包格式

| 格式 | 结构要求 | 适用场景 |
|---|---|---|
| **单文件脚本（`.musico.js`）** | 单个 JS 文件，在 `module.exports` 中同时导出 `manifest` 与钩子函数。 | 轻量脚本、快速分发（示例：[`starter-single-file.musico.js`](starter-single-file.musico.js)）。 |
| **扩展压缩包（`.musicoext`）** | 标准 `.zip` 包，包含 `manifest.json`、`index.js`（或 `manifest.entry`）以及可选 `icon.png`。 | 携带图标或独立配置的扩展包（示例：[`starter/`](starter/manifest.json)）。 |

将 `starter/` 目录打包成 `.musicoext`：

```bash
(cd docs/extensions/starter && zip -r ../../starter-example.musicoext manifest.json index.js)
```

> **安装目录：** 各平台均存放在应用数据目录下的 `Extensions/<id>/`（Windows 便携版为 `.\Data\Extensions\<id>\`）。ZIP 导入时会严格拦截 `../` 或绝对路径穿越、限制最多 256 个文件及 16 MB 解压总大小。

---

## 6. `manifest.json` 字段规范

| 字段 | 类型 | 必填 | 约束与说明 |
|---|---|---|---|
| `apiVersion` | `integer` | 是 | 固定填 `1`。 |
| `id` | `string` | 是 | 反向域名风格唯一 ID，须符合 `^[a-z0-9._-]{3,64}$`；曲库内部保存为 `providerID = "ext.<id>"`。 |
| `name` | `string` | 是 | 在 **扩展管理**、**添加云端文件夹** 与搜索/歌词源列表中的名称。 |
| `version` | `string` | 是 | 语义化版本号（`MAJOR.MINOR.PATCH`）。 |
| `author` | `string` | 否 | 作者名，展示在扩展详情中。 |
| `description` | `string` | 否 | 一句话简介。 |
| `badgeLabel` | `string` | 否 | 在 **Folders**、搜索切换标签及歌词来源胶囊上展示的短标签（默认取 `name`，最多 16 个字符）。 |
| `entry` | `string` | 否 | `.musicoext` 包内的入口 JS 路径（默认为 `"index.js"`）。 |
| `updateUrl` | `string` | 否 | 可选的 HTTPS `.musicoext` 更新包地址，用于自动与手动检查更新（省略时回退至链接导入时记录的原 URL）。 |
| `capabilities` | `string[]` | 是 | 非空数组，支持声明 `"playlist"`、`"tree"`、`"stream"`、`"cookie"`、`"proxy"`、`"lyrics"`、`"search"`、`"qr_login"`、`"web_login"`。 |
| `webLogin` | `object` | 条件必填 | 声明 `"web_login"` 时**必填**：`{ loginUrl, cookieDomains, requiredCookies?, userAgent? }`。`loginUrl` 须为 `https://`，`cookieDomains` 列出要捕获的 1–16 个 Cookie 域名，`requiredCookies` 可选指定用于识别登录成功的 Cookie 名称。 |
| `urlPatterns` | `string[]` | 条件必填 | 原生端先做预筛选的正则表达式列表。声明 `"playlist"` 或 `"tree"` 时**必填**；其余扩展（如纯 `"lyrics"`、纯 `"search"` 或 `"search" + "stream"`）可省略或留空。 |
| `searchKinds` | `string[]` | 否 | 支持 `"track"`、`"playlist"`、`"album"` 的子集，控制搜歌页面的分类筛选选项（省略或为空时默认开启全部三种）。 |
| `permissions.hosts` | `string[]` | 条件必填 | 严格的 HTTP/HTTPS 域名白名单：支持精确域名（`example.com`）或二级以上通配子域（`*.sub.domain`，禁止裸 `*` 或 `*.com`）。当 `settings` 包含 `"server_url"` 配置项时可留空。 |
| `qualities` | `array` | 否 | 可选的音质选项列表（最多 16 项）：`{ id, label, badge?, lossless? }`。省略或为空时宿主会隐藏音质选择器。 |
| `defaultStreamQuality` | `string` | 否 | 可选默认播放音质 ID（需存在于 `qualities` 中，省略时按 `"standard"` 处理）。 |
| `defaultDownloadQuality` | `string` | 否 | 可选默认下载/保存本地音质 ID（需存在于 `qualities` 中，省略时按 `"highest"` 处理）。 |
| `settings` | `array` | 否 | 配置项列表 `{ key, label, type, default, options? }`，`type` 支持 `"boolean"`、`"string"`、`"select"`、`"server_url"`；`select` 的 `options` 支持纯字符串或 `{ value, label }` 对象；`"server_url"` 会将用户填写的 `http://` 或 `https://` 服务器精确主机名动态加入扩展的网络白名单。 |

---

## 7. JS 钩子与数据结构规范（`module.exports`）

- **`canHandle(input)`** *（同步，声明 `"playlist"` 或 `"tree"` 时必填）*
  - **入参：** `{ rawText, extractedUrl }`
  - **返回：** `{ matched, normalizedUrl?, remoteId?, kind?: "playlist" | "folder" | "track" }`
- **`fetchCollection(ctx, req)`** *（异步，声明 `"playlist"` 或 `"tree"` 时必填；云端搜索歌单/专辑预览弹窗也会通过 `item.sourceUrl` 调用它）*
  - **入参：** `{ url, cookie?, cursor? }`
  - **返回：** `{ remoteId, title, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`fetchFolder(ctx, req)`** *（异步，声明 `"tree"` 时必填）*
  - **入参：** `{ folderId, pathSegments, depth, cookie?, cursor?, extra? }`
  - **返回：** `{ folderId, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`resolveStream(ctx, req)`** *（异步，声明 `"stream"` 时必填；临时试听传入扩展级 Cookie，已挂载云端文件夹传入各自独立的目录 Cookie）*
  - **入参：** `{ remoteTrackId, relativePath?, cookie?, forceRefresh, quality?, purpose?: "stream" | "download" }`
  - **返回：** `{ url, headers?, fileExtension, mimeType?, expiresAtMs?, fallbackUrls?, contentLength?, actualQuality?, qualityLabel?, bitrateKbps? }`
- **`search(ctx, req)`** *（异步，声明 `"search"` 时必填）*
  - **入参：** `{ query, kind: "all" | "track" | "playlist" | "album", limit, cursor?, cookie? }`
  - **返回：** `{ items: ExtSearchResult[], nextCursor?: string | null }`（无结果也可返回 `null`）
- **`searchLyrics(ctx, req)`** *（异步，声明 `"lyrics"` 时必填）*
  - **入参：** `{ title, artist, album?, durationSec? }`
  - **返回：** `ExtLyricsCandidate[]` | `{ candidates: ExtLyricsCandidate[] }` | `{ results: ExtLyricsCandidate[] }` | `null`
- **`startQrLogin(ctx, req)`** *（异步，声明 `"qr_login"` 时必填）*
  - **入参：** `{}`
  - **返回：** `{ sessionId, qrContent, expiresAtMs?, pollIntervalMs?, message? }`（`qrContent` 上限 512 UTF-8 字节；`pollIntervalMs` 限制在 `1000`–`10000` ms，默认 `2000`）
- **`pollQrLogin(ctx, req)`** *（异步，声明 `"qr_login"` 时必填）*
  - **入参：** `{ sessionId }`
  - **返回：** `{ status: "pending" | "scanned" | "confirmed" | "expired" | "error", cookie?, accountName?, message? }` —— 当 `status === "confirmed"` 时必须返回非空 `cookie`，宿主会自动保存到扩展 Cookie 且绝不写入日志

### `ExtSearchResult` 字段说明
- **`kind`** *（必填）*：`"track"` | `"playlist"` | `"album"`
- **`remoteTrackId`**：`kind === "track"` 时必填字符串 *(提示：Apple 端严格读取 `remoteTrackId` 字段，虽 Android/Windows 也兼容数字 ID 或回退 `id`，但全平台统一返回字符串 `remoteTrackId` 最稳妥！)*
- **`remoteId`**：`kind === "playlist"` 或 `"album"` 时必填字符串
- **`title`** *（必填）*：非空标题字符串
- **可选字段：** `artist?`、`album?`、`durationSec?`（单位：秒）、`artworkUrl?`（`https://` 走内存 LRU 缓存）、`sourceUrl?`（供歌单/专辑预览弹窗及“添加为云端文件夹”传给 `fetchCollection`）、`relativePath?`（透传给 `resolveStream`）、`trackCount?`（歌单/专辑曲目数）

### `ExtLyricsCandidate` 字段说明
- `externalId?`（`string`）
- `title?`（`string`）、`artist?`（`string`）、`album?`（`string`）、`durationSec?`（`number`）
- `syncedLyrics?`（LRC 时间轴文本）、`plainLyrics?`（纯文本歌词）、`translatedLyrics?`（翻译歌词，支持 LRC 或纯文本）、`instrumental?`（`boolean`）

### `ExtTrack` 跨平台映射表

| 扩展字段 | Apple (`CloudTrackInfo`) | Android (`CloudTrackMetadata`) | Windows (`CloudPlaylistItem`) |
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

## 8. 宿主桥接 API（`ctx`）、Cookie 与安全限制

- **`await ctx.http.request({ method?, url, headers?, body?, followRedirects?, timeoutMs?, returnBytes? })`**
  - 走各平台原生 HTTP 客户端（沿用平台超时与 Windows 代理设置，需声明 `"proxy"` 能力）。
  - 返回 `{ status, finalUrl, headers, setCookies?, text, bytesBase64? }`（响应头键名统一小写；`setCookies` 数组会单独保留每条 `Set-Cookie` 避免被逗号拼接；响应体上限 8 MB；当 `returnBytes === true` 或响应体 ≤ 1 MB 时返回 `bytesBase64`）。
  - 初始 URL **以及每一跳 3xx 重定向**都会校验 `permissions.hosts`（最多 10 跳；跨主机跳转自动剥离 `Cookie` 与 `Authorization`；非 `http`/`https` 协议直接抛出 `PERMISSION_DENIED`）。
  - `resolveStream` 返回的 `url` 与 `fallbackUrls` 初始地址同样会校验 `permissions.hosts`。
- **`ctx.crypto`**：`md5(str)`、`sha1(str)`、`sha256(str)`、`hmacSha256(key, msg)` *（均返回小写十六进制）*、`base64Encode(str)`、`base64Decode(b64)`。
- **`ctx.kv`**：按扩展隔离的本地 KV 缓存：`get(key) -> string | null`、`set(key, val, ttlSeconds?)`、`remove(key)`。
- **`ctx.settings.get(key)`**：读取用户在扩展管理中配置的设置值（未修改时回退到 manifest 默认值）。
- **`ctx.env`**：`{ platform: "ios" | "macos" | "android" | "windows", appVersion, apiVersion: 1 }`。
- **`ctx.log`**：`info(msg)`、`warn(msg)`、`error(msg)`，写入每个扩展独立保留最近 200 条的环形日志缓冲区。

### 扩展级 Cookie 与隐私保护
- 声明了 `"cookie"` 或 `"qr_login"` 能力的扩展会在 **扩展管理（Extensions）** 中获得一个遮罩隐藏的 **扩展级 Cookie（Extension Cookie）** 输入框（与已挂载云端文件夹各自的 Cookie 并存）；声明 `"qr_login"` 还会显示应用内 **扫码登录（QR Code Login）** 按钮，通过 `startQrLogin` / `pollQrLogin` 自动完成登录并保存 Cookie。
- Musico 会将此扩展级 Cookie 通过 `req.cookie` 传入 `search(ctx, req)`、搜索结果预览时的 `fetchCollection(ctx, req)`，以及临时曲目播放时的 `resolveStream(ctx, req)`。
- 对于已挂载到 **Folders** 的云端目录，各 `LibraryRoot` 依然保持严格隔离，且目录自己的 Cookie 优先级高于扩展级 Cookie。
- **切勿在 `ctx.log` 中打印 `req.cookie`！** *（作为纵深防御，Android 与 Windows 宿主还会自动将日志中意外出现的 Cookie 原文与 URL 编码变体替换为 `[REDACTED]`。）*

### 安全与兼容性要点
- **每次调用独立沙箱：** 每次执行钩子都会新建隔离实例，请勿依赖 JS 全局变量跨次持久化状态，短期令牌缓存请使用 `ctx.kv`。
- **超时、取消与缓存上限：** `canHandle`、`resolveStream`、`search` 与 `searchLyrics` 上限 `15s`（新搜歌或切换搜索源时会自动取消旧任务），`fetchCollection` / `fetchFolder` 上限 `60s`；QuickJS（Android）与 Jint（Windows）强制限制 32 MB 内存上限；搜索与预览列表的 `artworkUrl`（仅限 `https://`）只走内存 LRU 缓存，不污染本地磁盘封面库。
- **跨引擎 JS 写法建议：** 请只使用 **标准 ES2020 + CommonJS `module.exports` + `ctx.*`**，避免依赖 Node 内置模块（`require`、`fs`、`Buffer`）或浏览器 DOM（`window`、`document`）。

---

## 9. 标准错误码

抛出错误时请设置 `err.code`，各端会自动展示对应提示并正确处理鉴权/重试状态：

| 错误码 | 触发场景 |
|---|---|
| `INVALID_URL` | 输入链接无法解析出有效资源 ID。 |
| `AUTH_REQUIRED` | 资源需要登录态，或 `req.cookie` 缺失/失效。 |
| `EMPTY_COLLECTION` | 成功请求但未找到任何可播放曲目或子文件夹。 |
| `NO_PLAYABLE_STREAM` | 曲目无可用音频流地址。 |
| `RATE_LIMITED` | 上游接口返回 HTTP `429` 或触发限流。 |
| `NETWORK_ERROR` | 上游 HTTP `4xx`/`5xx`、响应 JSON 异常或网络失败。 |
| `PERMISSION_DENIED` | 请求或返回的音频地址超出 `permissions.hosts` 白名单。 |

---

## 10. 本地测试工具与仓库模板

无需编译三个原生客户端，可直接用仓库自带的零依赖 Node.js 18+ 测试工具 [`tools/mock-runner.mjs`](tools/mock-runner.mjs) 调试扩展：

```bash
# 运行全部一致性样例与模板自检
node docs/extensions/tools/mock-runner.mjs --self-test

# 单独测试你的云端音源、搜索与歌词钩子
node docs/extensions/tools/mock-runner.mjs docs/extensions/starter can-handle "https://example.com/playlist/demo_1"
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-collection "https://example.com/playlist/demo_1" --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-folder "folder_1" --path "Album/Disc 1" --depth 1
node docs/extensions/tools/mock-runner.mjs path/to/ext resolve-stream "track_1" --setting preferHighBitrate=true
node docs/extensions/tools/mock-runner.mjs path/to/ext search "Yoasobi" --kind track --limit 20 --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext search-lyrics "Idol" --artist "YOASOBI" --duration 213
node docs/extensions/tools/mock-runner.mjs path/to/ext start-qr-login
node docs/extensions/tools/mock-runner.mjs path/to/ext poll-qr-login "<sessionId>"
```

更多现成参考：
- Bundle 起步模板：[`starter/manifest.json`](starter/manifest.json) + [`starter/index.js`](starter/index.js)
- 单文件起步模板：[`starter-single-file.musico.js`](starter-single-file.musico.js)
- 跨平台测试样例：[`fixtures/basic-playlist.musico.js`](fixtures/basic-playlist.musico.js)、[`fixtures/tree-source/`](fixtures/tree-source/manifest.json)、[`fixtures/security-and-errors.musico.js`](fixtures/security-and-errors.musico.js)
