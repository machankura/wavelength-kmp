# Add descriptor and watch-only wallets to the Kotlin Multiplatform SDK

This ExecPlan is a living document. The sections `Progress`, `Surprises &
Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must stay current
while the work proceeds. It follows the ExecPlan rules in `PLANS.md` at the root
of the sibling `wavelength` repository.


## Purpose / Big Picture

Today an app built on this SDK can only hold a wallet that comes from a
mnemonic, a passkey or host-derived entropy, and the keys always live inside the
app. After this work an Android or iOS app can do three more things from shared
Kotlin or generated Swift:

1. Restore a wallet from private output descriptors, for example ones exported
   from Bitcoin Core or another descriptor wallet.
2. Export the wallet's descriptors, public by default, so the same keys can be
   watched or moved elsewhere.
3. Open a watch-only wallet, whose fund keys stay on a remote signer such as
   `wavesigner`, so a lost phone cannot spend the funds.

To see it working, run the Android sample on an emulator, choose "Watch-only
wallet", paste a bundle exported by a `wavesigner` running on the host machine,
and watch the wallet open and show the same identity key `wavesigner` reports.
Then stop `wavesigner`, restart the app and see a clear signer error.


## Progress

- [x] Milestone 0: branch, baseline and bindings from a local daemon checkout.
- [x] Milestone 1: regenerated models, remote signer config, redaction, errors.
- [x] Milestone 2: descriptors through `createWallet`, and descriptor export.
- [x] Milestone 3: `DescriptorWalletClient` with atomic startup.
- [x] Milestone 4: `WatchOnlyWalletClient` and watch-only `createWallet`.
- [x] Milestone 5: `WalletEngine`, `WalletProfiles`, `BackgroundRestartPolicy`.
- [ ] Milestone 6: Swift surface and iOS tests in macOS CI. Written, never compiled. Needs a Mac,
      or the release below, because CI stages the pinned bindings.
- [x] Milestone 7: Android sample and device tests against a real signer.
- [ ] Milestone 8: documentation and parity table are done. The release pin waits on a `wavelength`
      release that contains the `descriptors` branch.


## Surprises & Discoveries

These were found while writing this plan, from reading the code on 2026-11-04.

- Observation: `scripts/generate-models.sh` reads only
  `sdk/wavewalletdk/types.go`, so `DescriptorWalletOpenResult` (in
  `descriptor.go`) and `WatchOnlyWalletOpenResult` (in `watchonly.go`) would not
  be generated, while `ExternalSeedWalletOpenResult` is, because it lives in
  `types.go`.
  Evidence: `grep -n "^type .* struct" sdk/wavewalletdk/{types,descriptor,watchonly}.go`
  in the `wavelength` checkout.

- Observation: `BackgroundRestartPolicy` restarts with `client.start(config)`
  and expects the host to unlock with a password. A wallet opened through the
  atomic descriptor verb has a database password derived from its keys, which
  the host never sees, so `unlockWallet` cannot reopen it.
  Evidence: `parseDescriptorWalletRequest` in `sdk/wavewalletdk/descriptor.go`
  says the password "is derived, not user chosen".

- Observation: no published `wavelength` release contains this work. The
  `descriptors` branch has 98 commits that `v0.2.1` does not, and `v0.2.1` is not
  an ancestor of it.
  Evidence: `git rev-list --count v0.2.1..descriptors` and
  `git merge-base --is-ancestor v0.2.1 descriptors`.

- Observation: CI cannot pass on this branch until a release exists, so Milestone 6 as written, "the
  `ios` job passes on a pushed branch", cannot happen first. Every job stages the pinned `v0.2.1`
  bindings, which lack `MobileListWalletDescriptors` and the two new start verbs, so the Android and
  iOS code will not compile there, beyond the `generate-models.sh --check` failure this plan expected.
  Evidence: `scripts/fetch-bindings.sh all` in `.github/workflows/gradle.yml`, and the `v0.2.1` pin.

- Observation: The redaction regex ended a JSON array at the first `]`, and a descriptor's key origin
  holds one, so adding `descriptors` to the secret keys alone would have logged the private key after
  the origin.
  Evidence: `RedactionTest.aKeyOriginDoesNotEndTheArrayEarly`, with the old pattern in
  `internal/Redaction.kt`.

- Observation: `start` read its first info outside the lifecycle lock, so a concurrent `stop` could
  fail it with "not started". This predates this work and made `check` fail at random.
  Evidence: `FacadeWavelengthClientTest.concurrentLifecycleCallsAreSerialised` failed once on the
  Android host run.

- Observation: A reopened watch-only wallet reported an empty fingerprint and no descriptors, unlike a
  reopened descriptor wallet. Fixed in `wavelength` (`37c3ea48`), with a Go test, because this result
  type is shared with every SDK.
  Evidence: the device test `aSignerWithOtherKeysIsRefused`. The stub tests had covered only the import.

- Observation: A first open that failed after creating the database never recorded the key origin, and
  its retry goes through `UnlockWallet`, so that wallet exported no fingerprint or origins for good. A
  wrong signer or a chain backend outage was enough. Fixed in `wavelength` (`8d01d467`).
  Evidence: the same device test, before and after. It has no Go test, because the path needs a wallet
  start that fails after creating the database, and a stub cannot do that.

- Observation: The daemon's 8-byte password minimum also applies to watch-only wallets, so this plan's
  "nonempty password" would have let a short one through to fail after a boot.
  Evidence: `ValidateWalletPassword` in `waved/wallet_ops.go`.

- Observation: A failed first open on an unreachable signer stores nothing, while other keys or a later
  failure leave the database created, so the retry differs. `wavelength-mobile` found this first.
  Evidence: `aDeadSignerIsATypedFailureAndLeavesNoWallet` and `aSignerWithOtherKeysIsRefused`.


## Decision Log

- Decision: Add `DescriptorWalletClient` and `WatchOnlyWalletClient` as separate
  interfaces instead of growing `WavelengthClient`.
  Rationale: This repeats `ExternalSeedWalletClient` and the TypeScript SDK, so
  custom clients and fakes keep compiling. `FacadeWavelengthClient` implements
  all of them.
  Date/Author: 2026-11-04, plan author.

- Decision: Offer both ways in. `createWallet` takes descriptors or a watch-only
  bundle and a password chosen by the host; the atomic `start...Wallet` verbs
  take everything in one call.
  Rationale: `WalletEngine` and the sample are built on start, create and unlock,
  and need no new lifecycle for the first path. The atomic verbs are the facade's
  contract for hosts that select a data directory per wallet, and they keep
  parity with the TypeScript SDK.
  Date/Author: 2026-11-04, plan author.

- Decision: Move the two result types into `types.go` in `wavelength` before its
  release, rather than teaching the generator to read more files.
  Rationale: `ExternalSeedWalletOpenResult` already lives there, the branch is
  unreleased so nothing can break, and `descriptor.go` also holds a Go-only
  request that the generator should not emit. If that change cannot land, write
  the two classes by hand in `wire/Mobile.kt` and say so here.
  Date/Author: 2026-11-04, plan author.

- Decision: The SDK never stores descriptors, bundles, authentication keys or
  wallet passwords.
  Rationale: `WalletProfile` is documented as holding nothing secret and is
  persisted in `SharedPreferences` and `UserDefaults`. The host owns its secrets
  in the Android Keystore or iOS Keychain, as it does for mnemonics today.
  Date/Author: 2026-11-04, plan author.

- Decision: Do not add signer spending policy, in-process manifests or a daemon
  listener on the phone.
  Rationale: The first two are not built in the daemon yet. The third contradicts
  this SDK's promise of no open network port, so the phone always dials out to
  the signer.
  Date/Author: 2026-11-04, plan author.

- Decision: Add `EmbeddedWavelengthClient`, gathering the three optional interfaces, as the declared
  type of `Wavelength.client` and the delegate of `FakeWavelengthClient`.
  Rationale: Kotlin cannot delegate to several interfaces that share a parent, so the fake could not
  otherwise offer them all.
  Date/Author: 2026-11-04, implementer.

- Decision: Add `RemoteSignerFailed`, because the strings are stable: `ErrWrongRemoteSigner`,
  `ErrSlotProbeFailed` and fixed wrapping text at each connect and verify step. The message tells an
  unreachable signer from one with other keys, and both call for the same retry.
  Rationale: The whole point of the type is that a host does not call this a wrong password. Errors
  that a retry cannot fix, such as `ErrManifestMismatch`, are `InvalidWatchOnly` instead.
  Date/Author: 2026-11-04, implementer.

- Decision: `BackgroundRestartPolicy.reopening(client, scope, reopen)` instead of an optional `reopen`
  constructor parameter.
  Rationale: Hosts write `BackgroundRestartPolicy(client, scope) { config }`. A parameter after `config`
  would capture that trailing lambda and break them, and one before it would demand a config that a
  reopening policy never uses.
  Date/Author: 2026-11-04, implementer.

- Decision: The sample stops and starts through `WalletEngine`, not `BackgroundRestartPolicy`.
  Rationale: The policy drives the client directly and the engine would stay `STOPPED`, because the
  sample's whole UI is the engine's phase.
  Date/Author: 2026-11-04, implementer.

- Decision: Tests use the signet form of BIP32 test vector 1's master key, whose fingerprint `3442193e`
  is in the BIP.
  Rationale: The key is already public, so committing it in tests discloses nothing.
  Date/Author: 2026-11-04, implementer.


## Outcomes & Retrospective

Everything but iOS and the release works on Android, against the real runtime and real signers. The
branch sits on `wavelength` at `8d01d467`, built locally, so it cannot be merged or released yet.

Against an Android 16 emulator (AVD `Alice`), on signet, `EmbeddedRuntimeTest` passes all 11 tests with
two real `wavesigner` processes. Driving the sample through the validation list gave:

1. Descriptor restore: `READY` in 42 s, fingerprint `5bf9f809`, identity `0309b8ec...9e649909`, the same
   as the web, React Native and `wavelength-mobile` runs from those keys.
2. Export: `tr(` and `wpkh(` lines with `tpub` keys only.
3. Relaunch: `LOCKED`, and the password gives the same identity.
4. Watch-only against `wavesigner`: `READY` in 34 s, the same identity. The signer's files are
   `-rw-------` in a `drwx------` directory under `no_backup`.
5. Signer stopped, background, foreground: `LOCKED`; Unlock fails in 35 s with the sample's signer message
   and `connect to remote signer`. With the signer back, Unlock opens in 8 s.
6. Bundle with another signer's credentials: refused in 36 s naming each slot, `LOCKED`. Corrected settings
   from "Signer settings" open it in 6 s, with fingerprint `5bf9f809`.
7. `prv` in about 21,000 lines of logcat: none. A malformed descriptor's error does not echo the key.

`./gradlew check` passes, with new cases in `wavelength-core`, `wavelength-engine` and `wavelength-embedded`.
Both envelopes were fed to the real Go parsers, `parseDescriptorWalletStartRequest` and
`parseWatchOnlyWalletStartRequest`, with a throwaway test since deleted, and every field came through:
data directory, signer settings, descriptors or bundle, password, birthday `2026-01-01`, identity, recovery
flag and window, and for a manifest its authentication keys and identity override.

Not verified:

- Anything on iOS. `IosFacadeBindings.kt` and `IosEmbeddedRuntimeTest.kt` have never been compiled, SKIE's
  output has not been read, and `walletBirthday` was added without seeing what SKIE does with `Instant`.
- A manifest on the real runtime in this repository: only the client and the Go parser cover it here.
  `wavelength-mobile` ran one on an emulator against the same daemon.
- A real payment or Ark round through a signer.
- `scripts/generate-models.sh --check` and every CI job, which cannot pass until the pin moves.

Left to do: publish a `wavelength` release with this work, then follow Milestone 8 for the pin, the digests
and every test tier, with iOS first on macOS. The Android bindings in `bindings/` are built from a local
checkout, so they must be replaced. Release notes must say that an older SDK cannot read a profile list
holding the two new `WalletKind` values.


## Context and Orientation

This repository is a Kotlin Multiplatform SDK. It runs `waved`, the wavelength
wallet daemon written in Go, inside the app's process. The daemon reaches Kotlin
through gomobile, a tool that turns a Go package into an Android `.aar` and an
iOS `.xcframework`. The Go package is `sdk/wavewalletdk/mobile` in the sibling
`wavelength` repository, and it is called "the facade" here. Most facade
functions take and return UTF-8 JSON. Field names are PascalCase, and `[]byte`
fields are base64. Start configuration uses snake_case. Only one daemon runs per
process.

The modules that matter are these. `wavelength-core` holds everything shared:
`FacadeBindings.kt` is the interface over the facade, with the `FacadeVerb` enum
naming the JSON verbs; `WavelengthClient.kt` is the public API and
`FacadeWavelengthClient.kt` its implementation; `Requests.kt` holds public
request classes whose `toString` hides secrets; `RuntimeConfig.kt` is the public
config; `wire/Mobile.kt` holds hand-written envelopes and `MobileConfig`;
`wire/Requests.kt` and `model/Models.kt` are generated; `internal/Redaction.kt`
masks secrets before logging; `WavelengthException.kt` holds the typed errors and
`ErrorClassifier`, which turns Go error text into them;
`profiles/WalletProfiles.kt` is the on-device list of wallets. All of those sit
under `wavelength-core/src/commonMain/kotlin/engineering/lightning/wavelength/`.

`wavelength-embedded` holds the real bindings (`AndroidFacadeBindings.kt`,
`IosFacadeBindings.kt`), the `Wavelength.client` singleton and
`BackgroundRestartPolicy.kt`, which stops the daemon when the app goes to the
background and starts it again on return. `wavelength-engine` holds `WalletEngine`,
the observable wallet most UIs use. `wavelength-testing` holds
`FakeFacadeBindings`. `wavelength-kit` exports the API to Swift through SKIE, a
plugin that generates friendly Swift. `samples/android` is a Compose sample.
There is no iOS sample app.

`wavelength.version` pins one daemon tag, currently `v0.2.1`. `scripts/fetch-bindings.sh`
stages that release's binaries, and `scripts/generate-models.sh` regenerates the
models from it. Both accept `WAVELENGTH_DIR`, a path to a local checkout, for
unreleased work. CI runs `scripts/generate-models.sh --check`, so generated code
built from a local checkout will fail CI until the pin moves to a real release.
`docs/daemon-sync.md` is the runbook for moving the pin.

These terms are used throughout.

An output descriptor is a line of text that describes keys and how to spend from
them, such as `tr(tprv.../86h/0h/0h/<0;1>/*)#checksum`. A private descriptor holds
private keys and is as sensitive as a seed. The daemon accepts two forms: a set
sharing one master key, or account-level private keys covering the on-chain
account and every key family, recognized by a key origin like
`[fingerprint/86h/0h/0h]`.

A remote signer is a separate process holding the private keys, which the wallet
asks to sign over gRPC. `wavesigner` is ours, and lnd works too. A watch-only
wallet holds only public keys and signs everything through the signer.

A bundle is the text the signer exports for a watch-only wallet. A minimal bundle
also holds private "authentication" keys, used for routine Ark server
authentication so that the signer is not asked each time. A manifest is a JSON
file used instead of a bundle when the wallet's fund slots come from several keys;
it needs a matching `authKeys` file. The wallet password protects the local
database and any authentication keys, and the host must supply and keep it because
there is no seed to derive it from. A manifest hash identifies which manifest a
wallet was created from.

On every open, the daemon has the signer sign a challenge with each key slot and
checks the result, so opening a watch-only wallet fails if the signer is
unreachable or holds different keys.

### Prerequisites in `wavelength`

Before Milestone 8 can finish, the `descriptors` branch of `wavelength` must merge
and ship in a release, written `vX.Y.Z` here. Before Milestone 1, move
`DescriptorWalletOpenResult` and `WatchOnlyWalletOpenResult` into
`sdk/wavewalletdk/types.go` on that branch. Milestones 0 to 7 run against a local
checkout through `WAVELENGTH_DIR`.


## Plan of Work

### Milestone 0: branch, baseline and bindings

Create a `descriptors` branch from `master`. Build the bindings from the local
checkout and confirm the existing suite still passes against them before changing
anything. This proves that the only differences later are yours, and exposes any
break between `v0.2.1` and the `descriptors` branch early. Expect work here: the
branch forks from `main`, not from `v0.2.1`, so facade changes unrelated to
descriptors may appear. Record them in `Surprises & Discoveries`, fix what blocks
the build in small commits, and do not begin Milestone 1 until `check` passes.

The result is green tests on a locally built `.aar`, and a note of any drift.

### Milestone 1: models, config, redaction and errors

Run `scripts/generate-models.sh` with `WAVELENGTH_DIR`. The generated
`CreateWalletRequest` gains `Descriptors`, `Birthday`, `WatchOnlyBundle`,
`WatchOnlyManifest`, `AuthKeys` and `OverrideIdentity`; `CreateWalletResult` gains
`RootFingerprint`, `Descriptors` and `ManifestHash`; `Info` gains `ManifestHash`;
`ListWalletDescriptorsRequest`, `ListWalletDescriptorsResult` and the two open
results appear. The generator maps `time.Time` to `Instant?` already.

Add to `MobileConfig` in `wire/Mobile.kt` the fields `remote_signer_rpc_host`,
`remote_signer_macaroon_path`, `remote_signer_tls_cert_path`,
`remote_signer_connect_timeout_seconds` and `remote_signer_request_timeout_seconds`.
`RuntimeConfigTest.wireConfigKeysMatchTheGoFacade` fails until they exist, which is
the check doing its job. Add a `RemoteSignerConfig` data class to `RuntimeConfig.kt`
and a `remoteSigner: RemoteSignerConfig? = null` field. In `validate()`, require
all three strings to be nonblank when it is set, and timeouts to be nonnegative.

Add `descriptors`, `bundle`, `manifest`, `authkeys`, `watchonlybundle` and
`watchonlymanifest` to `secretKeys` in `internal/Redaction.kt`, and extend
`RedactionTest` with each one, in both snake_case and PascalCase. Public
descriptors are masked too; that costs only some log detail, and the redactor
cannot tell public from private.

Add `InvalidDescriptor` and `InvalidWatchOnly` to `WavelengthException`. Extend
`ErrorClassifier`, reading the exact strings from the daemon: `invalid
descriptors`, `invalid account keys`, `descriptor wallet identity mismatch`,
`invalid watch-only bundle`, `invalid manifest`, and the `ErrManifestMismatch`,
`ErrWatchOnlyWithoutSigner`, `ErrBundleOutdated` and `ErrFamilyNotSupplied`
sentinels in `walletcore`. Add `RemoteSignerFailed` only if the signer-probe error
text in `walletcore` turns out to be stable; otherwise leave it as `Daemon` and
record the reason in the Decision Log. Add a case for each to `ErrorClassifierTest`.

The result compiles and passes with no public behavior yet changed.

### Milestone 2: descriptors through createWallet, and export

Extend the public `CreateWalletRequest` in `Requests.kt` with `descriptors:
List<String> = emptyList()` and `birthday: Instant? = null`, added at the end so
existing positional callers keep compiling, and keep both secret in `toString`
(show only the count). Add a `validate()` that refuses a mnemonic or seed
passphrase together with descriptors, and map the fields where
`FacadeWavelengthClient.createWallet` builds the generated wire request.

Add `LIST_WALLET_DESCRIPTORS("listWalletDescriptors")` to `FacadeVerb`, with its
mapping in `AndroidFacadeBindings` (`Mobile.listWalletDescriptors`) and
`IosFacadeBindings` (`MobileListWalletDescriptors` plus its cinterop import). Add a
public `ListWalletDescriptorsRequest` with `includePrivate`, `mnemonic`,
`seedPassphrase` and `descriptors`, redacted in `toString`.

Add `restoreWalletFromDescriptors(password, descriptors, birthday, recoveryWindow)`
beside `restoreWallet` in `WavelengthClient.kt`.

The result: `FacadeWavelengthClientTest` shows a descriptor restore and an export
producing the exact JSON the facade expects.

### Milestone 3: DescriptorWalletClient

Add `startDescriptorWallet(request: ByteArray): ByteArray` to `FacadeBindings`,
implement it in both real bindings and in `FakeFacadeBindings`, and leave it out of
`FacadeVerb` as the existing comment there requires for lifecycle verbs. Add
`DescriptorWalletStartRequest` to `wire/Mobile.kt` with `config`, `descriptors`,
`birthday_unix`, `expected_identity_pubkey`, `recover_state` and `recovery_window`.

Add the interface below, and implement it in `FacadeWavelengthClient` by following
`startExternalSeedWallet` line for line: validate, refuse a blank `dataDir`, encode,
take `lifecycleMutex`, refuse if a runtime is already running, transition through
`STARTING`, zero the encoded bytes in `finally`, and emit `RuntimeReady`. Widen the
declared type of `Wavelength.client` so hosts can reach it.

The result: tests prove the start is atomic, refused while running, zeroes its
buffer, and sends `birthday_unix` in seconds.

### Milestone 4: WatchOnlyWalletClient

Do the same for `startWatchOnlyWallet`, with an envelope of `config`, `bundle`,
`manifest`, `auth_keys`, `override_identity`, `wallet_password` (a `ByteString`, so
base64), `birthday_unix`, `expected_identity_pubkey`, `recover_state` and
`recovery_window`. `WatchOnlyWalletRequest.validate()` must enforce what the daemon
enforces, so a host gets a typed error before any start: a nonempty password,
exactly one of bundle and manifest, authentication keys with a manifest, and no
authentication keys or identity override with a bundle. `config.remoteSigner` is
required.

Also add `watchOnlyBundle`, `watchOnlyManifest`, `authKeys` and `overrideIdentity`
to `CreateWalletRequest`, mutually exclusive with mnemonic and descriptors, so the
start, create, unlock path works too.

The result: a test for every validation rule, and an envelope test that pins the
field names. To check them against the real Go parser, write the envelope a test
produces to a file, and feed it to `parseWatchOnlyWalletStartRequest` from a
throwaway `_test.go` in `sdk/wavewalletdk/mobile` in the `wavelength` checkout, run
with `go test -tags 'mobile wavewalletrpc swapruntime' -run <name> .`. That function
is private, so the test must live in that package. Delete it afterward, and record
the printed fields here. Do the same for `parseDescriptorWalletStartRequest` in
Milestone 3.

### Milestone 5: engine, profiles and background restart

In `WalletEngine.restoreWallet`, accept a request carrying a mnemonic, descriptors
or a watch-only input, and reject none or several, matching the TypeScript engine.
Add `listWalletDescriptors` to the engine, failing with a typed error if its `client`
does not implement `DescriptorWalletClient`. `snapshot.info.manifestHash` then
flows through by itself, because `Info` is generated.

Add `DESCRIPTOR` and `WATCH_ONLY` to `WalletKind`, and document for each what the
host must keep. A wallet made through `createWallet` needs only its password to
reopen. One opened through an atomic verb needs that verb's whole request on every
open: the descriptors, or the bundle or manifest and the password. Old stored profiles still
decode, but an older SDK cannot decode a new value, so state this in the release
notes.

`BackgroundRestartPolicy` needs a way to reopen wallets it cannot unlock with a
password. Add an optional constructor parameter `reopen: (suspend () -> Info)? =
null`, which replaces the default `client.start(config())` when set, and pass
through its result on `restarts`. A wallet from the atomic descriptor verb uses
this. A watch-only wallet can use it or the ordinary start and unlock, because its
password is the host's. Each foreground return now requires the signer to answer,
so document that a watch-only wallet opens only when the signer is reachable, and
that the host should show the error and offer a retry instead of treating it as a
wrong password. Extend `BackgroundRestartPolicyTest` for both paths.

The result: an engine test restores from descriptors, and a policy test reopens
through `reopen` after a background and foreground cycle.

### Milestone 6: Swift surface and iOS

Check what SKIE generates for the new interfaces and for `Instant?`, since `Instant`
is awkward from Swift. If it is, add a `birthdayUnixSeconds: Long?` secondary
constructor. Add the new symbols to the `swift/` package if its link list needs them.
The new `Mobile*` symbols must be imported in `IosFacadeBindings.kt`. Extend
`IosEmbeddedRuntimeTest` with a descriptor create, restart and unlock. This repository
states that the iOS code first compiles and runs in the macOS CI job, so this
milestone is not done until the `ios` job in `.github/workflows/gradle.yml` passes on
a pushed branch. Ask before pushing.

### Milestone 7: sample and device tests

Add two screens to `samples/android`. "Restore from descriptors" takes a pasted list
and an optional birthday. "Watch-only wallet" takes the bundle, the signer host, and
the macaroon and certificate, which it copies into `context.noBackupFilesDir/signer/`
because the daemon reads file paths and Android content URIs are not paths. Add an
export action to the wallet screen that shows public descriptors. The sample's
wallet password goes through the same handling it has today; never log any of this.

Extend `EmbeddedRuntimeTest` with `bootCreateDescriptorRestartUnlock`, following
`bootCreateRestartUnlock`, using a fixed test descriptor. Add a separate
`watchOnlyOpensAgainstSigner` test, skipped unless instrumentation arguments name a
signer, because it needs one running on the host.

This milestone is the first real proof. The Go facade's watch-only path has only been
tested against a stub daemon, so treat failures here as likely real and record them.

### Milestone 8: documentation, parity and release

Add rows to `docs/parity.md` for `StartDescriptorWallet`, `ListWalletDescriptors` and
`StartWatchOnlyWallet`, and new sections to `docs/api-guide.md`, including where
secrets belong, what happens with the signer offline, and the imports a host needs.
Update `README.md`. Then, once `vX.Y.Z` is published, follow `docs/daemon-sync.md`:
set `wavelength.version`, regenerate without `WAVELENGTH_DIR`, run
`scripts/fetch-bindings.sh all --update-digests`, and run every test tier against the
released binaries. Release through `RELEASE.md`.


## Concrete Steps

Run all of these from the repository root. `WAVELENGTH_DIR` is the sibling checkout on
its `descriptors` branch. A source build of the bindings needs Go, gomobile, the Android
SDK and NDK, and JDK 17 or newer. gomobile finds the SDK only through `ANDROID_HOME`, not
`local.properties`, and without it `make` fails while `scripts/fetch-bindings.sh` stages nothing.

    git switch -c descriptors
    export ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' local.properties)"
    export WAVELENGTH_DIR="$(cd ../wavelength && pwd)"
    scripts/fetch-bindings.sh android
    scripts/generate-models.sh
    ./gradlew check

Each milestone repeats the last line, and expects `BUILD SUCCESSFUL`. For device tests,
start an emulator and run:

    ./gradlew :wavelength-embedded:connectedAndroidDeviceTest

To stand up a signer for Milestone 7, run this on the host machine. An emulator reaches
the host at `10.0.2.2`, which must be in the signer's certificate. Regtest needs a local
Ark server, so use signet to match the sample's presets.

    export WAVESIGNER_PASSWORD=test-password-only
    wavesigner --network=signet create --mnemonic-file wallet.mnemonic
    wavesigner --network=signet export-watchonly --output signer.bundle
    wavesigner --network=signet run --listen=0.0.0.0:10019 --tlsextraip=10.0.2.2

`wallet.mnemonic` holds a throwaway 24-word mnemonic for this test only; `create` also
takes `--descriptor-file`. The macaroon and certificate the phone needs, `signer.macaroon`
and `tls.cert`, are written to `~/.wavesigner/signet/` when `run` first starts.

Commit after each milestone, with the messages describing why.


## Validation and Acceptance

`./gradlew check` passes at every milestone, with new cases that fail without the change.
`scripts/generate-models.sh --check` passes once the pin is a real release; before that
it fails by design, and CI stays red on this branch for that one step.

On an Android emulator, end to end:

1. Restore from a descriptor on signet. The wallet reports `walletReady`, and the
   `rootFingerprint` matches the one in the descriptor's source.
2. Export. The list contains `tr(` and `wpkh(` lines, none with `prv`.
3. Kill and reopen the app. The wallet comes back with the same identity.
4. Start a watch-only wallet against the signer. The identity matches
   `wavesigner`'s, and no signer error appears.
5. Stop the signer, background and foreground the app. The open fails with a typed
   error naming the signer, not a wrong password. Restart the signer, tap retry, and it
   opens.
6. Point the wallet at a signer with different keys. The open fails and the wallet stays
   unusable.
7. Search the device log for `prv` during all of this and find nothing.

An actual payment or Ark round proves the most and is worth doing on signet with test
funds, but it depends on the public signet servers, so it is recommended, not required.


## Idempotence and Recovery

`generate-models.sh` and `fetch-bindings.sh` overwrite their outputs and can be rerun
freely. A locally built `.aar` is ignored by git, and `bindings.digests` changes only with
`--update-digests`. Do not commit digests from a local build: `scripts/fetch-bindings.sh
all --update-digests` belongs to Milestone 8. Device tests create wallets under their own
data directory and tear down in their `@After` method; clear the app's data if one fails midway.
The signer's data lives in `~/.wavesigner`, so use `--datadir` for a throwaway one.


## Artifacts and Notes

The envelope the facade expects, from `sdk/wavewalletdk/mobile/watchonly_wallet.go`:

    {
      "config": {"data_dir": "...", "network": "signet",
                 "remote_signer_rpc_host": "10.0.2.2:10019",
                 "remote_signer_macaroon_path": "/.../signer.macaroon",
                 "remote_signer_tls_cert_path": "/.../tls.cert"},
      "bundle": "...",
      "wallet_password": "cGFzc3dvcmQx",
      "birthday_unix": 1767225600
    }

`wavelength-sdk` on its `descriptors` branch has the same feature in TypeScript. Read
`packages/core/src/requests.ts` for its validation and `packages/core/src/facade.ts`
for its mapping when in doubt about behavior.


## Interfaces and Dependencies

No new dependencies. At the end, in `WavelengthClient.kt`:

    public interface DescriptorWalletClient : WavelengthClient {
        public suspend fun startDescriptorWallet(
            config: RuntimeConfig,
            request: DescriptorWalletRequest,
        ): DescriptorWalletOpenResult

        public suspend fun listWalletDescriptors(
            request: ListWalletDescriptorsRequest = ListWalletDescriptorsRequest(),
        ): ListWalletDescriptorsResult
    }

    public interface WatchOnlyWalletClient : WavelengthClient {
        public suspend fun startWatchOnlyWallet(
            config: RuntimeConfig,
            request: WatchOnlyWalletRequest,
        ): WatchOnlyWalletOpenResult
    }

In `RuntimeConfig.kt`:

    public data class RemoteSignerConfig(
        val rpcHost: String,
        val macaroonPath: String,
        val tlsCertPath: String,
        val connectTimeoutSeconds: Long? = null,
        val requestTimeoutSeconds: Long? = null,
    )

In `FacadeBindings.kt`:

    public fun startDescriptorWallet(request: ByteArray): ByteArray
    public fun startWatchOnlyWallet(request: ByteArray): ByteArray


Revision note (2026-11-04): first version, written from the code at `master` here and
the `descriptors` branch of `wavelength`.

Revision note (2026-11-04): recorded the implementation through Milestone 7 and the documentation half of
Milestone 8. Changed the sample password in the envelope to one that meets the daemon's minimum, and added
`ANDROID_HOME` to the steps, because the first bindings build failed without it.
