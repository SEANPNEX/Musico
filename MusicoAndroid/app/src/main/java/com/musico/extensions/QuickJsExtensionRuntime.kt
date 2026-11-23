package com.musico.extensions

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.musico.BuildConfig
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpRequest
import com.musico.cloud.CloudSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface ExtensionRuntimeHost {
    fun getSetting(extensionId: String, key: String): JsonElement?
    fun getKv(extensionId: String, key: String): String?
    fun setKv(extensionId: String, key: String, value: String, ttlMs: Long?)
    fun removeKv(extensionId: String, key: String)
    fun appendLog(extensionId: String, level: String, message: String)
}

object NoOpExtensionRuntimeHost : ExtensionRuntimeHost {
    override fun getSetting(extensionId: String, key: String): JsonElement? = null
    override fun getKv(extensionId: String, key: String): String? = null
    override fun setKv(extensionId: String, key: String, value: String, ttlMs: Long?) = Unit
    override fun removeKv(extensionId: String, key: String) = Unit
    override fun appendLog(extensionId: String, level: String, message: String) = Unit
}

class QuickJsExtensionRuntime(
    private val httpClient: CloudHttpClient,
    private val host: ExtensionRuntimeHost = NoOpExtensionRuntimeHost,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val shortTimeoutMs: Long = DEFAULT_SHORT_TIMEOUT_MS,
    private val longTimeoutMs: Long = DEFAULT_LONG_TIMEOUT_MS,
) {
    @Serializable
    private data class HookEnvelope(
        val ok: Boolean,
        val result: JsonElement? = null,
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class SingleFileExtractResult(
        val ok: Boolean,
        val manifest: ExtensionManifest? = null,
        val hasCanHandle: Boolean = false,
        val hasFetchCollection: Boolean = false,
        val hasFetchFolder: Boolean = false,
        val hasResolveStream: Boolean = false,
        val hasSearchLyrics: Boolean = false,
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class ExportValidationResult(
        val ok: Boolean,
        val hasCanHandle: Boolean = false,
        val hasFetchCollection: Boolean = false,
        val hasFetchFolder: Boolean = false,
        val hasResolveStream: Boolean = false,
        val hasSearchLyrics: Boolean = false,
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    data class ExtLyricsCandidateSpec(
        val externalId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationSec: Double? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val translatedLyrics: String? = null,
        val instrumental: Boolean? = null,
    )

    @Serializable
    private data class ExtLyricsSearchEnvelope(
        val candidates: List<ExtLyricsCandidateSpec>? = null,
        val results: List<ExtLyricsCandidateSpec>? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Evaluates a single-file `.musico.js` script in a zero-network, zero-bridge QuickJS instance
     * to extract and validate its exported `manifest` and verify hook functions.
     */
    suspend fun inspectSingleFile(scriptSource: String): ExtensionManifest {
        val wrapped = """
            (function() {
              var console = { log: function(){}, info: function(){}, warn: function(){}, error: function(){}, debug: function(){} };
              var module = { exports: {} };
              var exports = module.exports;
              try {
                (function(exports, module) {
            $scriptSource
                })(exports, module);
                var exported = module.exports || {};
                return JSON.stringify({
                  ok: true,
                  manifest: exported.manifest || null,
                  hasCanHandle: typeof exported.canHandle === 'function',
                  hasFetchCollection: typeof exported.fetchCollection === 'function',
                  hasFetchFolder: typeof exported.fetchFolder === 'function',
                  hasResolveStream: typeof exported.resolveStream === 'function',
                  hasSearchLyrics: typeof exported.searchLyrics === 'function'
                });
              } catch (err) {
                return JSON.stringify({
                  ok: false,
                  code: err && err.code ? String(err.code) : 'EXTENSION_RUNTIME_ERROR',
                  message: (err && err.message) ? String(err.message) : String(err)
                });
              }
            })();
        """.trimIndent()

        val raw = executeScript(
            timeoutMs = shortTimeoutMs,
            setupBridge = {},
            script = wrapped,
            sourceFileName = "inspect-single-file.js",
        )
        val parsed = runCatching { json.decodeFromString<SingleFileExtractResult>(raw) }.getOrElse { err ->
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Failed to parse extension manifest: ${err.message}",
                err,
            )
        }
        if (!parsed.ok) {
            throw CloudSourceException(
                CloudErrorCode.fromCodeString(parsed.code) ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                parsed.message ?: "Failed to evaluate extension script",
            )
        }
        val manifest = parsed.manifest
            ?: throw IllegalArgumentException("Single-file extension must export manifest on module.exports")
        val validated = ExtensionManifestValidator.validate(manifest)
        verifyRequiredHooks(
            manifest = validated,
            hasCanHandle = parsed.hasCanHandle,
            hasFetchCollection = parsed.hasFetchCollection,
            hasFetchFolder = parsed.hasFetchFolder,
            hasResolveStream = parsed.hasResolveStream,
            hasSearchLyrics = parsed.hasSearchLyrics,
        )
        return validated
    }

    /**
     * Verifies a bundle entry script exports the hooks required by [manifest] in a zero-network VM.
     */
    suspend fun verifyBundleExports(manifest: ExtensionManifest, scriptSource: String) {
        val wrapped = """
            (function() {
              var console = { log: function(){}, info: function(){}, warn: function(){}, error: function(){}, debug: function(){} };
              var module = { exports: {} };
              var exports = module.exports;
              try {
                (function(exports, module) {
            $scriptSource
                })(exports, module);
                var exported = module.exports || {};
                return JSON.stringify({
                  ok: true,
                  hasCanHandle: typeof exported.canHandle === 'function',
                  hasFetchCollection: typeof exported.fetchCollection === 'function',
                  hasFetchFolder: typeof exported.fetchFolder === 'function',
                  hasResolveStream: typeof exported.resolveStream === 'function',
                  hasSearchLyrics: typeof exported.searchLyrics === 'function'
                });
              } catch (err) {
                return JSON.stringify({
                  ok: false,
                  code: err && err.code ? String(err.code) : 'EXTENSION_RUNTIME_ERROR',
                  message: (err && err.message) ? String(err.message) : String(err)
                });
              }
            })();
        """.trimIndent()

        val raw = executeScript(
            timeoutMs = shortTimeoutMs,
            setupBridge = {},
            script = wrapped,
            sourceFileName = "${manifest.id}:verify.js",
        )
        val parsed = runCatching { json.decodeFromString<ExportValidationResult>(raw) }.getOrElse { err ->
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Failed to inspect extension exports: ${err.message}",
                err,
            )
        }
        if (!parsed.ok) {
            throw CloudSourceException(
                CloudErrorCode.fromCodeString(parsed.code) ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                parsed.message ?: "Failed to evaluate extension bundle",
            )
        }
        verifyRequiredHooks(
            manifest = manifest,
            hasCanHandle = parsed.hasCanHandle,
            hasFetchCollection = parsed.hasFetchCollection,
            hasFetchFolder = parsed.hasFetchFolder,
            hasResolveStream = parsed.hasResolveStream,
            hasSearchLyrics = parsed.hasSearchLyrics,
        )
    }

    suspend fun canHandle(
        extension: InstalledExtension,
        rawText: String,
        extractedUrl: String,
    ): ExtCanHandleResult {
        val inputJson = json.encodeToString(
            buildJsonObject {
                put("rawText", rawText)
                put("extractedUrl", extractedUrl)
            }
        )
        val raw = invokeHook(
            extension = extension,
            hookName = "canHandle",
            requestJson = inputJson,
            isAsync = false,
            timeoutMs = shortTimeoutMs,
        )
        return decodeEnvelope(raw)
    }

    suspend fun fetchCollection(
        extension: InstalledExtension,
        url: String,
        cookie: String? = null,
        cursor: String? = null,
    ): ExtCollectionResult {
        val reqJson = json.encodeToString(
            buildJsonObject {
                put("url", url)
                if (cookie != null) put("cookie", cookie) else put("cookie", JsonNull)
                if (cursor != null) put("cursor", cursor) else put("cursor", JsonNull)
            }
        )
        val raw = invokeHook(
            extension = extension,
            hookName = "fetchCollection",
            requestJson = reqJson,
            isAsync = true,
            timeoutMs = longTimeoutMs,
        )
        return decodeEnvelope(raw)
    }

    suspend fun fetchFolder(
        extension: InstalledExtension,
        folderId: String,
        pathSegments: List<String>,
        depth: Int,
        cookie: String? = null,
        cursor: String? = null,
        extra: JsonObject? = null,
    ): ExtFolderResult {
        val reqJson = json.encodeToString(
            buildJsonObject {
                put("folderId", folderId)
                put("pathSegments", json.parseToJsonElement(json.encodeToString(pathSegments)))
                put("depth", depth)
                if (cookie != null) put("cookie", cookie) else put("cookie", JsonNull)
                if (cursor != null) put("cursor", cursor) else put("cursor", JsonNull)
                if (extra != null) put("extra", extra) else put("extra", JsonNull)
            }
        )
        val raw = invokeHook(
            extension = extension,
            hookName = "fetchFolder",
            requestJson = reqJson,
            isAsync = true,
            timeoutMs = longTimeoutMs,
        )
        return decodeEnvelope(raw)
    }

    suspend fun resolveStream(
        extension: InstalledExtension,
        remoteTrackId: String,
        relativePath: String? = null,
        cookie: String? = null,
        forceRefresh: Boolean = false,
    ): ExtResolveStreamResult {
        val reqJson = json.encodeToString(
            buildJsonObject {
                put("remoteTrackId", remoteTrackId)
                if (relativePath != null) put("relativePath", relativePath) else put("relativePath", JsonNull)
                if (cookie != null) put("cookie", cookie) else put("cookie", JsonNull)
                put("forceRefresh", forceRefresh)
            }
        )
        val raw = invokeHook(
            extension = extension,
            hookName = "resolveStream",
            requestJson = reqJson,
            isAsync = true,
            timeoutMs = shortTimeoutMs,
        )
        val resolved = decodeEnvelope<ExtResolveStreamResult>(raw)
        val allowedHosts = extension.manifest.permissions.hosts
        HostAllowlistMatcher.assertAllowed(resolved.url, allowedHosts)
        resolved.fallbackUrls?.forEach { fb ->
            HostAllowlistMatcher.assertAllowed(fb, allowedHosts)
        }
        return resolved
    }

    suspend fun searchLyrics(
        extension: InstalledExtension,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Double? = null,
    ): List<ExtLyricsCandidateSpec> {
        val reqJson = json.encodeToString(
            buildJsonObject {
                put("title", title)
                put("artist", artist)
                if (!album.isNullOrBlank()) put("album", album)
                if (durationSec != null && durationSec.isFinite() && durationSec > 0.0) {
                    put("durationSec", durationSec)
                }
            }
        )
        val raw = invokeHook(
            extension = extension,
            hookName = "searchLyrics",
            requestJson = reqJson,
            isAsync = true,
            timeoutMs = shortTimeoutMs,
        )
        val envelope = runCatching { json.decodeFromString<HookEnvelope>(raw) }.getOrElse { err ->
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Malformed extension return payload: ${err.message}",
                err,
            )
        }
        if (!envelope.ok) {
            val code = CloudErrorCode.fromCodeString(envelope.code) ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR
            throw CloudSourceException(code, envelope.message ?: "Extension hook failed ($code)")
        }
        val payload = envelope.result
        if (payload == null || payload is JsonNull) {
            return emptyList()
        }
        return runCatching {
            when (payload) {
                is JsonArray -> json.decodeFromJsonElement(kotlinx.serialization.serializer<List<ExtLyricsCandidateSpec>>(), payload)
                else -> {
                    val obj = json.decodeFromJsonElement(kotlinx.serialization.serializer<ExtLyricsSearchEnvelope>(), payload)
                    obj.candidates ?: obj.results ?: emptyList()
                }
            }
        }.getOrElse { err ->
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Invalid extension lyrics schema: ${err.message}",
                err,
            )
        }
    }

    private suspend fun invokeHook(
        extension: InstalledExtension,
        hookName: String,
        requestJson: String,
        isAsync: Boolean,
        timeoutMs: Long,
    ): String {
        val envJson = json.encodeToString(
            buildJsonObject {
                put("platform", PLATFORM_NAME)
                put("appVersion", appVersion)
                put("apiVersion", ExtensionManifest.CURRENT_API_VERSION)
            }
        )
        val requestLiteral = json.encodeToString(requestJson)
        val hookNameLiteral = json.encodeToString(hookName)
        val invokeExpr = if (isAsync) {
            "await hook(ctx, req)"
        } else {
            "hook(req)"
        }

        val script = if (isAsync) {
            """
                (async function() {
                  var console = {
                    log: function(m) { __musicoBridge.log('info', String(m)); },
                    info: function(m) { __musicoBridge.log('info', String(m)); },
                    warn: function(m) { __musicoBridge.log('warn', String(m)); },
                    error: function(m) { __musicoBridge.log('error', String(m)); },
                    debug: function(m) { __musicoBridge.log('info', String(m)); }
                  };
                  var ctx = {
                    http: {
                      request: async function(req) {
                        var raw = await __musicoBridge.httpRequest(JSON.stringify(req || {}));
                        var env = JSON.parse(raw);
                        if (!env.ok) {
                          var httpErr = new Error(env.message || 'HTTP request failed');
                          httpErr.code = env.code || 'NETWORK_ERROR';
                          throw httpErr;
                        }
                        return env.data;
                      }
                    },
                    crypto: {
                      md5: function(s) { return __musicoBridge.md5(s == null ? '' : String(s)); },
                      sha1: function(s) { return __musicoBridge.sha1(s == null ? '' : String(s)); },
                      sha256: function(s) { return __musicoBridge.sha256(s == null ? '' : String(s)); },
                      hmacSha256: function(k, m) {
                        return __musicoBridge.hmacSha256(k == null ? '' : String(k), m == null ? '' : String(m));
                      },
                      base64Encode: function(s) { return __musicoBridge.base64Encode(s == null ? '' : String(s)); },
                      base64Decode: function(s) { return __musicoBridge.base64Decode(s == null ? '' : String(s)); }
                    },
                    kv: {
                      get: function(key) { return __musicoBridge.kvGet(String(key)); },
                      set: function(key, val, ttlSeconds) {
                        var ttlMs = (typeof ttlSeconds === 'number' && isFinite(ttlSeconds) && ttlSeconds > 0)
                          ? Math.round(ttlSeconds * 1000)
                          : -1;
                        __musicoBridge.kvSet(String(key), val == null ? '' : String(val), ttlMs);
                      },
                      remove: function(key) { __musicoBridge.kvRemove(String(key)); }
                    },
                    settings: {
                      get: function(key) {
                        return JSON.parse(__musicoBridge.settingsGetJson(String(key)));
                      }
                    },
                    env: $envJson,
                    log: {
                      info: function(msg) { __musicoBridge.log('info', String(msg)); },
                      warn: function(msg) { __musicoBridge.log('warn', String(msg)); },
                      error: function(msg) { __musicoBridge.log('error', String(msg)); }
                    }
                  };

                  var module = { exports: {} };
                  var exports = module.exports;
                  try {
                    (function(exports, module) {
                ${extension.scriptSource}
                    })(exports, module);
                    var hookName = $hookNameLiteral;
                    var hook = module.exports && module.exports[hookName];
                    if (typeof hook !== 'function') {
                      var missing = new Error('Missing extension hook: ' + hookName);
                      missing.code = 'EXTENSION_RUNTIME_ERROR';
                      throw missing;
                    }
                    var req = JSON.parse($requestLiteral);
                    var result = $invokeExpr;
                    __musicoComplete(JSON.stringify({
                      ok: true,
                      result: result === undefined ? null : result
                    }));
                  } catch (err) {
                    __musicoComplete(JSON.stringify({
                      ok: false,
                      code: (err && err.code) ? String(err.code) : 'EXTENSION_RUNTIME_ERROR',
                      message: (err && err.message) ? String(err.message) : String(err)
                    }));
                  }
                })().catch(function(err) {
                  __musicoComplete(JSON.stringify({
                    ok: false,
                    code: (err && err.code) ? String(err.code) : 'EXTENSION_RUNTIME_ERROR',
                    message: (err && err.message) ? String(err.message) : String(err)
                  }));
                });
            """.trimIndent()
        } else {
            """
                (function() {
                  var console = {
                    log: function(m) { __musicoBridge.log('info', String(m)); },
                    info: function(m) { __musicoBridge.log('info', String(m)); },
                    warn: function(m) { __musicoBridge.log('warn', String(m)); },
                    error: function(m) { __musicoBridge.log('error', String(m)); },
                    debug: function(m) { __musicoBridge.log('info', String(m)); }
                  };
                  var module = { exports: {} };
                  var exports = module.exports;
                  try {
                    (function(exports, module) {
                ${extension.scriptSource}
                    })(exports, module);
                    var hookName = $hookNameLiteral;
                    var hook = module.exports && module.exports[hookName];
                    if (typeof hook !== 'function') {
                      var missing = new Error('Missing extension hook: ' + hookName);
                      missing.code = 'EXTENSION_RUNTIME_ERROR';
                      throw missing;
                    }
                    var req = JSON.parse($requestLiteral);
                    var result = hook(req);
                    return JSON.stringify({
                      ok: true,
                      result: result === undefined ? null : result
                    });
                  } catch (err) {
                    return JSON.stringify({
                      ok: false,
                      code: (err && err.code) ? String(err.code) : 'EXTENSION_RUNTIME_ERROR',
                      message: (err && err.message) ? String(err.message) : String(err)
                    });
                  }
                })();
            """.trimIndent()
        }

        return executeScript(
            timeoutMs = timeoutMs,
            setupBridge = { qjs -> bindHostBridge(qjs, extension.manifest) },
            script = script,
            sourceFileName = "${extension.id}:$hookName.js",
            expectAsyncCallback = isAsync,
        )
    }

    private fun bindHostBridge(qjs: QuickJs, manifest: ExtensionManifest) {
        val extId = manifest.id
        val allowedHosts = manifest.permissions.hosts

        qjs.define("__musicoBridge") {
            asyncFunction(
                "httpRequest",
                AsyncFunctionBinding { args ->
                    executeHttpRequest(args.firstOrNull()?.toString() ?: "{}", allowedHosts)
                },
            )
            function(
                "md5",
                FunctionBinding { args -> digestHex("MD5", args.firstOrNull()?.toString().orEmpty()) },
            )
            function(
                "sha1",
                FunctionBinding { args -> digestHex("SHA-1", args.firstOrNull()?.toString().orEmpty()) },
            )
            function(
                "sha256",
                FunctionBinding { args -> digestHex("SHA-256", args.firstOrNull()?.toString().orEmpty()) },
            )
            function(
                "hmacSha256",
                FunctionBinding { args ->
                    val key = args.getOrNull(0)?.toString().orEmpty()
                    val msg = args.getOrNull(1)?.toString().orEmpty()
                    hmacSha256Hex(key, msg)
                },
            )
            function(
                "base64Encode",
                FunctionBinding { args ->
                    Base64.getEncoder().encodeToString(args.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8))
                },
            )
            function(
                "base64Decode",
                FunctionBinding { args ->
                    val encoded = args.firstOrNull()?.toString().orEmpty().trim()
                    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                },
            )
            function(
                "kvGet",
                FunctionBinding { args ->
                    val key = args.firstOrNull()?.toString().orEmpty()
                    host.getKv(extId, key)
                },
            )
            function(
                "kvSet",
                FunctionBinding { args ->
                    val key = args.getOrNull(0)?.toString().orEmpty()
                    val value = args.getOrNull(1)?.toString().orEmpty()
                    val ttlMs = (args.getOrNull(2) as? Number)?.toLong()?.takeIf { it > 0L }
                    host.setKv(extId, key, value, ttlMs)
                    null
                },
            )
            function(
                "kvRemove",
                FunctionBinding { args ->
                    val key = args.firstOrNull()?.toString().orEmpty()
                    host.removeKv(extId, key)
                    null
                },
            )
            function(
                "settingsGetJson",
                FunctionBinding { args ->
                    val key = args.firstOrNull()?.toString().orEmpty()
                    val elem = host.getSetting(extId, key)
                    json.encodeToString(elem ?: JsonNull)
                },
            )
            function(
                "log",
                FunctionBinding { args ->
                    val level = args.getOrNull(0)?.toString().orEmpty().ifBlank { "info" }
                    val message = args.getOrNull(1)?.toString().orEmpty()
                    host.appendLog(extId, level, message)
                    null
                },
            )
        }
    }

    private suspend fun executeHttpRequest(rawReqJson: String, allowedHosts: List<String>): String {
        return try {
            val reqObj = json.parseToJsonElement(rawReqJson).jsonObject
            val url = reqObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            HostAllowlistMatcher.assertAllowed(url, allowedHosts)

            val method = reqObj["method"]?.jsonPrimitive?.contentOrNull?.uppercase()?.ifBlank { "GET" } ?: "GET"
            val headers = linkedMapOf<String, String>()
            (reqObj["headers"] as? JsonObject)?.forEach { (k, v) ->
                if (v !is JsonNull) {
                    headers[k] = v.jsonPrimitive.contentOrNull ?: v.toString()
                }
            }
            val bodyElem = reqObj["body"]
            val body = when (bodyElem) {
                null, JsonNull -> null
                is JsonPrimitive -> bodyElem.contentOrNull
                else -> bodyElem.toString()
            }
            val followRedirects = reqObj["followRedirects"]?.jsonPrimitive?.booleanOrNull ?: true
            val timeoutMs = reqObj["timeoutMs"]?.jsonPrimitive?.intOrNull ?: 15_000
            val returnBytes = reqObj["returnBytes"]?.jsonPrimitive?.booleanOrNull ?: false

            val resp = httpClient.request(
                request = CloudHttpRequest(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    followRedirects = followRedirects,
                    timeoutMs = timeoutMs,
                    returnBytes = returnBytes,
                ),
                urlValidator = { hopUrl ->
                    HostAllowlistMatcher.assertAllowed(hopUrl, allowedHosts)
                },
            )

            json.encodeToString(
                buildJsonObject {
                    put("ok", true)
                    put(
                        "data",
                        buildJsonObject {
                            put("status", resp.status)
                            put("finalUrl", resp.finalUrl)
                            put(
                                "headers",
                                buildJsonObject {
                                    for ((k, v) in resp.headers) {
                                        put(k.lowercase(), v)
                                    }
                                },
                            )
                            put("text", resp.text)
                            if (resp.bytesBase64 != null) {
                                put("bytesBase64", resp.bytesBase64)
                            }
                        },
                    )
                }
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: CloudSourceException) {
            json.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", err.code.name)
                    put("message", err.message)
                }
            )
        } catch (err: Exception) {
            json.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", CloudErrorCode.NETWORK_ERROR.name)
                    put("message", err.message ?: err.javaClass.simpleName)
                }
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeScript(
        timeoutMs: Long,
        setupBridge: (QuickJs) -> Unit,
        script: String,
        sourceFileName: String,
        expectAsyncCallback: Boolean = false,
    ): String = vmLifecycleMutex.withLock {
        val qjs = QuickJs.create(daemonDispatcher)
        qjs.memoryLimit = MEMORY_LIMIT_BYTES
        qjs.maxStackSize = MAX_STACK_SIZE_BYTES
        val resultDeferred = CompletableDeferred<String>()
        if (expectAsyncCallback) {
            qjs.defineBinding(
                "__musicoComplete",
                FunctionBinding<Unit> { args ->
                    resultDeferred.complete(args.firstOrNull()?.toString() ?: "")
                    Unit
                },
            )
        }
        setupBridge(qjs)
        val evalJob = runtimeScope.async {
            try {
                qjs.evaluate<Any?>(script, sourceFileName, false)
            } finally {
                runCatching { qjs.close() }
            }
        }
        return@withLock try {
            withTimeout(timeoutMs) {
                val direct = evalJob.await()
                if (expectAsyncCallback) {
                    resultDeferred.await()
                } else {
                    direct?.toString() ?: ""
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            evalJob.cancel()
            resultDeferred.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(50L) { evalJob.join() }
            }
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Extension execution timed out after ${timeoutMs}ms",
                timeout,
            )
        } catch (cancel: CancellationException) {
            evalJob.cancel()
            resultDeferred.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(50L) { evalJob.join() }
            }
            throw cancel
        } catch (err: QuickJsException) {
            if (expectAsyncCallback && resultDeferred.isCompleted) {
                return@withLock resultDeferred.getCompleted()
            }
            resultDeferred.cancel()
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                err.message ?: "Extension JS runtime error",
                err,
            )
        }
    }

    private inline fun <reified T> decodeEnvelope(rawJson: String): T {
        val envelope = runCatching { json.decodeFromString<HookEnvelope>(rawJson) }.getOrElse { err ->
            throw CloudSourceException(
                CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                "Malformed extension return payload: ${err.message}",
                err,
            )
        }
        if (!envelope.ok) {
            val code = CloudErrorCode.fromCodeString(envelope.code) ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR
            throw CloudSourceException(code, envelope.message ?: "Extension hook failed ($code)")
        }
        val payload = envelope.result
            ?: throw CloudSourceException(CloudErrorCode.EXTENSION_RUNTIME_ERROR, "Extension hook returned null")
        return runCatching { json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), payload) }
            .getOrElse { err ->
                throw CloudSourceException(
                    CloudErrorCode.EXTENSION_RUNTIME_ERROR,
                    "Invalid extension return schema: ${err.message}",
                    err,
                )
            }
    }

    private fun verifyRequiredHooks(
        manifest: ExtensionManifest,
        hasCanHandle: Boolean,
        hasFetchCollection: Boolean,
        hasFetchFolder: Boolean,
        hasResolveStream: Boolean,
        hasSearchLyrics: Boolean,
    ) {
        val requiresSourceHooks = manifest.capabilities.any {
            it == ExtensionCapability.PLAYLIST || it == ExtensionCapability.TREE || it == ExtensionCapability.STREAM
        }
        if (requiresSourceHooks) {
            require(hasCanHandle) { "Extension must export canHandle(input) function" }
        }
        if (ExtensionCapability.PLAYLIST in manifest.capabilities || ExtensionCapability.TREE in manifest.capabilities) {
            require(hasFetchCollection) { "Extension declaring playlist/tree capability must export fetchCollection(ctx, req)" }
        }
        if (ExtensionCapability.TREE in manifest.capabilities) {
            require(hasFetchFolder) { "Extension declaring tree capability must export fetchFolder(ctx, req)" }
        }
        if (ExtensionCapability.STREAM in manifest.capabilities) {
            require(hasResolveStream) { "Extension declaring stream capability must export resolveStream(ctx, req)" }
        }
        if (ExtensionCapability.LYRICS in manifest.capabilities) {
            require(hasSearchLyrics) { "Extension declaring lyrics capability must export searchLyrics(ctx, req)" }
        }
    }

    private fun digestHex(algorithm: String, input: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.toLowerHex()
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (b in this@toLowerHex) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }

    companion object {
        const val PLATFORM_NAME = "android"
        const val MEMORY_LIMIT_BYTES = 32L * 1024L * 1024L
        const val MAX_STACK_SIZE_BYTES = 1024L * 1024L
        const val DEFAULT_SHORT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LONG_TIMEOUT_MS = 60_000L
        private const val HEX_CHARS = "0123456789abcdef"
        private val threadCounter = AtomicInteger(1)
        private val daemonDispatcher = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "musico-quickjs-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
        private val vmLifecycleMutex = Mutex()
        private val runtimeScope = CoroutineScope(SupervisorJob() + daemonDispatcher)
    }
}
