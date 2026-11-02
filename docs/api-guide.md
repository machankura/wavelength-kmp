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

## 11. Several wallets

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

## 12. Errors

Every failure is a `WavelengthException` subtype. `code` matches the
TypeScript SDK where the two overlap.

| Type | When |
|------|------|
| `RuntimeNotReady` | A call was made before `start` (or after `stop`). |
| `RuntimeActive` | An external-seed start while a runtime is active. |
| `InvalidConfig`, `InvalidArgument`, `InvalidExternalSeed`, `InvalidCursor` | Rejected locally; nothing reached the daemon. |
| `FailedPrecondition` | Wrong wallet state, for example a balance before a wallet exists. |
| `InvalidSendIntent`, `AmountRequired`, `AmountInvalid`, `AmountExceedsVtxoLimit`, `BalanceLimitExceeded`, `InvalidDestination` | Send errors. |
| `ReceiveOutcomeUncertain` | A receive timed out; reconcile before retrying. |
| `StreamGap` | The activity stream skipped entries; resume from `cursor`. |
| `Daemon` | Anything else; `message` is the Go error text and `grpcCode` the gRPC status, if any. |

The gomobile boundary carries only message text, so these types are
recovered from the Go SDK's stable error strings (`ErrorClassifier`).
