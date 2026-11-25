import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ExtensionsView: View {
    private let explicitStore: ExtensionStore?
    private let showDoneButton: Bool
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.dismiss) private var dismiss

    @State private var showFileImporter = false
    @State private var showURLImporter = false
    @State private var importError: String?
    @State private var reviewTarget: InstalledExtension?
    @State private var updateReviewTarget: PendingExtensionUpdate?

    init(extensionStore: ExtensionStore? = nil, showDoneButton: Bool = false) {
        self.explicitStore = extensionStore
        self.showDoneButton = showDoneButton
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    private static var supportedImportTypes: [UTType] {
        var types: [UTType] = []
        if let bundleType = UTType(filenameExtension: "musicoext") {
            types.append(bundleType)
        }
        if let jsType = UTType(filenameExtension: "js") {
            types.append(jsType)
        }
        types.append(.zip)
        types.append(.sourceCode)
        types.append(.data)
        return types
    }

    var body: some View {
        List {
            if extensionStore.extensions.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label(L10n.tr("No Extensions Installed"), systemImage: "puzzlepiece.extension")
                    } description: {
                        Text(L10n.tr("Import a .musicoext bundle or .musico.js script to add custom cloud music sources."))
                    } actions: {
                        HStack(spacing: 12) {
                            Button(L10n.tr("Import File…")) {
                                showFileImporter = true
                            }
                            .buttonStyle(.borderedProminent)

                            Button(L10n.tr("Import URL…")) {
                                showURLImporter = true
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
            } else {
                Section {
                    ForEach(extensionStore.extensions) { ext in
                        NavigationLink {
                            ExtensionDetailView(extensionID: ext.id, extensionStore: extensionStore)
                        } label: {
                            extensionRow(ext)
                        }
                    }
                } footer: {
                    Text(L10n.tr("Extensions run natively in an isolated JavaScript sandbox with strict host allowlists."))
                }

                Section {
                    Toggle(
                        L10n.tr("Automatic Updates"),
                        isOn: Binding(
                            get: { extensionStore.autoUpdateEnabled },
                            set: { extensionStore.autoUpdateEnabled = $0 }
                        )
                    )
                    Button {
                        Task { await extensionStore.checkForUpdates(manual: true) }
                    } label: {
                        HStack {
                            Label(L10n.tr("Check for Updates"), systemImage: "arrow.clockwise")
                            Spacer()
                            if extensionStore.isCheckingForUpdates {
                                ProgressView()
                                    .controlSize(.small)
                            }
                        }
                    }
                    .disabled(extensionStore.isCheckingForUpdates)
                } header: {
                    Text(L10n.tr("Updates"))
                } footer: {
                    if let lastCheck = extensionStore.lastUpdateCheckAt {
                        Text(L10n.tr("Last checked %@", L10n.formatDateTime(lastCheck)))
                    } else {
                        Text(L10n.tr("Extensions installed from HTTPS links or declaring an update URL check automatically every 6 hours."))
                    }
                }
            }
        }
        .navigationTitle(L10n.tr("Extensions"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if showDoneButton {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Done")) { dismiss() }
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    Button(L10n.tr("Import from File…"), systemImage: "doc.badge.plus") {
                        showFileImporter = true
                    }
                    Button(L10n.tr("Import from HTTPS URL…"), systemImage: "link.badge.plus") {
                        showURLImporter = true
                    }
                } label: {
                    Label(L10n.tr("Import Extension"), systemImage: "plus")
                }
            }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: Self.supportedImportTypes,
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .failure(let error):
                importError = error.localizedDescription
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    do {
                        let installed = try await extensionStore.importFromFile(at: url)
                        reviewTarget = installed
                    } catch {
                        importError = error.localizedDescription
                    }
                }
            }
        }
        .sheet(isPresented: $showURLImporter) {
            ImportExtensionURLSheet(extensionStore: extensionStore) { installed in
                reviewTarget = installed
            }
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
        }
        .sheet(item: $reviewTarget) { target in
            ExtensionPermissionReviewSheet(extensionItem: target, extensionStore: extensionStore)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .sheet(item: $updateReviewTarget) { pending in
            ExtensionUpdateReviewSheet(pendingUpdate: pending, extensionStore: extensionStore)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .alert(
            L10n.tr("Couldn’t Import Extension"),
            isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })
        ) {
            Button(L10n.tr("OK"), role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
    }

    @ViewBuilder
    private func extensionRow(_ ext: InstalledExtension) -> some View {
        HStack(spacing: 12) {
            ExtensionIconView(iconData: ext.iconData, size: 32)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(ext.manifest.name)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    Text(ext.manifest.effectiveBadgeLabel)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.14), in: Capsule())
                        .foregroundStyle(Color.accentColor)
                }
                Text("v\(ext.manifest.version) · \(statusText(for: ext))")
                    .font(.caption)
                    .foregroundStyle(ext.state.permissionApproved ? Color.secondary : Color.orange)
                    .lineLimit(1)
                if let subtitle = updateStatusSubtitle(for: ext) {
                    Text(subtitle.text)
                        .font(.caption2)
                        .foregroundStyle(subtitle.color)
                        .lineLimit(1)
                }
            }
            Spacer()
            if let pending = extensionStore.pendingUpdates[ext.id] {
                Button(L10n.tr("Review Update")) {
                    updateReviewTarget = pending
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            Toggle(
                "",
                isOn: Binding(
                    get: { ext.isActive },
                    set: { wantEnabled in
                        if wantEnabled && !ext.state.permissionApproved {
                            reviewTarget = ext
                        } else {
                            extensionStore.setEnabled(id: ext.id, enabled: wantEnabled)
                        }
                    }
                )
            )
            .labelsHidden()
        }
    }

    private func statusText(for ext: InstalledExtension) -> String {
        if !ext.state.permissionApproved {
            return L10n.tr("Permission Review Required")
        }
        return ext.isActive ? L10n.tr("Enabled") : L10n.tr("Disabled")
    }

    private func updateStatusSubtitle(for ext: InstalledExtension) -> (text: String, color: Color)? {
        if let pending = extensionStore.pendingUpdates[ext.id] {
            return (L10n.tr("Update v%@ requires permission review", pending.manifest.version), .orange)
        }
        switch extensionStore.updateStatusByID[ext.id] {
        case .checking:
            return (L10n.tr("Checking for updates…"), .secondary)
        case .updated(let version):
            return (L10n.tr("Updated to v%@", version), .green)
        case .error(let message):
            return (message, .red)
        case .upToDate, .pendingReview, .none:
            return nil
        }
    }
}

