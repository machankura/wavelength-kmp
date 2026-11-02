# ADR 0005: Android binary packaging

Status: accepted

## Decision

The upstream `Wavewalletdk.aar` is republished without changes as
`engineering.lightning.wavelength:wavelength-bindings-android:<version>`, built
from the `wavelength-bindings-android` module. `wavelength-embedded` depends
on it as an ordinary Maven dependency.

## Rationale

A Kotlin Multiplatform Android library cannot carry a local `.aar`
(`files(...)`) to its consumers. Publishing the bindings as their own
artifact:

- keeps the 191 MB (v0.2.1) binary out of the Kotlin artifacts;
- lets a consumer swap in a locally built AAR with dependency substitution;
- matches the one-version-per-daemon-tag rule (ADR 0003).

## Follow-ups

- An ABI-slim variant (`arm64-v8a` and `x86_64` only) is wavelength upstream
  item U3.
