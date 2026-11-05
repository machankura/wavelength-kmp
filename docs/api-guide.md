# API guide

This guide walks through common wallet tasks with the wavelength-kmp API.
All of the code lives in `commonMain`. The platform-specific parts are
only the data directory and the lifecycle hooks.

Two layers are available:

- **`WavelengthClient`** (`wavelength-core`) gives you direct, typed calls.
  Every call is a main-safe `suspend` function that throws
  `WavelengthException` on failure.
- **`WalletEngine`** (`wavelength-engine`) is an observable wallet: a
  `StateFlow<WalletSnapshot>` that the engine keeps current. Most UIs
  should use it.

## 1. Get the client

```kotlin
val client = Wavelength.client   // process-wide; the daemon is a per-process singleton
```

Use this one instance everywhere. Its lifecycle lock is what stops start
and stop calls from racing.

## 2. Configure and start

```kotlin
// Android
val config = RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir(context))
// iOS
val config = RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir())

val info = client.start(config)   // blocks off the main thread until the daemon serves
```

- **Presets.** `preset` fills in Lightning Labs' hosted operator, swap,
  and Esplora endpoints for signet, testnet, and testnet4.
- **Mainnet and regtest** have no preset; build their `RuntimeConfig`
  directly. Mainnet also needs `allowMainnet = true`.
- **Validation.** `validate()` runs before anything reaches the daemon.
- **Starting twice.** Calling `start` while a daemon is running returns
  that daemon's info and ignores the new config.

## 3. Create, restore, or unlock

```kotlin
val created = client.createWallet(CreateWalletRequest(password = "at least 8 bytes"))
showBackup(created.mnemonic)                       // shown once

client.restoreWallet("password".encodeToByteArray(), mnemonic = words)   // with a recovery scan

client.unlockWallet("password".encodeToByteArray())   // later launches: the wallet starts LOCKED
```

`info.walletState` is one of `NONE`, `LOCKED`, `SYNCING`, or `READY`.
`info.walletReady` is true only for `READY`.

