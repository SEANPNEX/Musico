import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Presents an in-app QR code sign-in flow driven by `startQrLogin` / `pollQrLogin` extension hooks.
struct ExtensionQRLoginSheet: View {
    let extensionItem: InstalledExtension
    private let explicitStore: ExtensionStore?

    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.dismiss) private var dismiss

    @State private var session: ExtensionQRLoginSession?
    @State private var qrImage: UIImage?
    @State private var pollResult: ExtensionQRLoginPollResult?
    @State private var errorMessage: String?
    @State private var isLoadingStart = true
    @State private var reloadToken = 0

    private static let ciContext = CIContext()

    init(extensionItem: InstalledExtension, extensionStore: ExtensionStore? = nil) {
        self.extensionItem = extensionItem
        self.explicitStore = extensionStore
    }

    private var extensionStore: ExtensionStore {
        explicitStore ?? envExtensionStore
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer(minLength: 0)

                if isLoadingStart {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(L10n.tr("Generating QR code…"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } else if let errorMessage {
                    statusFailureView(
                        symbol: "exclamationmark.triangle",
                        tint: .orange,
                        title: L10n.tr("QR Code Login Failed"),
                        message: errorMessage
                    )
                } else if pollResult?.status == .expired {
                    statusFailureView(
                        symbol: "clock.arrow.circlepath",
                        tint: .orange,
                        title: L10n.tr("QR Code Expired"),
                        message: pollResult?.message ?? L10n.tr("Refresh to generate a new QR code.")
                    )
                } else if pollResult?.status == .error {
                    statusFailureView(
                        symbol: "exclamationmark.triangle",
                        tint: .red,
                        title: L10n.tr("QR Code Login Failed"),
                        message: pollResult?.message ?? L10n.tr("Could not complete QR code login.")
                    )
                } else if pollResult?.status == .confirmed {
                    VStack(spacing: 14) {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(.green)
                        Text(L10n.tr("Signed In"))
                            .font(.title3.weight(.semibold))
                        if let account = pollResult?.accountName, !account.isEmpty {
                            Text(account)
                                .font(.subheadline.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 4)
                                .background(Color.green.opacity(0.14), in: Capsule())
                                .foregroundStyle(.green)
                        }
                        Text(pollResult?.message ?? L10n.tr("Session cookie saved to %@.", extensionItem.manifest.name))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 24)
                } else if let qrImage {
                    VStack(spacing: 16) {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .padding(16)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .shadow(color: .black.opacity(0.08), radius: 8, y: 2)

                        if pollResult?.status == .scanned {
                            VStack(spacing: 6) {
                                Label(L10n.tr("Scanned — Confirm on Your Phone"), systemImage: "iphone.radiowaves.left.and.right")
                                    .font(.headline)
                                    .foregroundStyle(Color.accentColor)
                                if let account = pollResult?.accountName, !account.isEmpty {
                                    Text(account)
                                        .font(.subheadline.weight(.medium))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 3)
                                        .background(Color.accentColor.opacity(0.14), in: Capsule())
                                        .foregroundStyle(Color.accentColor)
                                }
                            }
                        } else {
                            VStack(spacing: 4) {
                                Text(L10n.tr("Scan with %@ App", extensionItem.manifest.effectiveBadgeLabel))
                                    .font(.headline)
                                Text(pollResult?.message ?? session?.message ?? L10n.tr("Waiting for QR code scan…"))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                            }
                        }

                        Button {
                            reloadToken += 1
                        } label: {
                            Label(L10n.tr("Refresh QR Code"), systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 24)
                }

                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(L10n.tr("QR Code Login"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(pollResult?.status == .confirmed ? L10n.tr("Done") : L10n.tr("Cancel")) {
                        dismiss()
                    }
                }
            }
            .task(id: reloadToken) {
                await runQRLoginFlow()
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 380, idealWidth: 420, minHeight: 440, idealHeight: 480)
        #endif
    }

    @ViewBuilder
    private func statusFailureView(symbol: String, tint: Color, title: String, message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 46))
                .foregroundStyle(tint)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button {
                reloadToken += 1
            } label: {
                Label(L10n.tr("Refresh QR Code"), systemImage: "arrow.clockwise")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 24)
    }

    private func runQRLoginFlow() async {
        isLoadingStart = true
        errorMessage = nil
        pollResult = nil
        qrImage = nil
        session = nil

        do {
            let started = try await extensionStore.startQRLogin(extensionID: extensionItem.id)
            guard !Task.isCancelled else { return }
            guard let rendered = Self.makeQRCodeImage(from: started.qrContent) else {
                errorMessage = L10n.tr("Couldn’t generate QR code.")
                isLoadingStart = false
                return
            }
            session = started
            qrImage = rendered
            isLoadingStart = false

            while !Task.isCancelled {
                if let expiresAtMs = started.expiresAtMs,
                   Double(Date().timeIntervalSince1970 * 1000.0) >= expiresAtMs {
                    pollResult = ExtensionQRLoginPollResult(
                        status: .expired,
                        cookie: nil,
                        accountName: nil,
                        message: L10n.tr("Refresh to generate a new QR code.")
                    )
                    break
                }
                try await Task.sleep(for: .milliseconds(started.pollIntervalMs))
                guard !Task.isCancelled else { return }
                let next = try await extensionStore.pollQRLogin(
                    extensionID: extensionItem.id,
                    sessionID: started.sessionId
                )
                guard !Task.isCancelled else { return }
                pollResult = next
                switch next.status {
                case .pending, .scanned:
                    continue
                case .confirmed:
                    try? await Task.sleep(for: .milliseconds(1200))
                    if !Task.isCancelled {
                        dismiss()
                    }
                    return
                case .expired, .error:
                    return
                }
            }
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = error.localizedDescription
            isLoadingStart = false
        }
    }

    static func makeQRCodeImage(from text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = ciContext.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
