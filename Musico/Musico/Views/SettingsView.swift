import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SettingsView: View {
    var isSheet: Bool = false

    @Environment(\.themeManager) private var themeManager
    @Environment(\.languageManager) private var languageManager
    @Environment(\.colorScheme) private var colorScheme
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
                .listRowBackground(settingsRowBackground)
            generalSection
                .listRowBackground(settingsRowBackground)
            googleDriveSection
                .listRowBackground(settingsRowBackground)
            librarySection
                .listRowBackground(settingsRowBackground)
            aboutSection
                .listRowBackground(settingsRowBackground)
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
        let effectiveScheme = themeManager.preferredColorScheme ?? colorScheme
        return Section(L10n.tr("Appearance")) {
            appearanceLivePreviewCard

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

            if themeManager.hasLowContrast(fallbackScheme: effectiveScheme) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L10n.tr("Low Contrast Warning"))
                                .font(.subheadline.weight(.semibold))
                            Text(L10n.tr("Current text or accent color may be hard to read against the background."))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button(L10n.tr("Auto-fix Contrast")) {
                        themeManager.autoFixContrast(fallbackScheme: effectiveScheme)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(.vertical, 4)
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

            VStack(alignment: .leading, spacing: 10) {
                Text(L10n.tr("Sidebar Background Color"))
                    .font(.subheadline.weight(.medium))
                sidebarBackgroundPresetRow
            }
            .padding(.vertical, 4)

            if themeManager.settings.sidebarBackgroundPreset == .custom {
                ColorPicker(
                    L10n.tr("Custom Sidebar Color"),
                    selection: customSidebarBackgroundColorBinding,
                    supportsOpacity: false
                )
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(L10n.tr("Sidebar Opacity"))
                    Spacer()
                    Text("\(Int((themeManager.effectiveSidebarOpacity * 100).rounded()))%")
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(
                    value: Binding(
                        get: { themeManager.effectiveSidebarOpacity },
                        set: { themeManager.setSidebarOpacity($0) }
                    ),
                    in: AppearanceSettings.sidebarOpacityRange
                )
            }
            .padding(.vertical, 2)
            #endif

            Button(L10n.tr("Reset to Default"), role: .destructive) {
                themeManager.resetToDefaults()
            }
        }
    }

    private var appearanceLivePreviewCard: some View {
        let bgColor = themeManager.activeBackgroundColor ?? Color(.secondarySystemBackground)
        let fgPrimary = themeManager.primaryForegroundColor ?? Color.primary
        let fgSecondary = themeManager.secondaryForegroundColor ?? Color.secondary
        let accent = themeManager.accentColor
        let windowOpacity = themeManager.effectiveBackgroundOpacity

        let mainPreview = ZStack {
            ZStack {
                bgColor
                if let previewImage {
                    GeometryReader { proxy in
                        Image(uiImage: previewImage)
                            .resizable()
                            .scaledToFill()
                            .frame(width: proxy.size.width, height: proxy.size.height)
                            .blur(radius: themeManager.settings.backgroundImageBlur * 0.6)
                            .clipped()
                            .opacity(themeManager.settings.backgroundImageOpacity)
                    }
                }
            }
            .compositingGroup()
            .opacity(windowOpacity)

            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(accent.opacity(0.20))
                    .frame(width: 44, height: 44)
                    .overlay {
                        Image(systemName: "music.note")
                            .font(.headline)
                            .foregroundStyle(accent)
                    }

                VStack(alignment: .leading, spacing: 3) {
                    Text(L10n.tr("Now Playing Preview"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(fgPrimary)
                        .lineLimit(1)
                    Text(L10n.tr("Sample Artist — Sample Album"))
                        .font(.caption)
                        .foregroundStyle(fgSecondary)
                        .lineLimit(1)
                    Capsule()
                        .fill(fgSecondary.opacity(0.25))
                        .frame(height: 4)
                        .overlay(alignment: .leading) {
                            GeometryReader { barProxy in
                                Capsule()
                                    .fill(accent)
                                    .frame(width: barProxy.size.width * 0.62, height: 4)
                            }
                        }
                        .padding(.top, 3)
                }

                Spacer(minLength: 4)

                Image(systemName: "play.circle.fill")
                    .font(.title2)
                    .foregroundStyle(accent)
            }
            .padding(12)
        }

        return Group {
            #if targetEnvironment(macCatalyst)
            let sidebarColor = (themeManager.activeSidebarBackgroundColor
                ?? themeManager.activeBackgroundColor
                ?? Color(.tertiarySystemBackground))
                .opacity(themeManager.effectiveSidebarOpacity)
            let sidebarFg = themeManager.sidebarPrimaryForegroundColor ?? fgPrimary

            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(accent.opacity(0.32))
                        .frame(width: 34, height: 8)
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(sidebarFg.opacity(0.35))
                        .frame(width: 28, height: 6)
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(sidebarFg.opacity(0.25))
                        .frame(width: 32, height: 6)
                }
                .padding(.horizontal, 8)
                .frame(width: 52)
                .frame(maxHeight: .infinity)
                .background(sidebarColor)

                Divider()

                mainPreview
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            #else
            mainPreview
            #endif
        }
        .frame(height: 76)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.75)
        }
        .padding(.vertical, 4)
    }

    private var backgroundPresetRow: some View {
        backgroundColorPresetRow(
            selectedPreset: themeManager.settings.backgroundPreset,
            customHex: themeManager.settings.customBackgroundHex,
            defaultTitle: BackgroundColorPreset.default.displayName,
            defaultColor: nil,
            defaultLuminance: nil
        ) { preset in
            if preset == .custom && themeManager.settings.customBackgroundHex == nil {
                themeManager.setCustomBackgroundHex("#1E293B")
            } else {
                themeManager.setBackgroundPreset(preset)
            }
        }
    }

    private var sidebarBackgroundPresetRow: some View {
        backgroundColorPresetRow(
            selectedPreset: themeManager.settings.sidebarBackgroundPreset,
            customHex: themeManager.settings.customSidebarBackgroundHex,
            defaultTitle: L10n.tr("Follow Main"),
            defaultColor: themeManager.activeBackgroundColor,
            defaultLuminance: themeManager.activeBackgroundLuminance
        ) { preset in
            if preset == .custom && themeManager.settings.customSidebarBackgroundHex == nil {
                themeManager.setCustomSidebarBackgroundHex("#1E293B")
            } else {
                themeManager.setSidebarBackgroundPreset(preset)
            }
        }
    }

    private func backgroundColorPresetRow(
        selectedPreset: BackgroundColorPreset,
        customHex: String?,
        defaultTitle: String,
        defaultColor: Color?,
        defaultLuminance: Double?,
        onSelect: @escaping (BackgroundColorPreset) -> Void
    ) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(BackgroundColorPreset.allCases) { preset in
                    let isSelected = selectedPreset == preset
                    Button {
                        onSelect(preset)
                    } label: {
                        VStack(spacing: 5) {
                            ZStack {
                                backgroundSwatchFill(for: preset, customHex: customHex, defaultColor: defaultColor)
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
                                        .foregroundStyle(swatchCheckmarkColor(for: preset, customHex: customHex, defaultLuminance: defaultLuminance))
                                } else if preset == .custom {
                                    Image(systemName: "eyedropper")
                                        .font(.caption2.weight(.semibold))
                                        .foregroundStyle(swatchCheckmarkColor(for: preset, customHex: customHex, defaultLuminance: defaultLuminance))
                                }
                            }
                            Text(preset == .default ? defaultTitle : preset.displayName)
                                .font(.caption2)
                                .foregroundStyle(isSelected ? themeManager.accentColor : .secondary)
                                .lineLimit(1)
                        }
                        .frame(minWidth: 52, minHeight: 52)
                        .contentShape(Rectangle())
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
                        .frame(minWidth: 52, minHeight: 52)
                        .contentShape(Rectangle())
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

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(L10n.tr("Background Blur"))
                        Spacer()
                        Text("\(Int(themeManager.settings.backgroundImageBlur.rounded())) pt")
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Slider(
                        value: Binding(
                            get: { themeManager.settings.backgroundImageBlur },
                            set: { themeManager.setBackgroundImageBlur($0) }
                        ),
                        in: AppearanceSettings.backgroundImageBlurRange,
                        step: 1
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

    private var settingsRowBackground: Color? {
        guard themeManager.hasCustomBackdrop else { return nil }
        return (themeManager.activeBackgroundColor ?? Color(.secondarySystemGroupedBackground))
            .opacity(themeManager.translucentSurfaceOpacity)
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

    private var customSidebarBackgroundColorBinding: Binding<Color> {
        Binding(
            get: {
                themeManager.settings.customSidebarBackgroundHex.flatMap { Color(hex: $0) }
                    ?? Color(hex: "#1E293B")!
            },
            set: { newColor in
                if let hex = newColor.toHexString() {
                    themeManager.setCustomSidebarBackgroundHex(hex)
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
    private func backgroundSwatchFill(for preset: BackgroundColorPreset, customHex: String?, defaultColor: Color?) -> some View {
        switch preset {
        case .default:
            if let defaultColor {
                defaultColor
            } else {
                LinearGradient(
                    colors: [Color(.systemBackground), Color(.secondarySystemBackground)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            }
        case .custom:
            if let hex = customHex, let color = Color(hex: hex) {
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

    private func swatchCheckmarkColor(for preset: BackgroundColorPreset, customHex: String?, defaultLuminance: Double?) -> Color {
        switch preset {
        case .default:
            if let defaultLuminance {
                return defaultLuminance < 0.5 ? .white : .black
            }
            return .primary
        case .custom:
            if let hex = customHex,
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
