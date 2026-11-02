# ADR 0001: Maven coordinates and Kotlin packages

Status: **provisional**. Waiting on the ownership decision (open question 1 in
`docs/PLAN.md`).

## Decision

- Maven group: `engineering.lightning.wavelength`.
- Artifacts: `wavelength-core`, `wavelength-engine`, `wavelength-testing`,
  `wavelength-embedded`, `wavelength-bindings-android`.
- Kotlin packages: `engineering.lightning.wavelength[.<area>]`.
- Android namespaces: `engineering.lightning.wavelength.<module>`, derived by
  the `wavelength.kmp-base` convention plugin from the module name.

## Rationale

The gomobile bindings already live under `engineering.lightning.wavewalletdk`.
The existing `walletkit` wrapper uses `engineering.lightning.wavewalletdk.client`.
Using the same reverse-DNS root keeps the SDK family recognisable.

The bindings' own package (`engineering.lightning.wavewalletdk.mobile`) is
different from ours, so consumers never confuse the raw ABI with the typed
API.

## Changing it

If the library is published by a different owner, change the following, then
regenerate the models:

- `group` in `build-logic/src/main/kotlin/wavelength.kmp-base.gradle.kts`
- the package directories, the licence (the repo `LICENSE` is the template's Apache 2.0)
- the POM in `wavelength.publishing.gradle.kts`
