# wavelength-kmp: implementation plan

A phased plan for a Kotlin Multiplatform (KMP) library that lets Android and iOS
apps (and later JVM desktop and others) embed and drive the Lightning Labs
**wavelength** wallet from shared Kotlin code.

Sources surveyed (default branch `main` of each repo, as of 2026-11-02):

| Repo | Commit | Relevant parts |
|------|--------|----------------|
| `lightninglabs/wavelength` | `3b7ed47f` (latest tag `v0.2.1`) | `sdk/wavewalletdk` (Go SDK), `sdk/wavewalletdk/mobile` (gomobile facade), `cmd/wavewalletdk-wasm`, `docs/wavewalletdk_mobile.md`, `.github/workflows/mobile-bindings.yml` |
| `lightninglabs/wavelength-sdk` | `6f71128` (pinned to daemon `v0.2.0`) | `packages/core` (client contract, `WalletEngine`, config presets, errors), `packages/react-native` (Kotlin/ObjC dispatch over the same bindings) |
| `lightninglabs/wavelength-mobile` | `ba90d14` | `android/walletkit` (Kotlin `WalletClient`), `ios/WalletKit` (Swift actor), fetch scripts |

## Implementation status (2026-11-02)

| Phase | State | Notes |
|-------|-------|-------|
| 0 Foundations | Done | Build-logic, the daemon pin (`v0.2.1`), `fetch-bindings.sh` with digests, ADRs 0001–0005. The iOS spike still needs macOS (ADR 0004). |
| 1 Core and codegen | Done | `tools/kotlin-modelgen` and golden Go fixtures; `RuntimeConfig`, errors, `FacadeWavelengthClient`; `wavelength-testing`. |
| 2 Android | Done, verified | Instrumented tests pass on a 16 KB-page API 37 emulator against signet. |
| 3 iOS | Written, not built | cinterop, SKIE, and the XCFramework are wired for macOS hosts only. The first build is the macOS CI job. |
| 4 WalletEngine | Done | The TS phase table and engine behaviours are ported, with tests on virtual time. |
| 5 Sample, docs, release | Mostly done | The sample is a Jetpack Compose Android app (shared logic in `WalletEngine`), not Compose Multiplatform. The release workflow and `Package.swift` generation have not been run yet. |
| 6 Passkeys, external seed | Done, partly verified | `wavelength-passkey`: the common ceremony is unit-tested on the JVM and Android host; the Android Credential Manager authenticator compiles; the iOS authenticator is a Swift target ported from the React Native SDK. `WalletProfiles` handles multiple wallets. A ceremony on a real device still needs an rpId with Digital Asset Links or an AASA file. |
| 7 Beyond mobile | Not started | Needs upstream U4. |
| 8 Hardening | Not started | |

Findings from implementation:

- **INTERNET permission.** The embedded daemon needs it. `wavelength-embedded`
  now declares it in its library manifest.
- **Password length.** The daemon rejects wallet passwords under 8 bytes.
  The client now checks this before calling the daemon.
- **Unsigned binaries.** wavelength's signed release manifest does not cover
  the mobile binding assets (U7 below).
- **Stale walletkit host.** wavelength-mobile's signet operator host
  (`arkd-signet.testnet.lightningcluster.com`) no longer resolves.

---

## 1. Goals and non-goals

**Goals**

1. One Kotlin API, in `commonMain`, that Android and iOS apps (including
   Compose Multiplatform apps) use to run the embedded wavelength wallet.
2. Feature parity with the TypeScript SDK's `WavelengthClient` /
   `ExternalSeedWalletClient` contract and its headless `WalletEngine`, so
   behaviour and docs line up across the web, React Native and Kotlin SDKs.
3. Idiomatic Kotlin: `suspend` functions, `Flow` / `StateFlow`, sealed results and
   errors, typed models with no hand-written JSON at call sites.
4. A good Swift experience from the same library (via SKIE), so iOS-native
   teams can use it without a separate Swift wrapper.
5. Releases tied to wavelength daemon tags and published to Maven Central (and
   an SPM-consumable XCFramework), with CI that runs the real bindings on an
   Android emulator and the iOS Simulator.

**Non-goals (for v1)**

- Reimplementing any Ark, Lightning or wallet logic in Kotlin. The daemon stays
  the only runtime. The library is a facade, matching wavelength's own
  "one canonical runtime" rule (`docs/sdk_layered_architecture.md`).
- A remote-daemon (gRPC/REST to a separate `waved`) client. Neither the TS SDK
  nor the mobile wrappers ship one; see Phase 7 for when to add it.
- A key-derivation scheme for external-seed wallets (root seed → 16-byte
  entropy). Wavelength leaves this to the host and no spec exists yet; see
  open questions.

---

## 2. What exists today, and what this means for the design

### 2.1 The native boundary: the gomobile facade

`sdk/wavewalletdk/mobile` is the only API the library talks to on mobile. It is
deliberately flat and callback-free:

| Shape | Functions |
|-------|-----------|
| Lifecycle | `Start(cfgJSON string) error` (blocks until gRPC over the in-memory `bufconn` is serving; 90 s boot timeout), `Stop() error` (idempotent), `IsRunning() bool`, `StartExternalSeedWallet(reqJSON) ([]byte, error)` |
| JSON verbs (bytes in/out, throwing) | `GetInfo`, `Status`, `Balance`, `CreateWallet`, `UnlockWallet`, `OpenWalletFromPasskey`, `Deposit`, `Receive`, `PrepareSend`, `SendPrepared`, `List`, `Exit`, `ExitStatus`, `ExitSummary`, `GetExitPlan`, `SweepWallet` |
| Streaming (pull handle) | `Subscribe(reqJSON) *Subscription`, `Subscription.Next() ([]byte, error)` (blocks; `io.EOF` = clean end), `Subscription.Close()` |
| Scalars | `ConfirmedBalanceSat() int64`, `PendingInboundSat() int64`, `WalletReady() bool` |

Facts that shape the design:

- **One daemon per process.** A four-state machine (stopped, starting, started,
  stopping) guards it. A second `Start` returns an error, and a `Stop` during
  `Start` cancels the boot.
- **The host must restart after background/resume.** The Go side cannot see
  the app lifecycle. After a real suspend, the host must `Stop` and then
  `Start` (and unlock again) so external gRPC connections are re-dialled.
