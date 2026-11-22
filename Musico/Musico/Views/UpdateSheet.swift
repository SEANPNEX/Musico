#if targetEnvironment(macCatalyst)
import SwiftUI

/// Displays software update status, release notes, download progress, and DMG installation actions.
struct UpdateSheet: View {
    @Environment(\.updateManager) private var updateManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                content
                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .navigationTitle("Software Update")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .frame(minWidth: 440, minHeight: 360)
    }

    @ViewBuilder
    private var content: some View {
        switch updateManager.state {
        case .idle, .checking:
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Checking for updates…")
                        .font(.headline)
                    Text("Current version: \(updateManager.currentVersion.description)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

        case .upToDate:
            Label("Musico is Up to Date", systemImage: "checkmark.circle.fill")
                .font(.headline)
                .foregroundStyle(.green)
            Text("Version \(updateManager.currentVersion.description) is the latest available release.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("Check Again") {
                    Task { await updateManager.checkForUpdates(manual: true) }
                }
                .buttonStyle(.borderedProminent)
                Button("Open Release Page") {
                    Task { await updateManager.openReleasePage() }
                }
                .buttonStyle(.bordered)
            }

        case .available(let release):
            releaseHeader(release, subtitle: "Update Available")
            releaseNotes(release)
            HStack(spacing: 12) {
                Button("Download & Install Update") {
                    Task { await updateManager.downloadAndOpenUpdate() }
                }
                .buttonStyle(.borderedProminent)
                .disabled(release.dmgAsset == nil)

                Button("Skip Version") {
                    updateManager.skipVersion(release.version)
                    dismiss()
                }
                .buttonStyle(.bordered)

                Spacer()

                Button("Open Release Page") {
                    Task { await updateManager.openReleasePage() }
                }
                .buttonStyle(.bordered)
            }

        case .downloading(let release, let progress):
            releaseHeader(release, subtitle: "Downloading Update…")
            releaseNotes(release)
            VStack(alignment: .leading, spacing: 6) {
                if progress > 0 {
                    ProgressView(value: progress)
                } else {
                    ProgressView()
                }
                HStack {
                    Text("Downloading \(release.dmgAsset?.name ?? "installer")…")
                    Spacer()
                    if progress > 0 {
                        Text("\(Int((progress * 100).rounded()))%")
                            .monospacedDigit()
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Button("Open Release Page") {
                Task { await updateManager.openReleasePage() }
            }
            .buttonStyle(.bordered)

        case .installing(let release):
            releaseHeader(release, subtitle: "Installing Update…")
            HStack(spacing: 12) {
                ProgressView()
                Text("Replacing application bundle and relaunching Musico \(release.version.description)…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            releaseNotes(release)

        case .readyToInstall(let release, _):
            releaseHeader(release, subtitle: "Ready to Install")
            Label("Update verified and ready.", systemImage: "checkmark.circle.fill")
                .font(.subheadline)
                .foregroundStyle(.green)
            releaseNotes(release)
            HStack(spacing: 12) {
                Button("Install & Relaunch") {
                    Task { await updateManager.downloadAndOpenUpdate() }
                }
                .buttonStyle(.borderedProminent)

                Button("Open Release Page") {
                    Task { await updateManager.openReleasePage() }
                }
                .buttonStyle(.bordered)
            }

        case .error(let message):
            Label("Update Error", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("Check Again") {
                    Task { await updateManager.checkForUpdates(manual: true) }
                }
                .buttonStyle(.borderedProminent)

                Button("Open Release Page") {
                    Task { await updateManager.openReleasePage() }
                }
                .buttonStyle(.bordered)
            }
        }
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
        GroupBox("Release Notes") {
            ScrollView {
                Text(release.body.isEmpty ? "No release notes provided." : release.body)
                    .font(.callout)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 120, maxHeight: 220)
        }
    }
}
#endif
