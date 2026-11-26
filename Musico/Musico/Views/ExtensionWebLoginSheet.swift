import SwiftUI
import WebKit

/// Presents an isolated in-app browser sheet to sign in and capture domain-filtered session cookies
/// for extensions declaring `"web_login"` + `manifest.webLogin`.
struct ExtensionWebLoginSheet: View {
    let extensionItem: InstalledExtension
    let config: ExtensionWebLoginConfig
    private let explicitStore: ExtensionStore?

    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.dismiss) private var dismiss

    @State private var currentHost: String = ""
    @State private var isLoading: Bool = true
    @State private var capturedCount: Int = 0
    @State private var hasRequiredCookies: Bool = false
    @State private var capturedCookieHeader: String? = nil
    @State private var statusError: String? = nil
    @State private var didAutoComplete: Bool = false

    init(
        extensionItem: InstalledExtension,
        config: ExtensionWebLoginConfig,
        extensionStore: ExtensionStore? = nil
    ) {
        self.extensionItem = extensionItem
        self.config = config
        self.explicitStore = extensionStore
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statusBanner

                WebLoginWebView(
                    config: config,
                    onStateChange: { host, loading, count, hasReq, header, errorText in
                        currentHost = host
                        isLoading = loading
                        capturedCount = count
                        hasRequiredCookies = hasReq
                        capturedCookieHeader = header
                        statusError = errorText

                        if hasReq && !didAutoComplete && !isLoginChallengeHost(host) {
                            didAutoComplete = true
                            saveAndDismiss()
                        }
                    }
                )
            }
            .navigationTitle(L10n.tr("Sign In to %@", extensionItem.manifest.effectiveBadgeLabel))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.tr("Save Cookies")) {
                        saveAndDismiss()
                    }
                    .disabled((capturedCookieHeader ?? "").isEmpty)
                }
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 520, idealWidth: 640, minHeight: 560, idealHeight: 680)
        #endif
    }

    @ViewBuilder
    private var statusBanner: some View {
        HStack(spacing: 8) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else if hasRequiredCookies {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            } else {
                Image(systemName: "globe")
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 2) {
                if let statusError, !statusError.isEmpty {
                    Text(statusError)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                } else if hasRequiredCookies {
                    Text(L10n.tr("Sign-in cookies detected (%d). Tap Save Cookies to finish.", capturedCount))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.green)
                } else {
                    Text(currentHost.isEmpty
                         ? L10n.tr("Sign in below, then tap Save Cookies.")
                         : L10n.tr("%@ · %d cookies captured", currentHost, capturedCount))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func isLoginChallengeHost(_ host: String) -> Bool {
        let lower = host.lowercased()
        return lower == "accounts.google.com" || lower.hasPrefix("accounts.")
    }

    private func saveAndDismiss() {
        guard let header = capturedCookieHeader, !header.isEmpty else { return }
        extensionStore.updateCookie(extensionID: extensionItem.id, cookie: header)
        dismiss()
    }
}

private struct WebLoginWebView: UIViewRepresentable {
    let config: ExtensionWebLoginConfig
    let onStateChange: @MainActor (
        _ host: String,
        _ isLoading: Bool,
        _ count: Int,
        _ hasRequired: Bool,
        _ cookieHeader: String?,
        _ errorText: String?
    ) -> Void

    static func resolvedUserAgent(_ configured: String?) -> String {
        if let trimmed = configured?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty {
            return trimmed
        }
        #if targetEnvironment(macCatalyst)
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
        #else
        return "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        #endif
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(config: config, onStateChange: onStateChange)
    }

    func makeUIView(context: Context) -> WKWebView {
        let webConfig = WKWebViewConfiguration()
        webConfig.websiteDataStore = .nonPersistent()
        webConfig.defaultWebpagePreferences.allowsContentJavaScript = true

        let webView = WKWebView(frame: .zero, configuration: webConfig)
        webView.customUserAgent = Self.resolvedUserAgent(config.userAgent)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        context.coordinator.webView = webView

        if let url = URL(string: config.loginUrl) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let config: ExtensionWebLoginConfig
        let onStateChange: @MainActor (
            _ host: String,
            _ isLoading: Bool,
            _ count: Int,
            _ hasRequired: Bool,
            _ cookieHeader: String?,
            _ errorText: String?
        ) -> Void
        weak var webView: WKWebView?

        init(
            config: ExtensionWebLoginConfig,
            onStateChange: @escaping @MainActor (String, Bool, Int, Bool, String?, String?) -> Void
        ) {
            self.config = config
            self.onStateChange = onStateChange
        }

        nonisolated func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            Task { @MainActor in
                self.inspectCookies(webView: webView, isLoading: true, errorText: nil)
            }
        }

        nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                self.inspectCookies(webView: webView, isLoading: false, errorText: nil)
            }
        }

        nonisolated func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: Error
        ) {
            let nsErr = error as NSError
            if nsErr.domain == NSURLErrorDomain && nsErr.code == NSURLErrorCancelled { return }
            let message = error.localizedDescription
            Task { @MainActor in
                self.inspectCookies(webView: webView, isLoading: false, errorText: message)
            }
        }

        nonisolated func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            let nsErr = error as NSError
            if nsErr.domain == NSURLErrorDomain && nsErr.code == NSURLErrorCancelled { return }
            let message = error.localizedDescription
            Task { @MainActor in
                self.inspectCookies(webView: webView, isLoading: false, errorText: message)
            }
        }

        nonisolated func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }

        private func inspectCookies(webView: WKWebView, isLoading: Bool, errorText: String?) {
            let host = webView.url?.host ?? ""
            let allowedDomains = config.cookieDomains
            let required = Set((config.requiredCookies ?? []).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })

            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] allCookies in
                guard let self else { return }
                let now = Date()
                let matching = allCookies.filter { cookie in
                    if let exp = cookie.expiresDate, exp < now { return false }
                    guard !cookie.value.isEmpty else { return false }
                    return NetscapeCookieFileParser.domainMatches(cookieDomain: cookie.domain, allowedDomains: allowedDomains)
                }
                let pairs = matching.map { ($0.name, $0.value) }
                let header = NetscapeCookieFileParser.formatCookiePairs(pairs)
                let presentNames = Set(matching.map(\.name))
                let hasReq = !required.isEmpty && !presentNames.isDisjoint(with: required)

                Task { @MainActor in
                    self.onStateChange(host, isLoading, matching.count, hasReq, header, errorText)
                }
            }
        }
    }
}