- **Reads have a fixed 10 s binding deadline.** `Receive` has a 20 s default
  (up to 300 s via `TimeoutSeconds`). A timed-out receive fails with the
  stable prefix `receive outcome uncertain; reconcile Activity before retrying`.
- **Only the error message string crosses the boundary.** The Go SDK has
  typed sentinels (`ErrInvalidSendIntent`, `ErrAmountExceedsVTXOLimit`,
  `ErrBalanceLimitExceeded`, …) but gomobile flattens them to text.
- **Field names on the wire are not uniform** (`docs/wavewalletdk_mobile.md`):
  - DTOs in `sdk/wavewalletdk/types.go` have no `json` tags, so request and
    response keys are **PascalCase** with Go acronyms (`SendIntentID`, `VTXOs`,
    `CSV`, `CPFPFeeSat`, `ActorID`, `IdentityPubKey`).
  - The `Start` config is **snake_case** (`data_dir`, `wallet_esplora_url`, …).
  - The `StartExternalSeedWallet` envelope is **snake_case**
    (`config`, `seed_entropy` as standard base64, `expected_identity_pubkey`,
    `recover_state`, `recovery_window`).
  - `OpenWalletFromPasskey` takes **camelCase** `prfOutput` (hex).
  - `[]byte` fields (`WalletPassword`, `SeedPassphrase`, `EncipheredSeed`) are
    standard base64. `time.Time` fields are RFC 3339 strings.
  - `uint32`/`uint64` show up in responses. Go nil slices come out as `null`.
    `WalletState` is a number (0–4).
- **Build tags and artifacts.** The facade is built with
  `-tags "mobile wavewalletrpc swapruntime"`; Android adds `sqlite_cgo`, a
  16 KB page size and API level 21. The Java package is
  `engineering.lightning.wavewalletdk.mobile.Mobile`. iOS gets free C functions
  `MobileStart`, `MobileGetInfo`, … and an ObjC `MobileSubscription` class.
  CI attaches `Wavewalletdk.aar` (150 MB+, all ABIs) and
  `Wavewalletdk.xcframework.tar.gz` to every `v*` GitHub release.

### 2.2 The existing Kotlin wrapper (`wavelength-mobile/android/walletkit`)

This is the closest thing to prior art, and a good base for the Android
`actual`.

- `WalletClient` runs each verb on `Dispatchers.IO`.
- It encodes requests with `kotlinx.serialization` and PascalCase
  `@SerialName`s.
- It wraps every failure in a single `WalletException`.
- It exposes activity as a `channelFlow` pumping `Subscription.next()`.

Gaps compared with the facade and the TS SDK:

- **Missing verbs:** `exitSummary`, `getExitPlan`, `sweepWallet`,
  `openWalletFromPasskey`, `startExternalSeedWallet`, `pendingInboundSat`.
- **Missing model fields:** credit balances, cursors, `Entry.progress`,
  `Entry.request`, `failureCode`, the exit progress, CSV and fee details, and
  more.
- **Android-only APIs:** it uses `android.util.Base64` and is a plain Android
  library, so none of it can run in `commonMain`.
- **No lifecycle serialisation:** nothing beyond the Go guard, no typed errors,
  and end-of-stream is detected by `contains("EOF")`.
- **Stale signet defaults:** it uses `arkd-signet.testnet.lightningcluster.com`,
  while the newer TS presets use `signet.wavelength.lightning.finance:443`.
- **No licence file** in the repo root. Check the licence before copying code
  from it.

### 2.3 The TypeScript SDK (`wavelength-sdk`), the parity target

`packages/core` defines the contract all host SDKs should share.

- **Client:** `WavelengthClient` / `ExternalSeedWalletClient`.
  - `start` returns `WalletInfo`.
  - `send` is prepare plus `sendPrepared`, and copies `paymentHash` onto the
    result.
  - `callFacade` is an escape hatch that refuses the lifecycle verbs.
- **Config:** `RuntimeConfig` plus `validateRuntimeConfig`:
  - mainnet needs `allowMainnet`;
  - lwwallet-only and btcwallet-only fields cannot be mixed;
  - numeric fields are bounds-checked.
- **Network presets:** `networkDefaults(network, rest|grpc)` for signet,
  testnet and testnet4. There are no mainnet or regtest presets.
- **Errors:** `WavelengthError` with codes such as `runtime_not_ready`,
  `wallet_locked`, `invalid_config`, `invalid_external_seed` and
  `runtime_active`.
- **Normalisation:**
  - nil slices become `[]`;
  - the numeric `WalletState` becomes a string enum, with `walletReady`
    derived from it.
- **Events:** `runtimeReady`, `runtimeStopped`, `activity`,
  `activityStream` (ended or failed) and `log`.
- **`WalletEngine`:** the stateful layer apps actually bind to.
  - A pure phase machine with phases loading, runtimeReady, starting,
    needsWallet, locked, syncing, restoring, ready, stopping, stopped and
    error.
  - Activity-stream reconnect with backoff from 1 s to 30 s, giving up after
    5 failures, and resuming from the cursor.
  - A 250 ms debounce, then a balance "settle" re-read at 750, 1500 and
    3000 ms.
  - Sync polling every 2 s and restore polling every 1.5 s.
  - **Preimage retention:** the daemon emits a send's preimage only once, on
    the stream, so the engine keeps a `paymentHash → preimage` map and puts it
    back into later `list` results.
- **Helpers:**
  - `classifyDestination`: BOLT-11 HRP amount parsing with exact sat
    conversion.
  - `exitBatch` / `isExitInfeasibilityFundable`: unilateral exit
    orchestration.
  - Debug logging that redacts secrets.
  - Passkey PRF constants (salt `SHA-256("wavewalletdk-passkey:v1")`).
- **Lifecycle serialisation:**
  - Web runs `start`, `stop` and `startExternalSeedWallet` through a promise
    queue and a cross-tab Web Lock.
  - React Native does **not**, although the Go docs require typed SDKs to
    serialise these calls. The Kotlin SDK should do it properly with a
    `Mutex`.
- **Release:** the SDK version always equals the daemon pin
  (`RUNTIME_MANIFEST_VERSION = 'v0.2.0'`). A documented "daemon sync" runbook
  regenerates types, restages binaries and bumps versions.
