// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "afamfresh",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "afamfresh", targets: ["afamfresh"])
    ],
    dependencies: [], // Clear out external git repo constraints
    targets: [
        .executableTarget(
            name: "afamfresh",
            dependencies: [], // No bloated frameworks needed on Linux!
            path: "Sources/afamfresh"
        )
    ]
)
