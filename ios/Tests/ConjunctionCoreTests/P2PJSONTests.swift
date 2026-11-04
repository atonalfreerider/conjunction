import Foundation
import XCTest
@testable import ConjunctionCore

final class P2PJSONTests: XCTestCase {

    func testCanonicalEscapes() throws {
        let m: WireObject = ["b": .string("Zoë \"Z\" \\ 星\n\t\u{01}\u{08}\u{0C}/😀\u{2028}\u{7F}"),
                             "a": .array([.int(1), .int(-2), .null, .bool(true), .int(Int64.min)]), "n": .null]
        XCTAssertEqual(WireJSON.writeString(.object(m)),
                       "{\"b\":\"Zoë \\\"Z\\\" \\\\ 星\\n\\t\\u0001\\b\\f/😀\u{2028}\u{7F}\",\"a\":[1,-2,null,true,-9223372036854775808],\"n\":null}")
        XCTAssertEqual(try WireJSON.parse(WireJSON.write(.object(m))), .object(m))
        // Member order is kept on write and read.
        guard case .object(let back) = try WireJSON.parse(WireJSON.write(.object(m))) else { return XCTFail() }
        XCTAssertEqual(back.keys, ["b", "a", "n"])
    }

    func testStrictParsing() throws {
        let bad = ["{\"a\":1.0}", "{\"a\":1e3}", "{\"a\":1E3}", "{\"a\":1,\"a\":2}", "{\"a\":01}", "{\"a\":-01}", "{\"a\":\"\\ud800\"}",
                   "{\"a\":\"\\udc00\"}", "{\"a\":\"\\ud800\\u0041\"}", "{\"a\":99999999999999999999}", "{\"a\":1} x",
                   "{\"a\":\"\u{01}\"}", "[", "{\"a\":NaN}", "{\"a\":\"\\x\"}", "{\"a\":-}", "{\"a\":tru}", "", "{\"a\" 1}",
                   "{\"a\":\"\\u12\"}", "{a:1}", "[1,]", "{\"a\":+1}"]
        for s in bad {
            expectP2P(.malformed, s) { _ = try WireJSON.parse(s) }
        }
        expectP2P(.malformed, "invalid UTF-8") { _ = try WireJSON.parse([0x22, 0xC3, 0x22]) }
        expectP2P(.malformed, "overlong") { _ = try WireJSON.parse([0x22, 0xC0, 0xAF, 0x22]) }
        expectP2P(.malformed, "CESU surrogate") { _ = try WireJSON.parse([0x22, 0xED, 0xA0, 0x80, 0x22]) }
        expectP2P(.malformed, "too deep") { _ = try WireJSON.parse(String(repeating: "[", count: 40) + String(repeating: "]", count: 40)) }
        XCTAssertEqual(try WireJSON.parse(" { \"a\" : [ 1 , \"x\" ] } "), .object(["a": .array([.int(1), .string("x")])]))
        XCTAssertEqual(try WireJSON.parse("\"\\ud83d\\ude00\""), .string("😀"))
        XCTAssertEqual(try WireJSON.parse("\"\\u00e9\\/\\u0000\""), .string("é/\u{0}"))
        XCTAssertEqual(try WireJSON.parse("-0"), .int(0))
        XCTAssertEqual(try WireJSON.parse("9223372036854775807"), .int(Int64.max))
    }

    func testWireCompatIsExactForEveryCentidegree() {
        for c in 0..<36_000 {
            XCTAssertEqual(Compatibility.centidegrees(Double(c) / 100.0), c)
        }
    }

    func testStrictBase64() {
        XCTAssertEqual(try P2PBytes.b64Strict("AAAAAAAAAAA=", length: 8, what: "x"), [UInt8](repeating: 0, count: 8))
        for s in ["AAAAAAAAAAB=", "AAAAAAAAAAA", "AAAA AAAAAAA=", "AAAAAAAAAB==", "AAAAAAAAAAA=\n", "AAAA-AAAAAA="] {
            expectP2P(.malformed, s) { _ = try P2PBytes.b64Strict(s, length: nil, what: "x") }
        }
        expectP2P(.malformed) { _ = try P2PBytes.b64Strict("AAAA", length: 8, what: "x") }
    }
}
