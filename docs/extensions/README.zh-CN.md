# Musico JavaScript 扩展开发指南（API v1）

[English](README.md) | **简体中文**

只需编写一份 CommonJS JavaScript 扩展，即可在 **iOS / iPadOS / macOS**（`JavaScriptCore`）、**Android**（`QuickJS`）和 **Windows**（`Jint`）三大平台上零 WebView 原生运行。扩展与内置的 YouTube、Bilibili、Google Drive 共用同一套 `CloudSourceProvider` 管线，自动获得 **存到本地（Save to Local Folder）**、**从云端更新（Update from Cloud）**、播放列表、离线音频缓存和自定义来源标签等全部能力。

---

## 1. 快速上手 —— 最小可运行示例

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

## 3. 打包格式

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

## 4. `manifest.json` 字段规范

| 字段 | 类型 | 必填 | 约束与说明 |
|---|---|---|---|
| `apiVersion` | `integer` | 是 | 固定填 `1`。 |
| `id` | `string` | 是 | 反向域名风格唯一 ID，须符合 `^[a-z0-9._-]{3,64}$`；曲库内部保存为 `providerID = "ext.<id>"`。 |
| `name` | `string` | 是 | 在 **扩展管理** 与 **添加云端文件夹** 中展示的名称。 |
| `version` | `string` | 是 | 语义化版本号（`MAJOR.MINOR.PATCH`）。 |
| `author` | `string` | 否 | 作者名，展示在扩展详情中。 |
| `description` | `string` | 否 | 一句话简介。 |
| `badgeLabel` | `string` | 否 | 在 **Folders** 展示的来源短标签（默认取 `name`，最多 16 个字符）。 |
| `entry` | `string` | 否 | `.musicoext` 包内的入口 JS 路径（默认为 `"index.js"`）。 |
| `capabilities` | `string[]` | 是 | 支持声明 `"playlist"`、`"tree"`、`"stream"`、`"cookie"`、`"proxy"`。 |
| `urlPatterns` | `string[]` | 是 | 原生端先做预筛选的正则表达式列表，未命中时不启动 JS 引擎。 |
| `permissions.hosts` | `string[]` | 是 | 严格的 HTTP/HTTPS 域名白名单：支持精确域名（`example.com`）或二级以上通配子域（`*.sub.domain`，禁止裸 `*` 或 `*.com`）。 |
| `settings` | `array` | 否 | 配置项列表 `{ key, label, type, default, options? }`，`type` 支持 `"boolean"`、`"string"`、`"select"`；`select` 的 `options` 支持纯字符串或 `{ value, label }` 对象。 |

---

## 5. JS 钩子与曲目字段映射（`module.exports`）

- **`canHandle(input)`** *（同步，必填）*
  - **入参：** `{ rawText, extractedUrl }`
  - **返回：** `{ matched, normalizedUrl?, remoteId?, kind?: "playlist" | "folder" | "track" }`