struct ExtensionIconView: View {
    let iconData: Data?
    let size: CGFloat

    var body: some View {
        if let iconData, let image = UIImage(data: iconData) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        } else {
            Image(systemName: "puzzlepiece.extension.fill")
                .font(.system(size: size * 0.62))
                .foregroundStyle(.indigo)
                .frame(width: size, height: size)
                .background(Color.indigo.opacity(0.12), in: RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        }
    }
}

struct ExtensionPermissionReviewSheet: View {
    let extensionItem: InstalledExtension
    private let explicitStore: ExtensionStore?
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.dismiss) private var dismiss

    init(extensionItem: InstalledExtension, extensionStore: ExtensionStore? = nil) {
        self.extensionItem = extensionItem
        self.explicitStore = extensionStore
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        ExtensionIconView(iconData: extensionItem.iconData, size: 42)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(extensionItem.manifest.name)
                                .font(.headline)
                            Text("\(extensionItem.manifest.id) · v\(extensionItem.manifest.version)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if let author = extensionItem.manifest.author, !author.isEmpty {
                                Text(L10n.tr("By %@", author))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    if let desc = extensionItem.manifest.description, !desc.isEmpty {
                        Text(desc)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    ForEach(extensionItem.manifest.permissions.hosts, id: \.self) { host in
                        Label {
                            Text(host)
                                .font(.system(.subheadline, design: .monospaced))
                        } icon: {
                            Image(systemName: "network")
                                .foregroundStyle(.blue)
                        }
                    }
                } header: {
                    Text(L10n.tr("Allowed Network Hosts"))
                } footer: {
                    Text(L10n.tr("HTTP requests, redirects, and stream URLs outside these hosts are blocked automatically."))
                }

                Section(L10n.tr("Declared Capabilities")) {
                    ForEach(extensionItem.manifest.capabilities, id: \.self) { cap in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cap.localizedTitle)
                                .font(.subheadline.weight(.medium))
                            Text(cap.localizedDescription)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }

                Section {
                    Button(L10n.tr("Remove Extension"), role: .destructive) {
                        extensionStore.uninstall(id: extensionItem.id)
                        dismiss()
                    }
                }
            }
            .navigationTitle(L10n.tr("Permission Review"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Keep Disabled")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.tr("Allow & Enable")) {
                        extensionStore.approvePermissionsAndEnable(id: extensionItem.id)
                        dismiss()
                    }
                }
            }
        }
    }
}

