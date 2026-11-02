# Releasing wavelength-kmp

The library version equals the pinned wavelength daemon version
(`wavelength.version` without its `v`). A library-only release between
daemon tags adds `-kmp.N`, for example `0.2.1-kmp.1`.

1. Sync to the daemon release you want to ship, if needed (see
   [docs/daemon-sync.md](docs/daemon-sync.md)), and merge.
2. Run the **Release** workflow (`.github/workflows/publish.yml`) with the
   version. The workflow:
   1. checks the version against the pin;
   2. stages the bindings and verifies them against `bindings.digests`;
   3. checks that the generated models match the pin;
   4. runs the JVM, Android, and iOS simulator tests;
   5. publishes every module to Maven Central, signed (this includes
      `wavelength-bindings-android`, at the daemon version);
   6. builds and zips `WavelengthKit.xcframework` and the gomobile
      `Wavewalletdk.xcframework`;
   7. commits a generated `Package.swift`, then tags and pushes
      `v<version>`;
   8. creates the GitHub release with both zips attached.
3. Check that the artifacts appear on Maven Central, then try the quick
   start in a fresh Android project and a fresh Xcode project.

The workflow needs these repository secrets: `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, and
`GPG_KEY_CONTENTS`.

Status: the publish and Swift-package steps have not been run yet. The
first release is also their first test.
