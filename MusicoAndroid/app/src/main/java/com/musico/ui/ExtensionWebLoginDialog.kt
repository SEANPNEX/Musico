package com.musico.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musico.extensions.ExtensionStore
import com.musico.extensions.ExtensionWebLoginConfig
import com.musico.extensions.InstalledExtension
import com.musico.extensions.NetscapeCookieFileParser
import com.musico.i18n.AppStrings
import com.musico.ui.theme.musicoTopAppBarColors

private const val DEFAULT_WEB_LOGIN_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ExtensionWebLoginDialog(
    extension: InstalledExtension,
    config: ExtensionWebLoginConfig,
    extensionStore: ExtensionStore,
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    var currentHost by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var capturedCount by remember { mutableIntStateOf(0) }
    var hasRequiredCookies by remember { mutableStateOf(false) }
    var capturedCookieHeader by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var didAutoComplete by remember { mutableStateOf(false) }

    fun saveAndDismiss(headerOverride: String? = capturedCookieHeader) {
        val clean = headerOverride?.trim().orEmpty()
        if (clean.isEmpty()) return
        extensionStore.updateCookie(extension.id, clean)
        onDismiss()
    }

    fun inspectCookies(url: String?) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val host = runCatching { Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
        currentHost = host

        val pairs = ArrayList<Pair<String, String>>()
        for (rawDomain in config.cookieDomains) {
            val normalized = NetscapeCookieFileParser.normalizeDomain(rawDomain)
            if (normalized.isEmpty()) continue
            val rawHeader = cookieManager.getCookie("https://$normalized/") ?: continue
            for (seg in rawHeader.split(';')) {
                val trimmed = seg.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    pairs.add(name to value)
                }
            }
        }

        val header = NetscapeCookieFileParser.formatCookiePairs(pairs)
        val presentNames = pairs.map { it.first }.toSet()
        val required = config.requiredCookies.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val hasReq = required.isNotEmpty() && required.any { it in presentNames }

        capturedCookieHeader = header
        capturedCount = presentNames.size
        hasRequiredCookies = hasReq

        val isChallengeHost = host.equals("accounts.google.com", ignoreCase = true) ||
            host.startsWith("accounts.", ignoreCase = true)
        if (hasReq && !didAutoComplete && !isChallengeHost && !header.isNullOrBlank()) {
            didAutoComplete = true
            saveAndDismiss(header)
        }
    }

    DisposableEffect(extension.id) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.setAcceptCookie(true)
        onDispose {
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    colors = musicoTopAppBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = strings.cancel)
                        }
                    },
                    title = {
                        Text(
                            text = strings.webLoginDialogTitle(extension.manifest.effectiveBadgeLabel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        Button(
                            enabled = !capturedCookieHeader.isNullOrBlank(),
                            onClick = {
                                inspectCookies(null)
                                saveAndDismiss()
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = strings.saveCookiesAction,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            isLoading -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            hasRequiredCookies -> Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            else -> Icon(
                                Icons.Rounded.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            text = when {
                                !statusError.isNullOrBlank() -> statusError!!
                                hasRequiredCookies -> strings.webLoginCookiesDetected(capturedCount)
                                currentHost.isNotBlank() -> strings.webLoginHostStatus(currentHost, capturedCount)
                                else -> strings.webLoginPrompt
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                !statusError.isNullOrBlank() -> MaterialTheme.colorScheme.error
                                hasRequiredCookies -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    HorizontalDivider()
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val resolvedUa = config.userAgent?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: DEFAULT_WEB_LOGIN_UA
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = resolvedUa
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        isLoading = true
                                        statusError = null
                                        inspectCookies(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        inspectCookies(url)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            isLoading = false
                                            statusError = error?.description?.toString()
                                        }
                                    }
                                }
                                loadUrl(config.loginUrl)
                            }
                        },
                    )
                }
            }
        }
    }
}
