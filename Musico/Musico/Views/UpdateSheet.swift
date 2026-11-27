#if targetEnvironment(macCatalyst)
import SwiftUI

/// Displays software update status, release notes, download progress, and DMG installation actions.
struct UpdateSheet: View {
    @Environment(\.updateManager) private var updateManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let sheet = NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                content
                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background { ThemeBackdropView() }
            .navigationTitle(L10n.tr("Software Update"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Text(L10n.tr("Done"))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                }
            }
        }
        .frame(minWidth: 580, idealWidth: 600, minHeight: 400, idealHeight: 440)

        if #available(iOS 18.0, macCatalyst 18.0, *) {
            sheet.presentationSizing(.fitted)
        } else {
            sheet
        }
    }

    @ViewBuilder
    private var content: some View {
        switch updateManager.state {
        case .idle, .checking:
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.tr("Checking for updates…"))
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(L10n.tr("Current version: %@", updateManager.currentVersion.description))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

        case .upToDate:
            Label(L10n.tr("Musico is Up to Date"), systemImage: "checkmark.circle.fill")
                .font(.headline)
                .foregroundStyle(.green)
            Text(L10n.tr("Version %@ is the latest available release.", updateManager.currentVersion.description))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    checkAgainButton
                    openReleasePageButton
                }
                VStack(alignment: .leading, spacing: 10) {
                    checkAgainButton
                    openReleasePageButton
                }
            }

        case .available(let release):
            releaseHeader(release, subtitle: L10n.tr("Update Available"))
            releaseNotes(release)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    downloadAndInstallButton(disabled: release.dmgAsset == nil)
                    skipVersionButton(release: release)
                    Spacer(minLength: 12)
                    openReleasePageButton
                }
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        downloadAndInstallButton(disabled: release.dmgAsset == nil)
                        skipVersionButton(release: release)
                    }
                    openReleasePageButton
                }
                VStack(alignment: .leading, spacing: 10) {
                    downloadAndInstallButton(disabled: release.dmgAsset == nil)
                    skipVersionButton(release: release)
                    openReleasePageButton
                }
            }

        case .downloading(let release, let progress):
            releaseHeader(release, subtitle: L10n.tr("Downloading Update…"))
            releaseNotes(release)
            VStack(alignment: .leading, spacing: 6) {
                if progress > 0 {
                    ProgressView(value: progress)
                } else {
                    ProgressView()
                }
                HStack {
                    Text(L10n.tr("Downloading %@…", release.dmgAsset?.name ?? L10n.tr("installer")))
                    Spacer()
                    if progress > 0 {
                        Text("\(Int((progress * 100).rounded()))%")
                            .monospacedDigit()
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            openReleasePageButton

        case .installing(let release):
            releaseHeader(release, subtitle: L10n.tr("Installing Update…"))
            HStack(spacing: 12) {
                ProgressView()
                Text(L10n.tr("Replacing application bundle and relaunching Musico %@…", release.version.description))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            releaseNotes(release)

        case .readyToInstall(let release, _):
            releaseHeader(release, subtitle: L10n.tr("Ready to Install"))
            Label(L10n.tr("Update verified and ready."), systemImage: "checkmark.circle.fill")
                .font(.subheadline)
                .foregroundStyle(.green)
            releaseNotes(release)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    installAndRelaunchButton
                    openReleasePageButton
                }
                VStack(alignment: .leading, spacing: 10) {
                    installAndRelaunchButton
                    openReleasePageButton
                }
            }

        case .error(let message):
            Label(L10n.tr("Update Error"), systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    checkAgainButton
                    openReleasePageButton
                }
                VStack(alignment: .leading, spacing: 10) {
                    checkAgainButton
                    openReleasePageButton
                }
            }
        }
    }

    private var checkAgainButton: some View {
        Button {
            Task { await updateManager.checkForUpdates(manual: true) }
        } label: {
            Text(L10n.tr("Check Again"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
    }

    private func downloadAndInstallButton(disabled: Bool) -> some View {
        Button {
            Task { await updateManager.downloadAndOpenUpdate() }
        } label: {
            Text(L10n.tr("Download & Install Update"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
        .disabled(disabled)
    }

    private func skipVersionButton(release: ReleaseInfo) -> some View {
        Button {
            updateManager.skipVersion(release.version)
            dismiss()
        } label: {
            Text(L10n.tr("Skip Version"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.bordered)
    }

    private var installAndRelaunchButton: some View {
        Button {
            Task { await updateManager.downloadAndOpenUpdate() }
        } label: {
            Text(L10n.tr("Install & Relaunch"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
    }

    private var openReleasePageButton: some View {
        Button {
            Task { await updateManager.openReleasePage() }
        } label: {
            Text(L10n.tr("Open Release Page"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.bordered)
    }

    private func releaseHeader(_ release: ReleaseInfo, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(subtitle)
                .font(.headline)
            Text("\(updateManager.currentVersion.description) \u{2192} \(release.version.description)")
                .font(.title3.weight(.semibold))
                .monospacedDigit()
            if release.name != release.tagName {
                Text(release.name)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func releaseNotes(_ release: ReleaseInfo) -> some View {
        GroupBox(L10n.tr("Release Notes")) {
            ScrollView {
                Text(release.body.isEmpty ? L10n.tr("No release notes provided.") : release.body)
                    .font(.callout)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 120, maxHeight: 220)
        }
    }
}
#endif
