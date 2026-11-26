import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SettingsView: View {
    var isSheet: Bool = false

    @Environment(\.themeManager) private var themeManager
    @Environment(\.languageManager) private var languageManager
    @Environment(LibraryStore.self) private var library: LibraryStore?
    #if targetEnvironment(macCatalyst)
    @Environment(\.updateManager) private var updateManager
    #endif
    @Environment(\.dismiss) private var dismiss

    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showImageFileImporter = false
    @State private var previewImage: UIImage?
    @State private var imageError: String?
    @State private var googleDriveOAuth = GoogleDriveOAuthManager.shared

    var body: some View {
        if isSheet {
            NavigationStack {
                formBody
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L10n.tr("Done")) { dismiss() }
                        }
                    }
            }
            .applyTheme(themeManager)
        } else {
            formBody
        }
    }

    private var formBody: some View {
        Form {
            appearanceSection
            generalSection
            googleDriveSection
            librarySection
            aboutSection
        }
        .hiddenScrollBackgroundIfThemed(themeManager)
        .background {
            if isSheet {
                ThemeBackdropView()
            }
        }
        .navigationTitle(L10n.tr("Settings"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #else
        .navigationBarTitleDisplayMode(isSheet ? .inline : .large)
        #endif
        .task(id: themeManager.backgroundImageURL) {
            await refreshPreviewImage()
        }
        .onChange(of: selectedPhotoItem) { _, newItem in
            guard let newItem else { return }
            Task {
                await importFromPhotoPicker(newItem)
            }
        }
        .fileImporter(isPresented: $showImageFileImporter, allowedContentTypes: [.image]) { result in
            switch result {
            case .success(let url):
                do {
                    try themeManager.importBackgroundImage(from: url)
                } catch {
                    imageError = error.localizedDescription
                }
            case .failure(let error):
                imageError = error.localizedDescription
            }
        }
        .alert(
            L10n.tr("Couldn’t Import Background Image"),
            isPresented: Binding(get: { imageError != nil }, set: { if !$0 { imageError = nil } })
        ) {
            Button(L10n.tr("OK"), role: .cancel) {}
        } message: {
            Text(imageError ?? "")
        }
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section(L10n.tr("Appearance")) {
            Picker(L10n.tr("Theme Mode"), selection: Binding(
                get: { themeManager.settings.themeMode },
                set: { themeManager.setThemeMode($0) }
            )) {
                ForEach(AppThemeMode.allCases) { mode in
                    Text(mode.displayName).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            VStack(alignment: .leading, spacing: 10) {
                Text(L10n.tr("Background Color"))
                    .font(.subheadline.weight(.medium))
                backgroundPresetRow
            }
            .padding(.vertical, 4)

            if themeManager.settings.backgroundPreset == .custom {
                ColorPicker(
                    L10n.tr("Custom Background Color"),
                    selection: customBackgroundColorBinding,
                    supportsOpacity: false
                )
            }

            VStack(alignment: .leading, spacing: 10) {
                Text(L10n.tr("Foreground & Accent"))
                    .font(.subheadline.weight(.medium))
                foregroundPresetRow
            }
            .padding(.vertical, 4)

            if themeManager.settings.foregroundPreset == .custom {
                ColorPicker(
                    L10n.tr("Custom Accent Color"),
                    selection: customAccentColorBinding,
                    supportsOpacity: false
                )
            }

            Toggle(L10n.tr("Custom Text Color"), isOn: customTextColorEnabledBinding)

            if themeManager.settings.customForegroundHex != nil {
                HStack {
                    ColorPicker(
                        L10n.tr("Text Color"),
                        selection: customTextColorBinding,
                        supportsOpacity: false
                    )
                    Button(L10n.tr("Clear")) {
                        themeManager.setCustomForegroundHex(nil)
                    }
                    .buttonStyle(.borderless)
                    .font(.subheadline)
                }
            }

            backgroundImageControls

            #if targetEnvironment(macCatalyst)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(L10n.tr("Window Background Opacity"))
                    Spacer()
                    Text("\(Int((themeManager.settings.backgroundOpacity * 100).rounded()))%")
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(
                    value: Binding(
                        get: { themeManager.settings.backgroundOpacity },
                        set: { themeManager.setBackgroundOpacity($0) }
                    ),
                    in: AppearanceSettings.backgroundOpacityRange
                )
            }
            .padding(.vertical, 2)
            #endif

            Button(L10n.tr("Reset to Default"), role: .destructive) {
                themeManager.resetToDefaults()
            }
        }
    }

    private var backgroundPresetRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(BackgroundColorPreset.allCases) { preset in
                    let isSelected = themeManager.settings.backgroundPreset == preset
                    Button {
                        if preset == .custom && themeManager.settings.customBackgroundHex == nil {
                            themeManager.setCustomBackgroundHex("#1E293B")
                        } else {
                            themeManager.setBackgroundPreset(preset)
                        }
                    } label: {
                        VStack(spacing: 5) {
                            ZStack {
                                backgroundSwatchFill(for: preset)
                                    .frame(width: 34, height: 34)
                                    .clipShape(Circle())
                                    .overlay {
                                        Circle()
                                            .strokeBorder(
                                                isSelected ? themeManager.accentColor : Color.primary.opacity(0.18),
                                                lineWidth: isSelected ? 2.5 : 0.75
                                            )
                                    }
                                if isSelected {
                                    Image(systemName: "checkmark")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(swatchCheckmarkColor(for: preset))
                                } else if preset == .custom {
                                    Image(systemName: "eyedropper")
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(swatchCheckmarkColor(for: preset))
                                }
                            }
                            Text(preset.displayName)
                                .font(.caption2)
                                .foregroundStyle(isSelected ? themeManager.accentColor : .secondary)
                                .lineLimit(1)
                        }
                        .frame(minWidth: 52)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var foregroundPresetRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(ForegroundColorPreset.allCases) { preset in
                    let isSelected = themeManager.settings.foregroundPreset == preset
                    Button {
                        if preset == .custom && themeManager.settings.customAccentHex == nil {
                            themeManager.setCustomAccentHex(AppearanceSettings.defaultAccentHex)
                        } else {
                            themeManager.setForegroundPreset(preset)
                        }
                    } label: {
                        VStack(spacing: 5) {
                            ZStack {
                                foregroundSwatchFill(for: preset)
                                    .frame(width: 34, height: 34)
                                    .clipShape(Circle())
                                    .overlay {
                                        Circle()
                                            .strokeBorder(
                                                isSelected ? Color.primary : Color.primary.opacity(0.18),
                                                lineWidth: isSelected ? 2 : 0.75
                                            )
                                    }
                                if isSelected {
                                    Image(systemName: "checkmark")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(.white)
                                } else if preset == .custom {
                                    Image(systemName: "paintpalette")
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(.white)
                                }
                            }
                            Text(preset.displayName)
                                .font(.caption2)
                                .foregroundStyle(isSelected ? themeManager.accentColor : .secondary)
                                .lineLimit(1)
                        }
                        .frame(minWidth: 52)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
    }

    @ViewBuilder
    private var backgroundImageControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                if let previewImage {
                    Image(uiImage: previewImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.75)
                        }
                } else {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color.primary.opacity(0.06))
                        .frame(width: 48, height: 48)
                        .overlay {
                            Image(systemName: "photo")
                                .foregroundStyle(.secondary)
                        }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.tr("Background Image"))
                        .font(.subheadline.weight(.medium))
                    Text(themeManager.hasBackgroundImage ? L10n.tr("Custom image active") : L10n.tr("None"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if themeManager.hasBackgroundImage {
                    Button(L10n.tr("Remove"), role: .destructive) {
                        themeManager.clearBackgroundImage()
                    }
                    .buttonStyle(.borderless)
                    .font(.subheadline)
                }
            }

            HStack(spacing: 12) {
                PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                    Label(L10n.tr("Choose from Photos…"), systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button {
                    showImageFileImporter = true
                } label: {
                    Label(L10n.tr("Choose Image File…"), systemImage: "folder")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            if themeManager.hasBackgroundImage {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(L10n.tr("Image Visibility"))
                        Spacer()
                        Text("\(Int((themeManager.settings.backgroundImageOpacity * 100).rounded()))%")
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Slider(
                        value: Binding(
                            get: { themeManager.settings.backgroundImageOpacity },
                            set: { themeManager.setBackgroundImageOpacity($0) }
                        ),
                        in: AppearanceSettings.backgroundImageOpacityRange
                    )
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    private var generalSection: some View {
        Section(L10n.tr("General")) {
            Picker(L10n.tr("Language"), selection: Binding(
                get: { languageManager.selection },
                set: { languageManager.setLanguage($0) }
            )) {
                ForEach(AppLanguage.allCases) { lang in
                    Text(lang.displayName).tag(lang)
                }
            }
        }
    }

    private var googleDriveSection: some View {
        Section {
            HStack {
                Text(L10n.tr("Account Status"))
                Spacer()
                if googleDriveOAuth.isSignedIn {
                    Text(googleDriveOAuth.accountEmail ?? L10n.tr("Connected"))
                        .foregroundStyle(.green)
                        .lineLimit(1)
                } else {
                    Text(L10n.tr("Not Signed In"))
                        .foregroundStyle(.secondary)
                }
            }

            LabeledContent(L10n.tr("OAuth Client ID")) {
                TextField(
                    L10n.tr("Optional Google Cloud Client ID"),
                    text: Binding(
                        get: { googleDriveOAuth.clientId },
                        set: {
                            googleDriveOAuth.clientId = $0
                            googleDriveOAuth.saveClientCredentials()
                        }
                    )
                )
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            }

            LabeledContent(L10n.tr("OAuth Client Secret")) {
                SecureField(
                    L10n.tr("Optional"),
                    text: Binding(
                        get: { googleDriveOAuth.clientSecret },
                        set: {
                            googleDriveOAuth.clientSecret = $0
                            googleDriveOAuth.saveClientCredentials()
                        }
                    )
                )
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            }

            Button {
                Task { await googleDriveOAuth.startSignIn() }
            } label: {
                HStack {
                    Label(
                        googleDriveOAuth.isSignedIn ? L10n.tr("Reconnect Google Drive") : L10n.tr("Sign In with Google"),
                        systemImage: "person.badge.key"
                    )
                    Spacer()
                    if googleDriveOAuth.isSigningIn {
                        ProgressView()
                            .controlSize(.small)
                    }
                }
            }
            .disabled(googleDriveOAuth.isSigningIn || googleDriveOAuth.clientId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            if googleDriveOAuth.isSignedIn {
                Button(L10n.tr("Sign Out of Google Drive"), role: .destructive) {
                    googleDriveOAuth.signOut()
                }
            }

            if let errorMessage = googleDriveOAuth.errorMessage, !errorMessage.isEmpty {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            Text(L10n.tr("Google Drive Account"))
        } footer: {
            Text(L10n.tr("Sign in with OAuth 2.0 to access your private Google Drive folders and bypass public download confirmation pages. Public links still work without signing in."))
        }
    }

    private var librarySection: some View {
        Section(L10n.tr("Library")) {
            Button {
                guard let library else { return }
                Task { await library.rescan() }
            } label: {
                HStack {
                    Label(L10n.tr("Rescan Library"), systemImage: "arrow.clockwise")
                    Spacer()
                    if library?.isScanning == true {
                        ProgressView()
                            .controlSize(.small)
                    }
                }
            }
            .disabled(library == nil || library?.isScanning == true)
        }
    }

    private var aboutSection: some View {
        Section(L10n.tr("About & Updates")) {
            HStack {
                Text(L10n.tr("Version"))
                Spacer()
                Text(appVersionDisplay)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }

            #if targetEnvironment(macCatalyst)
            Button {
                updateManager.presentManualCheck()
            } label: {
                HStack {
                    Label(L10n.tr("Check for Updates"), systemImage: "arrow.down.circle")
                    Spacer()
                    if updateManager.hasAvailableUpdate {
                        Circle()
                            .fill(themeManager.accentColor)
                            .frame(width: 8, height: 8)
                            .accessibilityLabel(L10n.tr("Update available"))
                    }
                }
            }
            #endif
        }
    }

    // MARK: - Helpers

    private var appVersionDisplay: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String
        if let build, !build.isEmpty, build != short {
            return "\(short) (\(build))"
        }
        return short
    }

    private var customBackgroundColorBinding: Binding<Color> {
        Binding(
            get: {
                themeManager.settings.customBackgroundHex.flatMap { Color(hex: $0) }
                    ?? Color(hex: "#1E293B")!
            },
            set: { newColor in
                if let hex = newColor.toHexString() {
                    themeManager.setCustomBackgroundHex(hex)
                }
            }
        )
    }

    private var customAccentColorBinding: Binding<Color> {
        Binding(
            get: {
                themeManager.settings.customAccentHex.flatMap { Color(hex: $0) }
                    ?? Color(hex: AppearanceSettings.defaultAccentHex)!
            },
            set: { newColor in
                if let hex = newColor.toHexString() {
                    themeManager.setCustomAccentHex(hex)
                }
            }
        )
    }

    private var customTextColorEnabledBinding: Binding<Bool> {
        Binding(
            get: { themeManager.settings.customForegroundHex != nil },
            set: { enabled in
                if enabled {
                    let fallback = themeManager.resolvedForegroundHex ?? AppearanceSettings.lightForegroundHex
                    themeManager.setCustomForegroundHex(fallback)
                } else {
                    themeManager.setCustomForegroundHex(nil)
                }
            }
        )
    }

    private var customTextColorBinding: Binding<Color> {
        Binding(
            get: {
                themeManager.settings.customForegroundHex.flatMap { Color(hex: $0) }
                    ?? themeManager.primaryForegroundColor
                    ?? Color.primary
            },
            set: { newColor in
                if let hex = newColor.toHexString() {
                    themeManager.setCustomForegroundHex(hex)
                }
            }
        )
    }

    @ViewBuilder
    private func backgroundSwatchFill(for preset: BackgroundColorPreset) -> some View {
        switch preset {
        case .default:
            LinearGradient(
                colors: [Color(.systemBackground), Color(.secondarySystemBackground)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .custom:
            if let hex = themeManager.settings.customBackgroundHex, let color = Color(hex: hex) {
                color
            } else {
                AngularGradient(
                    colors: [.red, .orange, .yellow, .green, .blue, .purple, .red],
                    center: .center
                )
            }
        default:
            Color(hex: preset.hexString ?? "") ?? Color(.systemBackground)
        }
    }

    private func swatchCheckmarkColor(for preset: BackgroundColorPreset) -> Color {
        switch preset {
        case .default:
            return .primary
        case .custom:
            if let hex = themeManager.settings.customBackgroundHex,
               let lum = ThemeColorHex.relativeLuminance(hex: hex) {
                return lum < 0.5 ? .white : .black
            }
            return .white
        default:
            if let hex = preset.hexString,
               let lum = ThemeColorHex.relativeLuminance(hex: hex) {
                return lum < 0.5 ? .white : .black
            }
            return .primary
        }
    }

    @ViewBuilder
    private func foregroundSwatchFill(for preset: ForegroundColorPreset) -> some View {
        switch preset {
        case .custom:
            if let hex = themeManager.settings.customAccentHex, let color = Color(hex: hex) {
                color
            } else {
                AngularGradient(
                    colors: [.red, .orange, .yellow, .green, .blue, .purple, .red],
                    center: .center
                )
            }
        default:
            Color(hex: preset.accentHex ?? AppearanceSettings.defaultAccentHex)!
        }
    }

    private func refreshPreviewImage() async {
        guard let url = themeManager.backgroundImageURL else {
            previewImage = nil
            return
        }
        let loaded = await Task.detached(priority: .userInitiated) {
            UIImage(contentsOfFile: url.path)
        }.value
        if !Task.isCancelled {
            previewImage = loaded
        }
    }

    private func importFromPhotoPicker(_ item: PhotosPickerItem) async {
        defer { selectedPhotoItem = nil }
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                imageError = ThemeAppearanceError.unreadableImage.localizedDescription
                return
            }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            try themeManager.importBackgroundImage(data: data, fileExtension: ext)
        } catch {
            imageError = error.localizedDescription
        }
    }
}