struct ExtensionDetailView: View {
    let extensionID: String
    private let explicitStore: ExtensionStore?
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.dismiss) private var dismiss

    @State private var showReviewSheet = false
    @State private var showUpdateReviewSheet = false
    @State private var confirmUninstall = false
    @State private var showQRLoginSheet = false

    init(extensionID: String, extensionStore: ExtensionStore? = nil) {
        self.extensionID = extensionID
        self.explicitStore = extensionStore
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    private var current: InstalledExtension? {
        extensionStore.extension(forID: extensionID)
    }

    var body: some View {
        Group {
            if let ext = current {
                detailForm(ext)
            } else {
                ContentUnavailableView(
                    L10n.tr("Extension Removed"),
                    systemImage: "puzzlepiece.extension"
                )
            }
        }
        .navigationTitle(current?.manifest.name ?? L10n.tr("Extension"))
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func detailForm(_ ext: InstalledExtension) -> some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    ExtensionIconView(iconData: ext.iconData, size: 44)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(ext.manifest.name)
                            .font(.headline)
                        Text("\(ext.manifest.id) · v\(ext.manifest.version)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let author = ext.manifest.author, !author.isEmpty {
                            Text(L10n.tr("By %@", author))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                if let desc = ext.manifest.description, !desc.isEmpty {
                    Text(desc)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Toggle(
                    L10n.tr("Enabled"),
                    isOn: Binding(
                        get: { ext.isActive },
                        set: { wantEnabled in
                            if wantEnabled && !ext.state.permissionApproved {
                                showReviewSheet = true
                            } else {
                                extensionStore.setEnabled(id: ext.id, enabled: wantEnabled)
                            }
                        }
                    )
                )
            }

            if ext.manifest.supportsCookieAuth {
                Section {
                    if ext.manifest.supportsQRLogin {
                        Button {
                            showQRLoginSheet = true
                        } label: {
                            Label(L10n.tr("Sign In with QR Code…"), systemImage: "qrcode")
                        }
                        .disabled(!ext.isActive)
                    }
                    LabeledContent(L10n.tr("Cookie (optional)")) {
                        SecureField(
                            L10n.tr("Cookie (optional)"),
                            text: Binding(
                                get: { ext.state.cookie ?? "" },
                                set: { extensionStore.updateCookie(extensionID: ext.id, cookie: $0) }
                            )
                        )
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    }
                    if !(ext.state.cookie ?? "").isEmpty {
                        Button(L10n.tr("Clear Cookie"), role: .destructive) {
                            extensionStore.updateCookie(extensionID: ext.id, cookie: nil)
                        }
                    }
                } header: {
                    Text(L10n.tr("Authentication"))
                } footer: {
                    if ext.manifest.supportsQRLogin && !ext.isActive {
                        Text(L10n.tr("Enable this extension first to sign in with a QR code. Folder-specific cookies still take precedence."))
                    } else {
                        Text(L10n.tr("Saved locally for search and playback with this extension. Folder-specific cookies still take precedence."))
                    }
                }
            }

            let qualities = ext.manifest.effectiveQualities
            if !qualities.isEmpty {
                Section {
                    Picker(
                        L10n.tr("Streaming Quality"),
                        selection: Binding(
                            get: {
                                let stored = ext.state.streamQualityID
                                if let stored, qualities.contains(where: { $0.id == stored }) { return stored }
                                return ext.manifest.effectiveDefaultStreamQualityID ?? qualities[0].id
                            },
                            set: { newValue in
                                extensionStore.updateQualityPreferences(
                                    extensionID: ext.id,
                                    streamQualityID: newValue,
                                    downloadQualityID: ext.state.downloadQualityID
                                )
                            }
                        )
                    ) {
                        ForEach(qualities) { quality in
                            Text(quality.label).tag(quality.id)
                        }
                    }

                    Picker(
                        L10n.tr("Download Quality"),
                        selection: Binding(
                            get: {
                                let stored = ext.state.downloadQualityID
                                if let stored, qualities.contains(where: { $0.id == stored }) { return stored }
                                return ext.manifest.effectiveDefaultDownloadQualityID ?? qualities[0].id
                            },
                            set: { newValue in
                                extensionStore.updateQualityPreferences(
                                    extensionID: ext.id,
                                    streamQualityID: ext.state.streamQualityID,
                                    downloadQualityID: newValue
                                )
                            }
                        )
                    ) {
                        ForEach(qualities) { quality in
                            Text(quality.label).tag(quality.id)
                        }
                    }
                } header: {
                    Text(L10n.tr("Audio Quality"))
                } footer: {
                    Text(L10n.tr("Default quality used for playback and saving songs when a folder does not specify its own."))
                }
            }

            if let settings = ext.manifest.settings, !settings.isEmpty {
                Section(L10n.tr("Settings")) {
                    ForEach(settings) { spec in
                        settingControl(for: spec, ext: ext)
                    }
                    if !ext.state.settings.isEmpty {
                        Button(L10n.tr("Reset Settings")) {
                            extensionStore.resetSettings(extensionID: ext.id)
                        }
                    }
                }
            }

            Section(L10n.tr("Permissions")) {
                let staticNormalized = Set(ext.manifest.permissions.hosts.map { HostAllowlistMatcher.normalizeHost($0) })
                let dynamicHosts = ext.manifest.effectiveAllowedHosts { key in
                    ext.state.settings[key]
                }.filter { !staticNormalized.contains(HostAllowlistMatcher.normalizeHost($0)) }
                ForEach(ext.manifest.permissions.hosts, id: \.self) { host in
                    Label {
                        Text(host)
                            .font(.system(.caption, design: .monospaced))
                    } icon: {
                        Image(systemName: "network")
                            .foregroundStyle(.blue)
                    }
                }
                ForEach(dynamicHosts, id: \.self) { host in
                    Label {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(host)
                                .font(.system(.caption, design: .monospaced))
                            Text(L10n.tr("From server setting"))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "network")
                            .foregroundStyle(.green)
                    }
                }
                HStack {
                    Text(L10n.tr("Capabilities"))
                    Spacer()
                    Text(ext.manifest.capabilities.map(\.rawValue).joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if ext.manifest.capabilities.contains(.search) {
                    HStack {
                        Text(L10n.tr("Search Kinds"))
                        Spacer()
                        Text(ext.manifest.effectiveSearchKinds.map(\.localizedTitle).joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button(L10n.tr("Review Permissions…")) {
                    showReviewSheet = true
                }
            }

            if extensionStore.updateSourceURL(for: ext) != nil || extensionStore.pendingUpdates[ext.id] != nil {
                Section(L10n.tr("Updates")) {
                    if let updateURL = extensionStore.updateSourceURL(for: ext) {
                        LabeledContent(L10n.tr("Update Source")) {
                            Text(updateURL.absoluteString)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }

                    if extensionStore.pendingUpdates[ext.id] != nil {
                        Button {
                            showUpdateReviewSheet = true
                        } label: {
                            Label(L10n.tr("Review Update…"), systemImage: "exclamationmark.shield")
                        }
                        .tint(.orange)
                    }

                    Button {
                        Task {
                            await extensionStore.checkForUpdate(extensionID: ext.id)
                            if extensionStore.pendingUpdates[ext.id] != nil {
                                showUpdateReviewSheet = true
                            }
                        }
                    } label: {
                        HStack {
                            Label(L10n.tr("Check for Update"), systemImage: "arrow.clockwise")
                            Spacer()
                            if case .checking = extensionStore.updateStatusByID[ext.id] {
                                ProgressView()
                                    .controlSize(.small)
                            }
                        }
                    }
                    .disabled(extensionStore.updateStatusByID[ext.id] == .checking)

                    if let statusInfo = detailUpdateStatus(for: ext.id) {
                        Text(statusInfo.text)
                            .font(.caption)
                            .foregroundStyle(statusInfo.color)
                    }
                }
            }

            Section {
                if ext.logs.isEmpty {
                    Text(L10n.tr("No diagnostic logs recorded yet."))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(ext.logs.reversed())) { entry in
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(entry.level.rawValue.uppercased())
                                    .font(.caption2.weight(.bold))
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 1)
                                    .background(levelColor(entry.level).opacity(0.16), in: Capsule())
                                    .foregroundStyle(levelColor(entry.level))
                                Text(L10n.formatDateTime(Date(timeIntervalSince1970: Double(entry.timestampMs) / 1000.0)))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Text(entry.message)
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                        }
                        .padding(.vertical, 2)
                    }
                }
            } header: {
                HStack {
                    Text(L10n.tr("Logs (%@)", String(ext.logs.count)))
                    Spacer()
                    if !ext.logs.isEmpty {
                        Button(L10n.tr("Clear")) {
                            extensionStore.clearLogs(for: ext.id)
                        }
                        .font(.caption)
                    }
                }
            }

            Section {
                Button(L10n.tr("Uninstall Extension"), role: .destructive) {
                    confirmUninstall = true
                }
            }
        }
        .sheet(isPresented: $showReviewSheet) {
            ExtensionPermissionReviewSheet(extensionItem: ext, extensionStore: extensionStore)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .sheet(isPresented: $showUpdateReviewSheet) {
            if let pending = extensionStore.pendingUpdates[ext.id] {
                ExtensionUpdateReviewSheet(pendingUpdate: pending, extensionStore: extensionStore)
                    .environment(\.extensionStore, extensionStore)
                    .environment(\.languageManager, languageManager)
                    .environment(\.locale, languageManager.effectiveLocale)
            }
        }
        .sheet(isPresented: $showQRLoginSheet) {
            ExtensionQRLoginSheet(extensionItem: ext, extensionStore: extensionStore)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .confirmationDialog(
            L10n.tr("Uninstall “%@”?", ext.manifest.name),
            isPresented: $confirmUninstall,
            titleVisibility: .visible
        ) {
            Button(L10n.tr("Uninstall"), role: .destructive) {
                extensionStore.uninstall(id: ext.id)
                dismiss()
            }
            Button(L10n.tr("Cancel"), role: .cancel) {}
        } message: {
            Text(L10n.tr("Existing cloud folders from this extension stay in your library with their tracks and cached audio intact."))
        }
    }

    @ViewBuilder
    private func settingControl(for spec: ExtensionSettingSpec, ext: InstalledExtension) -> some View {
        let currentValue = ext.state.settings[spec.key] ?? spec.defaultValue
        switch spec.type {
        case .boolean:
            Toggle(
                spec.label,
                isOn: Binding(
                    get: { currentValue?.boolValue ?? false },
                    set: { extensionStore.updateSetting(extensionID: ext.id, key: spec.key, value: .bool($0)) }
                )
            )
        case .string:
            LabeledContent(spec.label) {
                TextField(
                    spec.label,
                    text: Binding(
                        get: { currentValue?.stringValue ?? "" },
                        set: { extensionStore.updateSetting(extensionID: ext.id, key: spec.key, value: .string($0)) }
                    )
                )
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            }
        case .select:
            let options = spec.options ?? []
            Picker(
                spec.label,
                selection: Binding(
                    get: { currentValue?.stringValue ?? options.first?.value ?? "" },
                    set: { extensionStore.updateSetting(extensionID: ext.id, key: spec.key, value: .string($0)) }
                )
            ) {
                ForEach(options) { opt in
                    Text(opt.label).tag(opt.value)
                }
            }
        case .serverURL:
            let currentText = currentValue?.stringValue ?? ""
            VStack(alignment: .leading, spacing: 4) {
                LabeledContent(spec.label) {
                    TextField(
                        "https://server.example.com",
                        text: Binding(
                            get: { currentText },
                            set: { extensionStore.updateSetting(extensionID: ext.id, key: spec.key, value: .string($0)) }
                        )
                    )
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.URL)
                    #endif
                }
                if let approvedHost = HostAllowlistMatcher.extractApprovedHost(fromServerURL: currentText) {
                    Text(L10n.tr("Allows network access to: %@", approvedHost))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(L10n.tr("Enter an http:// or https:// server URL. Its host will be allowed for this extension."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func levelColor(_ level: ExtensionLogEntry.Level) -> Color {
        switch level {
        case .info: .secondary
        case .warn: .orange
        case .error: .red
        }
    }

    private func detailUpdateStatus(for id: String) -> (text: String, color: Color)? {
        if let pending = extensionStore.pendingUpdates[id] {
            return (L10n.tr("Version %@ is ready for permission review.", pending.manifest.version), .orange)
        }
        switch extensionStore.updateStatusByID[id] {
        case .checking:
            return (L10n.tr("Checking for updates…"), .secondary)
        case .upToDate:
            return (L10n.tr("Extension is up to date."), .secondary)
        case .updated(let version):
            return (L10n.tr("Updated to v%@", version), .green)
        case .error(let message):
            return (message, .red)
        case .pendingReview, .none:
            return nil
        }
    }
}

struct ExtensionUpdateReviewSheet: View {
    let pendingUpdate: PendingExtensionUpdate
    private let explicitStore: ExtensionStore?
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.dismiss) private var dismiss

    init(pendingUpdate: PendingExtensionUpdate, extensionStore: ExtensionStore? = nil) {
        self.pendingUpdate = pendingUpdate
        self.explicitStore = extensionStore
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        ExtensionIconView(iconData: pendingUpdate.iconData, size: 42)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pendingUpdate.manifest.name)
                                .font(.headline)
                            Text("v\(pendingUpdate.installedVersion) \u{2192} v\(pendingUpdate.manifest.version)")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Color.accentColor)
                            Text(pendingUpdate.manifest.id)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text(L10n.tr("This update requests additional permissions. Review the new hosts or capabilities before applying it."))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if !pendingUpdate.addedHosts.isEmpty {
                    Section(L10n.tr("New Network Hosts")) {
                        ForEach(pendingUpdate.addedHosts, id: \.self) { host in
                            Label {
                                Text(host)
                                    .font(.system(.subheadline, design: .monospaced))
                            } icon: {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                }

                if !pendingUpdate.addedCapabilities.isEmpty {
                    Section(L10n.tr("New Capabilities")) {
                        ForEach(pendingUpdate.addedCapabilities, id: \.self) { cap in
                            VStack(alignment: .leading, spacing: 2) {
                                Label(cap.localizedTitle, systemImage: "exclamationmark.triangle.fill")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.orange)
                                Text(cap.localizedDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }

                Section(L10n.tr("All Allowed Hosts")) {
                    ForEach(pendingUpdate.manifest.permissions.hosts, id: \.self) { host in
                        Label {
                            Text(host)
                                .font(.system(.subheadline, design: .monospaced))
                        } icon: {
                            Image(systemName: "network")
                                .foregroundStyle(.blue)
                        }
                    }
                }

                Section(L10n.tr("All Capabilities")) {
                    ForEach(pendingUpdate.manifest.capabilities, id: \.self) { cap in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cap.localizedTitle)
                                .font(.subheadline.weight(.medium))
                            Text(cap.localizedDescription)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle(L10n.tr("Review Update"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.tr("Allow & Update")) {
                        extensionStore.approvePendingUpdate(id: pendingUpdate.id)
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct ImportExtensionURLSheet: View {
    let extensionStore: ExtensionStore
    let onImported: (InstalledExtension) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://example.com/source.musicoext", text: $urlText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text(L10n.tr("Extension Package URL"))
                } footer: {
                    Text(L10n.tr("Enter an HTTPS link to a .musicoext bundle or .musico.js file."))
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(L10n.tr("Import from URL"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) { dismiss() }
                        .disabled(isLoading)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isLoading {
                        ProgressView()
                    } else {
                        Button(L10n.tr("Import")) { runImport() }
                            .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    private func runImport() {
        errorMessage = nil
        isLoading = true
        let target = urlText
        Task {
            do {
                let installed = try await extensionStore.importFromURL(target)
                dismiss()
                onImported(installed)
            } catch {
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }
}
