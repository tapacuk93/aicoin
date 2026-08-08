// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AICoinKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "AICoinKit", targets: ["AICoinKit"]),
    ],
    targets: [
        .target(
            name: "AICoinKit",
            dependencies: []
        ),
        .testTarget(
            name: "AICoinKitTests",
            dependencies: ["AICoinKit"]
        ),
    ]
)
