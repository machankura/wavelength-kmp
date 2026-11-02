# wavelength-kmp

A Kotlin Multiplatform SDK for [wavelength](https://github.com/lightninglabs/wavelength),
Lightning Labs' self-custodial Bitcoin wallet. It unifies an Ark client, a
Lightning swap engine, and an on-chain wallet behind one daemon (`waved`).

The SDK runs the whole daemon **inside your app's process** on Android and
iOS, through wavelength's gomobile bindings. From shared Kotlin you get:

- `suspend` functions,
- `Flow`s for wallet activity,
- typed models and typed errors.

There is no separate process, and nothing listens on a network port.

> Status: under active development. See [docs/PLAN.md](docs/PLAN.md) for the
> phased plan and [docs/adr/](docs/adr) for the decisions behind it.

## Modules

| Module | Targets | Purpose |
|--------|---------|---------|
| `wavelength-core` | jvm, android, iOS | Client contract, generated models, config and network presets, errors, the lifecycle-safe client over an abstract binding |
| `wavelength-engine` | jvm, android, iOS | `WalletEngine`: an observable wallet state machine for UIs |
| `wavelength-testing` | jvm, android, iOS | Fakes and fixtures for testing apps without the native runtime |
| `wavelength-embedded` | android, iOS | Binds the core to the gomobile `Wavewalletdk` runtime |
| `wavelength-bindings-android` | Android | The upstream `Wavewalletdk.aar`, republished to Maven |

## Building

Requirements:

- JDK 17 or newer
- The Android SDK, with `ANDROID_HOME` or `local.properties` set
- macOS with Xcode, for iOS linking and tests only

```bash
scripts/fetch-bindings.sh   # stage the pinned gomobile bindings into bindings/
./gradlew jvmTest           # fast tests for the pure-Kotlin modules
```

`wavelength.version` pins the wavelength daemon release. The library
version, the native bindings, and the generated models all follow it. See
[ADR 0003](docs/adr/0003-versioning-and-pinning.md).

## License

Apache License 2.0. See [LICENSE](LICENSE). The licence inherited from the project
template is still under review; upstream wavelength is MIT.
