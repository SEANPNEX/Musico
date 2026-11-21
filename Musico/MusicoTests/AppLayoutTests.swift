import XCTest
@testable import Musico

final class AppLayoutTests: XCTestCase {
    func testWideRegularWindowUsesTheSidebar() {
        XCTAssertEqual(AppLayout.resolve(width: 1024, compactWidth: false, phone: false), .sidebar)
        XCTAssertEqual(AppLayout.resolve(width: AppLayout.minimumSidebarWidth, compactWidth: false, phone: false), .sidebar)
    }

    func testNarrowWindowKeepsTabs() {
        XCTAssertEqual(AppLayout.resolve(width: AppLayout.minimumSidebarWidth - 1, compactWidth: false, phone: false), .tabs)
    }

    func testCompactWidthKeepsTabsEvenWhenWide() {
        XCTAssertEqual(AppLayout.resolve(width: 1024, compactWidth: true, phone: false), .tabs)
    }

    func testPhoneKeepsTabsInLandscape() {
        XCTAssertEqual(AppLayout.resolve(width: 932, compactWidth: false, phone: true), .tabs)
    }
}
