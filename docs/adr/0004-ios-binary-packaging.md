# ADR 0004: iOS binary packaging

Status: **provisional**. The macOS spike from Phase 0 is still to be run; see
"Validation".

## Context

gomobile emits `Wavewalletdk.xcframework`, a static ObjC framework (about
134 MB compressed for v0.2.1) that contains the Go runtime and the embedded
daemon. Whatever produces the final iOS app binary has to link it.

## Decision

The `wavelength-embedded` klib carries only the **cinterop bindings**
(`MobileStart`, `MobileGetInfo`, …).

- **Consumers** add `Wavewalletdk.xcframework` to their app with SPM or
  Xcode. wavelength-mobile's `WalletKit` `Package.swift` already does this
  with a `binaryTarget`.
- **The XCFramework this repo builds** (`WavelengthKit`, Swift API through
  SKIE) links against it with `-framework Wavewalletdk`.
- **The SPM package** published with each release declares both binary
  targets.

## Rejected (for now)

Embedding the static archive in the klib with `staticLibraries=` would make
the library usable from Maven alone. The cost is a klib of over 100 MB per
slice, and Maven Central deployment size limits that are not yet verified.

## Implementation

- `wavelength-embedded/src/nativeInterop/cinterop/wavewalletdk.def` binds the
  `Wavewalletdk` clang module. `build.gradle.kts` adds `-F<slice>` for each
  target (`ios-arm64`, `ios-arm64_x86_64-simulator`).
- iOS targets, cinterop, SKIE, and the `WavelengthKit` XCFramework (static,
  exports `wavelength-core`) are configured only on macOS hosts.
- Test executables link `-framework Wavewalletdk -lresolv` themselves; apps
  link the Go framework through their own dependency on it.

## Validation (to do on macOS)

1. `./gradlew :wavelength-embedded:iosSimulatorArm64Test` boots the daemon
   from Kotlin/Native and reads `GetInfo`.
2. A soak test with the Go and Kotlin/Native runtimes in one process
   (signals, threads, memory).
3. Check whether the header rewrite (`@import Foundation;` → `#import`) that
   the React Native package applies is also needed for cinterop.
