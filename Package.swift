// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TypeFix",
    platforms: [.macOS(.v14)],
    dependencies: [
        // Embedded, on-device LLM inference (Apple Silicon). Pinned to a version
        // whose MLXLMCommon/MLXLLM API this app is built against. Pulling this
        // dependency makes the build heavier and the app larger; the MLX code is
        // guarded by `#if canImport(MLXLLM)` so the app still compiles without it.
        .package(url: "https://github.com/ml-explore/mlx-swift-examples", exact: "2.29.1")
    ],
    targets: [
        .executableTarget(
            name: "TypeFix",
            dependencies: [
                .product(name: "MLXLLM", package: "mlx-swift-examples"),
                .product(name: "MLXLMCommon", package: "mlx-swift-examples"),
            ],
            path: "Sources/TypeFix"
        )
    ]
)