- **Codegen:** models are generated from Go with tygo.

### 2.4 Resulting design decisions

| # | Decision | Why |
|---|----------|-----|
| D1 | Put a small `FacadeBindings` interface (lifecycle, `call(verb, ByteArray)`, `subscribe`, scalars) between shared Kotlin and each platform. Everything else (models, JSON, errors, lifecycle queue, engine) lives in `commonMain`. | The gomobile ABI is the same on both platforms, so only the FFI call itself differs. Shared logic can then be tested on the JVM with a fake binding. |
| D2 | Generate Kotlin models from the Go types (Go `go/packages` → KotlinPoet or text templates) with explicit `@SerialName` for every field. | Acronym-heavy PascalCase can't be mapped reliably with a naming strategy, and hand-written models already drifted in walletkit. This matches the TS SDK's tygo approach. |
| D3 | Always **send** exact PascalCase keys, never camelCase. | The TS SDK sends camelCase and relies on `encoding/json` matching keys case-insensitively. That stops working if wavelength moves to `encoding/json/v2` semantics. |
| D4 | Serialise `start`, `stop` and `startExternalSeedWallet` through one process-wide `Mutex`, with an explicit Kotlin lifecycle state. | Required by the facade docs (`StartExternalSeedWallet`), and it makes `start`/`stop` safe to call from any coroutine. |
| D5 | Map errors to a sealed `WavelengthException` hierarchy, using the TS error codes plus known Go sentinel messages. | Gives typed handling now, and upstream structured errors can replace the string matching later (see U1). |
| D6 | Mobile first: `androidTarget` + `iosArm64` + `iosSimulatorArm64`. Keep `jvm` only for the pure-Kotlin core module (fast tests). Drop `linuxX64` from the template for now. | gomobile produces only Android and iOS artifacts. Desktop needs an upstream C-ABI build (Phase 7). |
| D7 | Ship the Android bindings AAR as its own Maven artifact, versioned to the wavelength tag. | A KMP Android library cannot bundle a local `.aar` for downstream consumers. |
| D8 | Use SKIE for Swift export. | Exposes `Flow` as `AsyncSequence`, `suspend` as cancellable `async`, and sealed classes as Swift enums. This replaces the hand-maintained Swift `WalletKit` mirror. |

---

## 3. Target architecture