A wallet can also come from private output descriptors or a remote signer:
see [Descriptor wallets](#11-descriptor-wallets) and
[Watch-only wallets](#12-watch-only-wallets).

## 4. Balance and activity

```kotlin
val balance = client.balance()                 // confirmedSat, pendingInSat, pendingOutSat, credit…
client.activity(ActivityOptions(includeExisting = true)).collect { entry -> render(entry) }
val history = client.listActivity(limit = 50)
val vtxos = client.listVtxos()
```

- **Stream end.** The activity `Flow` completes cleanly when the daemon
  stops, and fails with a `WavelengthException` (for example `StreamGap`,
  which carries a resume cursor) on a stream error.
- **Cancellation.** Cancelling the collector closes the subscription.

## 5. Receive

```kotlin
val invoice = client.receive(ReceiveRequest(amountSat = 10_000, memo = "coffee")).invoice
val address = client.deposit().address        // on-chain boarding
```

If a receive times out, it throws `ReceiveOutcomeUncertain`: the invoice
may still exist. Reconcile the activity list before creating another
invoice.

## 6. Send

```kotlin
when (val d = Destination.classify(pasted)) {     // parse the input first, so the UI asks for an amount only when needed
    is Destination.Invoice -> …                    // d.amount: Known(sat), Amountless, Unrepresentable
    Destination.Address -> …
    Destination.Empty -> …
}
val quote = client.prepareSend(SendRequest.Invoice(pasted, maxFeeSat = 50))
confirm(quote.amountSat, quote.expectedFeeSat, quote.rail)
val sent = client.sendPrepared(quote)            // the quote is single-use
```

`client.send(request)` prepares and sends in one call. A reused or expired
quote throws `InvalidSendIntent`; prepare a new one.

## 7. Exit to the chain

```kotlin
client.exit(ExitRequest.Cooperative(outpoint))                 // folded into a round by the operator
client.exit(ExitRequest.ForceUnroll(outpoint))                 // unilateral; operator unavailable
client.exitStatus(outpoint, detailed = true)
client.exitBatch(ExitBatchOptions.Unilateral(outpoints)) { event -> progress(event) }
```

For a unilateral batch, `exitBatch` re-plans the funding before every
start. It skips exits that are already running, and stops cleanly when the
backing wallet can no longer pay.

## 8. The engine

```kotlin
class WalletViewModel(app: Application) : AndroidViewModel(app) {
    val engine = WalletEngine(Wavelength.client, viewModelScope, config, autoStart = true)
}

val snapshot by vm.engine.snapshot.collectAsStateWithLifecycle()
when (snapshot.phase) {
    RuntimePhase.NEEDS_WALLET -> Onboarding()
    RuntimePhase.LOCKED -> Unlock()
    RuntimePhase.SYNCING -> Syncing(snapshot.info?.blockHeight)
    RuntimePhase.READY -> Wallet(snapshot.balance, snapshot.activity)
    RuntimePhase.ERROR -> Retry(snapshot.error)
    else -> Progress()
}
```

The engine has the same phases and timings as the TypeScript SDK's
`WalletEngine`. While the wallet is `READY` it:

- keeps the activity stream open, reconnecting with backoff;
- refreshes after activity until the balance settles;
- polls while the wallet is syncing or restoring;
- keeps the payment preimages that the daemon reports only once.

`samples/android` is a complete example.

## 9. Background and resume

The Go runtime cannot see the app lifecycle. A daemon left running while
the OS suspends the process keeps stale network connections, so wavelength
recommends stopping it in the background and restarting it on resume:

```kotlin
val policy = BackgroundRestartPolicy(Wavelength.client, appScope) { config }
policy.bindToProcessLifecycle()       // Android; iOS: bindToApplicationLifecycle()
policy.restarts.collect { result -> promptUnlock() }   // the restarted wallet is LOCKED
```

A wallet opened through `startDescriptorWallet` cannot be unlocked with a
password, because its password is derived from the keys. Return through the
verb that opened it instead:

```kotlin
val policy = BackgroundRestartPolicy.reopening(Wavelength.client, appScope) {
    client.startDescriptorWallet(config, request)
    client.getInfo()
}
```

A watch-only wallet opens only while its signer answers, so the restart or the
unlock after it can fail with `RemoteSignerFailed`. Show that and offer a
retry; it is not a wrong password.

## 10. Passkey wallets

A passkey can stand in for the mnemonic and password. The WebAuthn PRF
extension, evaluated with wavelength's fixed salt, yields 32 bytes, and the
daemon derives the wallet from them. The same passkey gives the same wallet
on every device where it syncs.

```kotlin
// Android: Credential Manager. Your rpId domain must publish Digital Asset Links for the app.
val authenticator = CredentialManagerAuthenticator(context) { currentActivity }
// iOS (Swift): the WavelengthPasskey target in the Swift package (iOS 18+;
// associated domains webcredentials:<rpId>):
//   PasskeyAuthenticatorCompanion.shared.fromCallbacks(callbacks: WavelengthPasskey())
val ceremony = PasskeyCeremony(authenticator, rpId = "wallet.example.com")

if (ceremony.isSupported()) {
    client.start(config)                                          // at this wallet's dataDir
    val created = client.createPasskeyWallet(ceremony, appName = "My Wallet")
    saveCredentialId(created.credentialId)                        // public; scopes later unlocks
    // Later launches:
    client.openPasskeyWallet(ceremony, credentialId = savedCredentialId)
}
```

Failures are `PasskeyException`s:

- `Cancelled`
- `NoCredential`
- `Unavailable`
- `PrfUnsupported` (the authenticator has no PRF support)
- `TimedOut`
- `Failed`

## 11. Descriptor wallets

A wallet can come from private output descriptors, for example ones exported
from Bitcoin Core or another descriptor wallet, instead of a mnemonic. The
daemon takes two forms: descriptors that share one master key, or account-level
private keys covering the on-chain account and every key family, recognized by a
key origin like `[fingerprint/86h/0h/0h]`.

```kotlin
import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.restoreWalletFromDescriptors
import kotlin.time.Instant

val descriptors = listOf(
    "tr(tprv.../86h/0h/0h/<0;1>/*)#checksum",
    "wpkh(tprv.../84h/0h/0h/<0;1>/*)#checksum",
)
client.start(config)
val restored = client.restoreWalletFromDescriptors(
    password = "at least 8 bytes".encodeToByteArray(),
    descriptors = descriptors,
    birthday = Instant.parse("2026-01-01T00:00:00Z"),   // bounds the first rescan; null scans from genesis
)
println(restored.rootFingerprint)
```

From Swift, the `walletBirthday` function in `Requests.kt` builds the birthday from
Unix seconds. Later launches unlock with the password, as for any wallet. `restoreWalletFromDescriptors`
runs a recovery scan; pass `CreateWalletRequest(descriptors = ...)` to
`createWallet` to skip it.

### One atomic start

`startDescriptorWallet` boots the daemon and imports or unlocks the wallet in one
call, for hosts that choose a data directory per wallet. The result is ready to
use, with no readiness poll. The database password is derived from the keys, so
no password exists to unlock with, and the host needs the descriptors on **every**
open:

```kotlin
import engineering.lightning.wavelength.DescriptorWalletRequest

val opened = client.startDescriptorWallet(
    config,                                            // config.dataDir is required
    DescriptorWalletRequest(descriptors, expectedIdentityPubKey = savedIdentity),
)
```

### Export

```kotlin
import engineering.lightning.wavelength.ListWalletDescriptorsRequest

val public = client.listWalletDescriptors()            // tr(...), plus wpkh(...) if the wallet holds it; no private keys
```

A private export takes the wallet's own mnemonic or root descriptors, which the
daemon checks before rendering anything private. That is also how a watch-only
wallet can export private descriptors, from keys you supply:

```kotlin
val private = client.listWalletDescriptors(
    ListWalletDescriptorsRequest(includePrivate = true, descriptors = descriptors),
)
```

`WalletEngine` has `listWalletDescriptors` too, and keeps no copy of the result.

## 12. Watch-only wallets

A watch-only wallet holds only public keys. A remote signer such as `wavesigner`
holds the private keys and signs every fund spend, so a lost phone cannot spend
the funds. The phone always dials out to the signer, and opens no port of its own.

The signer exports a bundle. A minimal bundle also holds private "authentication"
keys, used for routine Ark server authentication. On the signer's machine:

```bash
export WAVESIGNER_PASSWORD=...
wavesigner --network=signet create --mnemonic-file wallet.mnemonic   # or --descriptor-file
wavesigner --network=signet export-watchonly --output signer.bundle
wavesigner --network=signet run --listen=0.0.0.0:10019 --tlsextraip=<address the phone dials>
```

`signer.macaroon` and `tls.cert` appear in `~/.wavesigner/signet/`. The daemon
reads them by path on every start, so copy them into the app's own storage. On
Android use `context.noBackupFilesDir`; a content URI is not a path.

```kotlin
import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.RemoteSignerConfig

val signed = config.copy(
    remoteSigner = RemoteSignerConfig(
        rpcHost = "signer.example.com:10019",
        macaroonPath = "$signerDir/signer.macaroon",
        tlsCertPath = "$signerDir/tls.cert",
    ),
)
client.start(signed)
val created = client.createWallet(CreateWalletRequest("at least 8 bytes", watchOnlyBundle = bundleText))
```

Later launches start with the same config and unlock with the password. A wallet
whose fund slots come from several keys uses `watchOnlyManifest` with its `authKeys`
instead, and `info.manifestHash` then identifies the manifest it came from.

`startWatchOnlyWallet(signed, WatchOnlyWalletRequest(password, bundle = bundleText))`
does it all in one call, as `startDescriptorWallet` does. It needs the bundle or
manifest and the password on every open.

### When the signer is unavailable

On every open the daemon has the signer sign a challenge with each key slot.
Opening fails with `RemoteSignerFailed` if the signer does not answer, which took
about 35 seconds on the default timeout on an emulator, or if it holds other keys.
Treat this as "the signer is unavailable", not as a wrong password, and offer a
retry. A first open can fail in two ways, and the retry differs:

| What failed | State afterwards | Retry with |
|-------------|------------------|------------|
| The signer could not be reached | `NONE`: nothing was stored | `createWallet` |
| The signer holds other keys, or something later failed, such as the chain backend | `LOCKED`: the database exists | `unlockWallet` |

`WalletEngine` follows this for you: its phase stays `NEEDS_WALLET` in the first
case and moves to `LOCKED` in the second. The atomic verbs handle both themselves,
so repeat the same call. To correct a wrong signer in the `LOCKED` case, restart
the daemon with the right `RemoteSignerConfig` and unlock.

### Where secrets belong

The SDK stores no descriptors, bundles, authentication keys or passwords, and
`toString` on these requests hides them. Private descriptors are as sensitive as
a seed, and a minimal bundle holds private keys too. Keep them in the Android
Keystore or iOS Keychain, and keep them out of `WalletProfile`, which is persisted
as plain text. Debug payload logging masks them, public descriptors included.

## 13. Several wallets

The daemon runs one wallet at a time. `WalletProfiles` keeps a list of the
wallets on the device. Each wallet gets its own data directory under a
root.

```kotlin
val profiles = WalletProfiles(SharedPreferencesProfileStore(context), Wavelength.defaultDataDir(context))
val profile = profiles.create("Spending", Network.SIGNET, WalletKind.PASSKEY, credentialId = created.credentialId)

client.stop()                                      // switch wallets
client.start(profile.config(RuntimeConfig.preset(Network.SIGNET)))
```

On iOS, use `UserDefaultsProfileStore()`. `EXTERNAL_SEED` profiles are
opened with `startExternalSeedWallet(profile.config(base), request)`.
Pinning `identityPubKey` after the first open is a good safeguard.

`DESCRIPTOR` and `WATCH_ONLY` profiles made through `createWallet` reopen with the
password alone. Those opened through an atomic verb need that verb's whole request
on every open, which the host must keep; see [Where secrets belong](#where-secrets-belong).
An SDK older than these two kinds cannot read a profile list that contains them, so
do not downgrade past them.

## 14. Errors

Every failure is a `WavelengthException` subtype. `code` matches the
TypeScript SDK where the two overlap.

| Type | When |
|------|------|
| `RuntimeNotReady` | A call was made before `start` (or after `stop`). |
| `RuntimeActive` | An atomic wallet start while a runtime is active. |
| `InvalidConfig`, `InvalidArgument`, `InvalidExternalSeed`, `InvalidCursor` | Rejected locally; nothing reached the daemon. |
| `InvalidDescriptor` | Refused descriptors, locally or by the daemon, or an identity mismatch. |
| `InvalidWatchOnly` | A refused bundle or manifest, locally or by the daemon, or a wallet that no longer matches it. Retrying will not help. |
| `RemoteSignerFailed` | The signer could not be reached, or holds other keys. Offer a retry. |
| `UnsupportedFacadeMethod` | A custom client cannot do what `WalletEngine` asked, such as export descriptors. |
| `FailedPrecondition` | Wrong wallet state, for example a balance before a wallet exists. |
| `InvalidSendIntent`, `AmountRequired`, `AmountInvalid`, `AmountExceedsVtxoLimit`, `BalanceLimitExceeded`, `InvalidDestination` | Send errors. |
| `ReceiveOutcomeUncertain` | A receive timed out; reconcile before retrying. |
| `StreamGap` | The activity stream skipped entries; resume from `cursor`. |
| `Daemon` | Anything else; `message` is the Go error text and `grpcCode` the gRPC status, if any. |

The gomobile boundary carries only message text, so these types are
recovered from the Go SDK's stable error strings (`ErrorClassifier`).
