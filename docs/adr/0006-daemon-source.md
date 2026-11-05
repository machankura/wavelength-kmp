# ADR 0006: the daemon's source

Status: accepted. Supersedes ADR 0003's rule that the pin is an upstream tag, for as long as this holds.

## Context

Descriptor and watch-only wallets need facade verbs (`StartDescriptorWallet`, `ListWalletDescriptors`,
`StartWatchOnlyWallet`) that no `lightninglabs/wavelength` release has. They are on the `descriptors` branch
of the fork `machankura/wavelength`, which has no release either. So this branch could not compile against
the bindings its own `v0.2.1` pin downloaded.

## Decision

- The fork is a git submodule at `experimental/wavelength`. **Its gitlink is the pin**: moving the daemon is a
  commit here that a reviewer can read.
- `scripts/fetch-bindings.sh` and `scripts/generate-models.sh` use that checkout when it is there. A stamp,
  `bindings/android/SOURCE`, records the commit a staged `.aar` came from, and a matching one is reused,
  because a build takes minutes.
- `wavelength.version` stays the library version. `v0.2.1-machankura.N` says this is not upstream's `v0.2.1`.
  Bump `N` when the gitlink moves, so that the bindings' Maven coordinate moves with it.
- A release on the fork, with digests, stays possible: with no nested checkout the script downloads from
  `machankura/wavelength` at that tag and verifies it, as before.

## Consequences

- A fresh build needs Go and gomobile, on top of the Android SDK and NDK, and the first bindings build takes a while.
- A source build has no digest to check. The gitlink is the integrity check, and the binary is only as good as
  the host's toolchain. A release build for money should come from a clean machine.
- The fork has no tags, so `generate-models.sh --check` compares against the nested commit, not a tag.
- When upstream releases this work, the submodule goes and ADR 0003 applies again.
