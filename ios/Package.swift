// swift-tools-version: 6.0
import PackageDescription

// ConjunctionKit: the engines the iOS app shares with Android, in plain Swift (Foundation + swift-crypto) so
// they build and are tested on Linux as well as on Apple platforms.
//
//   ConjunctionCore   natal chart astronomy, Chinese zodiac / BaZi, blood type, compatibility,
//                     match filter, palm features and the on-device prompt builders — a line-for-line
//                     port of android/core (Kotlin). Both test suites read the same goldens in
//                     ../testdata, so the two platforms give the same answers.
//                     P2P/ is the serverless Bluetooth protocol (docs/P2P_PROTOCOL.md), checked
//                     against testdata/p2p_wire.json.
//
// The only dependency is apple/swift-crypto (`import Crypto`): on Apple platforms it re-exports
// CryptoKit, on Linux it builds the same API over BoringSSL, so the P2P crypto is tested here too.
//
// The SwiftUI app lives in App/ (XcodeGen project) and depends on this package; it builds on macOS
// CI only. Run `swift build && swift test` here on Linux or macOS.

let package = Package(
    name: "ConjunctionKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ConjunctionCore", targets: ["ConjunctionCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", .upToNextMinor(from: "5.0.3")),
    ],
    targets: [
        .target(
            name: "ConjunctionCore",
            dependencies: [.product(name: "Crypto", package: "swift-crypto")],
            // Byte-for-byte copies of the Kotlin engine's resources (a test checks they match).
            resources: [.copy("Resources")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "ConjunctionCoreTests",
            dependencies: ["ConjunctionCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
