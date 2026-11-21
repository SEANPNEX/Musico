import XCTest
@testable import Musico

final class FLACTagsTests: XCTestCase {
    func testNonFLACFileGivesNil() throws {
        let url = try XCTUnwrap(Bundle(for: FLACTagsTests.self).url(forResource: "art-v23", withExtension: "mp3"))
        XCTAssertNil(FLACTags.read(url))
    }

    func testTruncatedFileDoesNotCrash() throws {
        let source = try XCTUnwrap(Bundle(for: FLACTagsTests.self).url(forResource: "art", withExtension: "flac"))
        let data = try Data(contentsOf: source)
        for length in [0, 3, 4, 7, 50, 200, 2000] {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".flac")
            try data.prefix(length).write(to: url)
            _ = FLACTags.read(url)
        }
    }
}
