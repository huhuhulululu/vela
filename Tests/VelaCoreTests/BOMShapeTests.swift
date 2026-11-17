import XCTest
@testable import VelaCore

/// 正文形态判断：只跳过开头完整的 UTF-8 BOM，单个 BOM 字节不当空白。
final class BOMShapeTests: XCTestCase {
    private func bytes(_ b: [UInt8]) -> Data { Data(b) }

    func testFullBOMThenJSONIsJSON() {
        XCTAssertTrue(ResponseShape.looksLikeJSON(bytes([0xEF, 0xBB, 0xBF] + Array("{\"a\":1}".utf8))))
        XCTAssertTrue(ResponseShape.looksLikeJSON(bytes([0xEF, 0xBB, 0xBF, 0x20, 0x0A] + Array("[1]".utf8))))
        XCTAssertTrue(ResponseShape.looksLikeJSON(bytes([0x20] + [0xEF, 0xBB, 0xBF] + Array("{}".utf8))), "BOM 前有空白也容忍")
    }

    func testPartialOrStrayBOMBytesAreNotWhitespace() {
        XCTAssertFalse(ResponseShape.looksLikeJSON(bytes([0xEF] + Array("{}".utf8))))
        XCTAssertFalse(ResponseShape.looksLikeJSON(bytes([0xBB] + Array("{}".utf8))))
        XCTAssertFalse(ResponseShape.looksLikeJSON(bytes([0xBF] + Array("{}".utf8))))
        XCTAssertFalse(ResponseShape.looksLikeJSON(bytes([0xEF, 0xBB] + Array("{}".utf8))))
        XCTAssertFalse(ResponseShape.looksLikeJSON(bytes([0xBB, 0xBF, 0xEF] + Array("{}".utf8))))
    }

    func testPlainCases() {
        XCTAssertTrue(ResponseShape.looksLikeJSON(Data("  \n{}".utf8)))
        XCTAssertFalse(ResponseShape.looksLikeJSON(Data()))
        XCTAssertFalse(ResponseShape.looksLikeJSON(Data("<html>".utf8)))
    }
}
