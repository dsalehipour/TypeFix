// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TypeFix",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "TypeFix",
            path: "Sources/TypeFix"
        )
    ]
)
