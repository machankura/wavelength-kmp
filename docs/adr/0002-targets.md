# ADR 0002: Kotlin targets

Status: accepted

## Decision

| Module | Targets |
|--------|---------|
| Pure Kotlin (`core`, `engine`, `testing`) | `jvm`, `android`, `iosArm64`, `iosSimulatorArm64` |
| Native (`embedded`) | `android`, `iosArm64`, `iosSimulatorArm64` |

- `iosX64` is not built. gomobile does build an x86_64 simulator slice, but
  Intel-Mac simulators are out of scope.
- `linuxX64` from the template is dropped.

## Rationale

wavelength publishes gomobile bindings only for Android (`.aar`) and iOS
(`.xcframework`). There is no embedded runtime for desktop JVM or Linux
native until wavelength ships a C-ABI build (`docs/PLAN.md`, Phase 7 / U4).

The pure modules still target `jvm`, so most tests run on the JVM without
an emulator.

`kotlin.native.enableKlibsCrossCompilation=true` lets Linux CI compile the
iOS klibs of pure modules. Linking, cinterop with the xcframework, and
running iOS tests still need macOS.
