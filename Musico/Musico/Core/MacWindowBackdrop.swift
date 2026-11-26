#if targetEnvironment(macCatalyst)
import ObjectiveC.runtime
import SwiftUI
import UIKit

/// Updates the host UIWindow and underlying NSWindow background opacity without altering
/// window `alphaValue`, keeping text, controls, and artwork 100% opaque.
struct MacWindowTransparencyApplier: UIViewRepresentable {
    let isTranslucent: Bool

    func makeUIView(context: Context) -> BackdropProbeView {
        let view = BackdropProbeView()
        view.isUserInteractionEnabled = false
        view.isOpaque = false
        view.backgroundColor = .clear
        view.isTranslucent = isTranslucent
        return view
    }

    func updateUIView(_ uiView: BackdropProbeView, context: Context) {
        uiView.isTranslucent = isTranslucent
        uiView.applyToHostWindow()
    }

    final class BackdropProbeView: UIView {
        var isTranslucent = false

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyToHostWindow()
        }

        func applyToHostWindow() {
            guard let window else { return }
            window.isOpaque = !isTranslucent
            window.backgroundColor = isTranslucent ? .clear : nil
            window.rootViewController?.view.isOpaque = !isTranslucent
            window.rootViewController?.view.backgroundColor = isTranslucent ? .clear : nil
            Self.applyNSWindowTransparency(isTranslucent: isTranslucent, hostUIWindow: window)
        }

        private static func applyNSWindowTransparency(isTranslucent: Bool, hostUIWindow: UIWindow) {
            guard let appClass = NSClassFromString("NSApplication") as? AnyObject else { return }
            let sharedSel = NSSelectorFromString("sharedApplication")
            guard appClass.responds(to: sharedSel),
                  let nsApp = appClass.perform(sharedSel)?.takeUnretainedValue() else {
                return
            }

            let windowsSel = NSSelectorFromString("windows")
            guard nsApp.responds(to: windowsSel),
                  let nsWindows = nsApp.perform(windowsSel)?.takeUnretainedValue() as? [AnyObject] else {
                return
            }

            guard let colorClass = NSClassFromString("NSColor") as? AnyObject else { return }
            let colorSel = NSSelectorFromString(isTranslucent ? "clearColor" : "windowBackgroundColor")
            guard colorClass.responds(to: colorSel),
                  let nsColor = colorClass.perform(colorSel)?.takeUnretainedValue() else {
                return
            }

            let matched = nsWindows.filter { nsWindow in
                let uiWindowsSel = NSSelectorFromString("uiWindows")
                if nsWindow.responds(to: uiWindowsSel),
                   let uiWindows = nsWindow.perform(uiWindowsSel)?.takeUnretainedValue() as? [AnyObject] {
                    return uiWindows.contains { $0 === hostUIWindow }
                }
                return false
            }
            let targets = matched.isEmpty ? nsWindows : matched

            let setOpaqueSel = NSSelectorFromString("setOpaque:")
            let setBgSel = NSSelectorFromString("setBackgroundColor:")

            for nsWindow in targets {
                if nsWindow.responds(to: setOpaqueSel),
                   let method = class_getInstanceMethod(type(of: nsWindow), setOpaqueSel) {
                    typealias SetOpaqueIMP = @convention(c) (AnyObject, Selector, ObjCBool) -> Void
                    let imp = unsafeBitCast(method_getImplementation(method), to: SetOpaqueIMP.self)
                    imp(nsWindow, setOpaqueSel, ObjCBool(!isTranslucent))
                }
                if nsWindow.responds(to: setBgSel) {
                    _ = nsWindow.perform(setBgSel, with: nsColor)
                }
            }
        }
    }
}
#endif
