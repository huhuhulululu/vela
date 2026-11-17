// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Vela",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "VelaCore", targets: ["VelaCore"])
    ],
    targets: [
        .target(name: "VelaCore"),
        .testTarget(name: "VelaCoreTests", dependencies: ["VelaCore"])
    ]
)