```
┌──────────────────────── App (Android / iOS / Compose Multiplatform) ─────────────────────────┐
│                                                                                              │
│  wavelength-engine   WalletEngine: StateFlow<WalletSnapshot>, phase machine, reconcile,     │
│        │             activity reconnect, preimage retention, exitBatch                       │
│        ▼                                                                                     │
│  wavelength-core     WavelengthClient (suspend + Flow), RuntimeConfig + presets + validation, │
│        │             generated models, JSON codec, WavelengthException, lifecycle Mutex,     │
│        │             destination classifier, redacting logger            (commonMain)        │
│        ▼                                                                                     │
│  FacadeBindings  ── expect/actual or injected ──────────────────────────────────────────    │
│        │ androidMain: engineering.lightning.wavewalletdk.mobile.Mobile (JNI via gomobile)    │
│        │ iosMain:     cinterop over Wavewalletdk.xcframework (MobileStart, MobileGetInfo …)  │
│        │ test:        FakeFacadeBindings (scripted JSON, in-memory subscription)             │
│        ▼                                                                                     │
│  Go: sdk/wavewalletdk/mobile ── bufconn gRPC ──► embedded waved (wallet, SQLite, Ark, swaps)  │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Gradle modules / published artifacts

| Module | Targets | Contents |
|--------|---------|----------|
| `:wavelength-core` | jvm, android, iosArm64, iosSimulatorArm64 | Public API, models (generated), config and presets, errors, lifecycle, codec, `FacadeBindings` interface, helpers. No native dependency. |
| `:wavelength-embedded` | android, iosArm64, iosSimulatorArm64 | `actual` bindings: the Android one depends on `wavelength-bindings-android`; the iOS one is the cinterop. Provides the `Wavelength.embedded()` factory and platform default `dataDir`. Optional lifecycle observers. |
| `:wavelength-engine` | same as core | `WalletEngine` and its state machine. Depends only on `core`. |
| `:wavelength-testing` | same as core | `FakeWavelengthClient`, `FakeFacadeBindings`, fixture JSON. Mirrors TS `core/src/testing/fake-client.ts`. |
| `:wavelength-bindings-android` | (publishing-only module) | Republishes the upstream `Wavewalletdk.aar` under Maven coordinates, version = wavelength tag. |
| `:wavelength-passkey` (Phase 6) | android, ios | Passkey PRF ceremony: Credential Manager on Android, AuthenticationServices on iOS 18+. |
| `:samples:compose-app` | android, ios | Compose Multiplatform reference wallet (replaces both sample apps). |
| `tools/kotlin-modelgen` | Go program | Generates `core/src/commonMain/.../generated/*.kt` from a pinned wavelength module version. |

Proposed coordinates (confirm in Phase 0): group `engineering.lightning.wavelength`,
artifacts as above. Kotlin package `engineering.lightning.wavelength`.

### 3.2 Public API sketch (`commonMain`)

```kotlin
interface WavelengthClient {
    val lifecycle: StateFlow<RuntimeLifecycle>          // Stopped, Starting, Running, Stopping
    val events: SharedFlow<WavelengthEvent>             // RuntimeReady, RuntimeStopped, Log, ActivityStreamEnded/Failed

    suspend fun start(config: RuntimeConfig): WalletInfo
    suspend fun stop()
    suspend fun isRunning(): Boolean

    suspend fun getInfo(): WalletInfo
    suspend fun status(): WalletStatus
    suspend fun balance(): Balance
    suspend fun confirmedBalanceSat(): Long
    suspend fun pendingInboundSat(): Long
    suspend fun walletReady(): Boolean

    suspend fun createWallet(req: CreateWalletRequest): CreateWalletResult
    suspend fun restoreWallet(req: RestoreWalletRequest): CreateWalletResult
    suspend fun unlockWallet(password: Secret): UnlockWalletResult
    suspend fun openWalletFromPasskey(prfOutput: ByteArray): OpenWalletResult

    suspend fun deposit(amountSatHint: Long = 0): DepositResult
    suspend fun receive(amountSat: Long, memo: String = "", timeout: Duration? = null): ReceiveResult
    suspend fun prepareSend(req: SendRequest): PrepareSendResult  // sealed: Invoice | Onchain
    suspend fun sendPrepared(prepared: PrepareSendResult): SendResult
    suspend fun send(req: SendRequest): SendResult

    suspend fun list(req: ListRequest = ListRequest.Activity()): ListResult  // sealed per view
    fun activity(includeExisting: Boolean = false, kinds: Set<EntryKind> = emptySet(),
                 cursor: Long? = null): Flow<Entry>

    suspend fun exit(req: ExitRequest): ExitResult                 // sealed: Cooperative | ForceUnroll
    suspend fun exitStatus(outpoint: String, detailed: Boolean = false): ExitStatusResult
    suspend fun exitSummary(): ExitSummaryResult
    suspend fun getExitPlan(outpoints: List<String>, confTarget: Int? = null): GetExitPlanResult
    suspend fun sweepWallet(req: SweepWalletRequest): SweepWalletResult
}

interface ExternalSeedWalletClient : WavelengthClient {
    suspend fun startExternalSeedWallet(config: RuntimeConfig, seed: ExternalSeed): ExternalSeedWalletOpenResult
}

object Wavelength {                                   // in :wavelength-embedded
    fun embedded(logger: WavelengthLogger? = null): ExternalSeedWalletClient   // process singleton
}
```

Conventions:

- Amounts are `Long` sats. `uint32`/`uint64` wire values decode into `Long`,
  with a range check that throws if a value overflows.
- Wire enums become Kotlin enums with an `Unknown(raw)` fallback, using a
  custom serializer, so a newer daemon doesn't break an older SDK.
- `time.Time` becomes `kotlin.time.Instant`.
- Passwords, mnemonics and entropy travel as a `Secret` / `ByteArray` wrapper
  that zeroes its buffer after encoding and redacts itself in `toString`.
- `ListResult` and `SendRequest` are sealed types, not a "check the `view`
  field" tagged union.

---

## 4. Phased delivery

Each phase ends in something mergeable and demoable. The estimates assume one
experienced KMP engineer with some help from a Go engineer for upstream
changes. Treat them as rough.

### Phase 0: Foundations and spikes (≈1–1.5 weeks)

Goal: remove the two technical unknowns before writing API code.

1. **iOS cinterop spike.**
   - Write a `.def` for `Wavewalletdk.xcframework`
     (`language = Objective-C`, `modules = Wavewalletdk`, per-slice
     `-F` compiler options for `ios-arm64` and
     `ios-arm64_x86_64-simulator`).
   - Call `MobileStart`, `MobileGetInfo` and `MobileSubscribe` from a
     Kotlin/Native test on the Simulator.
   - Prove the gomobile `NSError**` out-parameter pattern
     (`memScoped { alloc<ObjCObjectVar<NSError?>>() }`).
   - Confirm `Data` ↔ `ByteArray` conversion.
   - Check whether the `@import Foundation;` → `#import` header rewrite that
     the RN package applies is also needed for cinterop, and that `libresolv`
     must be linked.
   - Confirm that the Go runtime and the Kotlin/Native runtime coexist in one
     process: signals, threads, and memory under a 10-minute soak.
2. **iOS packaging decision.** gomobile's framework binary is a static
   archive, so whatever builds the final app must link it. Prototype two
   options and pick one:
   - **(a)** Embed the static library in the klib via `staticLibraries=` in
     the `.def`. Consumers only add a Maven dependency, but each klib is
     ~100 MB+ per slice.
   - **(b)** The klib carries bindings only. Consumers add
     `Wavewalletdk.xcframework` through SPM or CocoaPods, as the existing
     `WalletKit` `Package.swift` does, and the Kotlin framework links it with
     `linkerOpts`.

   Recommendation: (b) for app teams, with a Gradle plugin snippet or SPM
   package that wires it up. Use (a) only if Maven Central size limits allow
   it (check the Central Portal deployment size limit).
3. **Android packaging decision.**
   - Publish the upstream AAR unchanged as `wavelength-bindings-android:<tag>`.
   - Measure the size per ABI and decide whether to also publish an
     `arm64-v8a`+`x86_64` slim variant.
   - Confirm the AAR works as a transitive dependency of a KMP
     `androidLibrary` target with AGP 9.1.
4. **Project setup** (replacing the template's Fibonacci sample):
   - **Rename** the `rootProject`, set the group and namespaces, and set the
     POM to MIT with Lightning Labs attribution.
   - **Build structure:** convention plugins in `build-logic/`, with a
     version catalog adding coroutines, serialization, SKIE, Kotest/Turbine
     and binary-compatibility-validator.
   - **Code standards:** `explicitApi()`, ktlint/detekt, Dokka.
   - **Binaries:** a `scripts/fetch-bindings.sh` that downloads both
     artifacts for a pinned tag from the wavelength release. Port it from
     wavelength-mobile and RN, and support `WAVELENGTH_DIR` local builds.
   - **Pin file:** `wavelength.version` holds the daemon tag. It is the single
     source of truth, read by Gradle and by the scripts.
5. **Decisions recorded** in `docs/adr/`: coordinates, iOS packaging, target
   list, and where versions live.

Exit criteria:

- A throwaway test calls `getInfo` through both the AAR on an emulator and the
  xcframework on the Simulator.
- An ADR exists for packaging.
- The template code is gone.
- CI is green on the skeleton.

### Phase 1: Core contract and codegen (≈2 weeks)

Goal: everything in `:wavelength-core` that does not need native code, fully
unit-tested on the JVM.

1. **Model generator** (`tools/kotlin-modelgen`):
   - **Input and output:** walk `sdk/wavewalletdk` at the pinned tag with
     `golang.org/x/tools/go/packages` and emit `@Serializable` data classes
     with exact `@SerialName`s.
   - **Type mapping:**
     - `uint*` and `int64` become `Long`;
     - `[]byte` becomes `Base64ByteArray`;
     - `time.Time` becomes `Instant`;
     - pointers become nullable;
     - slices become `List` with a default of `emptyList()`;
     - string-constant groups become enums with an unknown fallback;
     - `WalletState` becomes an int enum.
   - **Docs:** copy Go doc comments into KDoc.
   - **Drift check:** a Gradle task `checkGeneratedModels` fails CI when the
     output drifts from the pin.
2. **Wire layer:**
   - Hand-written request envelopes where they differ from the SDK DTOs:
     - `mobileConfig` is snake_case;
     - the external-seed envelope is snake_case;
     - the passkey request is camelCase `prfOutput`;
     - `Receive` adds `TimeoutSeconds`.
   - A `WavelengthJson` instance with `ignoreUnknownKeys`,
     `explicitNulls = false` and `coerceInputValues`.
   - Normalisation of nil slices and of `WalletState` → `walletReady`.
3. **`RuntimeConfig`** ported from TS `config.ts`:
   - Typed fields and a `validate()` that ports the TS rules and adds the Go
     facade's non-negative checks.
   - `NetworkPresets` for signet, testnet and testnet4 with gRPC endpoints,
     taken from the TS SDK as the source of truth.
   - `disableSwaps`, which omits the swap fields.
   - `RuntimeConfig.toMobileConfigJson()`.
4. **Errors:**
   - **Hierarchy:** `sealed class WavelengthException(code, message, cause)`
     with subtypes:
     - `NotStarted` / `RuntimeNotReady`
     - `RuntimeActive` (already started)
     - `WalletLocked`
     - `InvalidConfig`
     - `InvalidExternalSeed`
     - `ReceiveOutcomeUncertain`
     - `SendIntentInvalid`
     - `AmountExceedsVtxoLimit`
     - `BalanceLimitExceeded`
     - `CreditReceiveUnavailable`
     - `SwapBackendUnavailable`
     - `FailedPrecondition`
     - `Daemon` (fallback, with the raw message)
     - `StreamGap(cursor)`
   - **Classifier:** one `ErrorClassifier` with table-driven tests built from
     the exact Go sentinel strings in `sdk/wavewalletdk/errors.go` and
     `mobile/*.go`.
5. **`FacadeBindings` interface and `EmbeddedWavelengthClient`.** The
   implementation is in common code and takes a `FacadeBindings`.
   - **Lifecycle:**
     - A process-wide `Mutex` serialises `start`, `stop` and
       `startExternalSeedWallet`.
     - `start` while running returns the current info, matching TS.
     - `startExternalSeedWallet` while running throws `RuntimeActive`.
     - `callFacade` refuses the lifecycle verbs.
   - **Dispatching:** a dedicated `Dispatchers.IO.limitedParallelism(n)` for
     blocking calls; each open subscription pins one thread.
   - **`activity()`:** a cold `Flow` that runs `next()` on the IO dispatcher.
     - A clean end is the exact `EOF` message (as RN does), not
       `contains("EOF")`.
     - `close()` runs in `awaitClose`, so cancelling the collector closes the
       subscription.
     - It tracks the cursor so callers can resume.
   - **Composite verbs:** `send = prepareSend + sendPrepared` (copying the
     payment hash) and `restoreWallet`.
   - **Logging:** a `WavelengthLogger` hook with secret redaction, using TS's
     redaction key list.
6. **Helpers:** `classifyDestination` (BOLT-11 HRP amount parsing, ported with
   its TS test vectors) and `Secret`.
7. **`:wavelength-testing`:**
   - `FakeFacadeBindings`, with a scriptable verb → JSON table and a channel
     subscription.
   - `FakeWavelengthClient`.
   - Golden JSON fixtures captured from a real daemon (see Phase 3).

Exit criteria:

- All of `core` passes `jvmTest` with more than 90% line coverage, including
  lifecycle races: concurrent start/stop, stop during start, and stop during
  `activity`.
- The API dump (`apiDump`) is checked in.

### Phase 2: Android embedded target (≈1–1.5 weeks)

1. Publish or stage `wavelength-bindings-android` (Maven Local in dev, from
   `fetch-bindings.sh`).
2. `androidMain` `actual` for `FacadeBindings`:
   - a thin mapping to `Mobile.*`;
   - `Mobile.start(String)` vs `ByteArray` verbs;
   - scalar verbs.
3. Default `dataDir` = `context.filesDir/wavelength`, through an
   `AndroidWavelength.init(context)` helper or an AndroidX Startup
   initializer.
4. Optional `WavelengthProcessLifecycle` built on `ProcessLifecycleOwner`.
   - It implements the documented restart-on-resume policy: on a real
     background/foreground transition, stop and then start again with the
     last config.
   - Restarting does not unlock the wallet. It emits a `RequiresUnlock` event
     instead, because the password is the app's.
5. Instrumented tests (`androidDeviceTest`) on an emulator against the real
   AAR:
   - boot with `RuntimeConfig` and no operator (sync-only);
   - create, stop, start, unlock, and check persistence;
   - error mapping for "not started" and a precondition failure;
   - check that `activity` cancellation closes the subscription.
6. ProGuard/R8 consumer rules that keep the `go.*` and
   `engineering.lightning.wavewalletdk.*` JNI classes.

Exit criteria: device tests are green on x86_64 and arm64 emulators.
walletkit's sample flow (boot, create, sync signet, balance) runs through
`:wavelength-embedded`.

### Phase 3: iOS embedded target (≈1.5–2 weeks)

1. Productionise the Phase 0 cinterop:
   - Gradle wiring that resolves the xcframework slice for each Kotlin target
     from the staged artifact.
   - `iosMain` `actual` for `FacadeBindings`:
     - `NSData` ↔ `ByteArray`;
     - `NSError` → `WavelengthException`, with the Go message taken from
       `localizedDescription`;
     - `MobileSubscription.next` / `close`.
2. Default `dataDir` = `Application Support/wavelength`, excluded from iCloud
   backup (`NSURLIsExcludedFromBackupKey`; decide this explicitly).
3. An optional lifecycle observer on `UIApplication` background/foreground
   notifications, with the same policy as Android.
4. Swift export through SKIE:
   - `Flow<Entry>` becomes an `AsyncSequence`;
   - `suspend` functions become cancellable `async throws`;
   - sealed `SendRequest`, `ExitRequest` and `ListResult` become Swift enums.
5. XCFramework assembly (`WavelengthKit.xcframework`), and an SPM
   `Package.swift` that depends on both it and `Wavewalletdk.xcframework`
   (binary targets with checksums).
6. Simulator tests with the same scenarios as Phase 2 (`iosSimulatorArm64Test`
   with the framework linked).
7. **Golden-fixture capture.** A small harness records real daemon JSON for
   every verb on regtest or signet into `:wavelength-testing` fixtures. These
   make the JVM codec tests realistic.

Exit criteria:

- Simulator tests are green.
- A minimal SwiftUI host app calls the SKIE API: boot, create, balance and
  activity.
- The SPM package resolves from a local path.

### Phase 4: WalletEngine (≈2 weeks)

Port `packages/core/src/engine` to `:wavelength-engine` with the same
semantics, so the web, RN and Kotlin apps behave the same.

1. **`WalletSnapshot`** (`phase`, `error`, `info`, `balance`, `activity`,
   `recovery`, `logs`) exposed as a `StateFlow`. The pure transition function
   is ported from `machine.ts`, with its tests ported case for case.
2. **Background jobs** with the constants from `engine/constants.ts`:
   - activity stream only in `ready`, with 1 s → 30 s backoff, a limit of
     5 failures, and cursor resume;
   - 250 ms debounce, then a settle reconcile at 750, 1500 and 3000 ms;
   - sync poll every 2 s and restore poll every 1.5 s, each escalating after
     5 failures;
   - serialised refreshes;
   - a 200-entry log tail.
3. **Preimage retention** (`paymentHash → preimage`) is reapplied to `list` and
   refresh results. Persist it optionally through a small `PreimageStore`
   interface, so preimages survive a process restart. This is an improvement
   over TS, which keeps them only in memory.
4. **The rest of the API:**
   - `restoreWallet` returns once the wallet is usable, with recovery tracked
     in `snapshot.recovery`;
   - `acknowledgeRecovery`, `refresh` and `clearLogs`;
   - `exitBatch` with a `Flow<ExitBatchEvent>` (planned, starting, started,
     stopped), cancelled through coroutine cancellation;
   - `isExitInfeasibilityFundable`.
5. **Snapshot stability:** `distinctUntilChanged` plus structural equality,
   replacing TS `stabilize`, so Compose recomposes as little as possible.
6. **Tests:** virtual-time tests (`runTest`, Turbine) against `FakeFacadeBindings`.
   They cover reconnect, gap, escalation, the settle loop and preimages.

Exit criteria: the engine test suite matches TS test coverage case for case
(track it in a checklist). No platform code.

### Phase 5: Reference app, docs and first release (≈2 weeks)

1. **`:samples:compose-app`** (Compose Multiplatform, Android and iOS) built
   on `WalletEngine`. Screens:
   - onboarding: create, restore or unlock;
   - balance, with confirmed, pending in/out and credit;
   - receive: Lightning invoice QR and on-chain deposit;
   - send: classify the destination, prepare, show the fee, confirm;
   - activity, with live updates and progress phases;
   - VTXOs;
   - exit: cooperative leave and exit status.

   It replaces both wavelength-mobile sample apps.
2. **Docs:**
   - Dokka site.
   - A `README` quick start for Android and iOS.
   - An API guide that ports `wavelength-mobile/docs/api-guide.md` to the
     KMP API.
   - Architecture notes.
   - A migration guide from walletkit and WalletKit.
   - A consumer skill (`skills/wavelength-kmp`), mirroring the TS SDK's
     `skills/`.
3. **Publishing** with vanniktech `maven-publish` (already in the template):
   - Maven Central for all modules plus `wavelength-bindings-android`;
   - signing;
   - a GitHub release carrying the XCFramework zip and the SPM `Package.swift`
     checksum.
4. **Versioning:**
   - The library version equals the wavelength daemon tag (`0.2.1` for
     daemon `v0.2.1`). Library-only fixes use a `-kmpN` suffix, or follow
     whatever scheme Lightning Labs prefers; record it in an ADR.
   - Pre-release daemon tags (`-rcN`) publish as pre-releases.
5. **Release workflow** (replaces the template `publish.yml`):
   - check that the tag, `wavelength.version` and the Gradle version agree;
   - fetch the bindings for the pin;
   - check binding checksums against a committed digest file, as TS does
     with runtime digests;
   - build, test, publish.
6. **Release v0.x** (alpha). Announce it as experimental until Phase 8
   hardening is done.

Exit criteria: a fresh Android project and a fresh Xcode project can each
add the published artifacts and run the quick start against signet.

### Phase 6: Passkeys and external-seed wallets (≈1.5–2 weeks)

1. **`startExternalSeedWallet`.**
   - **API:** the typed `ExternalSeed(entropy: 16 bytes,
     expectedIdentityPubKey?, recoverState, recoveryWindow?)`.
   - **Validation:** checked before the call; the entropy is zeroed after
     encoding.
   - **Lifecycle:** runs through the lifecycle `Mutex`.
   - **Result:** `mnemonic` is stripped from the result, as TS does.
   - **Data directories:** apps get a helper for a separate final `dataDir`
     per wallet.
2. **`:wavelength-passkey`:**
   - **Android:** Credential Manager (`androidx.credentials`) with the PRF
     extension. Port the logic from the TS RN `WavelengthModule.kt`:
     - salt `9a7a2e0f…9d03`, used as both the challenge and `prf.eval.first`;
     - platform authenticator, resident key, user verification;
     - an assertion fallback when the create response has no PRF result.
   - **iOS:** AuthenticationServices PRF on iOS 18+, ported from
     `WavelengthPasskey.swift`.
   - **Output:** a 32-byte PRF result passed to `openWalletFromPasskey`, with
     typed `PasskeyCancelled` handling.
3. **Multi-wallet helper** (optional, small): a `WalletProfile` registry API
   with pluggable storage (DataStore or NSUserDefaults through a
   `ProfileStore` interface). It mirrors the demo apps' `walletRegistry`,
   with `<defaultDataDir>/wallets/<id>` as an absolute path.

Exit criteria: passkey create and open works on an Android device. iOS works
on a device with a valid AASA file; until then it is flagged experimental, as
in TS. The external-seed flow is covered by device and simulator tests,
including the identity-mismatch error.

### Phase 7: Beyond mobile (optional, needs upstream work; ≈2–4 weeks)

Pick this up based on demand. Each option is independent.

1. **JVM desktop and server, and native Linux/macOS: embedded through a C
   ABI.**
   - **Upstream (wavelength):** add a `cmd/wavewalletdk-cshared` (or a
     `-buildmode=c-shared` facade) that exports the same verbs as C functions:
     ```c
     int32_t wwd_call(const char* verb,
                      const uint8_t* req, size_t req_len,
                      uint8_t** resp, size_t* resp_len,
                      char** err);
     ```
     plus `wwd_free`, subscription handles, and release artifacts for
     linux-x64/arm64, macos-arm64 and windows-x64.
   - **Here:**
     - a `jvmMain` `actual` using FFM (JDK 22+) or JNA;
     - `linuxX64`/`macosArm64` `actual`s using cinterop.

   This reuses the identical JSON ABI, so `core` and `engine` work
   unchanged.
2. **Remote daemon mode.**
   - **Approach:** a `RemoteWavelengthClient` that talks to a separate
     `waved` over the REST gateway (`rpc/wavewalletrpc/wallet.yaml`) using
     Ktor, or over gRPC.
   - **Mapping layer:** the REST JSON is proto-JSON (camelCase, string
     int64s), not the facade DTOs, so this needs its own layer. Generate it
     from `wallet.proto`/`wallet.yaml`.
   - **Value:** useful for server-side Kotlin and for desktop apps that
     drive a node.
3. **`wasmJs`/`js`.**
   - **Approach:** wrap the `wavewalletdkCall` global from the published wasm
     blob.
   - **Value:** low priority, because `@lightninglabs/wavelength-web` already
     serves the web.

### Phase 8: Hardening and 1.0 (ongoing, ≈2 weeks before 1.0)

- **Soak and chaos tests on devices:**
  - repeated background/resume;
  - network loss while streaming;
  - stop during a long `receive`;
  - process death during a recovery scan;
  - low storage.
- **Upgrade tests:** create a wallet on library version N−1 (daemon N−1) and
  unlock it on N, as the TS daemon-sync runbook recommends.
- **Performance:**
  - startup time and memory, published as a budget (like TS
    `perf-budget.json`);
  - the binary-size contribution per ABI and slice.
- **Security review:**
  - secret handling: zeroing, redaction, no secrets in logs or crash
    reports;
  - the `allowMainnet` gate;
  - backup exclusion of `dataDir`;
  - the R8 rules.
- **Mainnet presets**, once Lightning Labs publishes mainnet endpoints, plus
  the `allowMainnet` sign-off.
- **API freeze:** a binary-compatibility-validator baseline, a deprecation
  policy, and the 1.0 release.

---

## 5. Cross-cutting practices

**Testing pyramid**

| Layer | Where | What |
|-------|-------|------|
| Unit (fast) | `jvmTest` in core and engine | Codec round-trips on golden fixtures, config validation, error classification, lifecycle races with `FakeFacadeBindings`, engine state machine (virtual time) |
| Platform contract | `androidDeviceTest`, `iosSimulatorArm64Test` | Real bindings: boot, create, unlock, persistence across restart, stream cancel, error mapping. Sync-only config, so no operator is needed. |
| Integration | Nightly CI job | Signet smoke test (like TS `wavewalletdk-signet-smoke.spec.js`): boot, create, sync to tip, receive an invoice. Optionally regtest via wavelength's `harness/` in Docker for send, receive and exit. |
| Sample E2E | Manual per release, Maestro later | Compose sample on device |

**CI** (replaces the template `gradle.yml`)

- Linux:
  - `jvmTest`;
  - `checkGeneratedModels`;
  - `apiCheck`;
  - detekt and ktlint;
  - Android unit tests;
  - Android emulator device tests using KVM on GitHub-hosted runners.
- macOS: `iosSimulatorArm64Test` and the XCFramework assembly.
- Cache staged bindings by pin and digest. Each one is hundreds of MB.
- Free runner disk space before staging the AAR, as wavelength's
  `mobile-bindings.yml` does.

**Daemon sync runbook** (`docs/daemon-sync.md`, mirrors TS
`syncing-daemon-release`)

1. Bump `wavelength.version`.
2. Run `./gradlew generateModels`, then review the model diff for new
   verbs and fields.
3. Run `fetch-bindings.sh`, then update `bindings.digests`.
4. Update the presets, the error-classifier table and the docs.
5. Run the device and simulator suites, plus the N−1 → N wallet upgrade
   test.
6. Bump the version and release.

**API parity tracking.** Keep `docs/parity.md`, a table of
facade verb → TS method → Kotlin method → Swift (SKIE) name → test. A CI check
fails when the Go facade gains an exported function the table doesn't list
(grep `^func [A-Z]` in `sdk/wavewalletdk/mobile` at the pin).

---

## 6. Proposed upstream changes (wavelength / wavelength-sdk)

| # | Change | Why | Blocking? |
|---|--------|-----|-----------|
| U1 | Return structured errors from the facade: either a JSON error envelope `{code, reason, message}` or a stable `code:` prefix built from the `wavewalletrpc` `ErrorInfo` reason. | Ends string matching for typed errors on Kotlin, Swift and TS. | No (string classification works meanwhile) |
| U2 | Add `json` tags (or a documented stable naming) to the `types.go` DTOs, or commit to PascalCase as ABI. | Protects every host SDK from renames and from `json/v2` case-sensitivity changes. | No |
| U3 | Publish slim Android AARs per ABI, or let `gen_bindings.sh` take a target list. | The 150 MB+ AAR hurts app size, CI and Maven. | No |
| U4 | C-shared build of the facade (Phase 7.1). | JVM desktop and Linux/macOS native targets. | Only for Phase 7 |
| U5 | A written spec for external-seed derivation (root → 16-byte entropy, path, versioning). | Interop between SDKs and apps that manage seeds outside wavelength. | Only for a derivation helper |
| U6 | Fix the RN SDK's missing lifecycle queue; align wavelength-mobile signet defaults with the TS presets. | Consistency across SDKs (noticed during this survey). | No |
| U7 | List `Wavewalletdk.aar` and `Wavewalletdk.xcframework.tar.gz` in the signed release manifest. | Consumers could then verify the binaries against a maintainer signature instead of a digest recorded on first download (`bindings.digests`). | No |

---

## 7. Risks

| Risk | Impact | Mitigation |
|------|--------|-----------|
| The gomobile static framework plus Kotlin/Native linking or runtime conflicts (signals, duplicate symbols, bitcode/LTO) | iOS blocked | Phase 0 spike before any API work, with an ADR. Fallback: a thin Swift shim target that SKIE-exported Kotlin calls through an injected protocol. |
| Artifact size (AAR 150 MB+, iOS framework ~100 MB+ per slice) exceeds Central Portal limits or annoys consumers | Distribution | Measure in Phase 0. Fallbacks: binaries consumed via SPM and GitHub releases, slim ABIs (U3), or GitHub Packages for the bindings artifact. |
| Wire drift between the daemon and the models | Silent zero values | Codegen from Go at the pin, golden fixtures from a real daemon, and a CI drift check. |
| Error classification by string is brittle | Wrong typed errors | Table tests at the pinned Go sources, a `Daemon(raw)` fallback, and U1 upstream. |
| Thread exhaustion from blocking calls (each `next()` and boot pins a thread) | Deadlocks under load | A dedicated limited-parallelism dispatcher, documented subscription limits, and tests with many concurrent flows. |
| App suspend leaves stale network paths | Stuck sync or streams | Restart-on-resume observers (Phases 2 and 3) and soak tests (Phase 8). |
| Divergence from TS SDK semantics | Inconsistent apps | Port the engine case for case and track parity in `docs/parity.md`. |

---

## 8. Open questions (need an owner decision)

1. **Ownership and coordinates.** Is this an official Lightning Labs artifact
   (`engineering.lightning.*` on Lightning Labs' Sonatype namespace) or a
   community or Machankura package (for example `com.machankura.wavelength`)?
   This affects the package names, the signing keys and the release process.
2. **Should wavelength-kmp replace wavelength-mobile's walletkit/WalletKit?**
   Or should both coexist, with walletkit becoming a thin Android-only facade
   over KMP?
3. **iOS packaging:** option (a) static library in the klib vs (b) SPM
   xcframework. Phase 0 decides this, but distribution policy may override
   it.
4. **Minimum platform versions:** Android minSdk 24 (template) vs 21
   (bindings); iOS 15 (WalletKit) vs 16+.
5. **Version scheme** for library-only patch releases between daemon tags.
6. **Scope of Phase 7:** is JVM desktop or server a real requirement? It
   decides whether to pursue U4 or remote mode at all.
7. **External-seed derivation:** should the KMP library ship a BIP39/BIP32
   helper once U5 exists, or leave derivation to apps permanently?

---

## Appendix A: verb mapping

| Facade (Go) | Wire request keys | Kotlin `WavelengthClient` | In walletkit today | In TS SDK |
|-------------|-------------------|---------------------------|--------------------|-----------|
| `Start(cfg string)` | snake_case `mobileConfig` | `start(RuntimeConfig)` | yes | yes |
| `StartExternalSeedWallet` | snake_case envelope, base64 entropy | `startExternalSeedWallet` | no | yes |
| `Stop` / `IsRunning` | – | `stop()` / `isRunning()` | yes | yes |
| `GetInfo` / `Status` / `Balance` | – | `getInfo()` / `status()` / `balance()` | yes | yes |
| `ConfirmedBalanceSat` / `PendingInboundSat` / `WalletReady` | – | scalar `suspend fun`s | partial | via `callFacade` |
| `CreateWallet` | `Mnemonic`, `SeedPassphrase`(b64), `WalletPassword`(b64), `RecoverState`, `RecoveryWindow` | `createWallet` / `restoreWallet` | partial | yes |
| `UnlockWallet` | `WalletPassword`(b64) | `unlockWallet` | yes | yes |
| `OpenWalletFromPasskey` | `prfOutput` (hex, camelCase) | `openWalletFromPasskey` | no | yes |
| `Deposit` | `AmountSatHint` | `deposit` | yes | yes |
| `Receive` | `AmountSat`, `Memo`, `TimeoutSeconds` | `receive` | partial (no timeout) | yes |
| `PrepareSend` | `Invoice`/`OnchainAddress`, `AmountSat`, `Note`, `MaxFeeSat`, `MaxCreditSat`, `MaxCreditTopupSat`, `SweepAll` | `prepareSend(SendRequest)` | partial (no credit) | yes |
| `SendPrepared` | `SendIntentID` | `sendPrepared` / `send` | yes | yes |
| `List` | `View`, `PendingOnly`, `Kinds`, `Limit`, `Offset`, `Cursor` | `list(ListRequest)` | partial (no cursor) | yes |
| `Subscribe` + `Next`/`Close` | `IncludeExisting`, `Kinds`, `Cursor` | `activity(): Flow<Entry>` | partial (no cursor) | yes |
| `Exit` | `Outpoint`, `Destination`, `ForceUnrollAck` | `exit(ExitRequest)` | yes | yes |
| `ExitStatus` | `Outpoint`, `Detailed` | `exitStatus` | partial (no detailed) | yes |
| `ExitSummary` | `{}` | `exitSummary` | no | yes |
| `GetExitPlan` | `Outpoints`, `ConfTarget` | `getExitPlan` | no | yes |
| `SweepWallet` | `DestinationAddress`, `Broadcast`, `FeeRateSatPerVByte`, `ConfTarget` | `sweepWallet` | no | yes |

## Appendix B: rough timeline

| Phase | Duration | Cumulative |
|-------|----------|------------|
| 0 Foundations and spikes | 1–1.5 wk | ~1.5 wk |
| 1 Core and codegen | 2 wk | ~3.5 wk |
| 2 Android | 1–1.5 wk | ~5 wk |
| 3 iOS | 1.5–2 wk | ~7 wk |
| 4 WalletEngine | 2 wk | ~9 wk |
| 5 Sample, docs, first release | 2 wk | ~11 wk (alpha) |
| 6 Passkeys and external seed | 1.5–2 wk | ~13 wk |
| 7 Beyond mobile (optional) | 2–4 wk | – |
| 8 Hardening and 1.0 | ~2 wk | ~15 wk (1.0) |

Phases 2 and 3 can run in parallel with two engineers once Phase 1's
`FacadeBindings` interface is fixed. Phase 4 can also start as soon as
Phase 1 lands, because it only needs the fake bindings.
