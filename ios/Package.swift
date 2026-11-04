// swift-tools-version: 6.0
import PackageDescription

// ConjunctionKit: the engines the iOS app shares with Android, in plain Swift (Foundation only) so
// they build and are tested on Linux as well as on Apple platforms.
//
//   ConjunctionCore   natal chart astronomy, Chinese zodiac / BaZi, blood type, compatibility,
//                     match filter, palm features and the on-device prompt builders — a line-for-line
//                     port of android/core (Kotlin). Both test suites read the same goldens in
//                     ../testdata, so the two platforms give the same answers.
//
// The SwiftUI app lives in App/ (XcodeGen project) and depends on this package; it builds on macOS
// CI only. Run `swift build && swift test` here on Linux or macOS.

let package = Package(
    name: "ConjunctionKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ConjunctionCore", targets: ["ConjunctionCore"]),
    ],
    targets: [
        .target(
            name: "ConjunctionCore",
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
