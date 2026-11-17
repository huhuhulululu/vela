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
        .testTarget(name: "VelaCoreTests", dependencies: ["VelaCore"]),
        .testTarget(name: "VelaRelayTests", dependencies: ["VelaRelay", "VelaCore"])
    ]
)
