// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Vela",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "VelaCore", targets: ["VelaCore"])
    ],
    targets: [
        .target(name: "VelaCore"),
        .target(name: "VelaRelay", dependencies: ["VelaCore"], path: "Shared/Relay"),
        // 同步的 Apple 加密与传输（CryptoKit、Compression、Security、Foundation）；非 Apple 平台整文件编译为空。
        .target(name: "VelaSync", dependencies: ["VelaCore"], path: "Shared/Sync"),
        .testTarget(name: "VelaCoreTests", dependencies: ["VelaCore"]),
        .testTarget(name: "VelaRelayTests", dependencies: ["VelaRelay", "VelaCore"]),
        .testTarget(name: "VelaSyncTests", dependencies: ["VelaSync", "VelaCore"])
    ]
)
