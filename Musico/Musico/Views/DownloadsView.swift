import SwiftUI

struct DownloadsListView: View {
    private let explicitManager: DownloadManager?
    @Environment(\.downloadManager) private var envManager
    @Environment(\.dismiss) private var dismiss

    init(downloadManager: DownloadManager? = nil) {
        self.explicitManager = downloadManager
    }

    private var manager: DownloadManager {
        explicitManager ?? envManager
    }

    var body: some View {
        NavigationStack {
            Group {
                if manager.jobs.isEmpty {
                    ContentUnavailableView(
                        L10n.tr("No Downloads"),
                        systemImage: "arrow.down.circle",
                        description: Text(L10n.tr("Background cloud downloads and folder updates appear here."))
                    )
                } else {
                    List {
                        if !manager.activeJobs.isEmpty {
                            Section(L10n.tr("Active")) {
                                ForEach(manager.activeJobs) { job in
                                    jobRow(job)
                                }
                            }
                        }
                        if !manager.finishedJobs.isEmpty {
                            Section(L10n.tr("Completed")) {
                                ForEach(manager.finishedJobs) { job in
                                    jobRow(job)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(L10n.tr("Downloads"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !manager.finishedJobs.isEmpty {
                        Button(L10n.tr("Clear Finished")) {
                            manager.clearFinished()
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.tr("Done")) {
                        dismiss()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func jobRow(_ job: DownloadJob) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 8) {
                Image(systemName: iconName(for: job))
                    .foregroundStyle(iconColor(for: job))
                VStack(alignment: .leading, spacing: 2) {
                    Text(job.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(kindLabel(for: job.kind))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if job.state == .running {
                    Button(L10n.tr("Cancel"), role: .cancel) {
                        manager.cancel(jobID: job.id)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                } else {
                    Button(L10n.tr("Dismiss")) {
                        manager.dismiss(jobID: job.id)
                    }
                    .buttonStyle(.borderless)
                    .font(.caption)
                }
            }

            if job.state == .running {
                if let fraction = job.overallFraction {
                    ProgressView(value: fraction)
                } else {
                    ProgressView()
                }

                DownloadProgressMetricsView(
                    job: job,
                    stepText: job.totalTracks > 1
                        ? L10n.tr("Saving %@ of %@ songs…", String(min(job.completedTracks + 1, job.totalTracks)), String(job.totalTracks))
                        : (!job.currentTrackTitle.isEmpty ? job.currentTrackTitle : L10n.tr("Preparing…")),
                    showTrackTitle: job.totalTracks > 1
                )
            } else {
                if let summary = job.summaryMessage, !summary.isEmpty {
                    Text(summary)
                        .font(.caption)
                        .foregroundStyle(statusColor(for: job.state))
                }
                if !job.errors.isEmpty {
                    ForEach(Array(job.errors.prefix(3).enumerated()), id: \.offset) { _, errorText in
                        Text(errorText)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func kindLabel(for kind: DownloadJob.Kind) -> String {
        switch kind {
        case .playlistExport: L10n.tr("Save Cloud Playlist")
        case .singleTrackExport: L10n.tr("Save Song")
        case .folderUpdate: L10n.tr("Update from Cloud")
        }
    }

    private func iconName(for job: DownloadJob) -> String {
        switch job.state {
        case .running: "arrow.down.circle.fill"
        case .completed: job.errors.isEmpty ? "checkmark.circle.fill" : "exclamationmark.circle.fill"
        case .cancelled: "xmark.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    private func iconColor(for job: DownloadJob) -> Color {
        switch job.state {
        case .running: .accentColor
        case .completed: job.errors.isEmpty ? .green : .orange
        case .cancelled: .secondary
        case .failed: .red
        }
    }

    private func statusColor(for state: DownloadJob.State) -> Color {
        switch state {
        case .running: .secondary
        case .completed: .green
        case .cancelled: .secondary
        case .failed: .red
        }
    }
}

struct SidebarDownloadsWidget: View {
    private let explicitManager: DownloadManager?
    let onOpenDownloads: () -> Void
    @Environment(\.downloadManager) private var envManager

    init(downloadManager: DownloadManager? = nil, onOpenDownloads: @escaping () -> Void) {
        self.explicitManager = downloadManager
        self.onOpenDownloads = onOpenDownloads
    }

    private var manager: DownloadManager {
        explicitManager ?? envManager
    }

    var body: some View {
        if let featured = manager.activeJobs.first ?? manager.jobs.first {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Button(action: onOpenDownloads) {
                        HStack(spacing: 6) {
                            Image(systemName: featured.state == .running ? "arrow.down.circle.fill" : "checkmark.circle.fill")
                                .foregroundStyle(featured.state == .running ? Color.accentColor : Color.green)
                                .font(.caption)
                            Text(headerTitle(for: featured))
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                            Spacer(minLength: 4)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if featured.state == .running {
                        Button {
                            manager.cancel(jobID: featured.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help(L10n.tr("Cancel"))
                        .accessibilityLabel(L10n.tr("Cancel"))
                    } else {
                        Button {
                            manager.dismiss(jobID: featured.id)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help(L10n.tr("Dismiss"))
                        .accessibilityLabel(L10n.tr("Dismiss"))
                    }
                }

                if featured.state == .running {
                    if let fraction = featured.overallFraction {
                        ProgressView(value: fraction)
                            .controlSize(.small)
                    } else {
                        ProgressView()
                            .controlSize(.small)
                    }
                    HStack(spacing: 4) {
                        if let fraction = featured.overallFraction {
                            Text(DownloadManager.formattedPercentage(fraction))
                        }
                        Spacer(minLength: 4)
                        if featured.bytesPerSecond > 0 {
                            Text(DownloadManager.formattedSpeed(featured.bytesPerSecond))
                        } else if featured.totalTracks > 1 {
                            Text("\(min(featured.completedTracks + 1, featured.totalTracks))/\(featured.totalTracks)")
                        }
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                } else if let summary = featured.summaryMessage {
                    Button(action: onOpenDownloads) {
                        Text(summary)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(8)
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .padding(.horizontal, 6)
            .padding(.bottom, 4)
        }
    }

    private func headerTitle(for job: DownloadJob) -> String {
        let activeCount = manager.activeJobs.count
        if activeCount > 1 {
            return L10n.tr("%@ Downloads Active", String(activeCount))
        }
        return job.title
    }
}

struct DownloadProgressMetricsView: View {
    let job: DownloadJob
    let stepText: String
    var showTrackTitle: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(stepText)
                    .lineLimit(1)
                Spacer()
                if let fraction = job.overallFraction {
                    Text(DownloadManager.formattedPercentage(fraction))
                        .monospacedDigit()
                        .fontWeight(.medium)
                }
            }
            .font(.subheadline)

            if showTrackTitle, !job.currentTrackTitle.isEmpty {
                Text(job.currentTrackTitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            if job.bytesDownloaded > 0 || job.bytesPerSecond > 0 {
                HStack(spacing: 8) {
                    if let total = job.totalBytes, total > 0 {
                        Text("\(DownloadManager.formattedBytes(job.bytesDownloaded)) / \(DownloadManager.formattedBytes(total))")
                    } else {
                        Text(DownloadManager.formattedBytes(job.bytesDownloaded))
                    }
                    Spacer()
                    if job.bytesPerSecond > 0 {
                        Text(DownloadManager.formattedSpeed(job.bytesPerSecond))
                    }
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }
}
