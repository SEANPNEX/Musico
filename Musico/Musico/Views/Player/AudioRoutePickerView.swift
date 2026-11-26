import AVKit
import SwiftUI
import UIKit

struct AudioRoutePickerView: UIViewRepresentable {
    var tintColor: UIColor = .label
    var activeTintColor: UIColor = .systemRed

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        picker.tintColor = tintColor
        picker.activeTintColor = activeTintColor
        picker.accessibilityLabel = L10n.tr("Audio Output")
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        uiView.tintColor = tintColor
        uiView.activeTintColor = activeTintColor
        uiView.accessibilityLabel = L10n.tr("Audio Output")
    }
}
