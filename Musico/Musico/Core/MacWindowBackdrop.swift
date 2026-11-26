#if targetEnvironment(macCatalyst)
import ObjectiveC.runtime
import SwiftUI
import UIKit

/// Updates the host UIWindow, Catalyst container hierarchy, and underlying NSWindow background
/// with a real behind-window frosted blur (`NSVisualEffectView`) without altering `alphaValue`,
/// keeping text, controls, and artwork 100% opaque while letting per-region opacity control translucency.
struct MacWindowTransparencyApplier: UIViewRepresentable {
    let isTranslucent: Bool
    let hasCustomSidebarColor: Bool

    func makeUIView(context: Context) -> BackdropProbeView {
        let view = BackdropProbeView()
        view.isUserInteractionEnabled = false
        view.isOpaque = false
        view.backgroundColor = .clear
        view.isTranslucent = isTranslucent
        view.hasCustomSidebarColor = hasCustomSidebarColor
        return view
    }

    func updateUIView(_ uiView: BackdropProbeView, context: Context) {
        uiView.isTranslucent = isTranslucent
        uiView.hasCustomSidebarColor = hasCustomSidebarColor
        uiView.applyToHostWindow()
        DispatchQueue.main.async { [weak uiView] in
            uiView?.applyToHostWindow()
        }
    }

    final class BackdropProbeView: UIView {
        var isTranslucent = false
        var hasCustomSidebarColor = false

        private static let blurViewsByWindow = NSMapTable<AnyObject, AnyObject>.weakToWeakObjects()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyToHostWindow()
            DispatchQueue.main.async { [weak self] in
                self?.applyToHostWindow()
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            applyToHostWindow()
        }

        func applyToHostWindow() {
            guard let window else { return }
            let clearContainers = isTranslucent || hasCustomSidebarColor
            window.isOpaque = !isTranslucent
            window.backgroundColor = isTranslucent ? .clear : nil
            if let rootVC = window.rootViewController {
                Self.applyContainerTransparency(to: rootVC, clear: clearContainers)
            }
            Self.applyNSWindowTransparency(isTranslucent: isTranslucent, hostUIWindow: window)
        }

        private static func applyContainerTransparency(to vc: UIViewController, clear: Bool) {
            if clear {
                vc.view.isOpaque = false
                vc.view.backgroundColor = .clear
                clearStructuralContainerViews(in: vc.view, depth: 0)
            } else {
                vc.view.isOpaque = true
            }
            for child in vc.children {
                applyContainerTransparency(to: child, clear: clear)
            }
        }

        private static func clearStructuralContainerViews(in view: UIView, depth: Int) {
            guard depth < 10 else { return }
            let typeName = String(describing: type(of: view))
            if view is UIControl || view is UILabel || view is UIImageView || view is UISlider || view is UISwitch {
                return
            }

            if view is UICollectionView || view is UITableView {
                view.isOpaque = false
                view.backgroundColor = .clear
            } else if typeName.contains("SplitView")
                        || typeName.contains("Navigation")
                        || typeName.contains("TransitionView")
                        || typeName.contains("WrapperView")
                        || typeName.contains("Sidebar")
                        || typeName.contains("Column")
                        || typeName.contains("Hosting")
                        || typeName.contains("Backdrop") {
                view.isOpaque = false
                if view.backgroundColor != nil {
                    view.backgroundColor = .clear
                }
            }

            // Neutralize default sidebar/navigation chrome UIVisualEffectView backdrops inside containers
            // while preserving SwiftUI's interactive glass effects (which live inside UIHostingView leaves).
            if let effectView = view as? UIVisualEffectView,
               typeName.contains("BarBackground") || typeName.contains("Navigation") || typeName.contains("Sidebar") {
                effectView.isHidden = true
            }

            for subview in view.subviews {
                clearStructuralContainerViews(in: subview, depth: depth + 1)
            }
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
            let contentViewSel = NSSelectorFromString("contentView")

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

                if nsWindow.responds(to: contentViewSel),
                   let contentView = nsWindow.perform(contentViewSel)?.takeUnretainedValue() {
                    updateBehindWindowBlur(on: nsWindow, contentView: contentView, enabled: isTranslucent)
                }
            }
        }

