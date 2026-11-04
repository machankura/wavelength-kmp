# Architecture

```
App (Android / iOS)
  └─ wavelength-engine   WalletEngine: StateFlow<WalletSnapshot>, phase machine, background work
      └─ wavelength-core FacadeWavelengthClient: typed API, config, errors, lifecycle lock, JSON codec
          └─ FacadeBindings        ← the only per-platform code
              ├─ Android: engineering.lightning.wavewalletdk.mobile.Mobile (JNI, wavelength-bindings-android)
              ├─ iOS:     MobileStart/MobileGetInfo… (cinterop over Wavewalletdk.xcframework)
              └─ tests:   FakeFacadeBindings (wavelength-testing)
                  └─ Go sdk/wavewalletdk/mobile ── in-memory gRPC ──► embedded waved
```

## The binding boundary

wavelength's gomobile facade is flat and has no callbacks:

- **JSON verbs:** bytes in, bytes out, throwing on error.
- **A pull subscription:** `next()` and `close()`.
- **A few scalars.**

`FacadeBindings` mirrors that shape exactly, so each platform
implementation is a direct mapping of about 100 lines. The rest of the SDK
is common code that runs and is tested on the JVM.

## Wire format

Go's `encoding/json` writes the facade's DTOs without tags, so keys are
the PascalCase Go field names, acronyms included (`SendIntentID`,
`VTXOs`, `CPFPFeeSat`).

- **Generated models.** `tools/kotlin-modelgen` generates the Kotlin
  models from the pinned release's `sdk/wavewalletdk/types.go`, with an
  exact `@SerialName` on every field.
- **Fixtures.** `tools/go-fixtures` produces real `encoding/json` output
  for the codec tests.
- **Exceptions.** The `Start` config and the atomic start envelopes
  (external-seed, descriptor, watch-only) are snake_case, and the passkey request is camelCase. These are hand-written
  in `wire/Mobile.kt`, and their keys are checked against the Go json tags.
- **Requests** always use the exact Go key names. The TypeScript SDK
  instead relies on Go matching keys case-insensitively.
- **Enums.** Go string enums decode with an `UNKNOWN` fallback, and an
  empty string means unset (null). This keeps an older SDK working against
  a newer daemon.

## Threading and lifecycle

- **Blocking calls.** Binding calls block: a boot takes seconds, and
  `next()` waits for activity. They run on
  `Dispatchers.IO.limitedParallelism(16)`, and each open activity stream
  holds one of those threads.
- **Lifecycle lock.** `start`, `stop`, and the atomic wallet starts run
  one at a time under a mutex, as the facade requires of typed SDKs.
- **Singleton.** One daemon runs per process, so `Wavelength.client` is a
  process-wide singleton.

## Errors

Only an error's message crosses the gomobile boundary. `ErrorClassifier`
maps the Go SDK's stable sentinel strings, the facade's lifecycle errors,
and gRPC status text to typed `WavelengthException`s. Unrecognised errors
become `Daemon` with the raw message.

## Versioning

`wavelength.version` pins the daemon release. Everything follows it: the
library version, the downloaded bindings (checked against
`bindings.digests`), and the generated models (checked in CI by
`scripts/generate-models.sh --check`). See [ADR 0003](adr/0003-versioning-and-pinning.md).
