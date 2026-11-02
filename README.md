# wavelength-kmp

A Kotlin Multiplatform SDK for [wavelength](https://github.com/lightninglabs/wavelength),
Lightning Labs' self-custodial Bitcoin wallet. wavelength combines an Ark
client, a Lightning swap engine, and an on-chain wallet in one daemon
(`waved`).

On Android and iOS this SDK runs the whole daemon **inside your app's
process**, through wavelength's gomobile bindings: there is no separate
process and no open network port. You drive it from shared Kotlin, using
`suspend` functions, `Flow`s, typed models, and typed errors. The Swift
API comes from the same code through SKIE.

> Status: alpha.
>
> - **Android:** verified end to end on an emulator against signet.
> - **iOS:** the code is written, but its first build and test happen in
>   the macOS CI job.
>
> See [docs/PLAN.md](docs/PLAN.md) for the phased plan and
> [docs/adr/](docs/adr) for the decisions behind it.

## Quick start (Android)

```kotlin
dependencies {
    implementation("engineering.lightning.wavelength:wavelength-embedded:0.2.1")
    implementation("engineering.lightning.wavelength:wavelength-engine:0.2.1")  // optional observable wallet
}
```

```kotlin
val client = Wavelength.client
client.start(RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir(context)))

val created = client.createWallet(CreateWalletRequest(password = "at least 8 bytes"))
showBackup(created.mnemonic)

val invoice = client.receive(ReceiveRequest(amountSat = 10_000)).invoice
client.activity().collect { entry -> println("${entry.kind} ${entry.amountSat} ${entry.status}") }
```

To drive a UI, use `WalletEngine`, which exposes a single
`StateFlow<WalletSnapshot>`. See [docs/api-guide.md](docs/api-guide.md)
and the sample in [samples/android](samples/android).

## Modules

| Module | Targets | Purpose |
|--------|---------|---------|
| `wavelength-core` | jvm, android, iOS | The client contract, generated models, config and network presets, typed errors, a lifecycle-safe client over an abstract binding, destination parsing, and `exitBatch` |
| `wavelength-engine` | jvm, android, iOS | `WalletEngine`, a port of the TypeScript SDK's engine (same phases and timings) |
| `wavelength-embedded` | android, iOS | Binds the core to the gomobile `Wavewalletdk` runtime; also provides `Wavelength.client` and the background/resume policy |
| `wavelength-passkey` | jvm, android, iOS | Passkey (WebAuthn PRF) wallets: `PasskeyCeremony`, the Android Credential Manager authenticator, and the iOS authenticator protocol (its Swift implementation is in `swift/WavelengthPasskey`) |
| `wavelength-kit` | iOS (macOS builds only) | The `WavelengthKit` XCFramework that Swift apps consume, exporting every module, with SKIE |
| `wavelength-testing` | jvm, android, iOS | `FakeFacadeBindings` and `FakeWavelengthClient`: a simulated wallet for tests and previews |
| `wavelength-bindings-android` | Android | The upstream `Wavewalletdk.aar`, republished without changes |

## Building

Requirements:

- JDK 17 or newer
- the Android SDK (`local.properties` or `ANDROID_HOME`)
- Go 1.25 or newer, to regenerate models
- macOS with Xcode, for the iOS targets only

```bash
scripts/fetch-bindings.sh            # stage and verify the pinned gomobile bindings
./gradlew check                      # JVM and Android host tests; also compiles the iOS klibs on Linux
./gradlew :wavelength-embedded:connectedAndroidDeviceTest   # real runtime on a device or emulator
./gradlew :samples:android:installDebug
```

`wavelength.version` pins the daemon release. The library version, the
bindings (checked against `bindings.digests`), and the generated models
(`scripts/generate-models.sh`) all follow it. To move to a new release, see
[docs/daemon-sync.md](docs/daemon-sync.md). To publish one, see
[RELEASE.md](RELEASE.md).

## Documentation

- [API guide](docs/api-guide.md)
- [Architecture](docs/architecture.md)
- [API parity with the Go facade and TypeScript SDK](docs/parity.md)
- [Architecture decision records](docs/adr)

## Coming from wavelength-mobile's walletkit

| walletkit | wavelength-kmp |
|-----------|----------------|
| `WalletClient()` | `Wavelength.client` (a process-wide singleton) |
| `WalletConfig.signet(dataDir)` | `RuntimeConfig.preset(Network.SIGNET, dataDir)`, which uses the current hosted endpoints |
| `receiveLightning(amount, memo)` | `receive(ReceiveRequest(amount, memo))` |
| `newDepositAddress()` | `deposit()` |
| `prepareSend(invoice = …)` / `send(id)` | `prepareSend(SendRequest.Invoice(…))` / `sendPrepared(quote)` |
| `WalletException` | typed `WavelengthException` subclasses |
| `kind` / `status` strings | `EntryKind` / `EntryStatus` enums |

## License

Apache License 2.0; see [LICENSE](LICENSE). This licence came with the
project template and is still under review; upstream wavelength is MIT.