        private static func updateBehindWindowBlur(on nsWindow: AnyObject, contentView: AnyObject, enabled: Bool) {
            let existing = blurViewsByWindow.object(forKey: nsWindow)
            if !enabled {
                if let existing {
                    setBool(existing, selectorName: "setHidden:", value: true)
                }
                return
            }

            let blurView: AnyObject
            if let existing {
                blurView = existing
            } else {
                guard let vevClass = NSClassFromString("NSVisualEffectView") as? NSObject.Type else { return }
                let created = vevClass.init()
                setAutoresizingMask(created, mask: 18) // NSViewWidthSizable | NSViewHeightSizable
                setInt(created, selectorName: "setBlendingMode:", value: 0) // NSVisualEffectBlendingModeBehindWindow
                setInt(created, selectorName: "setMaterial:", value: 6) // NSVisualEffectMaterialPopover
                setInt(created, selectorName: "setState:", value: 1) // NSVisualEffectStateActive
                blurView = created
                blurViewsByWindow.setObject(created, forKey: nsWindow)
            }

            if let bounds = getBounds(contentView) {
                setFrame(blurView, frame: bounds)
            }
            if !isDescendant(blurView, of: contentView) {
                addSubviewBelow(parent: contentView, child: blurView)
            }
            setBool(blurView, selectorName: "setHidden:", value: false)
        }

        private static func getBounds(_ view: AnyObject) -> CGRect? {
            let sel = NSSelectorFromString("bounds")
            guard view.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: view), sel) else { return nil }
            typealias BoundsIMP = @convention(c) (AnyObject, Selector) -> CGRect
            let imp = unsafeBitCast(method_getImplementation(method), to: BoundsIMP.self)
            return imp(view, sel)
        }

        private static func setFrame(_ view: AnyObject, frame: CGRect) {
            let sel = NSSelectorFromString("setFrame:")
            guard view.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: view), sel) else { return }
            typealias SetFrameIMP = @convention(c) (AnyObject, Selector, CGRect) -> Void
            let imp = unsafeBitCast(method_getImplementation(method), to: SetFrameIMP.self)
            imp(view, sel, frame)
        }

        private static func setAutoresizingMask(_ view: AnyObject, mask: UInt) {
            let sel = NSSelectorFromString("setAutoresizingMask:")
            guard view.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: view), sel) else { return }
            typealias MaskIMP = @convention(c) (AnyObject, Selector, UInt) -> Void
            let imp = unsafeBitCast(method_getImplementation(method), to: MaskIMP.self)
            imp(view, sel, mask)
        }

        private static func setInt(_ target: AnyObject, selectorName: String, value: Int) {
            let sel = NSSelectorFromString(selectorName)
            guard target.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: target), sel) else { return }
            typealias IntIMP = @convention(c) (AnyObject, Selector, Int) -> Void
            let imp = unsafeBitCast(method_getImplementation(method), to: IntIMP.self)
            imp(target, sel, value)
        }

        private static func setBool(_ target: AnyObject, selectorName: String, value: Bool) {
            let sel = NSSelectorFromString(selectorName)
            guard target.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: target), sel) else { return }
            typealias BoolIMP = @convention(c) (AnyObject, Selector, ObjCBool) -> Void
            let imp = unsafeBitCast(method_getImplementation(method), to: BoolIMP.self)
            imp(target, sel, ObjCBool(value))
        }

        private static func isDescendant(_ child: AnyObject, of parent: AnyObject) -> Bool {
            let sel = NSSelectorFromString("isDescendantOf:")
            guard child.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: child), sel) else { return false }
            typealias DescendantIMP = @convention(c) (AnyObject, Selector, AnyObject) -> ObjCBool
            let imp = unsafeBitCast(method_getImplementation(method), to: DescendantIMP.self)
            return imp(child, sel, parent).boolValue
        }

        private static func addSubviewBelow(parent: AnyObject, child: AnyObject) {
            let sel = NSSelectorFromString("addSubview:positioned:relativeTo:")
            guard parent.responds(to: sel),
                  let method = class_getInstanceMethod(type(of: parent), sel) else { return }
            typealias AddIMP = @convention(c) (AnyObject, Selector, AnyObject, Int, AnyObject?) -> Void
            let imp = unsafeBitCast(method_getImplementation(method), to: AddIMP.self)
            imp(parent, sel, child, -1, nil) // NSWindowBelow == -1
        }
    }
}
#endif