- **`fetchCollection(ctx, req)`** *（异步，声明 `"playlist"` 或 `"tree"` 时必填）*
  - **入参：** `{ url, cookie?, cursor? }`
  - **返回：** `{ remoteId, title, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`fetchFolder(ctx, req)`** *（异步，声明 `"tree"` 时必填）*
  - **入参：** `{ folderId, pathSegments, depth, cookie?, cursor?, extra? }`
  - **返回：** `{ folderId, tracks: ExtTrack[], subfolders?: ExtSubfolder[], nextCursor? }`
- **`resolveStream(ctx, req)`** *（异步，声明 `"stream"` 时必填）*
  - **入参：** `{ remoteTrackId, relativePath?, cookie?, forceRefresh }`
  - **返回：** `{ url, headers?, fileExtension, mimeType?, expiresAtMs?, fallbackUrls?, contentLength? }`

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

## 6. 宿主桥接 API（`ctx`）与安全限制

- **`await ctx.http.request({ method?, url, headers?, body?, followRedirects?, timeoutMs?, returnBytes? })`**
  - 走各平台原生 HTTP 客户端（沿用平台超时与 Windows 代理设置，需声明 `"proxy"` 能力）。
  - 返回 `{ status, finalUrl, headers, text, bytesBase64? }`（响应头键名统一小写；响应体上限 8 MB；当 `returnBytes === true` 或响应体 ≤ 1 MB 时返回 `bytesBase64`）。
  - 初始 URL **以及每一跳 3xx 重定向**都会校验 `permissions.hosts`（最多 10 跳；跨主机跳转自动剥离 `Cookie` 与 `Authorization`；非 `http`/`https` 协议直接抛出 `PERMISSION_DENIED`）。
  - `resolveStream` 返回的 `url` 与 `fallbackUrls` 初始地址同样会校验 `permissions.hosts`。
- **`ctx.crypto`**：`md5(str)`、`sha1(str)`、`sha256(str)`、`hmacSha256(key, msg)` *（均返回小写十六进制）*、`base64Encode(str)`、`base64Decode(b64)`。
- **`ctx.kv`**：按扩展隔离的本地 KV 缓存：`get(key) -> string | null`、`set(key, val, ttlSeconds?)`、`remove(key)`。
- **`ctx.settings.get(key)`**：读取用户在扩展管理中配置的设置值（未修改时回退到 manifest 默认值）。
- **`ctx.env`**：`{ platform: "ios" | "macos" | "android" | "windows", appVersion, apiVersion: 1 }`。
- **`ctx.log`**：`info(msg)`、`warn(msg)`、`error(msg)`，写入每个扩展独立保留最近 200 条的环形日志缓冲区。

### 安全与兼容性要点
- **每次调用独立沙箱：** 每次执行钩子都会新建隔离实例（Cookie 仅传入当前 `LibraryRoot` 自身的 Cookie），请勿依赖 JS 全局变量跨次持久化状态，短期令牌缓存请使用 `ctx.kv`。
- **超时与内存上限：** `canHandle` / `resolveStream` 上限 `15s`，`fetchCollection` / `fetchFolder` 上限 `60s`；QuickJS（Android）与 Jint（Windows）强制限制 32 MB 内存上限。
- **跨引擎 JS 写法建议：** 请只使用 **标准 ES2020 + CommonJS `module.exports` + `ctx.*`**，避免依赖 Node 内置模块（`require`、`fs`、`Buffer`）或浏览器 DOM（`window`、`document`）。

---

## 7. 标准错误码

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

## 8. 本地测试工具与仓库模板

无需编译三个原生客户端，可直接用仓库自带的零依赖 Node.js 18+ 测试工具 [`tools/mock-runner.mjs`](tools/mock-runner.mjs) 调试扩展：

```bash
# 运行全部一致性样例与模板自检
node docs/extensions/tools/mock-runner.mjs --self-test

# 单独测试你的扩展
node docs/extensions/tools/mock-runner.mjs docs/extensions/starter can-handle "https://example.com/playlist/demo_1"
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-collection "https://example.com/playlist/demo_1" --cookie "sid=..."
node docs/extensions/tools/mock-runner.mjs path/to/ext fetch-folder "folder_1" --path "Album/Disc 1" --depth 1
node docs/extensions/tools/mock-runner.mjs path/to/ext resolve-stream "track_1" --setting preferHighBitrate=true
```

更多现成参考：
- Bundle 起步模板：[`starter/manifest.json`](starter/manifest.json) + [`starter/index.js`](starter/index.js)
- 单文件起步模板：[`starter-single-file.musico.js`](starter-single-file.musico.js)
- 跨平台测试样例：[`fixtures/basic-playlist.musico.js`](fixtures/basic-playlist.musico.js)、[`fixtures/tree-source/`](fixtures/tree-source/manifest.json)、[`fixtures/security-and-errors.musico.js`](fixtures/security-and-errors.musico.js)
