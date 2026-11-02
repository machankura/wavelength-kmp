# API parity

Each gomobile facade function at the pinned release (`sdk/wavewalletdk/mobile`),
with its Kotlin and TypeScript SDK equivalents and the tests that cover it.
"Device" means the Android instrumented test (`EmbeddedRuntimeTest`) and its
iOS twin (`IosEmbeddedRuntimeTest`).

| Go facade | Kotlin (`WavelengthClient`) | TypeScript SDK | Tests |
|-----------|-----------------------------|----------------|-------|
| `Start` | `start(RuntimeConfig)` | `start` | client, device |
| `StartExternalSeedWallet` | `startExternalSeedWallet` | `startExternalSeedWallet` | client |
| `Stop` | `stop()` | `stop` | client, device |
| `IsRunning` | `isRunning()` | `isRunning` | client |
| `GetInfo` | `getInfo()` | `getInfo` | codec, client, device |
| `Status` | `status()` | `status` | codec |
| `Balance` | `balance()` | `balance` | codec, client |
| `ConfirmedBalanceSat` | `confirmedBalanceSat()` | `callFacade` | client |
| `PendingInboundSat` | `pendingInboundSat()` | `callFacade` | client |
| `WalletReady` | `walletReady()` | `callFacade` | client |
| `CreateWallet` | `createWallet`, `restoreWallet` | `createWallet` | codec, client, device |
| `UnlockWallet` | `unlockWallet` | `unlockWallet` | client, device |
| `OpenWalletFromPasskey` | `openWalletFromPasskey` | `openWalletFromPasskey` | client |
| `Deposit` | `deposit` | `deposit` | engine |
| `Receive` | `receive` | `receive` | client, engine, sample |
| `PrepareSend` | `prepareSend` | `prepareSend` | client |
| `SendPrepared` | `sendPrepared`, `send` | `sendPrepared`, `send` | codec, client |
| `List` | `list`, `listActivity`, `listVtxos`, `listOnchain` | `list` | codec, client |
| `Subscribe` / `Next` / `Close` | `activity`, `activityEvents` | `startActivity` + events | client, engine, device |
| `Exit` | `exit` | `exit` | client, exitBatch |
| `ExitStatus` | `exitStatus` | `exitStatus` | codec |
| `ExitSummary` | `exitSummary` | `exitSummary` | none yet |
| `GetExitPlan` | `getExitPlan` | `getExitPlan` | exitBatch |
| `SweepWallet` | `sweepWallet` | `sweepWallet` | none yet |

Helpers ported from TypeScript:

- `Destination.classify` (from `classifyDestination`)
- `exitBatch` and `isFundable` (from `isExitInfeasibilityFundable`)
- `WalletEngine` (from the `WalletEngine` in `engine/`)

Not ported yet:

- the passkey ceremony (WebAuthn / Credential Manager / AuthenticationServices)
- performance events
