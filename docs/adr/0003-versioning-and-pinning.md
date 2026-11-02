# ADR 0003: Versioning and daemon pinning

Status: accepted (the library-only patch suffix is provisional)

## Decision

- **Pin file.** `wavelength.version` holds the wavelength daemon tag (for
  example `v0.2.1`) whose bindings and Go types this build uses. It is the
  single source of truth:
  - Gradle derives the library version from it (`0.2.1`, or
    `0.2.1-SNAPSHOT` unless `-Prelease=true`);
  - `wavelength-core` exposes it as `WAVELENGTH_RUNTIME_VERSION`;
  - `scripts/fetch-bindings.sh` downloads that tag;
  - the model generator reads the Go module at that tag.
- **Digests.** The mobile binding assets are not covered by wavelength's
  signed release manifest. Their SHA-256 digests are recorded per tag in
  `bindings.digests` and checked on every fetch.
  - A pin bump must update them with `--update-digests` in the same change.
  - The reviewer must check that the new digests come from the official
    release.
- **Library-only fixes.** A fix between daemon tags is released as
  `<tag>-kmp.<n>`, for example `0.2.1-kmp.1`.

## Rationale

This mirrors `wavelength-sdk`, where the npm version always equals
`RUNTIME_MANIFEST_VERSION`. One number tells an integrator which daemon
behaviour they get.
